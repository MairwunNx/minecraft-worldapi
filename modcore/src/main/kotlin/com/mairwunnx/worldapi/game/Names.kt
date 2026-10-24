package com.mairwunnx.worldapi.game

import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Names set through `PATCH /players/{player}`: the chat name and the player list's, as Paper's `displayName` and
 * `playerListName` keep them. The game has a slot for neither, so mixins ask here; a name lasts until the player
 * leaves, as on Paper.
 */
object Names {
    val display = ConcurrentHashMap<UUID, Component>()
    val list = ConcurrentHashMap<UUID, Component>()

    fun forget(uuid: UUID) {
        display.remove(uuid)
        list.remove(uuid)
    }

    @JvmStatic
    fun display(uuid: UUID): Component? = display[uuid]

    @JvmStatic
    fun list(uuid: UUID): Component? = list[uuid]
}
