package com.mairwunnx.worldapi.api

import kotlinx.serialization.Serializable

@Serializable
data class Page<T>(
    val items: List<T>,
    /** Pass it as `cursor` to get the next page; absent on the last page. */
    val nextCursor: String? = null,
    val total: Int? = null,
)

/** Pages through a list that is computed in full, such as online players or loaded entities. */
fun <T> List<T>.page(limit: Int, cursor: String?): Page<T> {
    val start = cursor?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: badRequest("Unknown cursor", "invalid_cursor") } ?: 0
    val end = minOf(size, start + limit)
    val items = if (start >= size) emptyList() else subList(start, end)
    return Page(items, nextCursor = if (end < size) end.toString() else null, total = size)
}
