package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class ManepearBot extends PvpBot {

    private enum Mode { MELEE, BUFF, RETREAT, CHARGE, DASH, TAKEOFF, AIR, LAND }

    private enum Style { GROUND, AIR }

    private final ItemStack spear;
    private Mode mode = Mode.MELEE;
    private Style style = Style.GROUND;
    private int fireworks, styleTicks, modeT, runCd = 60, boostLeft, fwCd, airTicks;
    private boolean diving;
    private double climbAlt;

    public ManepearBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.manepear.gapples", 35);
        eapples = plugin.getConfig().getInt("bots.manepear.enchanted-gapples", 8);
        fireworks = plugin.getConfig().getInt("bots.manepear.fireworks", 32);
        pearls = 0;
        totemsLeft = plugin.getConfig().getInt("bots.manepear.totems", 1);
        styleTicks = plugin.getConfig().getInt("bots.manepear.style-seconds", 30) * 20;

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack sp = new ItemStack(Material.NETHERITE_SPEAR);
        sp.addUnsafeEnchantment(Enchantment.SHARPNESS, 6);
        sp.addUnsafeEnchantment(Enchantment.LUNGE, 3);
        sp.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 3);
        sp.editMeta(m -> m.setUnbreakable(true));
        spear = sp;
        weapon = sp;
        meleeDamage = 8.5;
        fireTicks = 240;
        knockbackBonus = 0;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    protected double reach() {
        return plugin.getConfig().getDouble("bots.manepear.reach", 4.0);
    }

    @Override
    protected double speed() {
        return entity.hasPotionEffect(PotionEffectType.SPEED) ? 0.34 : 0.23;
    }

    @Override
    protected boolean busy() {
        return mode != Mode.MELEE;
    }

    private void setMode(Mode m) {
        mode = m;
        modeT = 0;
    }

    private double strengthBonus() {
        PotionEffect e = entity.getPotionEffect(PotionEffectType.STRENGTH);
        return e == null ? 0.0 : 3.0 * (e.getAmplifier() + 1);
    }

    private boolean needBuff() {
        PotionEffect s = entity.getPotionEffect(PotionEffectType.SPEED);
        PotionEffect st = entity.getPotionEffect(PotionEffectType.STRENGTH);
        return s == null || s.getDuration() < 400 || st == null || st.getDuration() < 400;
    }

    private boolean needHeal() {
        return (gapples > 0 || eapples > 0)
                && entity.getHealth() <= plugin.getConfig().getDouble("eat-health", 12.0);
    }

    @Override
    protected void abortModes() {
        toGround();
    }

    @Override
    protected void cleanup() {
        if (entity != null) {
            entity.clearActiveItem();
        }
    }

    @Override
    protected boolean specialTick(double dist) {
        meleeDamage = 8.5 + strengthBonus();
        modeT++;
        if (styleTicks > 0) {
            styleTicks--;
        }
        if (styleTicks == 0) {
            switchStyle();
        }
        switch (mode) {
            case MELEE:
                return meleeMode(dist);
            case BUFF:
                buffTick();
                return true;
            case RETREAT:
                retreatTick(dist);
                return true;
            case CHARGE:
                chargeTick();
                return true;
            case DASH:
                dashTick();
                return true;
            case TAKEOFF:
                takeoffTick();
                return true;
            case AIR:
                airTick();
                return true;
            default:
                landTick();
                return true;
        }
    }

    private void switchStyle() {
        int full = plugin.getConfig().getInt("bots.manepear.style-seconds", 30) * 20;
        if (style == Style.GROUND) {
            if (fireworks > 0 && mode == Mode.MELEE && !needHeal()) {
                style = Style.AIR;
                setMode(Mode.TAKEOFF);
                styleTicks = full;
            } else {
                styleTicks = fireworks > 0 ? 20 : full;
            }
        } else {
            style = Style.GROUND;
            styleTicks = full;
            if (mode == Mode.AIR || mode == Mode.TAKEOFF) {
                setMode(Mode.LAND);
            }
        }
    }

    private boolean meleeMode(double dist) {
        if (runCd > 0) {
            runCd--;
        }
        if (eatTicks > 0 || needHeal() || !entity.isOnGround()) {
            return false;
        }
        if (needBuff()) {
            setMode(Mode.BUFF);
            return true;
        }
        if (runCd == 0 && dist <= 6.0 && entity.hasLineOfSight(target)) {
            setMode(Mode.RETREAT);
            return true;
        }
        return false;
    }

    private void buffTick() {
        holdStill();
        int n = modeT;
        if (n == 1) {
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.SPLASH_POTION));
        } else if (n == 3 || n == 13) {
            entity.swingMainHand();
            entity.launchProjectile(ThrownPotion.class, new Vector(0, -0.6, 0));
        } else if (n == 8) {
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1800, 2));
        } else if (n == 18) {
            entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 1800, 1));
        } else if (n >= 22) {
            holdWeapon();
            setMode(Mode.MELEE);
        }
    }

    private void retreatTick(double dist) {
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        Vector away = new Vector(pos.getX() - tl.getX(), 0, pos.getZ() - tl.getZ());
        if (away.lengthSquared() < 1e-4) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        Vector m = away.clone();
        Location ahead = pos.clone().add(away.clone().multiply(0.8));
        if (!ahead.getBlock().isPassable() && !ahead.clone().add(0, 1, 0).getBlock().isPassable()) {
            m = new Vector(-away.getZ(), 0, away.getX());
        }
        m.multiply(speed() + 0.04);
        Vector v = entity.getVelocity();
        v.setX(m.getX()).setZ(m.getZ());
        entity.setVelocity(v);
        face(tl.clone().add(0, 1.0, 0), 60f);
        if (dist >= 8.5 || modeT > 45) {
            setMode(Mode.CHARGE);
        }
    }

    private void chargeTick() {
        holdStill();
        face(target.getLocation().add(0, 1.0, 0), 80f);
        if (modeT == 1) {
            entity.startUsingItem(EquipmentSlot.HAND);
        }
        if (modeT >= 12) {
            setMode(Mode.DASH);
        }
    }

    private void dashTick() {
        Location pos = entity.getLocation();
        Location aim = target.getLocation().add(0, 1.0, 0);
        Vector d = new Vector(aim.getX() - pos.getX(), 0, aim.getZ() - pos.getZ());
        double dh = d.length();
        face(aim, 90f);
        if (dh > 0.01) {
            d.multiply(1.0 / dh);
            Vector v = entity.getVelocity();
            v.setX(d.getX() * 0.95).setZ(d.getZ() * 0.95);
            entity.setVelocity(v);
        }
        if (pos.distance(aim) <= reach()) {
            spearHit(19.0);
            endDash();
        } else if (modeT > 16) {
            endDash();
        }
    }

    private void endDash() {
        entity.clearActiveItem();
        holdWeapon();
        runCd = 70 + random.nextInt(60);
        setMode(Mode.MELEE);
    }

    private void spearHit(double speedBps) {
        double dmg = 8.5 + Math.min(12.0, speedBps * 0.45) + strengthBonus();
        entity.swingMainHand();
        boolean wasBlocking = target.isBlocking();
        double hpBefore = target.getHealth() + target.getAbsorptionAmount();
        Vector prev = target.getVelocity();
        manager.markVictim(target, this);
        target.damage(dmg, entity);
        entity.getWorld().playSound(target.getLocation(), "entity.player.attack.strong", SoundCategory.PLAYERS, 1f, 1f);
        if (wasBlocking && target.getHealth() + target.getAbsorptionAmount() >= hpBefore) {
            target.setVelocity(prev);
            return;
        }
        target.setFireTicks(Math.max(target.getFireTicks(), 240));
    }

    private void takeoffTick() {
        int n = modeT;
        if (n < 3) {
            holdStill();
        }
        if (n == 1) {
            entity.getEquipment().setChestplate(elytra());
            entity.getEquipment().setItemInMainHand(spear);
        } else if (n == 3) {
            entity.setVelocity(new Vector(0, 0.6, 0));
        } else if (n == 6) {
            entity.setGliding(true);
        } else if (n == 7) {
            airTicks = 0;
            diving = false;
            fwCd = 0;
            climbAlt = 18 + random.nextInt(8);
            setMode(Mode.AIR);
            useFirework();
            boostLeft = 12 + random.nextInt(6);
        }
    }

    private void airTick() {
        airTicks++;
        entity.setGliding(true);
        if (airTicks > 12 && entity.isOnGround()) {
            toGround();
            return;
        }
        if (target == null || target.getWorld() != entity.getWorld() || needHeal() || airTicks > 600) {
            entity.clearActiveItem();
            setMode(Mode.LAND);
            return;
        }
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        double dx = tl.getX() - pos.getX();
        double dz = tl.getZ() - pos.getZ();
        double dh = Math.hypot(dx, dz);
        double dy = pos.getY() - tl.getY();

        if (!diving && (fireworks <= 0 || (dy >= climbAlt - 4 && dh < 26))) {
            diving = true;
            entity.startUsingItem(EquipmentSlot.HAND);
        }
        if (diving) {
            Vector v = new Vector(dx, tl.getY() + 1.0 - pos.getY(), dz);
            if (v.length() <= 3.4) {
                spearHit(30.0);
                entity.clearActiveItem();
                diving = false;
                boostLeft = 0;
                climbAlt = 18 + random.nextInt(8);
                entity.setVelocity(new Vector(0, 0.5, 0));
                if (fireworks <= 0) {
                    setMode(Mode.LAND);
                }
                return;
            }
            v.normalize().multiply(1.5);
            entity.setVelocity(v);
            lookAlong(v);
            if (pos.getY() < tl.getY() - 2) {
                diving = false;
                entity.clearActiveItem();
                if (fireworks <= 0) {
                    setMode(Mode.LAND);
                }
            }
            return;
        }

        double hx = dh > 1 ? dx / dh : 0;
        double hz = dh > 1 ? dz / dh : 0;
        Vector heading = new Vector(hx * 0.7, clamp((climbAlt - dy) / 18.0, 0.3, 1.0), hz * 0.7).normalize();
        if (boostLeft > 0) {
            if (--boostLeft == 0) {
                fwCd = 4;
            }
            entity.setVelocity(heading.clone().multiply(1.55));
            lookAlong(heading);
            return;
        }
        if (fwCd > 0) {
            fwCd--;
        }
        if (fireworks > 0 && fwCd == 0) {
            useFirework();
            boostLeft = 12 + random.nextInt(6);
            entity.setVelocity(heading.clone().multiply(1.55));
            lookAlong(heading);
            return;
        }
        Vector glide = new Vector(hx * 0.8, -0.1, hz * 0.8);
        entity.setVelocity(glide);
        lookAlong(glide);
    }

    private void useFirework() {
        fireworks--;
        entity.swingOffHand();
        entity.getWorld().spawn(entity.getLocation(), Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.setPower(1);
            f.setFireworkMeta(meta);
            f.setAttachedTo(entity);
        });
    }

    private void landTick() {
        Location pos = entity.getLocation();
        double ground = groundDistance(pos);
        Vector v = entity.getVelocity();
        double hx = 0, hz = 0;
        if (target != null && target.getWorld() == entity.getWorld()) {
            double dx = target.getLocation().getX() - pos.getX();
            double dz = target.getLocation().getZ() - pos.getZ();
            double dh = Math.hypot(dx, dz);
            if (dh > 1.0) {
                hx = dx / dh * 0.35;
                hz = dz / dh * 0.35;
            }
        }
        v.setX(hx).setZ(hz);
        if (ground > 3.0) {
            entity.setGliding(true);
            v.setY(-0.45);
        } else {
            entity.setGliding(false);
        }
        entity.setVelocity(v);
        if (ground > 3.0) {
            lookAlong(v);
        }
        if (entity.isOnGround() && ground < 1.5) {
            toGround();
        }
    }

    private void toGround() {
        entity.setGliding(false);
        entity.clearActiveItem();
        entity.getEquipment().setChestplate(chestplate);
        holdWeapon();
        style = Style.GROUND;
        runCd = 40;
        setMode(Mode.MELEE);
    }
}
