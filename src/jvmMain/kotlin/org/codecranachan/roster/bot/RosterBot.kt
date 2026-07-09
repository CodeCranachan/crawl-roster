package org.codecranachan.roster.bot

import com.benasher44.uuid.Uuid
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.guild.GuildCreateEvent
import discord4j.core.event.domain.guild.GuildDeleteEvent
import discord4j.core.event.domain.guild.GuildUpdateEvent
import discord4j.core.event.domain.interaction.InteractionCreateEvent
import discord4j.core.`object`.component.ActionRow
import discord4j.core.`object`.component.Button
import discord4j.core.`object`.emoji.Emoji
import discord4j.core.`object`.entity.User
import discord4j.discordjson.json.InteractionApplicationCommandCallbackData
import discord4j.discordjson.json.InteractionResponseData
import discord4j.rest.util.MultipartRequest
import org.codecranachan.roster.DiscordUser
import org.codecranachan.roster.core.Event
import org.codecranachan.roster.core.Player
import org.codecranachan.roster.core.PlayerAlreadyRegistered
import org.codecranachan.roster.core.Registration
import org.codecranachan.roster.core.RosterCore
import org.codecranachan.roster.core.RosterLogicException
import org.codecranachan.roster.core.events.CalendarEventCanceled
import org.codecranachan.roster.core.events.CalendarEventClosed
import org.codecranachan.roster.core.events.CalendarEventCreated
import org.codecranachan.roster.core.events.CalendarEventUpdated
import org.codecranachan.roster.core.events.EventBus
import org.codecranachan.roster.core.events.RegistrationCanceled
import org.codecranachan.roster.core.events.RegistrationCreated
import org.codecranachan.roster.core.events.RegistrationUpdated
import org.codecranachan.roster.core.events.RosterEvent
import org.codecranachan.roster.core.events.TableCanceled
import org.codecranachan.roster.core.events.TableCreated
import org.codecranachan.roster.core.events.TableUpdated
import org.codecranachan.roster.query.PlayerQueryResult
import org.codecranachan.roster.util.orNull
import org.slf4j.LoggerFactory
import reactor.core.Disposable

fun User.asDiscordUser(): DiscordUser =
    DiscordUser(
        id.asString(),
        username,
        avatarUrl
    )

fun InteractionCreateEvent.sendEphemeralResponse(content: String) {
    val ephemeralFlag = 1 shl 6
    val channelMessageWithSource = 4
    client.restClient.interactionService.createInteractionResponse(
        interaction.id.asLong(),
        interaction.token,
        MultipartRequest.ofRequest(
            InteractionResponseData.builder()
                .type(channelMessageWithSource)
                .data(
                    InteractionApplicationCommandCallbackData.builder()
                        .content(content)
                        .flags(ephemeralFlag)
                        .build()
                )
                .build()
        )
    ).subscribe()
}


class RosterBot(val core: RosterCore, botToken: String, rootUrl: String) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client: DiscordClient = botToken.let(DiscordClient::create)
    private val disposables = ArrayList<Disposable>()

    private val tracking = DiscordTracking()

    private val templates = MessageTemplates(rootUrl)

    fun start() {
        val gateway = client.login().block()
        if (gateway != null) {
            logger.info("Bot connected to discord gateway")
            subscribeHandlers(gateway, core.eventBus)
            gateway.onDisconnect().block()
            disposables.forEach(Disposable::dispose)
            logger.info("Bot disconnected from discord gateway")
        } else {
            logger.warn("Connection to discord gateway failed.")
        }
    }

    private fun subscribeHandlers(gateway: GatewayDiscordClient, eventBus: EventBus) {
        eventBus.getFlux().subscribe(this::handleRosterEvent).apply(disposables::add)

        gateway.on(GuildCreateEvent::class.java).subscribe(this::handleGuildCreateEvent).apply(disposables::add)
        gateway.on(GuildUpdateEvent::class.java).subscribe(this::handleGuildUpdateEvent).apply(disposables::add)
        gateway.on(GuildDeleteEvent::class.java).subscribe(this::handleGuildDeleteEvent).apply(disposables::add)

        gateway.on(InteractionCreateEvent::class.java).subscribe(this::handleInteraction).apply(disposables::add)

        tracking.subscribeHandlers(gateway).apply(disposables::addAll)
    }

    private fun handleGuildCreateEvent(event: GuildCreateEvent) {
        val link = core.guildRoster.link(event.guild)
        if (link == null) {
            logger.info("Butler unable to tend to ${event.guild.id.asString()} - guild limit reached")
        } else {
            logger.info("Butler tending to ${event.guild.id.asString()}")
            tracking.add(link, event.guild)
        }
    }

    private fun handleGuildUpdateEvent(event: GuildUpdateEvent) {
        core.guildRoster.update(event.current)
    }

    private fun handleGuildDeleteEvent(event: GuildDeleteEvent) {
        if (event.isUnavailable) {
            logger.info("Butler disconnected from ${event.guildId.asString()}")
        } else {
            logger.info("Butler removed from ${event.guildId.asString()}")
        }
        tracking.remove(event.guildId)
    }

    private fun createPlayerRegistration(
        interaction: InteractionCreateEvent,
        p: PlayerQueryResult,
        eventId: Uuid,
        dm: Player?,
    ) {
        core.eventCalendar.addPlayerRegistration(
            eventId,
            p.player.id,
            Registration.Details(dm?.id)
        )
        if (dm == null) {
            interaction.sendEphemeralResponse("I have added you to the waiting list.")
        } else {
            interaction.sendEphemeralResponse("I have added you to ${dm.discordMention}'s table.")
        }
    }

    private fun updatePlayerRegistration(
        interaction: InteractionCreateEvent,
        p: PlayerQueryResult,
        eventId: Uuid,
        dm: Player?,
    ) {
        core.eventCalendar.updatePlayerRegistration(
            eventId,
            p.player.id,
            Registration.Details(dm?.id)
        )
        if (dm == null) {
            interaction.sendEphemeralResponse("I have moved you to the waiting list.")
        } else {
            interaction.sendEphemeralResponse("I have moved you to ${dm.discordMention}'s table.")
        }
    }

    private fun handleInteraction(event: InteractionCreateEvent) {
        event.interaction.commandInteraction.ifPresent {
            val activeId = it.customId.orNull()?.let(ActiveId::fromCustomId)
            when (activeId?.action) {
                Action.RegisterPlayer -> {
                    val p = core.playerRoster.registerDiscordPlayer(event.interaction.user.asDiscordUser())
                    val eventId = activeId.getParam(0)!!
                    val dm = activeId.getParam(1)?.let { dmId -> core.playerRoster.getPlayer(dmId)?.player }
                    try {
                        joinEvent(event, p, eventId, dm)
                    } catch (e: RosterLogicException) {
                        event.sendEphemeralResponse(
                            """
                            I am afraid I could not process your registration.
                            The computer said: `${e.message}`""".trimIndent()
                        )
                    }
                }

                Action.UnregisterPlayer -> {
                    val p = core.playerRoster.registerDiscordPlayer(event.interaction.user.asDiscordUser())
                    val eventId = activeId.getParam(0)!!
                    try {
                        core.eventCalendar.deletePlayerRegistration(eventId, p.player.id)
                        event.sendEphemeralResponse("I have canceled your registration. I hope you can make it next time.")
                    } catch (e: RosterLogicException) {
                        event.sendEphemeralResponse(
                            """
                            I am afraid I could not cancel your registration.
                            The computer said: `${e.message}`""".trimIndent()
                        )
                    }
                }

                Action.RegisterBeginner -> {
                    val p = core.playerRoster.registerDiscordPlayer(event.interaction.user.asDiscordUser())
                    val eventId = activeId.getParam(0)!!
                    val e = core.eventCalendar.queryEvent(eventId)!!
                    val dm = e.beginnerTables.values.filter { t -> !t.isFull }.randomOrNull()?.dungeonMaster
                    try {
                        if (dm == null) {
                            event.sendEphemeralResponse(
                                """
                                I am afraid there are no beginner tables with free seats at the moment.""".trimIndent()
                            )
                        } else {
                            joinEvent(event, p, eventId, dm)
                        }
                    } catch (e: RosterLogicException) {
                        event.sendEphemeralResponse(
                            """
                            I am afraid I could not process your registration.
                            The computer said: `${e.message}`""".trimIndent()
                        )
                    }
                }

                else -> {}
            }
        }

    }

    private fun joinEvent(
        event: InteractionCreateEvent,
        p: PlayerQueryResult,
        eventId: Uuid,
        dm: Player?,
    ) {
        kotlin.runCatching {
            createPlayerRegistration(event, p, eventId, dm)
        }.onFailure { err ->
            if (err is PlayerAlreadyRegistered) updatePlayerRegistration(event, p, eventId, dm)
            else throw err
        }
    }

    private fun handleRosterEvent(e: RosterEvent) {
        logger.debug("Handling event from RosterCore: {}", e)
        when (e) {
            is CalendarEventCreated -> updateEventOnDiscord(e.current.id)
            is CalendarEventUpdated -> updateEventOnDiscord(e.current.id)
            is CalendarEventCanceled -> removeEventFromDiscord(e.previous)
            is CalendarEventClosed -> removeEventFromDiscord(e.current)
            is TableCreated -> {
                updateEventOnDiscord(e.current.eventId)
                updateTableOnDiscord(e.current.eventId, e.current.dungeonMasterId)
            }

            is TableUpdated -> {
                updateEventOnDiscord(e.current.eventId)
                updateTableOnDiscord(e.current.eventId, e.current.dungeonMasterId)
            }

            is TableCanceled -> {
                updateEventOnDiscord(e.previous.eventId)
                cancelTableOnDiscord(e.previous.eventId, e.previous.dungeonMasterId)
            }

            is RegistrationCreated -> {
                updateEventOnDiscord(e.current.eventId)
                e.current.details.dungeonMasterId?.let { dmId ->
                    updateTableOnDiscord(e.current.eventId, dmId)
                }
            }

            is RegistrationUpdated -> {
                updateEventOnDiscord(e.current.eventId)
                e.previous.details.dungeonMasterId?.let { dmId ->
                    updateTableOnDiscord(e.previous.eventId, dmId)
                }
                e.current.details.dungeonMasterId?.let { dmId ->
                    updateTableOnDiscord(e.current.eventId, dmId)
                }
            }

            is RegistrationCanceled -> {
                updateEventOnDiscord(e.previous.eventId)
                e.previous.details.dungeonMasterId?.let { dmId ->
                    updateTableOnDiscord(e.previous.eventId, dmId)
                }
            }

            else -> {}
        }
    }

    private fun updateEventOnDiscord(eventId: Uuid) {
        val data = core.eventCalendar.queryEvent(eventId) ?: return
        tracking.get(data.event.guildId)?.apply {
            withEventMessage(data.event) { msg ->
                val content = templates.eventMessageContent(data)
                val components =
                    buildList {
                        add(
                            Button.primary(
                                ActiveId(Action.RegisterPlayer, data.event.id).asCustomId(),
                                "Sign Up"
                            )
                        )
                        add(
                            Button.secondary(
                                ActiveId(Action.UnregisterPlayer, data.event.id).asCustomId(),
                                "Cancel"
                            )
                        )
                        val beginnerButton = Button.secondary(
                            ActiveId(Action.RegisterBeginner, data.event.id).asCustomId(),
                            Emoji.unicode("\uD83D\uDD30"),
                            "Join Beginner Table"
                        )
                        add(
                            if (data.beginnerTables.isEmpty()) beginnerButton.disabled() else beginnerButton
                        )
                    }.let {
                        ActionRow.of(*it.toTypedArray())
                    }

                val botMessage = BotMessage(botId, data.event.getChannelName(), content)

                msg.edit()
                    .withContentOrNull(botMessage.asContent().take(2000))
                    .withComponents(components)
                    .subscribe()
            }
        }
    }

    private fun updateTableOnDiscord(eventId: Uuid, dmId: Uuid) {
        val data = core.eventCalendar.queryTable(eventId, dmId) ?: return
        tracking.get(data.event.guildId)?.apply {
            withTableMessage(data.event, data.dm) { msg ->
                val content = templates.openTableMessageContent(data)

                val components =
                    ActionRow.of(
                        Button.primary(
                            ActiveId(
                                Action.RegisterPlayer,
                                data.event.id,
                                data.dm.id
                            ).asCustomId(),
                            "Join table"
                        ),
                        Button.secondary(
                            ActiveId(
                                Action.RegisterPlayer,
                                data.event.id
                            ).asCustomId(),
                            "Leave table"
                        )
                    )

                val botMessage = BotMessage(botId, BotMessage.getTableTitle(data.event, data.dm), content)

                msg.edit()
                    .withContentOrNull(botMessage.asContent().take(2000))
                    .withComponents(components)
                    .subscribe()
            }
        }
    }

    private fun cancelTableOnDiscord(eventId: Uuid, dmId: Uuid) {
        val event = core.eventCalendar.queryEvent(eventId)?.event ?: return
        val dm = core.playerRoster.getPlayer(dmId)?.player ?: return
        tracking.get(event.guildId)?.apply {
            withTableMessage(event, dm) { msg ->
                val content = templates.closedTableMessageContent(dm)
                val botMessage = BotMessage(botId, BotMessage.getTableTitle(event, dm), content)
                msg.edit()
                    .withContentOrNull(botMessage.asContent().take(2000))
                    .withComponents()
                    .subscribe()
            }
        }
    }

    private fun removeEventFromDiscord(event: Event) {
        tracking.get(event.guildId)?.apply {
            if (hasChannelFor(event)) {
                withEventChannel(event) { channel ->
                    channel.delete("Event has been canceled by admin")
                        .subscribe({}, { err -> logger.error("Failed to delete event channel", err) })
                }
            } else {
                // Channel does not exist - do nothing
            }
        }
    }

}