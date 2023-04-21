package net.stellarica.server.multiblock.matching

import net.stellarica.server.material.type.block.BlockType

@JvmInline
value class MultiBlockMatcher(val types: Set<BlockType>) : BlockMatcher {
	override fun matches(block: BlockType) = block in types
}