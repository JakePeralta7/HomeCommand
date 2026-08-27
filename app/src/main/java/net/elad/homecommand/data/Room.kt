package net.elad.homecommand.data

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class Room(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    /** Ordering position on the home screen; lower values appear first. */
    @SerializedName("position") val position: Int = 0,
)
