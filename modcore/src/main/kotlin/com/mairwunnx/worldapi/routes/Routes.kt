package com.mairwunnx.worldapi.routes

/** Every REST endpoint on the mods, in the order the OpenAPI document lists their groups, as on Paper. */
object Routes {
    val all = serverRoutes + statsRoutes + eventRoutes + fileRoutes + manageRoutes + scriptRoutes + extensionRoutes
}
