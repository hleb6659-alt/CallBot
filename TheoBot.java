package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.util.Vector;

import java.util.UUID;

public final class TheoBot extends PvpBot {

    private final ItemStack bow;
    private final ItemStack crossbow;
    private Sequence seq;
    private int cartCd = 40;

    public TheoBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        helmet = armor(Material.NETHERITE_HELMET, false);
        chestplate = armor(Material.NETHERITE_CHESTPLATE, false);
        leggings = armor(Material.NETHERITE_LEGGINGS, true);
        boots = armor(Material.NETHERITE_BOOTS, false);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 11.0;
        fireTicks = 160;
        knockbackBonus = 0.35;

        ItemStack b = new ItemStack(Material.BOW);
        b.addUnsafeEnchantment(Enchantment.FLAME, 1);
        b.editMeta(m -> m.setUnbreakable(true));
        bow = b;

        ItemStack c = new ItemStack(Material.CROSSBOW);
        c.editMeta(CrossbowMeta.class, m -> {
            m.addChargedProjectile(new ItemStack(Material.ARROW));
            m.setUnbreakable(true);
        });
        crossbow = c;
    }

    private static ItemStack armor(Material m, boolean blast) {
        ItemStack it = new ItemStack(m);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        if (blast) {
            it.addUnsafeEnchantment(Enchantment.BLAST_PROTECTION, 4);
        }
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    @Override
    protected boolean busy() {
        return seq != null;
    }

    @Override
    protected double preferredRange() {
        return (seq == null && cartCd <= 0) ? 4.5 : super.preferredRange();
    }

    @Override
    protected void cleanup() {
        if (seq != null) {
            seq.cleanup();
            seq = null;
        }
    }

    @Override
    protected boolean specialTick(double dist) {
        if (seq != null) {
            if (seq.tick()) {
                seq = null;
                cartCd = Math.max(20, (int) (plugin.getConfig().getDouble("cart-cooldown-seconds", 4) * 20));
            }
            return true;
        }
        if (cartCd > 0) {
            cartCd--;
            if (!strikeWindow()) {
                return false;
            }
        }
        if (eatTicks > 0 || pearlInFlight() || !entity.isOnGround() || dist < 2.5 || dist > 7.5) {
            return false;
        }
        if (!strikeWindow() && random.nextInt(100) >= 8) {
            return false;
        }
        if (!entity.hasLineOfSight(target)) {
            return false;
        }
        Sequence s = random.nextDouble() < plugin.getConfig().getDouble("xbow-chance", 0.30)
                ? new XBow() : new Insta();
        if (!s.prepare(dist)) {
            cartCd = 30;
            return false;
        }
        seq = s;
        strikeTicks = 0;
        return true;
    }

    private void hold(ItemStack item) {
        entity.getEquipment().setItemInMainHand(item);
    }

    private void freeze() {
        holdStill();
    }

    private Vector flatDir() {
        Location me = entity.getLocation();
        Location tl = target.getLocation();
        Vector d = new Vector(tl.getX() - me.getX(), 0, tl.getZ() - me.getZ());
        if (d.lengthSquared() < 1e-4) {
            d = me.getDirection().setY(0);
        }
        return d.normalize();
    }

    private Block column(Vector dir, double d) {
        Location o = entity.getLocation();
        World w = o.getWorld();
        int x = (int) Math.floor(o.getX() + dir.getX() * d);
        int z = (int) Math.floor(o.getZ() + dir.getZ() * d);
        int y0 = o.getBlockY();
        for (int y = y0 + 1; y >= y0 - 3; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.isReplaceable() || b.isLiquid()) {
                continue;
            }
            if (b.getRelative(BlockFace.DOWN).getType().isSolid() && b.getRelative(BlockFace.UP).isPassable()) {
                return b;
            }
        }
        return null;
    }

    private void place(Block b, Material m) {
        b.setType(m, false);
        entity.swingMainHand();
        b.getWorld().playSound(b.getLocation(), "block.stone.place", SoundCategory.BLOCKS, 1f, 1f);
    }

    private Entity spawnCart(Location c) {
        Entity cart = c.getWorld().spawnEntity(c, EntityType.TNT_MINECART);
        manager.mark(cart);
        cart.setPersistent(false);
        entity.swingMainHand();
        return cart;
    }

    private Arrow shoot(Location to, double speed) {
        Location eye = entity.getEyeLocation();
        Vector dir = to.toVector().subtract(eye.toVector()).normalize();
        Arrow a = entity.launchProjectile(Arrow.class, dir.multiply(speed));
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setFireTicks(200);
        a.setCritical(true);
        entity.swingMainHand();
        return a;
    }

    private void blast(Location c) {
        float power = (float) plugin.getConfig().getDouble("cart-explosion-power", 4.0);
        boolean grief = plugin.getConfig().getBoolean("cart-block-damage", false);
        for (Player p : c.getWorld().getPlayers()) {
            if (validTarget(p, summoner) && p.getLocation().distanceSquared(c) < 81) {
                manager.markVictim(p, this);
            }
        }
        c.getWorld().createExplosion(c, power, false, grief, entity);
    }

    private void restore(Block b, BlockData old, Material expect) {
        if (b != null && old != null && b.getType() == expect) {
            b.setBlockData(old, false);
        }
    }

    private abstract class Sequence {
        int t;

        abstract boolean prepare(double dist);

        abstract boolean tick();

        abstract void cleanup();
    }

    private final class Insta extends Sequence {
        Block rail;
        BlockData old;
        Entity cart;
        Arrow arrow;
        Location c;

        @Override
        boolean prepare(double dist) {
            rail = column(flatDir(), clamp(dist - 1.0, 2.5, 4.0));
            if (rail == null) {
                return false;
            }
            c = rail.getLocation().add(0.5, 0.1, 0.5);
            return true;
        }

        @Override
        boolean tick() {
            freeze();
            face(c, 60f);
            int n = t++;
            if (n == 0) {
                hold(bow);
            } else if (n == 12) {
                arrow = shoot(c, 2.5);
            } else if (n == 16) {
                hold(new ItemStack(Material.RAIL));
            } else if (n == 17) {
                old = rail.getBlockData();
                place(rail, Material.RAIL);
            } else if (n == 18) {
                hold(new ItemStack(Material.TNT_MINECART));
            } else if (n == 19) {
                cart = spawnCart(c);
            } else if (n == 20) {
                if (cart != null) {
                    cart.remove();
                    cart = null;
                }
                blast(c);
            } else if (n >= 28) {
                cleanup();
                holdWeapon();
                return true;
            }
            return false;
        }

        @Override
        void cleanup() {
            if (arrow != null && !arrow.isDead()) {
                arrow.remove();
            }
            arrow = null;
            if (cart != null && !cart.isDead()) {
                cart.remove();
            }
            cart = null;
            restore(rail, old, Material.RAIL);
            old = null;
        }
    }

    private final class XBow extends Sequence {
        Block rail, slab;
        BlockData oldRail, oldSlab;
        Entity cart;
        Arrow arrow;
        Location c;
        int shotAt = -1;
        int endAt = -1;
        boolean exploded;

        @Override
        boolean prepare(double dist) {
            Vector dir = flatDir();
            rail = column(dir, clamp(dist - 1.0, 3.0, 4.3));
            slab = column(dir, 1.6);
            if (rail == null || slab == null || rail.equals(slab)) {
                return false;
            }
            c = rail.getLocation().add(0.5, 0.1, 0.5);
            return true;
        }

        @Override
        boolean tick() {
            freeze();
            face(c, 60f);
            int n = t++;
            if (n == 0) {
                hold(new ItemStack(Material.RAIL));
            } else if (n == 6) {
                oldRail = rail.getBlockData();
                place(rail, Material.RAIL);
            } else if (n == 9) {
                hold(new ItemStack(Material.TNT_MINECART));
            } else if (n == 12) {
                cart = spawnCart(c);
            } else if (n == 15) {
                hold(new ItemStack(Material.STONE_SLAB));
            } else if (n == 18) {
                oldSlab = slab.getBlockData();
                place(slab, Material.STONE_SLAB);
            } else if (n == 21) {
                hold(crossbow);
            } else if (n == 34) {
                arrow = shoot(c, 3.1);
                shotAt = n;
            }
            if (shotAt >= 0 && n > shotAt) {
                if (!exploded) {
                    if (cart == null || cart.isDead()) {
                        exploded = true;
                        endAt = n + 10;
                    } else if (arrow == null || arrow.isDead() || arrow.isInBlock() || n - shotAt > 12) {
                        cart.remove();
                        blast(c);
                        exploded = true;
                        endAt = n + 10;
                    }
                } else if (n >= endAt) {
                    cleanup();
                    holdWeapon();
                    return true;
                }
            }
            return false;
        }

        @Override
        void cleanup() {
            if (arrow != null && !arrow.isDead()) {
                arrow.remove();
            }
            arrow = null;
            if (cart != null && !cart.isDead()) {
                cart.remove();
            }
            cart = null;
            restore(rail, oldRail, Material.RAIL);
            restore(slab, oldSlab, Material.STONE_SLAB);
            oldRail = null;
            oldSlab = null;
        }
    }
}
