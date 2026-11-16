package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class FlameFragsBot extends TheoBot {

    private record Temp(Block block, BlockData old, Material expect, int expireAge) {}

    private final ItemStack sword6;
    private final ItemStack axe;
    private final ItemStack shield;
    private final List<Temp> temps = new ArrayList<>();
    private boolean swordDropped;
    private int webCd = 60, shieldDisabled, axeTicks, stuck, escapeCd;

    public FlameFragsBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = plugin.getConfig().getInt("bots.flamefrags.gapples", 50);
        eapples = plugin.getConfig().getInt("bots.flamefrags.enchanted-gapples", 5);
        pearls = plugin.getConfig().getInt("bots.flamefrags.pearls", 8);
        totemsLeft = 0;

        helmet = protectionArmor(Material.NETHERITE_HELMET);
        chestplate = protectionArmor(Material.NETHERITE_CHESTPLATE);
        leggings = protectionArmor(Material.NETHERITE_LEGGINGS);
        boots = protectionArmor(Material.NETHERITE_BOOTS);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 11.0;
        fireTicks = 0;
        knockbackBonus = 0.35;

        ItemStack s6 = new ItemStack(Material.NETHERITE_SWORD);
        s6.addUnsafeEnchantment(Enchantment.SHARPNESS, 6);
        s6.editMeta(m -> m.setUnbreakable(true));
        sword6 = s6;

        ItemStack a = new ItemStack(Material.NETHERITE_AXE);
        a.editMeta(m -> m.setUnbreakable(true));
        axe = a;

        ItemStack sh = new ItemStack(Material.SHIELD);
        sh.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        sh.addUnsafeEnchantment(Enchantment.MENDING, 1);
        shield = sh;
    }

    private static ItemStack protectionArmor(Material material) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    protected double reach() {
        return plugin.getConfig().getDouble("bots.flamefrags.reach", 2.8);
    }

    @Override
    protected double preferredRange() {
        return (webCd <= 0 && seq == null) ? 4.0 : reach() - 0.7;
    }

    @Override
    protected ItemStack offhandItem() {
        return shield.clone();
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

    private boolean shielding() {
        return shieldDisabled <= 0 && attackCd > 1 && eatTicks == 0 && seq == null;
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

    @Override
    protected void afterHit() {
        if (swordDropped || random.nextInt(100) >= 1) {
            return;
        }
        swordDropped = true;
        Location at = entity.getLocation().add(0, 1.0, 0);
        entity.getWorld().dropItemNaturally(at, weapon);
        weapon = sword6;
        meleeDamage = 11.5;
        knockbackBonus = 0;
        holdWeapon();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        for (Temp t : temps) {
            restoreTemp(t);
        }
        temps.clear();
    }

    private void restoreTemp(Temp t) {
        if (t.block().getType() == t.expect()) {
            t.block().setBlockData(t.old(), false);
        }
    }

    private void tickTemps() {
        Iterator<Temp> it = temps.iterator();
        while (it.hasNext()) {
            Temp t = it.next();
            if (age >= t.expireAge()) {
                restoreTemp(t);
                it.remove();
            }
        }
    }

    @Override
    protected boolean specialTick(double dist) {
        if (target == null) {
            return false;
        }
        if (shieldDisabled > 0) {
            shieldDisabled--;
        }
        if (axeTicks > 0 && --axeTicks == 0 && seq == null) {
            holdWeapon();
        }
        tickTemps();
        syncShield();
        if (seq != null) {
            if (seq.tick()) {
                seq = null;
            }
            return true;
        }
        escapeWeb();
        if (webCd > 0) {
            webCd--;
        }
        if (eatTicks > 0 || pearlInFlight()) {
            return false;
        }
        boolean los = entity.hasLineOfSight(target);
        if (los && attackCd == 0 && dist <= reach() && target.isBlocking()) {
            axeHit();
            return false;
        }
        if (webCd == 0 && los && dist <= 5.0 && entity.isOnGround()) {
            webCd = 100;
            if (random.nextInt(100) < 10) {
                Insta cart = new Insta();
                if (cart.prepare(dist)) {
                    seq = cart;
                    return true;
                }
            } else {
                placeWeb();
            }
        }
        return false;
    }

    private void axeHit() {
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

    private void placeWeb() {
        Block b = target.getLocation().getBlock();
        if (!b.isReplaceable() || b.isLiquid()) {
            return;
        }
        hold(new ItemStack(Material.COBWEB));
        axeTicks = 6;
        entity.swingMainHand();
        temps.add(new Temp(b, b.getBlockData(), Material.COBWEB, age + 160));
        b.setType(Material.COBWEB, false);
        b.getWorld().playSound(b.getLocation(), "block.cobweb.place", SoundCategory.BLOCKS, 1f, 1f);
    }

    private boolean inWeb() {
        return entity.getLocation().getBlock().getType() == Material.COBWEB
                || entity.getEyeLocation().getBlock().getType() == Material.COBWEB;
    }

    private void escapeWeb() {
        if (escapeCd > 0) {
            escapeCd--;
        }
        if (!inWeb()) {
            stuck = 0;
            return;
        }
        if (++stuck < 8 || escapeCd > 0) {
            return;
        }
        escapeCd = 40;
        if (random.nextBoolean() && pearlReady()) {
            Location esc = pickEscape();
            if (esc != null) {
                throwPearl(esc, false);
                return;
            }
        }
        useWater();
    }

    private void useWater() {
        hold(new ItemStack(Material.WATER_BUCKET));
        axeTicks = 10;
        entity.swingMainHand();
        List<Block> blocks = new ArrayList<>();
        blocks.add(entity.getLocation().getBlock());
        blocks.add(entity.getEyeLocation().getBlock());
        for (Block b : blocks) {
            if (b.getType() == Material.COBWEB) {
                temps.add(new Temp(b, Material.AIR.createBlockData(), Material.WATER, age + 14));
                b.setType(Material.WATER, false);
            }
        }
        entity.getWorld().playSound(entity.getLocation(), "item.bucket.empty", SoundCategory.PLAYERS, 1f, 1f);
    }
}
