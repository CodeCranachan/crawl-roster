package org.codecranachan.roster

import discord4j.rest.util.Permission
import discord4j.rest.util.PermissionSet
import io.ktor.server.sessions.*
import io.ktor.util.*

object Configuration {
    private val env = System.getenv()

    val guildLimit = (env["ROSTER_GUILD_LIMIT"] ?: "3").toInt()
    val devMode = env["ROSTER_DEV_MODE"] == "true"
    val rootUrl = env["ROSTER_ROOT_URL"] ?: "http://localhost:8080"
    val jdbcUri = env["ROSTER_JDBC_URI"] ?: "jdbc:h2:mem:test"
    val botToken = env["DISCORD_BOT_TOKEN"]
    val discordCredentials = getClientCreds("DISCORD")
    private val permissions = PermissionSet.of(
        Permission.MANAGE_CHANNELS,
        Permission.CREATE_PUBLIC_THREADS,
        Permission.SEND_MESSAGES_IN_THREADS,
        Permission.MANAGE_MESSAGES,
        Permission.PIN_MESSAGES,
        Permission.MANAGE_THREADS,
        Permission.ADD_REACTIONS,
    )
    val botCoordinates = discordCredentials?.let { BotCoordinates(it.id, permissions.rawValue) }

    private val sessionEncryptKey = env["ROSTER_SESSION_ENCRYPT_KEY"] ?: "79EBA26C10A7A6D8B22864DB05987369"
    private val sessionSignKey = env["ROSTER_SESSION_SIGN_KEY"] ?: "2FFBBF335042E92C09B988DF4BC040A5"
    val sessionTransformer = SessionTransportTransformerEncrypt(hex(sessionEncryptKey), hex(sessionSignKey))

    private fun getClientCreds(prefix: String): ClientCredentials? {
        val id = env["${prefix}_CLIENT_ID"]
        val secret = env["${prefix}_CLIENT_SECRET"]
        return if (id != null && secret != null) {
            ClientCredentials(id, secret)
        } else {
            null
        }
    }
}