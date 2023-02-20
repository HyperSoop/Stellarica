package net.stellarica.server.crafts.pilotables.starships

import net.stellarica.server.utils.Tasks
import net.stellarica.server.utils.gui.setScoreboardContents
import org.bukkit.entity.Player
import kotlin.math.roundToInt

object StarshipHUD {
	private val ships = mutableSetOf<Starship>() // todo: weakref?

	init {
		Tasks.syncRepeat(1, 1) {
			ships.forEach { ship ->
				display(ship)
			}
		}
	}

	fun open(ship: Starship) = ships.add(ship)

	fun close(ship: Starship) {
		ships.remove(ship)
		ship.passengers.forEach {
			(it as? Player)?.setScoreboardContents("", listOf())
		}
	}

	private fun display(ship: Starship) {
		ship.passengers.forEach {
			(it as? Player)?.setScoreboardContents(
				"Starship", listOf(
					"Block Count: ${ship.blockCount}",
					"Origin: ${ship.origin}",
					if (ship.shields.maxShieldHealth != 0)
						"Shields: ${getShieldBar(ship)}" // - ${ship.shields.shieldHealth}/${ship.shields.maxShieldHealth}"
					else "<red>No Shields",
					"Hull: ${(ship.hullIntegrityPercent * 100).roundToInt()}%"
				)
			)
		}
	}

	private fun getShieldBar(ship: Starship): String {
		val percent = ship.shields.shieldHealth / ship.shields.maxShieldHealth.toDouble()
		val bar = "|".repeat((percent * 30).roundToInt())
		return "[<aqua>$bar<dark_gray>${"|".repeat(30 - bar.length)}<reset>]"
	}
}