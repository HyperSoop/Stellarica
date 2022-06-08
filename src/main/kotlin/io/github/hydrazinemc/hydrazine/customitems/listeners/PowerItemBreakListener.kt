package io.github.hydrazinemc.hydrazine.customitems.listeners

import io.github.hydrazinemc.hydrazine.customitems.isPowerable
import io.github.hydrazinemc.hydrazine.customitems.updatePowerDurability
import io.github.hydrazinemc.hydrazine.utils.Tasks
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemBreakEvent

/**
 * Cancels powerable items breaking, as their item durability is set by the [power]
 */
class PowerItemBreakListener : Listener {
	// Have to cancel damage on powerable items, otherwise ones at 0 power will break
	/**
	 * Cancels the breaking of powerable custom items
	 */
	@EventHandler
	fun preventDamage(event: PlayerItemBreakEvent) {
		if (!event.brokenItem.isPowerable) return
		// https://bukkit.org/threads/playeritembreakevent-cancelling.282678/
		event.brokenItem.amount += 1 // If there's a custom item dupe it's probably because of this
		Tasks.syncDelay(1) { event.brokenItem.updatePowerDurability() }
	}
}
