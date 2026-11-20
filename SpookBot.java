package me.prime.callbot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SpookBot extends PvpBot {

    private final List<PvpBot> army = new ArrayList<>();
    private int rodCd = 600;
    private int rodT = -1;

    public SpookBot(CallBotPlugin plugin, BotManager manager, BotType type, UUID summoner) {
        super(plugin, manager, type, summoner);
        gapples = 0;
        eapples = plugin.getConfig().getInt("bots.spook.enchanted-gapples", 64);
        pearls = 0;
        totemsLeft = 1;

        helmet = armor(Material.NETHERITE_HELMET, TrimMaterial.NETHERITE);
        chestplate = armor(Material.NETHERITE_CHESTPLATE, TrimMaterial.REDSTONE);
        leggings = armor(Material.NETHERITE_LEGGINGS, TrimMaterial.QUARTZ);
        boots = armor(Material.NETHERITE_BOOTS, TrimMaterial.GOLD);

        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        sword.editMeta(m -> m.setUnbreakable(true));
        weapon = sword;
        meleeDamage = 11.0;
        fireTicks = 160;
        knockbackBonus = 0.35;
    }

    private static ItemStack armor(Material material, TrimMaterial trim) {
        ItemStack it = new ItemStack(material);
        it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        it.addUnsafeEnchantment(Enchantment.BLAST_PROTECTION, 4);
        it.addUnsafeEnchantment(Enchantment.FIRE_PROTECTION, 4);
        it.addUnsafeEnchantment(Enchantment.PROJECTILE_PROTECTION, 4);
        it.editMeta(ArmorMeta.class, meta -> {
            meta.setUnbreakable(true);
            meta.setTrim(new ArmorTrim(trim, TrimPattern.SENTRY));
        });
        return it;
    }

    @Override
    protected ItemStack offhandItem() {
        if (!offhandTotem) {
            return null;
        }
        ItemStack t = new ItemStack(Material.TOTEM_OF_UNDYING);
        t.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        t.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return t;
    }

    @Override
    public void spawn(Location loc, boolean flying) {
        super.spawn(loc, flying);
        if (isAlive()) {
            spawnArmy(loc);
        }
    }

    private void spawnArmy(Location center) {
        int privates = plugin.getConfig().getInt("bots.spook.army-privates", 8);
        int generals = plugin.getConfig().getInt("bots.spook.army-generals", 7);
        int commanders = plugin.getConfig().getInt("bots.spook.army-commanders", 1);
        int total = privates + generals + commanders;
        for (int i = 0; i < total; i++) {
            double ang = i * (Math.PI * 2 / Math.max(1, total));
            double r = 3.0 + (i % 2) * 2.0;
            Location pos = groundAt(center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r));
            PvpBot b = null;
            try {
                if (i < privates) {
                    b = new SoldierBot(plugin, manager, new BotType("army-private", "NULL Soldier", "", 0, null),
                            summoner, SoldierBot.Tier.PRIVATE);
                } else if (i < privates + generals) {
                    b = new SoldierBot(plugin, manager, new BotType("army-general", "NULL General", "", 0, null),
                            summoner, SoldierBot.Tier.GENERAL);
                } else {
                    b = new CommanderBot(plugin, manager, new BotType("army-commander", "NULL Commander", "", 0, null),
                            summoner);
                }
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
    public void onTotemPop() {
        super.onTotemPop();
        for (PvpBot b : army) {
            b.kill();
        }
        army.clear();
    }

    @Override
    protected boolean busy() {
        return rodT >= 0;
    }

    @Override
    protected void abortModes() {
        rodT = -1;
        holdWeapon();
    }

    @Override
    protected boolean specialTick(double dist) {
        if (rodT >= 0) {
            rodTick();
            return true;
        }
        if (--rodCd <= 0) {
            rodCd = 600;
            if (eatTicks == 0 && random.nextInt(100) < 10) {
                rodT = 0;
            }
        }
        return false;
    }

    private void rodTick() {
        rodT++;
        holdStill();
        if (rodT == 1) {
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
        } else if (rodT == 20) {
            World w = entity.getWorld();
            w.playSound(entity.getLocation(), "item.break", SoundCategory.HOSTILE, 2f, 1f);
            w.playSound(entity.getLocation(), "entity.item.break", SoundCategory.HOSTILE, 2f, 1f);
            holdWeapon();
        } else if (rodT == 25) {
            rodT = -1;
            if (target != null) {
                dropRing();
            }
        }
    }

    private void dropRing() {
        World w = entity.getWorld();
        Location c = target.getLocation().add(0, 18, 0);
        int count = 100;
        double radius = 6.5;
        for (int i = 0; i < count; i++) {
            double ang = i * (Math.PI * 2 / count);
            Location p = c.clone().add(Math.cos(ang) * radius, random.nextDouble() * 2.0, Math.sin(ang) * radius);
            w.spawn(p, TNTPrimed.class, t -> {
                t.setFuseTicks(70 + random.nextInt(20));
                t.setSource(entity);
                manager.mark(t);
            });
        }
        Location center = target.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player pl : w.getPlayers()) {
                if (validTarget(pl, summoner) && pl.getLocation().distanceSquared(center) < 225) {
                    manager.markVictim(pl, this);
                }
            }
        }, 85L);
    }
}
