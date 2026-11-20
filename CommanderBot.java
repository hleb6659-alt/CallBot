package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class CommanderBot extends PvpBot {

    private enum Style { SWORD, MACE }

    private final ItemStack sword;
    private final ItemStack mace;
    private Style style = Style.SWORD;
    private int windCharges, styleTicks, hopCd = 20, hopT;
    private boolean hopping;
    private double peakY;

    public CommanderBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = 40;
        eapples = 0;
        pearls = 0;
        totemsLeft = 0;
        windCharges = 128;
        styleTicks = 600;

        helmet = armor(Material.NETHERITE_HELMET);
        chestplate = armor(Material.NETHERITE_CHESTPLATE);
        leggings = armor(Material.NETHERITE_LEGGINGS);
        boots = armor(Material.NETHERITE_BOOTS);

        ItemStack sw = new ItemStack(Material.NETHERITE_SWORD);
        sw.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sw.editMeta(m -> m.setUnbreakable(true));
        sword = sw;

        ItemStack mc = new ItemStack(Material.MACE);
        mc.addUnsafeEnchantment(Enchantment.DENSITY, 5);
        mc.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
        mc.editMeta(m -> m.setUnbreakable(true));
        mace = mc;

        weapon = sword;
        meleeDamage = 11.0;
    }

    private static ItemStack armor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        it.editMeta(ArmorMeta.class, meta -> meta.setTrim(new ArmorTrim(TrimMaterial.QUARTZ, TrimPattern.SENTRY)));
        return it;
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
    protected boolean canMelee() {
        return !hopping;
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
            style = style == Style.SWORD ? Style.MACE : Style.SWORD;
            styleTicks = 600;
            hopping = false;
            hopCd = 10;
            weapon = style == Style.SWORD ? sword : mace;
            meleeDamage = style == Style.SWORD ? 11.0 : 6.0;
            if (eatTicks == 0) {
                holdWeapon();
            }
        }
        if (style == Style.SWORD) {
            return false;
        }
        if (hopCd > 0) {
            hopCd--;
        }
        if (hopping) {
            hopTick();
            return true;
        }
        if (hopCd == 0 && windCharges > 0 && eatTicks == 0 && entity.isOnGround()
                && dist >= 1.5 && dist <= 20.0 && entity.hasLineOfSight(target)) {
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
            endHop(5);
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
            endHop(2 + random.nextInt(3));
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
                    + Math.max(0.0, fall - 8.0)
                    + fall * 2.5;
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
        entity.setVelocity(new Vector(0, 0.9, 0));
        hopT = 3;
    }
}
