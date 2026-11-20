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
import org.bukkit.util.Vector;

import java.util.UUID;

public final class SunSoldierBot extends PvpBot {

    private final ItemStack shield;
    private final ItemStack axe;
    private int shieldDisabled, axeTicks;
    private boolean useShield;

    public SunSoldierBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        helmet = armor(Material.DIAMOND_HELMET);
        chestplate = armor(Material.DIAMOND_CHESTPLATE);
        leggings = armor(Material.DIAMOND_LEGGINGS);
        boots = armor(Material.DIAMOND_BOOTS);

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 7.0 + 0.5 * 2 + 0.5;

        ItemStack a = new ItemStack(Material.DIAMOND_AXE);
        a.editMeta(m -> m.setUnbreakable(true));
        axe = a;

        gapples = 10;
        eapples = 0;
        pearls = 0;
        totemsLeft = 0;
        shield = sunShield();
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 1);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    private static ItemStack sunShield() {
        ItemStack sh = new ItemStack(Material.SHIELD);
        try {
            sh.editMeta(BlockStateMeta.class, m -> {
                Banner banner = (Banner) m.getBlockState();
                banner.setBaseColor(DyeColor.WHITE);
                banner.addPattern(new Pattern(DyeColor.YELLOW, PatternType.CROSS));
                banner.addPattern(new Pattern(DyeColor.YELLOW, PatternType.STRAIGHT_CROSS));
                banner.addPattern(new Pattern(DyeColor.YELLOW, PatternType.CIRCLE));
                m.setBlockState(banner);
            });
        } catch (Throwable ignored) {
        }
        return sh;
    }

    @Override
    protected ItemStack offhandItem() {
        return shield.clone();
    }

    private boolean shielding() {
        return useShield && shieldDisabled <= 0 && attackCd > 1 && eatTicks == 0;
    }

    private void syncShield() {
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
        if (axeTicks > 0 && --axeTicks == 0) {
            holdWeapon();
        }
        if (attackCd == 0) {
            useShield = random.nextInt(100) < 12;
        }
        syncShield();
        if (eatTicks == 0 && attackCd == 0 && dist <= reach() && target.isBlocking()
                && entity.hasLineOfSight(target)) {
            attackCd = 12 + random.nextInt(5);
            entity.getEquipment().setItemInMainHand(axe);
            axeTicks = 8;
            entity.swingMainHand();
            manager.markVictim(target, this);
            target.damage(9.0, entity);
            target.setCooldown(Material.SHIELD, 100);
            target.clearActiveItem();
            entity.getWorld().playSound(target.getLocation(), "item.shield.break", SoundCategory.PLAYERS, 1f, 1f);
        }
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
