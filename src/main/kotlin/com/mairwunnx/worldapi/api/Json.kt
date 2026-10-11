package com.mairwunnx.worldapi.api

import kotlinx.serialization.json.Json

/**
 * One JSON format for the whole API. Unknown keys fail decoding, so a typo in a write request
 * is an error instead of a silent no-op. Nulls are left out of responses to keep them small.
 */
val ApiJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = false
}
