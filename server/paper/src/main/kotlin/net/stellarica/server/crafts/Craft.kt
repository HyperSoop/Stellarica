package net.stellarica.server.crafts

import io.papermc.paper.entity.TeleportFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.audience.ForwardingAudience
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import net.stellarica.common.utils.OriginRelative
import net.stellarica.common.utils.asDegrees
import net.stellarica.common.utils.rotate
import net.stellarica.common.utils.rotateCoordinates
import net.stellarica.common.utils.toBlockPos
import net.stellarica.common.utils.toVec3
import net.stellarica.server.StellaricaServer.Companion.identifier
import net.stellarica.server.crafts.starships.Starship
import net.stellarica.server.mixin.BlockEntityMixin
import net.stellarica.server.multiblocks.MultiblockHandler
import net.stellarica.server.multiblocks.MultiblockInstance
import net.stellarica.server.utils.extensions.sendRichMessage
import net.stellarica.server.utils.extensions.toLocation
import net.stellarica.server.utils.extensions.toVec3
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * Base class for all Crafts; sets of moving blocks
 */
open class Craft(
	/**
	 * The point from which detection starts, and
	 * the craft rotates around
	 */
	var origin: BlockPos,
	var direction: Direction,
	var world: ServerLevel,
	var owner: Player? = null
) : ForwardingAudience {

	var detectedBlocks = mutableSetOf<BlockPos>()

	/**
	 * The.. uh.. passengers...
	 */
	var passengers = mutableSetOf<Entity>()

	/**
	 * The number of detected blocks
	 */
	val blockCount: Int
		get() = detectedBlocks.size

	var initialBlockCount: Int = 1
		private set

	val hullIntegrityPercent
		get() = blockCount / initialBlockCount.toDouble()

	var multiblocks = mutableSetOf<OriginRelative>()


	/**
	 * The blocks considered to be "inside" of the ship, but not neccecarily detected.
	 */
	var bounds = mutableSetOf<OriginRelative>()

	/**
	 * Message this craft's pilot, if it has one.
	 * If the ship isn't being piloted, message the owner.
	 * MiniMessage formatting is allowed
	 *
	 * @see messagePassengers
	 */
	fun messagePilot(message: String) {
		if (this is Starship) {
			pilot?.sendRichMessage(message) ?: owner?.sendRichMessage(message)
		}
	}

	companion object {
		const val sizeLimit = 10000
		val detectableKey = TagKey(Registries.BLOCK, identifier("starship_detectable"))
	}

	/**
	 * @return Whether [block] is considered to be inside this craft
	 */
	fun contains(block: BlockPos?): Boolean {
		block ?: return false
		return detectedBlocks.contains(block) || bounds.contains(
			OriginRelative.getOriginRelative(
				block,
				origin,
				direction
			)
		)
	}

	private fun calculateHitbox() {
		// might cause issues if run when direction isn't north?
		detectedBlocks
			.map { pos ->
				OriginRelative.getOriginRelative(pos, origin, direction)
			}
			.sortedBy { -it.y }
			.forEach { block ->
				val max = bounds.filter { it.x == block.x && it.z == block.z }.maxByOrNull { it.y }?.y ?: block.y
				for (y in block.y..max) {
					bounds.add(OriginRelative(block.x, y, block.z))
				}
			}
	}

	fun getMultiblock(pos: OriginRelative): MultiblockInstance? {
		println()
		println("origin: $origin, direction: $direction, pos: $pos")
		val mb = pos.getBlockPos(origin, direction)
		println("adjusted: $mb")
		return MultiblockHandler[world.getChunkAt(mb).bukkitChunk].firstOrNull { it.origin == mb }
			.also { println("found: $it") }
	}

	/**
	 * Translate the craft by [offset] blocks
	 * @see change
	 */
	fun move(offset: Vec3i) {
		val change = offset.toVec3()
		// don't want to let them pass a vec3
		// since the ships snap to blocks but entities can actually move by that much
		// relative entity teleportation will be messed up

		change({ current ->
			return@change current.add(change)
		}, world)
	}

	/**
	 * Rotate the craft and contents by [rotation]
	 * @see change
	 */
	fun rotate(rotation: Rotation) {
		change({ current ->
			return@change rotateCoordinates(current, origin.toVec3(), rotation)
		}, world, rotation)
	}

	private fun change(
		/** The transformation to apply to each block in the craft */
		modifier: (Vec3) -> Vec3,
		/** The world to move to */
		targetWorld: ServerLevel,
		/** The amount to rotate each directional block by */
		rotation: Rotation = Rotation.NONE,
		/** Callback called after the craft finishes moving */
		callback: () -> Unit = {}
	) {
		// calculate new blocks locations
		val targetsCHM = ConcurrentHashMap<BlockPos, BlockPos>()
		runBlocking {
			detectedBlocks.chunked(500).forEach { section ->
				// chunk into sections to process parallel
				launch(Dispatchers.Default) {
					section.forEach { current ->
						targetsCHM[current] = modifier(current.toVec3()).toBlockPos()
					}
				}
			}
		}

		// possible optimization because iterating over a CHM is pain
		val targets = targetsCHM.toMap()

		// We need to get the original blockstates before we start setting blocks
		// otherwise, if we just try to get the state as we set the blocks, the state might have already been set.
		// Consider moving a blocks from b to c. If a has already been moved to b, we don't want to copy a to c.
		// see https://discord.com/channels/1038493335679156425/1038504764356427877/1066184457264046170
		//
		// However, we don't need to go and get the states of the current blocks, as if it isn't in
		// the target blocks, it won't be overwritten, so we can just get it when it comes time to set the blocks
		//
		// This solution ~~may not be~~ isn't the most efficient, but it works
		val original = mutableMapOf<BlockPos, BlockState>()
		val entities = mutableMapOf<BlockPos, BlockEntity>()

		// check for collisions
		// if the world we're moving to isn't the world we're coming from, the whole map of original states we got is useless
		if (world == targetWorld) {
			targets.values.forEach { target ->
				val state = targetWorld.getBlockState(target)

				if (!state.isAir && !detectedBlocks.contains(target)) {
					sendRichMessage("<gold>Blocked by ${world.getBlockState(target).block.name} at <bold>(${target.x}, ${target.y}, ${target.z}</bold>)!\"")
					return
				}

				// also use this time to get the original state of these blocks
				if (state.hasBlockEntity()) entities[target] = targetWorld.getBlockEntity(target)!!

				original[target] = state
			}
		}

		// iterating over twice isn't great
		val newDetectedBlocks = mutableSetOf<BlockPos>()
		targets.forEach { (current, target) ->
			val currentBlock = original.getOrElse(current) { world.getBlockState(current) }

			// set the blocks
			setBlockFast(targetWorld, target, currentBlock.rotate(rotation))
			newDetectedBlocks.add(target)

			// move any entities
			if (entities.contains(current) || currentBlock.hasBlockEntity()) {
				val entity = entities.getOrElse(current) { world.getBlockEntity(current)!! }

				world.getChunk(current).removeBlockEntity(current)

				(entity as BlockEntityMixin).setWorldPosition(target)
				entity.level = targetWorld
				entity.setChanged()

				targetWorld.getChunk(target).setBlockEntity(entity)
			}
		}

		if (newDetectedBlocks.size != detectedBlocks.size)
			println("Lost ${detectedBlocks.size - newDetectedBlocks.size} blocks while moving! This is a bug!")

		if (world == targetWorld) {
			// set air where we were
			detectedBlocks.removeAll(newDetectedBlocks)
			detectedBlocks.forEach { setBlockFast(world, it, Blocks.AIR.defaultBlockState()) }
		}
		detectedBlocks = newDetectedBlocks

		// move multiblocks
		multiblocks.forEach { pos ->
			val mb = getMultiblock(pos)!!
			print("moved $mb to ")
			val new = MultiblockInstance(
				origin = modifier(mb.origin.toVec3()).toBlockPos(),
				world = targetWorld.world,
				direction = mb.direction.rotate(rotation),
				typeId = mb.typeId
			)
			println(new)
			MultiblockHandler[mb.chunk].remove(mb)
			MultiblockHandler[targetWorld.getChunkAt(new.origin).bukkitChunk].add(new)
		}

		// finish up
		movePassengers(modifier, rotation)
		world = targetWorld
		origin = modifier(origin.toVec3()).toBlockPos()
		direction = direction.rotate(rotation)
		callback()
	}


	fun detect() {
		var nextBlocksToCheck = detectedBlocks
		nextBlocksToCheck.add(origin)
		detectedBlocks = mutableSetOf()
		val checkedBlocks = nextBlocksToCheck.toMutableSet()

		val startTime = System.currentTimeMillis()

		val chunks = mutableSetOf<Chunk>()

		while (nextBlocksToCheck.size > 0) {
			val blocksToCheck = nextBlocksToCheck
			nextBlocksToCheck = mutableSetOf()

			for (currentBlock in blocksToCheck) {

				// todo: block tags for detection? this is idiocy
				if (world.getBlockState(currentBlock).block !in setOf(
						Blocks.JUKEBOX,
						Blocks.GRAY_CONCRETE,
						Blocks.FURNACE,
						Blocks.IRON_BLOCK
					)
				) continue

				if (detectedBlocks.size > sizeLimit) {
					owner?.sendRichMessage("<gold>Detection limit reached. (${sizeLimit} blocks)")
					nextBlocksToCheck.clear()
					detectedBlocks.clear()
					break
				}

				detectedBlocks.add(currentBlock)
				chunks.add(world.getChunkAt(currentBlock).bukkitChunk)

				// Slightly condensed from MSP's nonsense, but this could be improved
				for (x in -1..1) {
					for (y in -1..1) {
						for (z in -1..1) {
							if (x == y && z == y && y == 0) continue
							val block = currentBlock.offset(x, y, z)
							if (!checkedBlocks.contains(block)) {
								checkedBlocks.add(block)
								nextBlocksToCheck.add(block)
							}
						}
					}
				}
			}
		}

		val elapsed = System.currentTimeMillis() - startTime
		owner?.sendRichMessage("<green>Craft detected! (${detectedBlocks.size} blocks)")
		owner?.sendRichMessage(
			"<gray>Detected ${detectedBlocks.size} blocks in ${elapsed}ms. " +
					"(${detectedBlocks.size / elapsed.coerceAtLeast(1)} blocks/ms)"
		)
		owner?.sendRichMessage(
			"<gray>Calculated Hitbox in ${
				measureTimeMillis {
					calculateHitbox()
				}
			}ms. (${bounds.size} blocks)")

		// Detect all multiblocks
		multiblocks.clear()
		// this is probably slow
		multiblocks.addAll(chunks
			.map { MultiblockHandler[it] }
			.flatten()
			.filter { detectedBlocks.contains(it.origin) }
			.map { OriginRelative.getOriginRelative(it.origin, origin, direction) }
		)

		owner?.sendRichMessage("<gray>Detected ${multiblocks.size} multiblocks")
	}

	// A modified, kotlin-ified version of the block placement from
	// https://github.com/APDevTeam/Movecraft/blob/main/modules/v1_18_R2/src/main/java/net/countercraft/movecraft/compat/v1_18_R2/IWorldHandler.java
	// Under GPL-3 as noted in the readme
	/**
	 * Set the block at [position] in [world] to [data] using NMS
	 */
	private fun setBlockFast(world: Level, position: BlockPos, data: BlockState) {
		val chunk: LevelChunk = world.getChunkAt(position)
		val chunkSection = (position.y shr 4) - chunk.minSection
		var section = chunk.sections[chunkSection]
		if (section == null) {
			// Put a GLASS block to initialize the section. It will be replaced next with the real block.
			chunk.setBlockState(position, Blocks.GLASS.defaultBlockState(), false)
			section = chunk.sections[chunkSection]
		}
		if (section!!.getBlockState(position.x and 15, position.y and 15, position.z and 15) == data) {
			//Block is already of correct type and data, don't overwrite
			return
		}
		section.setBlockState(position.x and 15, position.y and 15, position.z and 15, data)
		world.sendBlockUpdated(position, data, data, 3)
		// world.lightEngine.checkBlock(position) // boolean corresponds to if chunk section empty
		//todo: LIGHTING IS FOR CHUMPS!
		chunk.isUnsaved = true
	}

	/**
	 * Move all passengers by offset.
	 * Uses bukkit to teleport entities, and NMS to move players.
	 */
	@Suppress("UnstableApiUsage")
	fun movePassengers(offset: (Vec3) -> Vec3, rotation: Rotation = Rotation.NONE) {
		passengers.forEach {
			// TODO: FIX
			// this is not a good solution because if there is any rotation, the player will not be translated by the offset
			// The result is that any ship movement that attempts to rotate and move in the same action will break.
			// For now there aren't any actions like that, but if there are in the future, this will need to be fixed.
			//
			// Rotating the whole ship around the adjusted origin will not work,
			// as rotating the ship 4 times does not bring it back to the original position
			//
			// However, without this dumb fix players do not rotate to the proper relative location
			val destination =
				if (rotation != Rotation.NONE) rotateCoordinates(
					it.location.toVec3(),
					origin.toVec3().add(
						Vec3(
							0.5,
							0.0,
							0.5
						)
					), rotation
				).toLocation(world.world)
				else offset(it.location.toVec3()).toLocation(world.world)


			destination.world = it.world // todo: fix

			destination.pitch = it.location.pitch
			destination.yaw = (it.location.yaw + rotation.asDegrees).toFloat()

			it.teleport(
				destination,
				PlayerTeleportEvent.TeleportCause.PLUGIN,
				TeleportFlag.EntityState.RETAIN_OPEN_INVENTORY, // this might cause issues...
				TeleportFlag.Relative.PITCH,
				TeleportFlag.Relative.YAW,
				TeleportFlag.Relative.Y,
				TeleportFlag.Relative.X,
				TeleportFlag.Relative.Z
			)
		}
	}

	override fun audiences() = passengers
}
