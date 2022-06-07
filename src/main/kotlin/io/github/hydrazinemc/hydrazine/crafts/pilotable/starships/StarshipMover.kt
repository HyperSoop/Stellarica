package io.github.hydrazinemc.hydrazine.crafts.pilotable.starships

import io.github.hydrazinemc.hydrazine.Hydrazine.Companion.pilotedCrafts
import io.github.hydrazinemc.hydrazine.Hydrazine.Companion.plugin
import io.github.hydrazinemc.hydrazine.utils.Vector3
import org.bukkit.scheduler.BukkitRunnable


/**
 * Main bukkit runnable for moving starships
 */
object StarshipMover : BukkitRunnable() {

	private var tickCounter = 0
		private set

	private val movesPerSecond: Float
		get() = 20 / ticksPerMove.toFloat()

	private var ticksPerMove = 0


	/**
	 * Should not be called manually, as this is part of a Bukkit runnable.
	 */
	override fun run() {
		tickCounter++
		if (tickCounter >= ticksPerMove) {
			pilotedCrafts.forEach { ship ->
				if (ship !is Starship) return@forEach
				if (ship.velocity == Vector3.zero) return@forEach
				if (ship.isMoving) return@forEach

				ship.queueMovement((ship.velocity / movesPerSecond).asBlockLocation)
				ship.messagePilot("|Velocity: <bold>(${ship.velocity.x}, ${ship.velocity.y}, ${ship.velocity.z})")
				ship.messagePilot("<gray>$movesPerSecond moves per second")
			}
			tickCounter = 0
			ticksPerMove = plugin.server.averageTickTime.toInt()
		}
	}
}
