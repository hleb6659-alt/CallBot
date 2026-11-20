package me.prime.callbot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class NullTotems implements Listener {

    private final CallBotPlugin plugin;
    private final NamespacedKey nullKey;
    private final NamespacedKey lockKey;
    private final Set<UUID> holders = new HashSet<>();

    public NullTotems(CallBotPlugin plugin) {
        this.plugin = plugin;
        this.nullKey = new NamespacedKey(plugin, "totem_null");
        this.lockKey = new NamespacedKey(plugin, "totem_lock");
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 20L, 20L);
    }

    public ItemStack nullTotem() {
        ItemStack t = new ItemStack(Material.TOTEM_OF_UNDYING);
        t.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        t.editMeta(m -> {
            m.displayName(Component.text("Totem of NULL").decoration(TextDecoration.ITALIC, false));
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(nullKey, PersistentDataType.BYTE, (byte) 1);
        });
        return t;
    }

    public ItemStack lockTotem() {
        ItemStack t = new ItemStack(Material.TOTEM_OF_UNDYING);
        t.editMeta(m -> {
            m.displayName(Component.text("Lock").decoration(TextDecoration.ITALIC, false));
            m.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
        });
        return t;
    }

    private boolean is(ItemStack it, NamespacedKey key) {
        return it != null && it.getType() == Material.TOTEM_OF_UNDYING && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean locked(ItemStack it) {
        return is(it, nullKey) || is(it, lockKey);
    }

    private boolean has(Player p, NamespacedKey key) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (is(it, key)) {
                return true;
            }
        }
        return is(p.getItemOnCursor(), key);
    }

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (has(p, nullKey)) {
                holders.add(p.getUniqueId());
            } else if (holders.remove(p.getUniqueId())) {
                punish();
            }
        }
    }

    private void punish() {
        for (Player q : Bukkit.getOnlinePlayers()) {
            if (has(q, lockKey)) {
                World w = q.getWorld();
                Location l = q.getLocation();
                l.setY(w.getMinHeight() - 100);
                q.teleport(l);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (!locked(p.getInventory().getItemInOffHand())) {
            return;
        }
        boolean offhandClick = e.getClickedInventory() == p.getInventory() && e.getSlot() == 40;
        if (offhandClick || e.getClick() == ClickType.SWAP_OFFHAND) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (locked(p.getInventory().getItemInOffHand()) && e.getRawSlots().contains(45)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }
}
