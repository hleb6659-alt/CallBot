package me.prime.callbot;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.UUID;

public abstract class PvpBot {

    protected enum State { FLYING, LANDING, COMBAT }

    protected final CallBotPlugin plugin;
    protected final BotManager manager;
    protected final BotType type;
    protected final UUID summoner;
    protected final Random random = new Random();

    protected Mannequin entity;
    protected State state = State.FLYING;
    protected Player target;

    protected ItemStack helmet, chestplate, leggings, boots, weapon;
    protected double meleeDamage = 8.0;
    protected int fireTicks;
    protected double knockbackBonus;

    protected int age, attackCd, eatTicks, eatCd, swapTicks, strafeTimer, strafeDir = 1, stagger, idleTicks;
    protected int gapples, eapples, pearls, totemsLeft, pearlCd, strikeTicks, critWait;
    protected boolean offhandTotem;
    private boolean eatingEnchanted;

    private EnderPearl pearl;
    private Location pearlOrigin, pearlLast;
    private boolean pearlAggressive;

    protected PvpBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        this.plugin = plugin;
        this.manager = manager;
        this.type = type;
        this.summoner = summoner;
        this.gapples = plugin.getConfig().getInt("gapples", 15);
        this.eapples = plugin.getConfig().getInt("enchanted-gapples", 1);
        this.pearls = plugin.getConfig().getInt("pearls", 6);
        this.totemsLeft = plugin.getConfig().getInt("totems", 1);
    }

    protected abstract boolean specialTick(double dist);

    protected double reach() {
        return plugin.getConfig().getDouble("melee-reach", 2.5);
    }

    protected double preferredRange() {
        return reach() - 0.7;
    }

    protected boolean busy() {
        return false;
    }

    protected void cleanup() {
    }

    public static boolean validTarget(Player p, UUID summoner) {
        if (!p.isOnline() || p.isDead() || p.getUniqueId().equals(summoner)) {
            return false;
        }
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    public UUID id() {
        return entity.getUniqueId();
    }

    public String name() {
        return type.name();
    }

    public Location location() {
        return entity.getLocation();
    }

    public void swingHand() {
        entity.swingMainHand();
    }

    public boolean isAlive() {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    public void kill() {
        if (isAlive()) {
            entity.setHealth(0.0);
        }
    }

    public void markHurt() {
        stagger = 6;
    }

    public void dispose() {
        cleanup();
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    public void spawn(Location loc, boolean flying) {
        if (totemsLeft > 0) {
            totemsLeft--;
            offhandTotem = true;
        }
        entity = loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setPersistent(false);
            m.customName(Component.text(type.name()));
            m.setCustomNameVisible(true);
            try {
                m.setDescription(null);
            } catch (Throwable ignored) {
            }
            try {
                m.setProfile(ResolvableProfile.resolvableProfile().name(type.skin()).build());
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось задать скин " + type.skin() + ": " + t);
            }
            AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
                hp.setBaseValue(20.0);
            }
            m.setHealth(20.0);
            manager.mark(m);
        });
        if (flying) {
            state = State.FLYING;
            equip(elytra());
            entity.setGliding(true);
        } else {
            state = State.COMBAT;
            equip(chestplate);
        }
    }

    private ItemStack elytra() {
        ItemStack e = new ItemStack(Material.ELYTRA);
        e.editMeta(m -> m.setUnbreakable(true));
        return e;
    }

    protected void equip(ItemStack chest) {
        EntityEquipment eq = entity.getEquipment();
        eq.setHelmet(helmet);
        eq.setChestplate(chest);
        eq.setLeggings(leggings);
        eq.setBoots(boots);
        eq.setItemInMainHand(weapon);
        eq.setItemInOffHand(offhandTotem ? new ItemStack(Material.TOTEM_OF_UNDYING) : null);
    }

    protected void holdWeapon() {
        entity.getEquipment().setItemInMainHand(weapon);
    }

    public void onTotemPop() {
        offhandTotem = false;
        if (totemsLeft > 0) {
            totemsLeft--;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isAlive()) {
                    offhandTotem = true;
                    entity.getEquipment().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
                }
            }, 8L);
        }
    }

    public final void tick() {
        age++;
        if (!isAlive()) {
            return;
        }
        int life = plugin.getConfig().getInt("lifetime-seconds", 900) * 20;
        if (life > 0 && age > life) {
            dispose();
            return;
        }
        if (age % 10 == 1 || (target != null && !validTarget(target, summoner))) {
            retarget();
        }
        switch (state) {
            case FLYING -> fly();
            case LANDING -> land();
            case COMBAT -> combat();
        }
    }

    private void retarget() {
        Location me = entity.getLocation();
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : me.getWorld().getPlayers()) {
            if (!validTarget(p, summoner)) {
                continue;
            }
            double d = p.getLocation().distanceSquared(me);
            if (p == target) {
                d *= 0.6;
            }
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        target = best;
    }

    private void fly() {
        if (target == null) {
            state = State.LANDING;
            return;
        }
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        if (tl.getWorld() != pos.getWorld()) {
            target = null;
            return;
        }
        double dx = tl.getX() - pos.getX();
        double dz = tl.getZ() - pos.getZ();
        double dh = Math.hypot(dx, dz);
        if (dh < 10.0) {
            state = State.LANDING;
            return;
        }
        double speed = plugin.getConfig().getDouble("fly-speed", 0.9);
        double wantY = tl.getY() + 2 + Math.min(40, dh * 0.5);
        double vy = clamp((wantY - pos.getY()) * 0.2, -0.7, 0.5);
        Vector v = new Vector(dx / dh * speed, vy, dz / dh * speed);
        RayTraceResult hit = pos.getWorld().rayTraceBlocks(entity.getEyeLocation(),
                v.clone().normalize(), 10, FluidCollisionMode.NEVER, true);
        if (hit != null) {
            v.setY(Math.max(v.getY(), 0.6));
        }
        entity.setGliding(true);
        entity.setVelocity(v);
        lookAlong(v);
    }

    private void land() {
        Location pos = entity.getLocation();
        double ground = groundDistance(pos);
        Vector v = entity.getVelocity();
        double hx = 0, hz = 0;
        if (target != null && target.getWorld() == pos.getWorld()) {
            double dx = target.getLocation().getX() - pos.getX();
            double dz = target.getLocation().getZ() - pos.getZ();
            double dh = Math.hypot(dx, dz);
            if (dh > 1.0) {
                hx = dx / dh * 0.4;
                hz = dz / dh * 0.4;
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
            entity.setGliding(false);
            state = State.COMBAT;
            swapTicks = 8 + random.nextInt(5);
        }
    }

    private void startFlying() {
        equip(elytra());
        entity.setVelocity(new Vector(0, 0.9, 0));
        entity.setGliding(true);
        state = State.FLYING;
    }

    private void combat() {
        if (swapTicks > 0) {
            holdStill();
            if (target != null) {
                face(target.getLocation().add(0, 1.0, 0), 30f);
            }
            if (--swapTicks == 0) {
                equip(chestplate);
            }
            return;
        }
        if (target == null) {
            holdStill();
            tickPearl();
            if (++idleTicks > plugin.getConfig().getInt("idle-despawn-seconds", 120) * 20) {
                dispose();
            }
            return;
        }
        idleTicks = 0;
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        if (tl.getWorld() != pos.getWorld()) {
            target = null;
            return;
        }
        double dist = pos.distance(tl);
        if (dist > 45 && pearl == null && !busy()) {
            startFlying();
            return;
        }
        if (strikeTicks > 0) {
            strikeTicks--;
        }
        tickPearl();
        if (specialTick(dist)) {
            return;
        }
        face(tl.clone().add(0, 1.0, 0), 35f);
        tickEating();
        decidePearl(dist);
        startEating();
        move(dist);
        melee(dist);
    }

    private void decidePearl(double dist) {
        if (!pearlReady()) {
            return;
        }
        boolean low = entity.getHealth() <= plugin.getConfig().getDouble("eat-health", 12.0);
        if (low) {
            if ((gapples > 0 || eapples > 0) && dist < 14) {
                Location esc = pickEscape();
                if (esc != null) {
                    throwPearl(esc, false);
                }
            }
        } else if (dist > 10 && dist < 28 && target.isOnGround() && random.nextDouble() < 0.006) {
            Location me = entity.getLocation();
            Location tl = target.getLocation();
            Vector back = me.toVector().subtract(tl.toVector()).setY(0);
            if (back.lengthSquared() < 1e-4) {
                return;
            }
            back.normalize().multiply(2.5);
            Location to = tl.clone().add(back);
            throwPearl(to, true);
        }
    }

    private boolean pearlReady() {
        return pearls > 0 && pearlCd <= 0 && pearl == null && eatTicks == 0 && !busy();
    }

    protected boolean pearlInFlight() {
        return pearl != null;
    }

    private Location pickEscape() {
        Location me = entity.getLocation();
        Location tl = target.getLocation();
        World w = me.getWorld();
        double base = Math.atan2(me.getZ() - tl.getZ(), me.getX() - tl.getX());
        for (int i = 0; i < 8; i++) {
            double ang = base + Math.toRadians(random.nextInt(121) - 60);
            double d = 5 + random.nextDouble() * 5;
            int bx = (int) Math.floor(me.getX() + Math.cos(ang) * d);
            int bz = (int) Math.floor(me.getZ() + Math.sin(ang) * d);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                continue;
            }
            int gy = w.getHighestBlockYAt(bx, bz);
            Block ground = w.getBlockAt(bx, gy, bz);
            Material gt = ground.getType();
            if (ground.isLiquid() || gt == Material.MAGMA_BLOCK || gt == Material.CACTUS) {
                continue;
            }
            if (Math.abs(gy + 1 - me.getY()) > 12) {
                continue;
            }
            return new Location(w, bx + 0.5, gy + 1.0, bz + 0.5);
        }
        return null;
    }

    private void throwPearl(Location to, boolean aggressive) {
        Location eye = entity.getEyeLocation();
        double dx = to.getX() - eye.getX();
        double dz = to.getZ() - eye.getZ();
        double dh = Math.hypot(dx, dz);
        if (dh < 1.0) {
            return;
        }
        double pitch = pearlPitch(dh, to.getY() - eye.getY());
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.setRotation(yaw, (float) -pitch);
        double a = Math.toRadians(pitch);
        Vector vel = new Vector(dx / dh * Math.cos(a), Math.sin(a), dz / dh * Math.cos(a)).multiply(1.5);
        entity.getEquipment().setItemInMainHand(new ItemStack(Material.ENDER_PEARL));
        entity.swingMainHand();
        pearl = entity.launchProjectile(EnderPearl.class, vel);
        pearlOrigin = entity.getLocation();
        pearlLast = null;
        pearlAggressive = aggressive;
        pearls--;
        pearlCd = 100 + random.nextInt(100);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isAlive() && eatTicks == 0 && !busy()) {
                holdWeapon();
            }
        }, 4L);
    }

    private static double pearlPitch(double dh, double dy) {
        double best = 45, bestErr = Double.MAX_VALUE;
        for (double deg = 8; deg <= 70; deg += 2) {
            double a = Math.toRadians(deg);
            double vx = 1.5 * Math.cos(a), vy = 1.5 * Math.sin(a), x = 0, y = 0;
            for (int i = 0; i < 200; i++) {
                x += vx;
                y += vy;
                vx *= 0.99;
                vy = vy * 0.99 - 0.03;
                if (y <= dy && vy < 0) {
                    break;
                }
            }
            double err = Math.abs(x - dh);
            if (err < bestErr) {
                bestErr = err;
                best = deg;
            }
        }
        return best;
    }

    private void tickPearl() {
        if (pearl == null) {
            if (pearlCd > 0) {
                pearlCd--;
            }
            return;
        }
        if (!pearl.isDead()) {
            pearlLast = pearl.getLocation();
            return;
        }
        Location dest = pearlLast;
        boolean aggressive = pearlAggressive;
        pearl = null;
        if (dest != null && dest.getWorld() == entity.getWorld()
                && entity.getLocation().distance(pearlOrigin) < 4
                && dest.distance(pearlOrigin) > 3
                && dest.getY() > entity.getWorld().getMinHeight()) {
            entity.teleport(dest.clone().add(0, 0.1, 0));
            entity.getWorld().playSound(dest, "entity.enderman.teleport", SoundCategory.PLAYERS, 1f, 1f);
        }
        if (aggressive) {
            strikeTicks = 60;
        }
    }

    protected boolean strikeWindow() {
        return strikeTicks > 0;
    }

    private void startEating() {
        if (eatTicks > 0 || eatCd > 0 || pearl != null) {
            return;
        }
        double hp = entity.getHealth();
        if (eapples > 0 && hp <= plugin.getConfig().getDouble("enchanted-eat-health", 6.0)) {
            eapples--;
            eatingEnchanted = true;
        } else if (gapples > 0 && hp <= plugin.getConfig().getDouble("eat-health", 12.0)) {
            gapples--;
            eatingEnchanted = false;
        } else {
            return;
        }
        eatTicks = 32;
        entity.getEquipment().setItemInMainHand(new ItemStack(
                eatingEnchanted ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE));
    }

    private void tickEating() {
        if (eatCd > 0) {
            eatCd--;
        }
        if (eatTicks <= 0) {
            return;
        }
        eatTicks--;
        Material apple = eatingEnchanted ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE;
        if (eatTicks % 4 == 0) {
            World w = entity.getWorld();
            w.playSound(entity.getLocation(), "entity.generic.eat", SoundCategory.NEUTRAL, 1f, 1f);
            w.spawnParticle(Particle.ITEM, entity.getEyeLocation(), 6, 0.15, 0.15, 0.15, 0.03,
                    new ItemStack(apple));
        }
        if (eatTicks == 0) {
            if (eatingEnchanted) {
                entity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 1));
                entity.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3));
                entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0));
                entity.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
            } else {
                entity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                entity.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
            }
            holdWeapon();
            eatCd = 10;
        }
    }

    private void move(double dist) {
        if (stagger > 0) {
            stagger--;
            return;
        }
        Location pos = entity.getLocation();
        Location tl = target.getLocation();
        Vector fwd = new Vector(tl.getX() - pos.getX(), 0, tl.getZ() - pos.getZ());
        double len = fwd.length();
        if (len < 1e-3) {
            return;
        }
        fwd.multiply(1.0 / len);
        Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
        if (--strafeTimer <= 0) {
            strafeDir = random.nextInt(3) - 1;
            strafeTimer = 20 + random.nextInt(40);
        }
        double range = preferredRange();
        double f;
        if (eatTicks > 0) {
            f = -0.8;
        } else if (dist > range + 0.7) {
            f = 1.0;
        } else if (dist < range - 0.7) {
            f = -0.7;
        } else {
            f = 0.1;
        }
        Vector m = fwd.clone().multiply(f).add(side.clone().multiply(strafeDir * 0.2));
        if (m.length() > 1.0) {
            m.normalize();
        }
        m.multiply(eatTicks > 0 ? 0.18 : 0.23);
        Vector v = entity.getVelocity();
        v.setX(m.getX()).setZ(m.getZ());
        if (entity.isOnGround() && blocked(m)) {
            v.setY(0.42);
        }
        if (entity.isInWater()) {
            v.setY(Math.max(v.getY(), 0.12));
        }
        entity.setVelocity(v);
    }

    private boolean blocked(Vector m) {
        if (m.lengthSquared() < 1e-4) {
            return false;
        }
        Location ahead = entity.getLocation().add(m.clone().normalize().multiply(0.7));
        Block feet = ahead.getBlock();
        return !feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable()
                && feet.getRelative(0, 2, 0).isPassable();
    }

    private void melee(double dist) {
        if (attackCd > 0) {
            attackCd--;
        }
        if (eatTicks > 0 || attackCd > 0 || dist > reach()) {
            critWait = 0;
            return;
        }
        if (!entity.hasLineOfSight(target)) {
            return;
        }
        boolean falling = !entity.isOnGround() && entity.getVelocity().getY() < -0.08;
        if (!falling && !entity.isInWater() && critWait < 24) {
            critWait++;
            if (entity.isOnGround()) {
                Vector jump = entity.getVelocity();
                jump.setY(0.42);
                entity.setVelocity(jump);
            }
            return;
        }
        critWait = 0;
        attackCd = 11 + random.nextInt(6);
        entity.swingMainHand();
        if (random.nextInt(100) < 10) {
            return;
        }
        double dmg = falling ? meleeDamage * 1.5 : meleeDamage;
        boolean wasBlocking = target.isBlocking();
        double hpBefore = target.getHealth() + target.getAbsorptionAmount();
        Vector prev = target.getVelocity();
        manager.markVictim(target, this);
        target.damage(dmg, entity);
        boolean blocked = wasBlocking && target.getHealth() + target.getAbsorptionAmount() >= hpBefore;
        if (blocked) {
            target.setVelocity(prev);
            return;
        }
        if (fireTicks > 0) {
            target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));
        }
        if (knockbackBonus > 0) {
            Vector kb = target.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0);
            if (kb.lengthSquared() > 1e-4) {
                kb.normalize().multiply(knockbackBonus).setY(0.1);
                target.setVelocity(target.getVelocity().add(kb));
            }
        }
    }

    protected void holdStill() {
        if (stagger > 0) {
            stagger--;
            return;
        }
        Vector v = entity.getVelocity();
        v.setX(0).setZ(0);
        entity.setVelocity(v);
    }

    protected void face(Location at, float maxTurn) {
        Location eye = entity.getEyeLocation();
        double dx = at.getX() - eye.getX();
        double dy = at.getY() - eye.getY();
        double dz = at.getZ() - eye.getZ();
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        Location cur = entity.getLocation();
        float yaw = cur.getYaw();
        float pitch = cur.getPitch();
        yaw += (float) clamp(wrap(wantYaw - yaw), -maxTurn, maxTurn);
        pitch += (float) clamp(wantPitch - pitch, -maxTurn, maxTurn);
        entity.setRotation(yaw, pitch);
    }

    private void lookAlong(Vector v) {
        double h = Math.hypot(v.getX(), v.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(v.getY(), h));
        entity.setRotation(yaw, pitch);
    }

    private double groundDistance(Location pos) {
        RayTraceResult r = pos.getWorld().rayTraceBlocks(pos, new Vector(0, -1, 0), 64,
                FluidCollisionMode.NEVER, true);
        return r == null ? 64 : pos.getY() - r.getHitPosition().getY();
    }

    protected static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) {
            a -= 360f;
        }
        if (a < -180f) {
            a += 360f;
        }
        return a;
    }
}
