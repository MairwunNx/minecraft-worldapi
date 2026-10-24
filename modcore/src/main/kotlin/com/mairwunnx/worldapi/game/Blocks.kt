package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.mixin.LockAccess
import com.mairwunnx.worldapi.model.BlockView
import com.mairwunnx.worldapi.model.Light
import com.mairwunnx.worldapi.model.TileEntityView
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.LockCode
import net.minecraft.world.Nameable
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.JukeboxBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState

val BlockState.typeKey: String get() = BuiltInRegistries.BLOCK.getKey(block).toString()

/** The full state, as Bukkit's `BlockData.getAsString` writes it: `minecraft:oak_log[axis=y]`. */
val BlockState.data: String get() = BlockStateParser.serialize(this)

/** A block state from `stone` or `minecraft:oak_stairs[facing=east]`, or 400 `invalid_block`. */
fun blockState(text: String): BlockState =
    runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.lowercase(), false).blockState() }
        .getOrElse { badRequest("Can't read block \"$text\": ${it.message}", "invalid_block") }

fun ServerLevel.biomeKey(pos: BlockPos): String =
    getNoiseBiome(QuartPos.fromBlock(pos.x), QuartPos.fromBlock(pos.y), QuartPos.fromBlock(pos.z)).unwrapKey().map { it.identifier().toString() }.orElse("unknown")

/** A block as Paper's `blockView` shows it. Server thread. */
fun blockView(level: ServerLevel, pos: BlockPos, inventories: Boolean): BlockView {
    val state = level.getBlockState(pos)
    return BlockView(
        world = level.worldName,
        x = pos.x, y = pos.y, z = pos.z,
        type = state.typeKey,
        data = state.data,
        light = Light(level.getBrightness(LightLayer.SKY, pos), level.getBrightness(LightLayer.BLOCK, pos), level.getMaxLocalRawBrightness(pos)),
        biome = level.biomeKey(pos),
        solid = state.`is`(net.minecraft.tags.BlockTags.BLOCKS_MOTION),
        liquid = state.liquid(),
        passable = state.getCollisionShape(level, pos).isEmpty,
        power = maxOf(level.getBestNeighborSignal(pos), if (state.hasProperty(net.minecraft.world.level.block.RedstoneWireBlock.POWER)) state.getValue(net.minecraft.world.level.block.RedstoneWireBlock.POWER) else 0),
        tileEntity = level.getBlockEntity(pos)?.let { tileEntity(level, it, inventories) },
    )
}

/** Bukkit's names for block states where they differ from the block entity's id. */
private val KINDS = mapOf("mob_spawner" to "CreatureSpawner", "structure_block" to "Structure", "trapped_chest" to "Chest")

/** What Paper calls a block entity: `Chest`, `Sign`, `CreatureSpawner`. */
fun kind(entity: BlockEntity): String {
    val id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.type)?.path ?: return entity.javaClass.simpleName
    return KINDS[id] ?: id.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
}

/** The inventory of a container block: both halves for a double chest, as Bukkit's `Container.getInventory`. */
fun containerOf(level: ServerLevel, entity: BlockEntity): Container? = when (entity) {
    is ChestBlockEntity -> (entity.blockState.block as? ChestBlock)?.let { ChestBlock.getContainer(it, entity.blockState, level, entity.blockPos, true) } ?: entity
    is Container -> entity
    else -> null
}

fun locked(entity: BlockEntity) = (entity as? BaseContainerBlockEntity)?.let { (it as LockAccess).`worldapi$lock`() != LockCode.NO_LOCK } == true

private fun tileEntity(level: ServerLevel, entity: BlockEntity, inventories: Boolean): TileEntityView {
    val container = containerOf(level, entity)
    val sign = entity as? SignBlockEntity
    val registries = level.server.registryAccess()
    return TileEntityView(
        kind = kind(entity),
        customName = (entity as? Nameable)?.customName?.plain(),
        items = container?.takeIf { inventories }?.views(registries),
        size = container?.containerSize,
        frontLines = sign?.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT)?.getMessages(false)?.map { it.plain() },
        backLines = sign?.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK)?.getMessages(false)?.map { it.plain() },
        spawnedType = (entity as? SpawnerBlockEntity)?.let { spawner ->
            (spawner.spawner as com.mairwunnx.worldapi.mixin.SpawnerAccess).`worldapi$next`()?.entityToSpawn()?.getString("id")?.orElse(null)
        },
        owner = (entity as? SkullBlockEntity)?.ownerProfile?.name()?.orElse(null),
        record = (entity as? JukeboxBlockEntity)?.theItem?.takeUnless { it.isEmpty }?.typeKey,
    )
}

/** Registry lookups need the server's: the dimension registry and the biome registry live there. */
fun ServerLevel.biomes() = registryAccess().lookupOrThrow(Registries.BIOME)
