package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.changes.Changes
import io.ktor.http.HttpStatusCode

/** How the undo endpoint describes itself, the same on every platform. */
const val UNDO_DESCRIPTION = "Puts back every block the change touched as it was, a container's items and a sign's text included. " +
    "A block that changed again since, by a player or a later change, stays as it is now unless `force` is on. " +
    "Blocks that physics moved, such as sand that fell, and entities aren't part of a change."

val changeRoutes = endpoints("Changes", Scope.WORLD_WRITE) {
    get(
        "/changes", "Changes the API made to blocks and containers, newest first, to undo",
        description = "Setting and filling blocks and editing containers record what they replace, in memory until the server stops. " +
            "The oldest changes drop out once they hold more than limits.undoBlocks blocks.",
        query = pageParams + string("world", "Only changes in this world"),
    ) {
        val world = query("world")
        api.changes.list().filter { world == null || it.world == world }.map { it.view() }.page(limit, cursor)
    }
}

/** The change `{id}` names, still in the journal and not undone yet. */
fun <S> ApiCall<*>.changeToUndo(changes: Changes<S>): Changes.Change<S> {
    val id = path("id").toLongOrNull() ?: badRequest("id must be a number")
    val change = changes.get(id) ?: notFound("change_not_found", "No change $id: it was never made, or it dropped out of the journal")
    if (change.undone) throw ApiException(HttpStatusCode.Conflict, "already_undone", "Change $id is undone already")
    auditTarget = "#$id ${change.world}: ${change.what}"
    return change
}
