package me.prime.callbot;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class ShoeBillyBot extends PvpBot {

    private enum Mode { GROUND, TAKEOFF, SIDE, DIVE, LAND }

    private final ItemStack mace;
    private Mode mode = Mode.GROUND;
    private int fireworks, modeT, groundTicks = 20, boostLeft, fwCd;
    private double peakY;
    private Vector sideOffset = new Vector(0, 25, 25);

    public ShoeBillyBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.shoebilly.gapples", 50);
        eapples = plugin.getConfig().getInt("bots.shoebilly.enchanted-gapples", 2);
        fireworks = plugin.getConfig().getInt("bots.shoebilly.fireworks", 128);
        totemsLeft = plugin.getConfig().getInt("bots.shoebilly.totems", 1);
        pearls = 0;

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack mc = new ItemStack(Material.MACE);
        mc.addUnsafeEnchantment(Enchantment.BREACH, 2);
        mc.editMeta(m -> m.setUnbreakable(true));
        mace = mc;
        weapon = mc;
        meleeDamage = 6.0;
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
        toGround();
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
        if (mode == Mode.GROUND) {
            if (groundTicks > 0) {
                groundTicks--;
            }
            if (groundTicks <= 0 && canFly(dist)) {
                setMode(Mode.TAKEOFF);
                return true;
            }
            return false;
        }
        if (mode == Mode.TAKEOFF) {
            takeoffTick();
        } else if (mode == Mode.SIDE) {
            sideTick();
        } else if (mode == Mode.DIVE) {
            diveTick();
        } else {
            landTick();
        }
        return true;
    }

    private boolean canFly(double dist) {
        if (target == null || fireworks <= 0 || eatTicks > 0 || !entity.isOnGround() || dist > 40 || needHeal()) {
            return false;
        }
        RayTraceResult r = entity.getWorld().rayTraceBlocks(entity.getEyeLocation(),
                new Vector(0, 1, 0), 26, FluidCollisionMode.NEVER, true);
        return r == null;
    }

    private void pickSide() {
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        Vector toT = new Vector(tl.getX() - pos.getX(), 0, tl.getZ() - pos.getZ());
        if (toT.lengthSquared() < 1e-4) {
            toT = new Vector(1, 0, 0);
        }
        toT.normalize();
        Vector side = new Vector(-toT.getZ(), 0, toT.getX()).multiply(random.nextBoolean() ? 1 : -1);
        double lateral = 22 + random.nextInt(8);
        sideOffset = new Vector(side.getX() * lateral, 22 + random.nextInt(8), side.getZ() * lateral);
    }

    private void takeoffTick() {
        int n = modeT;
        if (n < 3) {
            holdStill();
        }
        if (n == 1) {
            entity.getEquipment().setChestplate(elytra());
        } else if (n == 3) {
            entity.setVelocity(new Vector(0, 0.6, 0));
        } else if (n == 6) {
            entity.setGliding(true);
        } else if (n == 7) {
            pickSide();
            useFirework();
            boostLeft = 14 + random.nextInt(5);
            fwCd = 0;
            setMode(Mode.SIDE);
        }
    }

    private void sideTick() {
        entity.setGliding(true);
        if (modeT > 12 && entity.isOnGround()) {
            toGround();
            return;
        }
        if (needHeal() || fireworks <= 0 || target == null || target.getWorld() != entity.getWorld()) {
            setMode(Mode.LAND);
            return;
        }
        Location pos = entity.getLocation();
        Location point = target.getLocation().add(sideOffset);
        Vector toP = point.toVector().subtract(pos.toVector());
        double d = toP.length();
        if ((d < 8 && pos.getY() - target.getLocation().getY() > 12) || modeT > 120) {
            setMode(Mode.DIVE);
            return;
        }
        if (d < 1e-3) {
            return;
        }
        Vector heading = toP.clone().normalize();
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
        if (fireworks > 0 && fwCd == 0 && d > 10) {
            useFirework();
            boostLeft = 12 + random.nextInt(6);
            entity.setVelocity(heading.clone().multiply(1.55));
            lookAlong(heading);
            return;
        }
        Vector glide = new Vector(heading.getX() * 0.8, Math.max(heading.getY() * 0.5, -0.1), heading.getZ() * 0.8);
        entity.setVelocity(glide);
        lookAlong(glide);
    }

    private void diveTick() {
        entity.setGliding(true);
        if (target == null || target.getWorld() != entity.getWorld()) {
            setMode(Mode.LAND);
            return;
        }
        int n = modeT;
        if (n == 1) {
            if (fireworks > 0) {
                useFirework();
                boostLeft = 14;
            } else {
                boostLeft = 0;
            }
        }
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        Vector v = new Vector(tl.getX() - pos.getX(), tl.getY() + 1.0 - pos.getY(), tl.getZ() - pos.getZ());
        double dist = v.length();
        if (dist <= 3.4) {
            maceHit();
            return;
        }
        v.normalize();
        double speed = 1.3;
        if (boostLeft > 0) {
            boostLeft--;
            speed = 1.8;
        }
        entity.setVelocity(v.clone().multiply(speed));
        lookAlong(v);
        if (pos.getY() < tl.getY() - 2 || n > 60) {
            pickSide();
            setMode(fireworks > 0 ? Mode.SIDE : Mode.LAND);
            boostLeft = 0;
        }
    }

    private void maceHit() {
        double fall = Math.min(30.0, Math.max(0.0, peakY - entity.getLocation().getY()));
        double dmg = 6.0;
        if (fall > 1.5) {
            dmg += Math.min(fall, 3.0) * 4.0
                    + Math.max(0.0, Math.min(fall - 3.0, 5.0)) * 2.0
                    + Math.max(0.0, fall - 8.0);
        }
        dmg *= 1.25;
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
        boostLeft = 0;
        pickSide();
        setMode(fireworks > 0 ? Mode.SIDE : Mode.LAND);
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
        entity.getEquipment().setChestplate(chestplate);
        holdWeapon();
        groundTicks = 40;
        boostLeft = 0;
        setMode(Mode.GROUND);
    }
}
