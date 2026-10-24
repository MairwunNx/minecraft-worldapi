package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ScriptRunRequest(
    /** A whole class implementing WorldApiScript, or just the body of its `run` method. */
    val source: String,
    /** `main` runs on the server thread and may touch the world; `async` runs beside it. */
    val thread: String = "main",
)

@Serializable
data class ScriptRunResult(
    /** What `run` returned, as JSON; objects without a JSON form become their toString(). */
    val result: JsonElement? = null,
    val output: List<String>,
    val durationMillis: Long,
)

@Serializable
data class ScriptLoadRequest(
    /** Lowercase letters, digits, dashes, and underscores. */
    val id: String,
    /** A class implementing WorldApiScript; its `enable` runs on load. */
    val source: String,
    /** Save the source and load it again after a restart. */
    val persistent: Boolean = false,
    /** Unload a script with the same id first. */
    val replace: Boolean = false,
)

@Serializable
data class ScriptView(
    val id: String,
    val className: String,
    val persistent: Boolean,
    val loadedAt: String,
    val listeners: Int,
    val tasks: Int,
    val errors: Int,
    /** Only in the single-script view. */
    val source: String? = null,
)

@Serializable
data class ScriptLogLine(val at: String, val message: String)
