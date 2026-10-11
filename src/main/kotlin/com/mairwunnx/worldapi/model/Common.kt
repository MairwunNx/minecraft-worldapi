package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class Position(val x: Double, val y: Double, val z: Double)

@Serializable
data class BlockPosition(val x: Int, val y: Int, val z: Int)

@Serializable
data class LocationView(
    /** World name, such as `world` or `world_nether`. */
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float? = null,
    val pitch: Float? = null,
)

@Serializable
data class PlayerRef(val uuid: String, val name: String? = null)

@Serializable
data class Done(val done: Boolean = true, val message: String? = null)

/** How text in a request is read: as plain text or as MiniMessage markup like `<green>Hi`. */
@Serializable
enum class TextFormat {
    @kotlinx.serialization.SerialName("plain") PLAIN,
    @kotlinx.serialization.SerialName("minimessage") MINIMESSAGE,
}
