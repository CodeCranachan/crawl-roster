package org.codecranachan.roster.auth

import io.ktor.client.call.*
import io.ktor.client.request.*
import org.codecranachan.roster.*

@kotlinx.serialization.Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    val avatar: String,
    val discriminator: String,
    val public_flags: Int,
)

@kotlinx.serialization.Serializable
data class DiscordAuthorizationInfo(
    val scopes: List<String>,
    val expires: String,
    val user: DiscordUser
)

fun createDiscordOidProvider() = OpenIdProvider(
    ClientCredentials(
        id = "976931433903960074",
        secret = "xb0sP7Zwz8VjWuMA8MPqkmYLVILH5dFU"
    ),
    OpenIdConfiguration(
        authorization_endpoint = "https://discord.com/api/oauth2/authorize",
        token_endpoint = "https://discord.com/api/oauth2/token",
        userinfo_endpoint = "https://discord.com/api/oauth2/@me",
        revocation_endpoint = "https://discord.com/api/oauth2/token/revoke",
    ),
    listOf("identify")
) { principal, provider ->
    val info: DiscordAuthorizationInfo = RosterServer.httpClient.get(provider.conf.userinfo_endpoint) {
        bearerAuth(principal.accessToken)
    }.body()
    UserIdentity(
        info.user.id,
        info.user.username,
        "https://cdn.discordapp.com/avatars/${info.user.id}/${info.user.avatar}"
    )
}