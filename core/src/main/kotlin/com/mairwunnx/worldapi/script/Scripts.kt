package com.mairwunnx.worldapi.script

import com.mairwunnx.worldapi.model.ScriptLogLine
import com.mairwunnx.worldapi.model.ScriptRunResult
import com.mairwunnx.worldapi.model.ScriptView

/** Java scripts, where a platform runs them: Paper does, the mods don't yet. */
interface Scripts {
    suspend fun run(source: String, async: Boolean): ScriptRunResult
    fun list(): List<ScriptView>
    suspend fun load(id: String, source: String, persistent: Boolean, replace: Boolean): ScriptView
    fun get(id: String): ScriptView
    fun logs(id: String, limit: Int): List<ScriptLogLine>
    suspend fun unload(id: String, forget: Boolean = true)
}
