package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.routes.accessRoutes
import com.mairwunnx.worldapi.routes.blockRoutes
import com.mairwunnx.worldapi.routes.entityRoutes
import com.mairwunnx.worldapi.routes.eventRoutes
import com.mairwunnx.worldapi.routes.extensionRoutes
import com.mairwunnx.worldapi.routes.inventoryRoutes
import com.mairwunnx.worldapi.routes.playerDataRoutes
import com.mairwunnx.worldapi.routes.playerRoutes
import com.mairwunnx.worldapi.routes.recipeRoutes
import com.mairwunnx.worldapi.routes.scriptRoutes
import com.mairwunnx.worldapi.routes.searchRoutes
import com.mairwunnx.worldapi.routes.serverRoutes
import com.mairwunnx.worldapi.routes.statsRoutes
import com.mairwunnx.worldapi.routes.worldEffectRoutes
import com.mairwunnx.worldapi.routes.worldInfoRoutes
import com.mairwunnx.worldapi.routes.worldRoutes

/** Every REST endpoint, in the order the OpenAPI document lists their groups. */
object Routes {
    val all = serverRoutes + accessRoutes + worldRoutes + worldInfoRoutes + searchRoutes + blockRoutes + entityRoutes + worldEffectRoutes +
        playerRoutes + inventoryRoutes + playerDataRoutes + statsRoutes + recipeRoutes + eventRoutes + scriptRoutes + extensionRoutes
}
