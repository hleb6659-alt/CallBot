package me.prime.callbot;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class WemmbuBot extends PvpBot {

    private enum Mode { GROUND, LAUNCH, AIR, RECOVER }

    private final ItemStack mace;
    private Mode mode = Mode.GROUND;
    private Launch launch;
    private int windCharges, fireworks;
    private int groundTicks = 30, launchCd, recoverTicks, boostLeft, airTicks, orbitalCd = 100, fwCd;
    private boolean diving;
    private double climbAlt, peakY;

    public WemmbuBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.wemmbu.gapples", 10);
        eapples = plugin.getConfig().getInt("bots.wemmbu.enchanted-gapples", 3);
        pearls = plugin.getConfig().getInt("bots.wemmbu.pearls", 16);
        windCharges = plugin.getConfig().getInt("bots.wemmbu.wind-charges", 128);
        fireworks = plugin.getConfig().getInt("bots.wemmbu.fireworks", 32);
        totemsLeft = plugin.getConfig().getInt("bots.wemmbu.totems", 1);

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 11.0;

        ItemStack m = new ItemStack(Material.MACE);
        m.addUnsafeEnchantment(Enchantment.DENSITY, 2);
        m.addUnsafeEnchantment(Enchantment.WIND_BURST, 2);
        m.editMeta(meta -> meta.setUnbreakable(true));
        mace = m;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    protected boolean busy() {
        return mode != Mode.GROUND;
    }

    @Override
    protected void abortModes() {
        if (launch != null) {
            launch.cleanup();
            launch = null;
        }
        diving = false;
        boostLeft = 0;
        entity.setGliding(false);
        entity.getEquipment().setChestplate(chestplate);
        holdWeapon();
        mode = Mode.GROUND;
        groundTicks = 40;
    }

    @Override
    protected void cleanup() {
        if (launch != null) {
            launch.cleanup();
            launch = null;
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
        if (launchCd > 0) {
            launchCd--;
        }
        if (orbitalCd > 0) {
            orbitalCd--;
        }
        if (mode == Mode.GROUND) {
            if (groundTicks > 0) {
                groundTicks--;
            }
            if (groundTicks <= 0 && launchCd <= 0 && canLaunch(dist)) {
                launch = new Launch();
                mode = Mode.LAUNCH;
                launchCd = 100;
                return true;
            }
            return false;
        }
        if (mode == Mode.LAUNCH) {
            if (launch.tick()) {
                launch = null;
                mode = Mode.AIR;
                startAir();
            }
            return true;
        }
        if (mode == Mode.AIR) {
            airTick();
            return true;
        }
        recoverTick();
        return true;
    }

    private boolean canLaunch(double dist) {
        if (target == null || windCharges <= 0 || eatTicks > 0 || !entity.isOnGround() || dist > 16) {
            return false;
        }
        boolean canHeal = gapples > 0 || eapples > 0;
        if (canHeal && entity.getHealth() <= plugin.getConfig().getDouble("eat-health", 12.0)) {
            return false;
        }
        RayTraceResult r = entity.getWorld().rayTraceBlocks(entity.getEyeLocation(),
                new Vector(0, 1, 0), 22, FluidCollisionMode.NEVER, true);
        return r == null;
    }

    private void hold(Material m) {
        entity.getEquipment().setItemInMainHand(new ItemStack(m));
    }

    private void startAir() {
        airTicks = 0;
        diving = false;
        boostLeft = 0;
        fwCd = 0;
        climbAlt = 18 + random.nextInt(8);
        entity.getEquipment().setItemInMainHand(mace);
        entity.setGliding(true);
        if (orbitalCd <= 0 && random.nextInt(100) < 10) {
            orbital();
        }
    }

    private void airTick() {
        airTicks++;
        entity.setGliding(true);
        if (airTicks > 12 && entity.isOnGround()) {
            endAir();
            return;
        }
        if (airTicks > 400) {
            entity.setVelocity(new Vector(0, -0.4, 0));
            return;
        }
        if (target == null || target.getWorld() != entity.getWorld()) {
            entity.setVelocity(new Vector(0, -0.35, 0));
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
        }
        if (diving) {
            Vector v = new Vector(dx, tl.getY() + 1.0 - pos.getY(), dz);
            if (v.length() <= 3.2) {
                maceHit();
                return;
            }
            v.normalize().multiply(1.5);
            entity.setVelocity(v);
            lookAlong(v);
            if (pos.getY() < tl.getY() - 2) {
                diving = false;
                if (fireworks <= 0) {
                    entity.setVelocity(new Vector(0, -0.3, 0));
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

    private void maceHit() {
        Location pos = entity.getLocation();
        double fall = Math.min(30.0, Math.max(0.0, peakY - pos.getY()));
        double dmg = 6.0;
        if (fall > 1.5) {
            dmg += Math.min(fall, 3.0) * 4.0
                    + Math.max(0.0, Math.min(fall - 3.0, 5.0)) * 2.0
                    + Math.max(0.0, fall - 8.0)
                    + fall;
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
        entity.getEquipment().setChestplate(chestplate);
        entity.setGliding(false);
        entity.getWorld().playSound(entity.getLocation(), "entity.wind_charge.wind_burst",
                SoundCategory.PLAYERS, 1f, 1f);
        entity.setVelocity(new Vector(0, 1.2, 0));
        diving = false;
        boostLeft = 0;
        recoverTicks = 14;
        mode = Mode.RECOVER;
    }

    private void recoverTick() {
        recoverTicks--;
        entity.setFallDistance(0f);
        if (target != null && target.getWorld() == entity.getWorld()) {
            face(target.getLocation().add(0, 1.0, 0), 40f);
        }
        if (recoverTicks > 0 || (!entity.isOnGround() && recoverTicks > -40)) {
            return;
        }
        boolean again = windCharges > 0 && random.nextInt(100) < 75;
        toGround(again ? 0 : 60 + random.nextInt(60));
    }

    private void endAir() {
        entity.setGliding(false);
        entity.setFallDistance(0f);
        toGround(40 + random.nextInt(40));
    }

    private void toGround(int ticks) {
        mode = Mode.GROUND;
        groundTicks = ticks;
        entity.getEquipment().setChestplate(chestplate);
        holdWeapon();
    }

    private void orbital() {
        orbitalCd = 160;
        if (target == null) {
            return;
        }
        World w = entity.getWorld();
        double ang = random.nextDouble() * Math.PI * 2;
        double r = 1.5 + random.nextDouble() * 2.5;
        Location p = target.getLocation().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
        w.playSound(p, "item.break", SoundCategory.HOSTILE, 2f, 1f);
        w.playSound(p, "entity.item.break", SoundCategory.HOSTILE, 2f, 1f);
        w.spawn(p, TNTPrimed.class, t -> {
            t.setFuseTicks(30);
            t.setSource(entity);
            manager.mark(t);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player pl : w.getPlayers()) {
                if (validTarget(pl, summoner) && pl.getLocation().distanceSquared(p) < 81) {
                    manager.markVictim(pl, this);
                }
            }
        }, 29L);
    }

    private final class Launch {
        int t;
        Entity pearlEntity;

        boolean tick() {
            int n = t++;
            Location pos = entity.getLocation();
            if (n < 9) {
                holdStill();
                entity.setRotation(pos.getYaw(), -85f);
            }
            if (n == 0) {
                hold(pearls > 0 ? Material.ENDER_PEARL : Material.WIND_CHARGE);
            } else if (n == 2 && pearls > 0) {
                pearls--;
                entity.swingMainHand();
                pearlEntity = entity.launchProjectile(EnderPearl.class, new Vector(0, 1.45, 0));
            } else if (n == 5) {
                hold(Material.WIND_CHARGE);
            } else if (n == 7) {
                windCharges--;
                entity.swingMainHand();
                entity.launchProjectile(WindCharge.class, new Vector(0, -1.0, 0));
            } else if (n == 9) {
                double hx = 0, hz = 0;
                if (target != null && target.getWorld() == entity.getWorld()) {
                    Location tl = target.getLocation();
                    double dx = tl.getX() - pos.getX();
                    double dz = tl.getZ() - pos.getZ();
                    double dh = Math.hypot(dx, dz);
                    if (dh > 1.0) {
                        hx = dx / dh * 0.12;
                        hz = dz / dh * 0.12;
                    }
                }
                entity.setVelocity(new Vector(hx, 1.3, hz));
            } else if (n == 24) {
                if (pearlEntity != null && !pearlEntity.isDead()
                        && pearlEntity.getLocation().getY() > pos.getY() + 2) {
                    Location to = pearlEntity.getLocation();
                    pearlEntity.remove();
                    pearlEntity = null;
                    entity.teleport(to);
                    entity.getWorld().playSound(to, "entity.enderman.teleport", SoundCategory.PLAYERS, 1f, 1f);
                }
            } else if (n == 25) {
                cleanup();
                entity.getEquipment().setChestplate(elytra());
                entity.getEquipment().setItemInMainHand(mace);
                entity.setGliding(true);
            }
            return n >= 25;
        }

        void cleanup() {
            if (pearlEntity != null && !pearlEntity.isDead()) {
                pearlEntity.remove();
            }
            pearlEntity = null;
        }
    }
}
