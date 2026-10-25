package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiException
import io.ktor.http.HttpStatusCode

/** A WorldAPI permission node in a command, such as `lp user Steve permission set worldapi.admin`. */
val WORLDAPI_NODE = Regex("""(?i)(?<![\w.])worldapi\.(\*|[a-z0-9_]+)""")

/** worldapi.admin opens /worldapi, which makes tokens with any permission; the API never hands it out or takes it. */
fun refuseProtected(command: String) {
    if (WORLDAPI_NODE.containsMatchIn(command)) {
        throw ApiException(HttpStatusCode.Forbidden, "protected_permission", "WorldAPI's own permissions (worldapi.*) can't change through the API; change them in game or in the permissions plugin")
    }
}

/** Why making operators needs `server:console`, for both platforms' docs. */
const val OPERATORS_NEED_CONSOLE = "Needs `server:console`, not `players:moderate`: an operator may run every command, `/worldapi` among them."
