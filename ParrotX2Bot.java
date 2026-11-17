package me.prime.callbot;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class ParrotX2Bot extends PvpBot {

    private enum Style { CROSSBOW, MACE }

    private final ItemStack crossbow;
    private final ItemStack mace;
    private Style style = Style.CROSSBOW;
    private int windCharges, styleTicks, shotCd = 40, hopCd = 20, hopT;
    private boolean hopping;
    private double peakY;

    public ParrotX2Bot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.parrotx2.gapples", 45);
        eapples = plugin.getConfig().getInt("bots.parrotx2.enchanted-gapples", 7);
        pearls = plugin.getConfig().getInt("bots.parrotx2.pearls", 12);
        windCharges = plugin.getConfig().getInt("bots.parrotx2.wind-charges", 128);
        styleTicks = plugin.getConfig().getInt("bots.parrotx2.style-seconds", 30) * 20;
        totemsLeft = 0;

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack cb = new ItemStack(Material.CROSSBOW);
        cb.editMeta(CrossbowMeta.class, m -> {
            m.addChargedProjectile(rocket(1));
            m.setUnbreakable(true);
        });
        crossbow = cb;

        ItemStack mc = new ItemStack(Material.MACE);
        mc.editMeta(m -> m.setUnbreakable(true));
        mace = mc;

        weapon = crossbow;
        meleeDamage = 6.0;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    private static FireworkEffect purple() {
        return FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.PURPLE).build();
    }

    private static ItemStack rocket(int count) {
        ItemStack it = new ItemStack(Material.FIREWORK_ROCKET, count);
        it.editMeta(FireworkMeta.class, m -> {
            m.setPower(1);
            for (int i = 0; i < 7; i++) {
                m.addEffect(purple());
            }
        });
        return it;
    }

    @Override
    protected ItemStack offhandItem() {
        return rocket(64);
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

    @Override
    protected double preferredRange() {
        return style == Style.CROSSBOW ? 9.0 : super.preferredRange();
    }

    @Override
    protected double speed() {
        return style == Style.CROSSBOW ? 0.27 : 0.23;
    }

    @Override
    protected double strafeWeight() {
        return style == Style.CROSSBOW ? 0.8 : 0.2;
    }

    @Override
    protected boolean alwaysStrafe() {
        return style == Style.CROSSBOW;
    }

    @Override
    protected boolean canMelee() {
        return style == Style.MACE && !hopping;
    }

    @Override
    protected boolean specialTick(double dist) {
        double y = entity.getLocation().getY();
        if (entity.isOnGround()) {
            peakY = y;
        } else {
            peakY = Math.max(peakY, y);
        }
        if (--styleTicks <= 0) {
            switchStyle();
        }
        if (style == Style.CROSSBOW) {
            return crossbowTick(dist);
        }
        return maceTick(dist);
    }

    private void switchStyle() {
        style = style == Style.CROSSBOW ? Style.MACE : Style.CROSSBOW;
        styleTicks = plugin.getConfig().getInt("bots.parrotx2.style-seconds", 30) * 20;
        hopping = false;
        shotCd = 20;
        hopCd = 20;
        weapon = style == Style.CROSSBOW ? crossbow : mace;
        meleeDamage = style == Style.CROSSBOW ? 2.0 : 6.0;
        if (eatTicks == 0) {
            holdWeapon();
        }
    }

    private boolean crossbowTick(double dist) {
        if (shotCd > 0) {
            shotCd--;
        }
        if (shotCd == 0 && eatTicks == 0 && !pearlInFlight() && dist >= 3.0 && dist <= 45.0
                && entity.hasLineOfSight(target)) {
            shoot();
            shotCd = plugin.getConfig().getInt("bots.parrotx2.shot-seconds", 3) * 20;
        }
        return false;
    }

    private void shoot() {
        Location eye = entity.getEyeLocation();
        Location aim = target.getEyeLocation().subtract(0, 0.3, 0);
        double speed = 1.6;
        double flight = eye.distance(aim) / speed;
        aim.add(target.getVelocity().multiply(flight * 0.6));
        Vector dir = aim.toVector().subtract(eye.toVector()).normalize();
        lookAlong(dir);
        entity.swingMainHand();
        entity.getWorld().playSound(eye, "item.crossbow.shoot", SoundCategory.HOSTILE, 1f, 1f);
        manager.markVictim(target, this);
        Vector vel = dir.clone().multiply(speed);
        Location at = eye.clone().add(dir.clone().multiply(0.8));
        entity.getWorld().spawn(at, Firework.class, f -> {
            FireworkMeta fm = f.getFireworkMeta();
            fm.setPower(1);
            for (int i = 0; i < 7; i++) {
                fm.addEffect(purple());
            }
            f.setFireworkMeta(fm);
            f.setShotAtAngle(true);
            f.setShooter(entity);
            f.setVelocity(vel);
            f.setMaxLife(50);
        });
    }

    private boolean maceTick(double dist) {
        if (hopCd > 0) {
            hopCd--;
        }
        if (hopping) {
            hopTick();
            return true;
        }
        if (hopCd == 0 && windCharges > 0 && eatTicks == 0 && !pearlInFlight() && entity.isOnGround()
                && dist >= 4.0 && dist <= 14.0 && entity.hasLineOfSight(target)) {
            hopping = true;
            hopT = 0;
            windCharges--;
            return true;
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
            endHop(20);
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
            endHop(10 + random.nextInt(10));
        }
    }

    private void endHop(int cooldown) {
        hopping = false;
        hopCd = cooldown;
        holdWeapon();
    }

    private void maceHit() {
        double fall = Math.min(30.0, Math.max(0.0, peakY - entity.getLocation().getY()));
        double dmg = 6.0;
        if (fall > 1.5) {
            dmg += Math.min(fall, 3.0) * 4.0
                    + Math.max(0.0, Math.min(fall - 3.0, 5.0)) * 2.0
                    + Math.max(0.0, fall - 8.0);
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
        entity.setVelocity(new Vector(0, 0.5, 0));
        endHop(6);
    }
}
