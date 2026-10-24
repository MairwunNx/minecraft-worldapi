package com.mairwunnx.worldapi.routes

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.model.RecipeIngredient
import com.mairwunnx.worldapi.model.RecipeView
import com.mojang.serialization.JsonOps
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder

val recipeRoutes = endpoints("Recipes", Scope.SERVER_READ) {
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
            api.server.recipeManager.recipes.mapNotNull { recipeView(api.server, it)?.takeIf { view -> matches(view, item, uses) } }
                .sortedBy { it.key }.page(limit, cursor)
        }
    }
}

private fun itemKey(name: String): String {
    val id = Identifier.tryParse(name.lowercase()) ?: badRequest("Unknown item \"$name\"", "unknown_item")
    return BuiltInRegistries.ITEM.getOptional(id).orElse(null)?.takeIf { it != Items.AIR }?.let { BuiltInRegistries.ITEM.getKey(it).toString() }
        ?: badRequest("Unknown item \"$name\"", "unknown_item")
}

/** Whether a recipe makes [item] and takes [uses]; a null filter matches anything. */
internal fun matches(recipe: RecipeView, item: String?, uses: String?): Boolean =
    (item == null || recipe.result == item) && (uses == null || recipe.ingredients.any { uses in it.options })

/**
 * A recipe as Paper's recipe classes show it, read from the recipe as a data pack would write it: the game keeps its
 * result and ingredients private, and its codec says them all.
 */
private fun recipeView(server: MinecraftServer, holder: RecipeHolder<*>): RecipeView? {
    val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)
    val json = Recipe.CODEC.encodeStart(ops, net.minecraft.core.Holder.direct(holder.value())).result().orElse(null) as? JsonObject
        ?: return RecipeView(holder.id().identifier().toString(), "special", null, 1, null, emptyList(), null, null)
    val type = json["type"]?.asString?.removePrefix("minecraft:") ?: "special"
    fun options(element: JsonElement?): List<String> {
        element ?: return emptyList()
        val ingredient = Ingredient.CODEC.parse(ops, element).result().orElse(null) ?: return emptyList()
        return ingredient.items().map { it.unwrapKey().map { key -> key.identifier().toString() }.orElse("unknown") }.toList().distinct()
    }
    fun slot(name: String, element: JsonElement?) = element?.let { RecipeIngredient(name, options(it)) }
    val result = json["result"]?.let { element ->
        when {
            element.isJsonPrimitive -> element.asString to 1
            element.isJsonObject -> (element.asJsonObject["id"]?.asString ?: return@let null) to (element.asJsonObject["count"]?.asInt ?: 1)
            else -> null
        }
    }
    fun view(kind: String, ingredients: List<RecipeIngredient>, shape: List<String>? = null, withResult: Boolean = true) = RecipeView(
        key = holder.id().identifier().toString(),
        type = kind,
        result = if (withResult) result?.first?.let { Identifier.tryParse(it)?.toString() } else null,
        amount = if (withResult) result?.second ?: 1 else 1,
        shape = shape,
        ingredients = ingredients,
        cookingTimeTicks = json["cookingtime"]?.asInt?.takeIf { type in COOKING },
        experience = json["experience"]?.asFloat?.takeIf { type in COOKING },
    )
    return when (type) {
        "crafting_shaped" -> {
            val key = json.getAsJsonObject("key")
            view(type, key.entrySet().map { (char, element) -> RecipeIngredient(char, options(element)) }.sortedBy { it.slot },
                json.getAsJsonArray("pattern").map { it.asString })
        }
        "crafting_shapeless" -> view(type, (json["ingredients"] as? JsonArray).orEmpty().mapIndexed { i, element -> RecipeIngredient((i + 1).toString(), options(element)) })
        "crafting_transmute" -> view(type, listOfNotNull(slot("input", json["input"]), slot("material", json["material"])))
        in COOKING -> view(type, listOfNotNull(slot("input", json["ingredient"])))
        "stonecutting" -> view(type, listOfNotNull(slot("input", json["ingredient"])))
        "smithing_transform" -> view(type, listOfNotNull(slot("template", json["template"]), slot("base", json["base"]), slot("addition", json["addition"])))
        "smithing_trim" -> view(type, listOfNotNull(slot("template", json["template"]), slot("base", json["base"]), slot("addition", json["addition"])), withResult = false)
        else -> view("special", emptyList())
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

private val COOKING = setOf("smelting", "blasting", "smoking", "campfire_cooking")
