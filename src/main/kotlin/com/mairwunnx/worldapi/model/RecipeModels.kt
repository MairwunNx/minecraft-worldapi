package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class RecipeView(
    /** Recipe key, such as `minecraft:torch`. */
    val key: String,
    /**
     * `crafting_shaped`, `crafting_shapeless`, `crafting_transmute`, `smelting`, `blasting`, `smoking`,
     * `campfire_cooking`, `stonecutting`, `smithing_transform`, `smithing_trim`, or `special` for
     * recipes the game computes, such as map cloning.
     */
    val type: String,
    /** Item key of what comes out; absent when the game decides it, as for trims. */
    val result: String? = null,
    val amount: Int = 1,
    /** Shaped recipes only: the grid rows, top to bottom. Each character names an ingredient; a space stays empty. */
    val shape: List<String>? = null,
    val ingredients: List<RecipeIngredient>,
    /** Furnaces, smokers, blast furnaces, and campfires. */
    val cookingTimeTicks: Int? = null,
    val experience: Float? = null,
)

@Serializable
data class RecipeIngredient(
    /**
     * Where it goes: a character of the shape, a number for shapeless recipes, or `input`,
     * `material`, `template`, `base`, `addition`.
     */
    val slot: String,
    /** Item keys; any one of them works. */
    val options: List<String>,
)
