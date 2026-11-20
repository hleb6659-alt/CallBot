package me.prime.callbot;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Banner;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class SoldierBot extends PvpBot {

    public enum Tier { PRIVATE, GENERAL }

    private final ItemStack shield;
    private int shieldDisabled;

    public SoldierBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner, Tier tier) {
        super(plugin, manager, type, summoner);
        boolean priv = tier == Tier.PRIVATE;
        int prot = priv ? 2 : 3;
        int sharp = priv ? 2 : 3;
        TrimMaterial trim = priv ? TrimMaterial.NETHERITE : TrimMaterial.REDSTONE;

        helmet = armor(Material.NETHERITE_HELMET, prot, trim);
        chestplate = armor(Material.NETHERITE_CHESTPLATE, prot, trim);
        leggings = armor(Material.NETHERITE_LEGGINGS, prot, trim);
        boots = armor(Material.NETHERITE_BOOTS, prot, trim);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, sharp);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 8.0 + 0.5 * sharp + 0.5;

        gapples = priv ? 10 : 20;
        eapples = 0;
        pearls = 0;
        totemsLeft = 0;
        shield = priv ? bannerShield() : null;
    }

    private static ItemStack armor(Material material, int prot, TrimMaterial trim) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, prot);
        it.editMeta(org.bukkit.inventory.meta.ArmorMeta.class, meta -> {
            meta.setUnbreakable(true);
            meta.setTrim(new ArmorTrim(trim, TrimPattern.SENTRY));
        });
        return it;
    }

    private static ItemStack bannerShield() {
        ItemStack sh = new ItemStack(Material.SHIELD);
        try {
            sh.editMeta(BlockStateMeta.class, m -> {
                Banner banner = (Banner) m.getBlockState();
                banner.setBaseColor(DyeColor.BLACK);
                banner.addPattern(new Pattern(DyeColor.WHITE, PatternType.CROSS));
                m.setBlockState(banner);
            });
        } catch (Throwable ignored) {
        }
        return sh;
    }

    @Override
    protected ItemStack offhandItem() {
        return shield == null ? null : shield.clone();
    }

    private boolean shielding() {
        return shield != null && shieldDisabled <= 0 && attackCd > 1 && eatTicks == 0;
    }

    private void syncShield() {
        if (shield == null) {
            return;
        }
        boolean want = shielding();
        boolean up = entity.isHandRaised();
        if (want && !up) {
            entity.startUsingItem(EquipmentSlot.OFF_HAND);
        } else if (!want && up) {
            entity.clearActiveItem();
        }
    }

    @Override
    protected boolean specialTick(double dist) {
        if (shieldDisabled > 0) {
            shieldDisabled--;
        }
        syncShield();
        return false;
    }

    @Override
    public void onAttacked(EntityDamageByEntityEvent e) {
        if (!shielding()) {
            return;
        }
        DamageCause cause = e.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK
                && cause != DamageCause.PROJECTILE) {
            return;
        }
        Entity damager = e.getDamager();
        Vector toAttacker = damager.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0);
        Vector facing = entity.getLocation().getDirection().setY(0);
        if (toAttacker.lengthSquared() < 1e-4 || facing.lengthSquared() < 1e-4) {
            return;
        }
        if (toAttacker.normalize().dot(facing.normalize()) <= 0) {
            return;
        }
        e.setCancelled(true);
        entity.getWorld().playSound(entity.getLocation(), "item.shield.block", SoundCategory.PLAYERS, 1f, 1f);
        if (damager instanceof Player pl && pl.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) {
            shieldDisabled = 100;
            entity.getWorld().playSound(entity.getLocation(), "item.shield.break", SoundCategory.PLAYERS, 1f, 1f);
        }
    }
}
