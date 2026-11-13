package me.prime.callbot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HornListener implements Listener {

    private final CallBotPlugin plugin;
    private final HornItem horn;
    private final BotManager bots;
    private final Set<UUID> busy = new HashSet<>();

    public HornListener(CallBotPlugin plugin, HornItem horn, BotManager bots) {
        this.plugin = plugin;
        this.horn = horn;
        this.bots = bots;
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (e.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack item = e.getItem();
        EquipmentSlot hand = e.getHand();
        if (hand == null || !horn.is(item)) {
            return;
        }
        Player player = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !player.isSneaking()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!busy.add(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            busy.remove(id);
            ItemStack current = player.getInventory().getItem(hand);
            if (horn.is(current)) {
                int left = current.getAmount() - 1;
                if (left <= 0) {
                    player.getInventory().setItem(hand, null);
                } else {
                    current.setAmount(left);
                    player.getInventory().setItem(hand, current);
                }
            }
        }, 2L);
        bots.scheduleSummon(id);
    }
}
