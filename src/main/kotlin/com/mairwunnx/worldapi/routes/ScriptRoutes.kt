package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.model.ScriptLoadRequest
import com.mairwunnx.worldapi.model.ScriptLogLine
import com.mairwunnx.worldapi.model.ScriptRunRequest
import com.mairwunnx.worldapi.model.ScriptRunResult
import com.mairwunnx.worldapi.model.ScriptView

private const val SCRIPT_DOCS = "Scripts are Java, compiled on the server. They may use Java and the Paper API only; blocked calls are listed in config.yml. " +
    "Off unless scripts.enabled is true, and every call needs the code scope, which admin doesn't include."

val scriptRoutes = endpoints("Scripts") {
    post<ScriptRunRequest, ScriptRunResult>(
        "/scripts/run", "Compile and run Java once, and return what it returns", Scope.CODE,
        description = "$SCRIPT_DOCS Send a class implementing WorldApiScript, or just the body of `run`: `return server.getOnlinePlayers().size();`. " +
            "`ctx` and `server` are in scope. The script unloads as soon as it returns.",
    ) { request ->
        if (request.thread !in setOf("main", "async")) badRequest("thread must be main or async")
        auditDetail = request.source
        api.scripts.run(request.source, async = request.thread == "async")
    }

    get("/scripts", "Loaded scripts", scope = Scope.CODE) {
        api.scripts.list()
    }

    post<ScriptLoadRequest, ScriptView>(
        "/scripts", "Load a script that stays: it can listen to events and schedule tasks", Scope.CODE,
        description = "$SCRIPT_DOCS The class's `enable(ctx)` runs on the server thread. Register listeners with `ctx.on(...)` and tasks with `ctx.every(...)`: unloading removes them.",
        status = io.ktor.http.HttpStatusCode.Created,
    ) { request ->
        auditTarget = request.id
        auditDetail = request.source
        api.scripts.load(request.id, request.source, request.persistent, request.replace)
    }

    get("/scripts/{id}", "One script with its source", scope = Scope.CODE) {
        api.scripts.get(path("id"))
    }

    get("/scripts/{id}/logs", "The latest lines a script logged", scope = Scope.CODE, query = listOf(integer("limit", "How many lines; 100 by default"))) {
        val limit = (int("limit") ?: 100).coerceIn(1, 10_000)
        api.scripts.logs(path("id"), limit)
    }

    delete(
        "/scripts/{id}", "Unload a script: remove its listeners and tasks", Scope.CODE,
        description = "A persistent script's saved copy goes too, unless `keepSaved` is true; then it loads again on the next start.",
        query = listOf(boolean("keepSaved", "Keep the saved copy of a persistent script")),
    ) {
        auditTarget = path("id")
        api.scripts.unload(path("id"), forget = bool("keepSaved") != true)
    }
}

