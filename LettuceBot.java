package me.prime.callbot;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LettuceBot extends PvpBot {

    private enum Mode { GROUND, LAUNCH, GLIDE, DROP }

    private final List<PvpBot> army = new ArrayList<>();
    private final ItemStack sword;
    private final ItemStack mace;
    private Mode mode = Mode.GROUND;
    private Launch launch;
    private int windCharges, groundTicks = 40, launchCd, modeT;
    private double peakY;

    public LettuceBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.lettuce.gapples", 30);
        eapples = plugin.getConfig().getInt("bots.lettuce.enchanted-gapples", 3);
        pearls = plugin.getConfig().getInt("bots.lettuce.pearls", 64);
        windCharges = plugin.getConfig().getInt("bots.lettuce.wind-charges", 128);
        totemsLeft = plugin.getConfig().getInt("bots.lettuce.totems", 1);

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack sw = new ItemStack(Material.NETHERITE_SWORD);
        sw.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sw.editMeta(m -> m.setUnbreakable(true));
        sword = sw;

        ItemStack mc = new ItemStack(Material.MACE);
        mc.addUnsafeEnchantment(Enchantment.DENSITY, 3);
        mc.editMeta(m -> m.setUnbreakable(true));
        mace = mc;

        weapon = sword;
        meleeDamage = 11.0;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    public void spawn(Location loc, boolean flying) {
        super.spawn(loc, flying);
        if (isAlive()) {
            spawnArmy(loc);
        }
    }

    private void spawnArmy(Location center) {
        int total = plugin.getConfig().getInt("bots.lettuce.army-size", 30);
        for (int i = 0; i < total; i++) {
            double ang = i * (Math.PI * 2 / Math.max(1, total)) * 3;
            double r = 3.0 + (i % 3) * 2.0;
            Location pos = groundAt(center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r));
            PvpBot b = null;
            try {
                b = new SunSoldierBot(plugin, manager, new BotType("army-sun", "Sun Soldier", "", 0, null), summoner);
                b.spawn(pos, false);
                if (b.isAlive()) {
                    manager.register(b);
                    army.add(b);
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("Не удалось создать бойца армии: " + t);
                t.printStackTrace();
                if (b != null) {
                    try {
                        b.dispose();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private Location groundAt(Location l) {
        World w = l.getWorld();
        int bx = l.getBlockX();
        int bz = l.getBlockZ();
        int by = l.getBlockY();
        for (int dy = 3; dy >= -4; dy--) {
            Block b = w.getBlockAt(bx, by + dy, bz);
            if (b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.UP).isPassable()
                    && !b.getRelative(BlockFace.DOWN).isPassable()) {
                return new Location(w, bx + 0.5, by + dy, bz + 0.5);
            }
        }
        return l;
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
        toGround(40);
    }

    @Override
    protected void cleanup() {
        if (launch != null) {
            launch.cleanup();
            launch = null;
        }
    }

    private void setMode(Mode m) {
        mode = m;
        modeT = 0;
    }

    private boolean needHeal() {
        return (gapples > 0 || eapples > 0)
                && entity.getHealth() <= plugin.getConfig().getDouble("eat-health", 12.0);
    }

    @Override
    protected boolean specialTick(double dist) {
        double y = entity.getLocation().getY();
        if (entity.isOnGround()) {
            peakY = y;
        } else {
            peakY = Math.max(peakY, y);
        }
        modeT++;
        if (launchCd > 0) {
            launchCd--;
        }
        if (mode == Mode.GROUND) {
            if (groundTicks > 0) {
                groundTicks--;
            }
            if (groundTicks <= 0 && launchCd <= 0 && canLaunch(dist)) {
                launch = new Launch();
                setMode(Mode.LAUNCH);
                launchCd = 100;
                return true;
            }
            return false;
        }
        if (mode == Mode.LAUNCH) {
            if (launch.tick()) {
                launch = null;
                setMode(Mode.GLIDE);
            }
            return true;
        }
        if (mode == Mode.GLIDE) {
            glideTick();
        } else {
            dropTick();
        }
        return true;
    }

    private boolean canLaunch(double dist) {
        if (target == null || windCharges <= 0 || eatTicks > 0 || !entity.isOnGround() || dist > 16) {
            return false;
        }
        if (needHeal()) {
            return false;
        }
        RayTraceResult r = entity.getWorld().rayTraceBlocks(entity.getEyeLocation(),
                new Vector(0, 1, 0), 22, FluidCollisionMode.NEVER, true);
        return r == null;
    }

    private void hold(Material m) {
        entity.getEquipment().setItemInMainHand(new ItemStack(m));
    }

    private void glideTick() {
        entity.setGliding(true);
        if (modeT > 12 && entity.isOnGround()) {
            toGround(60);
            return;
        }
        if (target == null || target.getWorld() != entity.getWorld()) {
            startDrop();
            return;
        }
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        double dx = tl.getX() - pos.getX();
        double dz = tl.getZ() - pos.getZ();
        double dh = Math.hypot(dx, dz);
        if (dh < 9 || modeT > 220 || needHeal()) {
            startDrop();
            return;
        }
        double vy = clamp((tl.getY() + 14 - pos.getY()) * 0.1, -0.35, 0.1);
        Vector v = new Vector(dx / dh * 0.7, vy, dz / dh * 0.7);
        entity.setVelocity(v);
        lookAlong(v);
    }

    private void startDrop() {
        entity.setGliding(false);
        entity.getEquipment().setChestplate(chestplate);
        entity.getEquipment().setItemInMainHand(mace);
        setMode(Mode.DROP);
    }

    private void dropTick() {
        entity.setFallDistance(0f);
        if (target == null || target.getWorld() != entity.getWorld()) {
            toGround(40);
            return;
        }
        Location pos = entity.getLocation();
        Location aim = target.getLocation().add(0, 1.0, 0);
        Vector vel = entity.getVelocity();
        if (vel.getY() < -0.1 && pos.distance(aim) <= 3.2) {
            maceHit();
            return;
        }
        Vector toT = new Vector(aim.getX() - pos.getX(), 0, aim.getZ() - pos.getZ());
        double dh = toT.length();
        if (dh > 0.5) {
            toT.multiply(1.0 / dh);
            double s = Math.min(0.4, dh * 0.2 + 0.05);
            vel.setX(toT.getX() * s);
            vel.setZ(toT.getZ() * s);
        }
        if (vel.getY() > -0.4) {
            vel.setY(-0.4);
        }
        entity.setVelocity(vel);
        face(aim, 60f);
        if ((modeT > 6 && entity.isOnGround()) || modeT > 100) {
            toGround(100);
        }
    }

    private void maceHit() {
        double fall = Math.min(30.0, Math.max(0.0, peakY - entity.getLocation().getY()));
        double dmg = 6.0;
        if (fall > 1.5) {
            dmg += Math.min(fall, 3.0) * 4.0
                    + Math.max(0.0, Math.min(fall - 3.0, 5.0)) * 2.0
                    + Math.max(0.0, fall - 8.0)
                    + fall * 1.5;
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
        toGround(100);
    }

    private void toGround(int ticks) {
        entity.setGliding(false);
        entity.getEquipment().setChestplate(chestplate);
        holdWeapon();
        groundTicks = ticks;
        setMode(Mode.GROUND);
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
