package net.stellarica.server.material.type.block

import net.minecraft.resources.ResourceLocation
import net.stellarica.server.material.custom.block.CustomBlock
import net.stellarica.server.material.custom.block.CustomBlocks
import net.stellarica.server.material.type.item.ItemType
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.craftbukkit.v1_19_R3.block.data.CraftBlockData

interface BlockType {
	fun getBukkitBlockData(): org.bukkit.block.data.BlockData
	fun getVanillaBlockState(): net.minecraft.world.level.block.state.BlockState
	fun getBukkitBlock(): org.bukkit.Material
	fun getVanillaBlock(): net.minecraft.world.level.block.Block
	fun getId(): ResourceLocation
	fun getStringId(): String = getId().path
	fun getItem(): ItemType?

	val isCustom: Boolean
		get() = this is CustomBlockType

	companion object {
		fun of(block: CustomBlock): CustomBlockType {
			return CustomBlockType(block)
		}

		fun of(block: net.minecraft.world.level.block.state.BlockState): BlockType {
			TODO()
		}

		fun of(block: org.bukkit.block.data.BlockData): BlockType {
			if (block !is NoteBlock) return of(block.material)

			return CustomBlocks.all().firstOrNull {
				it.note == block.note && it.instrument == block.instrument
			}?.let { CustomBlockType(it) } ?: of(block.material)
		}

		fun of(block: org.bukkit.block.BlockState): BlockType {
			return of(block.blockData)
		}

		fun of(block: net.minecraft.world.level.block.Block): VanillaBlockType {
			return VanillaBlockType(block)
		}

		fun of(block: org.bukkit.Material): VanillaBlockType {
			return of((block.createBlockData() as CraftBlockData).state.block)
		}

		fun of(block: ResourceLocation): BlockType? {
			return CustomBlocks.byId(block)?.let { CustomBlockType(it) }
				?: org.bukkit.Material.getMaterial(block.path)?.let { of(it) }
		}

		fun of(block: org.bukkit.block.Block): BlockType {
			return of(block.blockData)
		}
	}
}