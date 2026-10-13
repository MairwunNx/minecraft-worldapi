package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.model.RecipeIngredient
import com.mairwunnx.worldapi.model.RecipeView
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CampfireRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.SmithingTrimRecipe
import org.bukkit.inventory.SmokingRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.TransmuteRecipe

val recipeRoutes = endpoints("Recipes") {
    get(
        "/recipes", "Recipes that make an item, or use it",
        description = "Give `item` for the recipes that make it, `uses` for the recipes that take it as an ingredient, or both. " +
            "Covers crafting, furnaces, smokers, blast furnaces, campfires, stonecutters, and smithing tables, plugin recipes included.",
        query = pageParams + listOf(
            string("item", "Item key the recipe makes, such as `minecraft:torch`"),
            string("uses", "Item key the recipe takes, such as `minecraft:coal`"),
        ),
    ) {
        val item = query("item")?.let(::itemKey)
        val uses = query("uses")?.let(::itemKey)
        if (item == null && uses == null) badRequest("Give item, uses, or both")
        val (limit, cursor) = limit to cursor
        main {
            val found = mutableListOf<RecipeView>()
            api.server.recipeIterator().forEach { recipe -> recipeView(recipe)?.takeIf { matches(it, item, uses) }?.let(found::add) }
            found.sortedBy { it.key }.page(limit, cursor)
        }
    }
}

private fun itemKey(name: String): String =
    Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir }?.key?.asString() ?: badRequest("Unknown item \"$name\"", "unknown_item")

/** Whether a recipe makes [item] and takes [uses]; a null filter matches anything. */
internal fun matches(recipe: RecipeView, item: String?, uses: String?): Boolean =
    (item == null || recipe.result == item) && (uses == null || recipe.ingredients.any { uses in it.options })

private fun recipeView(recipe: Recipe): RecipeView? {
    val key = (recipe as? Keyed)?.key?.asString() ?: return null
    fun view(type: String, ingredients: List<RecipeIngredient>, shape: List<String>? = null, result: ItemStack? = recipe.result) = RecipeView(
        key = key,
        type = type,
        result = result?.takeIf { !it.type.isAir }?.type?.key?.asString(),
        amount = result?.amount ?: 1,
        shape = shape,
        ingredients = ingredients,
        cookingTimeTicks = (recipe as? CookingRecipe<*>)?.cookingTime,
        experience = (recipe as? CookingRecipe<*>)?.experience,
    )
    fun slot(name: String, choice: RecipeChoice?) = choice?.let { RecipeIngredient(name, options(it)) }
    return when (recipe) {
        is ShapedRecipe -> view("crafting_shaped", recipe.choiceMap.mapNotNull { (char, choice) -> slot(char.toString(), choice) }.sortedBy { it.slot }, recipe.shape.toList())
        is ShapelessRecipe -> view("crafting_shapeless", recipe.choiceList.mapIndexed { i, choice -> RecipeIngredient((i + 1).toString(), options(choice)) })
        is TransmuteRecipe -> view("crafting_transmute", listOfNotNull(slot("input", recipe.input), slot("material", recipe.material)))
        is CookingRecipe<*> -> view(
            when (recipe) {
                is BlastingRecipe -> "blasting"
                is SmokingRecipe -> "smoking"
                is CampfireRecipe -> "campfire_cooking"
                is FurnaceRecipe -> "smelting"
                else -> "cooking"
            },
            listOfNotNull(slot("input", recipe.inputChoice)),
        )
        is StonecuttingRecipe -> view("stonecutting", listOfNotNull(slot("input", recipe.inputChoice)))
        is SmithingTransformRecipe -> view("smithing_transform", listOfNotNull(slot("template", recipe.template), slot("base", recipe.base), slot("addition", recipe.addition)))
        is SmithingTrimRecipe -> view("smithing_trim", listOfNotNull(slot("template", recipe.template), slot("base", recipe.base), slot("addition", recipe.addition)), result = null)
        is SmithingRecipe -> view("smithing", listOfNotNull(slot("base", recipe.base), slot("addition", recipe.addition)))
        else -> view("special", emptyList())
    }
}

private fun options(choice: RecipeChoice): List<String> = when (choice) {
    is RecipeChoice.MaterialChoice -> choice.choices.map { it.key.asString() }
    is RecipeChoice.ExactChoice -> choice.choices.map { it.type.key.asString() }
    is RecipeChoice.ItemTypeChoice -> choice.itemTypes().values().map { it.key().asString() }
    else -> listOf(choice.itemStack.type.key.asString())
}.distinct()
