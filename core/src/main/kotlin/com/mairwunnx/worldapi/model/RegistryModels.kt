package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class RegistryInfo(
    /** The vanilla name, such as `item` or `mob_effect`. */
    val registry: String,
    val size: Int,
)

@Serializable
data class RegistryEntry(
    /** Such as `minecraft:oak_log`; what the endpoints that take an item, block, or effect expect. */
    val key: String,
)
