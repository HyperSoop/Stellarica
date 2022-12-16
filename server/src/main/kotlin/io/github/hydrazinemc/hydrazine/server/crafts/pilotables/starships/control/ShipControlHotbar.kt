package io.github.hydrazinemc.hydrazine.server.crafts.pilotables.starships.control

import io.github.hydrazinemc.hydrazine.server.crafts.pilotables.starships.Starship
import io.github.hydrazinemc.hydrazine.server.utils.extensions.craft
import io.github.hydrazinemc.hydrazine.server.utils.extensions.hotbar
import io.github.hydrazinemc.hydrazine.server.utils.gui.hotbar.HotbarMenu
import io.github.hydrazinemc.hydrazine.server.utils.gui.namedItem
import io.github.hydrazinemc.hydrazine.common.utils.rotation.RotationAmount
import io.github.hydrazinemc.hydrazine.server.utils.locations.BlockLocation
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Hotbar menu for basic ship control and movement functions
 */
object ShipControlHotbar : HotbarMenu() {
	override val toggleable = true

	private val menuEntries = mutableListOf(
		namedItem(Material.LIME_STAINED_GLASS, "<green>Precision Movement", null),
		null,
		null,
		null,
		namedItem(Material.BLUE_STAINED_GLASS, "<blue>Left", null), // these might be
		namedItem(Material.BLUE_STAINED_GLASS, "<blue>Right", null),
		null,
		namedItem(Material.FIRE_CHARGE, "<red>Fire", null),
		namedItem(Material.RED_CONCRETE, "<red>Unpilot Ship", null)
	)

	override fun onMenuOpened(player: Player) {
		player.hotbar = menuEntries
	}

	override fun onButtonClicked(index: Int, player: Player) {
		val ship = player.craft as? Starship ?: run {
			player.sendRichMessage("<red>You are not piloting a starship, yet the ship menu is open! This is a bug!")
			return
		}
		when (index) {
			0 -> ship.controlQueue.add { ship.queueMovement(BlockLocation(
				player.eyeLocation.direction.normalize().multiply(1.5f).toLocation(ship.origin.world!!))
			)}
			1 -> TODO() // ship.velocity -= Vector3(player.eyeLocation.direction.normalize())
			2 -> TODO() // ship.velocity = Vector3.zero
			4 -> ship.controlQueue.add { ship.queueRotation(RotationAmount.CLOCKWISE) }
			5 -> ship.controlQueue.add { ship.queueRotation(RotationAmount.COUNTERCLOCKWISE) }
			7 -> ship.weapons.fire()
			8 -> ship.controlQueue.add { ship.deactivateCraft() }
		}
	}
}