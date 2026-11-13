package me.prime.callbot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class HornItem {

    private final NamespacedKey key;

    public HornItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "call_horn");
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.GOAT_HORN);
        item.editMeta(MusicInstrumentMeta.class, meta -> {
            meta.setInstrument(MusicInstrument.CALL);
            meta.displayName(Component.text("Call", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("~ By Prime", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean is(ItemStack item) {
        if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
