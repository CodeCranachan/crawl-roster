package org.codecranachan.roster

import com.benasher44.uuid.Uuid
import com.benasher44.uuid.uuid4
import kotlinx.serialization.Serializable

@Serializable
data class PlayTable(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid = uuid4(),
    val adventure: String
)
