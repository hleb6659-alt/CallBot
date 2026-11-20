package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class ClownPierceBot extends PvpBot {

    private final ItemStack mace;
    private final ItemStack axe;
    private final ItemStack shield;
    private int windCharges, hopTimer, hopT, shieldDisabled, axeTicks;
    private boolean hopping;
    private double peakY;

    public ClownPierceBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.clownpierce.gapples", 50);
        eapples = plugin.getConfig().getInt("bots.clownpierce.enchanted-gapples", 15);
        windCharges = plugin.getConfig().getInt("bots.clownpierce.wind-charges", 128);
        hopTimer = hopTicks();
        pearls = 0;
        totemsLeft = 0;

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 11.0;

        ItemStack mc = new ItemStack(Material.MACE);
        mc.addUnsafeEnchantment(Enchantment.DENSITY, 4);
        mc.editMeta(m -> m.setUnbreakable(true));
        mace = mc;

        ItemStack a = new ItemStack(Material.NETHERITE_AXE);
        a.editMeta(m -> m.setUnbreakable(true));
        axe = a;

        ItemStack sh = new ItemStack(Material.SHIELD);
        sh.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        shield = sh;
    }

    private int hopTicks() {
        return plugin.getConfig().getInt("bots.clownpierce.hop-seconds", 20) * 20;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    public void spawn(Location loc, boolean flying) {
        Location at = loc;
        Player owner = Bukkit.getPlayer(summoner);
        if (owner != null) {
            int life = plugin.getConfig().getInt("bots.clownpierce.portal-seconds", 10) * 20;
            Location inside = manager.openPortal(owner, life);
            if (inside != null) {
                at = inside;
            }
        }
        super.spawn(at, false);
        if (isAlive()) {
            entity.setPortalCooldown(400);
        }
    }

    @Override
    protected ItemStack offhandItem() {
        return shield.clone();
    }

    @Override
    protected boolean busy() {
        return hopping;
    }

    @Override
    protected void abortModes() {
        hopping = false;
        holdWeapon();
    }

    private boolean shielding() {
        return !hopping && shieldDisabled <= 0 && attackCd > 1 && eatTicks == 0;
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
        double y = entity.getLocation().getY();
        if (entity.isOnGround()) {
            peakY = y;
        } else {
            peakY = Math.max(peakY, y);
        }
        if (shieldDisabled > 0) {
            shieldDisabled--;
        }
        if (axeTicks > 0 && --axeTicks == 0 && !hopping) {
            holdWeapon();
        }
        syncShield();
        if (hopping) {
            hopTick();
            return true;
        }
        if (hopTimer > 0) {
            hopTimer--;
        }
        boolean los = entity.hasLineOfSight(target);
        if (hopTimer == 0 && windCharges > 0 && eatTicks == 0 && entity.isOnGround() && los
                && dist >= 2.5 && dist <= 14.0) {
            hopping = true;
            hopT = 0;
            windCharges--;
            return true;
        }
        if (los && eatTicks == 0 && attackCd == 0 && dist <= reach() && target.isBlocking()) {
            attackCd = 12 + random.nextInt(5);
            entity.getEquipment().setItemInMainHand(axe);
            axeTicks = 8;
            entity.swingMainHand();
            manager.markVictim(target, this);
            target.damage(10.0, entity);
            target.setCooldown(Material.SHIELD, 100);
            target.clearActiveItem();
            entity.getWorld().playSound(target.getLocation(), "item.shield.break", SoundCategory.PLAYERS, 1f, 1f);
        }
        return false;
    }

    private void hopTick() {
        int n = hopT++;
        Location pos = entity.getLocation();
        if (n == 0) {
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.WIND_CHARGE));
            entity.swingMainHand();
            entity.launchProjectile(WindCharge.class, new Vector(0, -1.0, 0));
        } else if (n == 2) {
            double hx = 0, hz = 0;
            if (target != null && target.getWorld() == entity.getWorld()) {
                Location tl = target.getLocation();
                double dx = tl.getX() - pos.getX();
                double dz = tl.getZ() - pos.getZ();
                double dh = Math.hypot(dx, dz);
                if (dh > 1.0) {
                    hx = dx / dh * 0.3;
                    hz = dz / dh * 0.3;
                }
            }
            entity.setVelocity(new Vector(hx, 1.25, hz));
            entity.getEquipment().setItemInMainHand(mace);
            peakY = pos.getY();
        }
        if (n < 2) {
            holdStill();
            return;
        }
        entity.setFallDistance(0f);
        if (target == null || target.getWorld() != entity.getWorld()) {
            endHop();
            return;
        }
        Location aim = target.getLocation().add(0, 1.0, 0);
        Vector toT = new Vector(aim.getX() - pos.getX(), 0, aim.getZ() - pos.getZ());
        double dh = toT.length();
        Vector vel = entity.getVelocity();
        if (dh > 0.5) {
            toT.multiply(1.0 / dh);
            double s = Math.min(0.32, dh * 0.2 + 0.05);
            vel.setX(toT.getX() * s);
            vel.setZ(toT.getZ() * s);
            entity.setVelocity(vel);
        }
        face(aim, 60f);
        if (vel.getY() < -0.1 && pos.distance(aim) <= 3.2) {
            maceHit();
            return;
        }
        if ((n > 6 && entity.isOnGround()) || n > 80) {
            endHop();
        }
    }

    private void endHop() {
        hopping = false;
        hopTimer = hopTicks();
        holdWeapon();
    }

    private void maceHit() {
        double fall = Math.min(30.0, Math.max(0.0, peakY - entity.getLocation().getY()));
        double dmg = 6.0;
        if (fall > 1.5) {
            dmg += Math.min(fall, 3.0) * 4.0
                    + Math.max(0.0, Math.min(fall - 3.0, 5.0)) * 2.0
                    + Math.max(0.0, fall - 8.0)
                    + fall * 2.0;
        }
        entity.swingMainHand();
        boolean wasBlocking = target.isBlocking();
        double hpBefore = target.getHealth() + target.getAbsorptionAmount();
        Vector prev = target.getVelocity();
        manager.markVictim(target, this);
        target.damage(dmg, entity);
        if (wasBlocking && target.getHealth() + target.getAbsorptionAmount() >= hpBefore) {
            target.setVelocity(prev);
        }
        entity.getWorld().playSound(target.getLocation(), "item.mace.smash_air", SoundCategory.PLAYERS, 1f, 1f);
        entity.setFallDistance(0f);
        peakY = entity.getLocation().getY();
        entity.setVelocity(new Vector(0, 0.3, 0));
        endHop();
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
