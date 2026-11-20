package me.prime.callbot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HornListener implements Listener {

    private final CallBotPlugin plugin;
    private final HornItem horn;
    private final BotManager bots;

    public HornListener(CallBotPlugin plugin, HornItem horn, BotManager bots) {
        this.plugin = plugin;
        this.horn = horn;
        this.bots = bots;
    }

    @EventHandler
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
        bots.scheduleSummon(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.setCooldown(Material.GOAT_HORN, 0), 2L);
    }
}
