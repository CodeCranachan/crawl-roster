package org.codecranachan.roster.bot

import discord4j.common.util.Snowflake
import discord4j.core.`object`.PermissionOverwrite
import discord4j.core.`object`.entity.Entity
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.Role
import discord4j.core.`object`.entity.channel.Category
import discord4j.core.`object`.entity.channel.Channel
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.`object`.entity.channel.ThreadChannel
import discord4j.core.spec.StartThreadWithoutMessageSpec
import discord4j.rest.util.Permission
import discord4j.rest.util.PermissionSet
import org.codecranachan.roster.LinkedGuild
import org.codecranachan.roster.core.Event
import org.codecranachan.roster.core.Player
import org.codecranachan.roster.util.orNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import kotlin.jvm.optionals.getOrNull

class GuildTracker(
    val linkedGuild: LinkedGuild,
    private val discordGuild: Guild,
    val botId: Snowflake,
) {
    companion object {
        const val eventCalendarCategoryName = "Organisation"
        const val dungeonMasterRoleName = "DM"
        const val memberRoleName = "Member"
        const val everyoneRoleName = "__Everyone"
    }

    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val entityCache = EntityCache()

    private val channelProcessingOrder =
        listOf(Category::class.java, TextChannel::class.java, ThreadChannel::class.java).reversed()

    private fun Message.isAuthoredByBot(): Boolean = botId == author.getOrNull()?.id && isPinned

    fun initialize() {
        discordGuild.roles.subscribe { r -> putEntity(r) }
        val categoryMono = discordGuild.channels.filter { it is Category }
            .filter { it.name == eventCalendarCategoryName }
            .map { it as Category }
            .doOnNext(::putEntity)
            .single()

        val channelFlux = categoryMono.flatMapMany { category ->
            discordGuild.channels
                .filter { it is TextChannel }
                .map { it as TextChannel }
                .filter { it.categoryId.orNull() == category.id }
                .doOnNext(::putEntity)
        }

        channelFlux
            .flatMap { channel ->
                channel
                    .pinnedMessages
                    .map { it.message }
                    .flatMap { msg ->
                        if (getEntityName(msg) != null && getEntityName(msg) != getEntityName(channel)
                        ) {
                            msg.delete().ofType(Message::class.java)
                        } else {
                            Mono.just(msg)
                        }
                    }
                    .concatWith(
                        discordGuild.activeThreads
                            .flatMapIterable { it.threads }
                            .filter { channel.id == it.parentId.orNull() }
                            .doOnNext(::putEntity)
                            .flatMap { it.pinnedMessages }
                            .map { it.message }
                    )
            }
            .subscribe(::putEntity)
    }

    private fun getEntityName(e: Entity): String? {
        return when (e) {
            is Category -> e.name
            is TextChannel -> e.name
            is ThreadChannel -> e.name
            is Message -> {
                if (e.isAuthoredByBot()) BotMessage.getMessageTitle(e) else null
            }

            is Role -> if (e.isEveryone) everyoneRoleName else e.name
            else -> null
        }
    }

    fun putEntity(e: Entity) {
        val name = getEntityName(e)
        name?.apply {
            logger.debug("Adding {}({}) as {}", e.javaClass.simpleName, e.id, name)
            entityCache.put(this, e)
        }
    }

    fun <T : Entity> removeEntities(clazz: Class<T>, predicate: (T) -> Boolean) {
        getEntities(clazz)
            .filterValues { predicate(it) }
            .forEach { (_, v) -> entityCache.remove(v.id) }
    }

    fun <T : Entity> getEntities(clazz: Class<T>): Map<EntityCache.Key, T> {
        return entityCache.getAll(clazz)
            .mapValues { (_, v) -> v.get() }
    }

    fun removeEntity(e: Snowflake) {
        entityCache.remove(e)
    }

    private fun getEventCalendarCategory(): CompletableFuture<Category> =
        entityCache.get(eventCalendarCategoryName) { name ->
            discordGuild
                .createCategory(name)
                .withReason("Guild is missing $eventCalendarCategoryName category to post Crawl Roster events")
                .toFuture()
                .thenApply { it!! }
        }

    private fun getEventChannel(event: Event): CompletableFuture<TextChannel> =
        entityCache.get(event.getChannelName()) { name ->
            getEventCalendarCategory().thenCompose { category ->
                discordGuild
                    .createTextChannel(name)
                    .withTopic(event.getChannelTopic())
                    .withPosition(event.date.toEpochDays())
                    .withParentId(category.id)
                    .withPermissionOverwrites(
                        membersOnly(
                            Permission.VIEW_CHANNEL,
                            Permission.SEND_MESSAGES
                        )
                    )
                    .withReason("New event posted by Crawl Roster")
                    .toFuture()
                    .thenApply { it!! }
            }
        }

    private fun getEventMessage(event: Event): CompletableFuture<Message> {
        return entityCache.get(event.getChannelName()) { name ->
            getEventChannel(event).thenCompose { channel ->
                val template = BotMessage(botId, name)
                channel.createMessage(template.asContent())
                    .doOnNext { it.pin().subscribe() }
                    .toFuture()
                    .thenApply { it!! }
            }
        }
    }

    private fun getTableThread(event: Event, dm: Player): CompletableFuture<ThreadChannel> {
        return entityCache.get(BotMessage.getTableTitle(event, dm)) { name ->
            getEventChannel(event).thenCompose { channel ->
                val spec = StartThreadWithoutMessageSpec.of(name, ThreadChannel.Type.GUILD_PUBLIC_THREAD)
                    .withAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.DURATION4)
                    .withReason("New Table Posted by Crawl Roster")
                channel.startPublicThreadWithoutMessage(spec).toFuture().thenApply { it!! }
            }
        }
    }

    private fun getTableMessage(event: Event, dm: Player): CompletableFuture<Message> {
        return entityCache.get(BotMessage.getTableTitle(event, dm)) { name ->
            getTableThread(event, dm).thenCompose { thread ->
                val template = BotMessage(botId, name)
                thread
                    .createMessage(template.asContent())
                    .doOnNext { it.pin().subscribe() }
                    .toFuture()
                    .thenApply { it!! }
            }
        }
    }

    private fun getRole(name: String): Role? {
        return entityCache
            .get(name) { CompletableFuture.failedFuture<Role>(UnsupportedOperationException("Can not create Roles")) }
            .getNow(null)
    }

    private fun getEveryoneRole(): Role? {
        return getRole(everyoneRoleName)
    }

    private fun getMemberRole(): Role? {
        return getRole(memberRoleName)
    }

    private fun getDungeonMasterRole(): Role? {
        return getRole(dungeonMasterRoleName)
    }

    fun withEventChannel(event: Event, block: (Channel) -> Unit) {
        getEventChannel(event).thenAccept(block)
    }

    fun withEventMessage(event: Event, block: (Message) -> Unit) {
        getEventMessage(event).thenAccept(block)
    }

    fun withTableMessage(event: Event, dm: Player, block: (Message) -> Unit) {
        getTableMessage(event, dm).thenAccept(block)
    }

    fun hasChannelFor(event: Event): Boolean {
        return entityCache.hasKey(event.getChannelName(), TextChannel::class.java)
    }

    private fun membersOnly(vararg permissions: Permission): List<PermissionOverwrite> {
        val everyone = getEveryoneRole()
        val member = getMemberRole()
        if (everyone == null || member == null) {
            return emptyList()
        } else {
            val set = PermissionSet.of(*permissions)
            return listOf(
                PermissionOverwrite.forRole(everyone.id, PermissionSet.none(), set),
                PermissionOverwrite.forRole(member.id, set, PermissionSet.none()),
                PermissionOverwrite.forMember(botId, set, PermissionSet.none())
            )
        }
    }
}