package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FileList(
    /** The folder, relative to the server folder; empty for the server folder itself. */
    val path: String,
    val entries: List<FileEntry>,
    /** More entries than one answer holds; list a subfolder instead. */
    val truncated: Boolean,
)

@Serializable
data class FileEntry(
    val name: String,
    /** Relative to the server folder, as the other /files endpoints take it. */
    val path: String,
    val directory: Boolean,
    val size: Long? = null,
    val modified: String,
    /** Whether /files/read may read it, and /files/write and the like change it, with the right permissions. */
    val readable: Boolean,
    val writable: Boolean,
    /** Why it can't be written, when it can't. */
    val readOnlyReason: String? = null,
)

@Serializable
data class FileContent(
    val path: String,
    /** Numbered lines, `<number>\t<text>`, as an editor shows them; `--` marks skipped lines between grep matches. */
    val content: String,
    /** Lines in the whole file. */
    val totalLines: Int,
    /** Lines returned. */
    val lines: Int,
    /** Pass as `offset` to read on; absent when nothing is left. */
    val nextOffset: Int? = null,
    /** Lines cut at the length limit; read a narrower range or grep to see them. */
    val cutLines: Int = 0,
    /** With grep: matching lines in the file from `offset` on. */
    val matches: Int? = null,
    val size: Long,
    val modified: String,
    /** Of the file's bytes; send it back as `expectedSha256` so a write fails if the file changed meanwhile. Absent for gzip logs. */
    val sha256: String? = null,
    /** Whether the write endpoints accept it, given the caller's permissions too. */
    val writable: Boolean = false,
    val readOnlyReason: String? = null,
)

@Serializable
data class FileWrite(
    val path: String,
    val content: String,
    /** The sha256 from /files/read; the write fails with 409 if the file changed since. Leave it out to create a file. */
    val expectedSha256: String? = null,
)

@Serializable
data class FileEdit(
    val path: String,
    val edits: List<TextEdit>,
    val expectedSha256: String? = null,
)

@Serializable
data class TextEdit(
    /** Exact text to find, spaces and line breaks included; it must appear once unless replaceAll. */
    val oldText: String,
    val newText: String,
    val replaceAll: Boolean = false,
)

@Serializable
data class FileSet(
    val path: String,
    /** Dotted paths to new values, such as `{"chunks.auto-save-interval": 6000}`; null removes the key. */
    val values: Map<String, JsonElement>,
    val expectedSha256: String? = null,
)

@Serializable
data class FileWritten(
    val path: String,
    val sha256: String,
    val size: Long,
    val created: Boolean,
    /** The previous version, under WorldAPI's backups folder; absent for a new file. */
    val backup: String? = null,
    /** Lines added and removed, roughly, for a quick check. */
    val added: Int,
    val removed: Int,
    /** When the change takes effect. */
    val note: String,
)

@Serializable
data class ConfigFiles(
    /** The server's own configs that exist here, as paths for /files. */
    val server: List<String>,
    /** Each plugin's config files. */
    val plugins: Map<String, List<String>>,
)
