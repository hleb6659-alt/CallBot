package me.prime.callbot;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BotManager implements Listener {

    private record Victim(int tick, UUID botId) {}

    private static final BlockFace[] RING = {
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST
    };

    private final CallBotPlugin plugin;
    private final NamespacedKey markKey;
    private final Map<UUID, PvpBot> bots = new HashMap<>();
    private final Map<UUID, Victim> victims = new HashMap<>();
    private final List<BotType> types = new ArrayList<>();
    private final Random random = new Random();
    private final Map<String, ResolvableProfile> skins = new ConcurrentHashMap<>();
    private BukkitTask task;

    public BotManager(CallBotPlugin plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "call_bot_mark");
        addType("theo", "TheoBaldTheBird", TheoBot::new);
        addType("wemmbu", "Wemmbu", WemmbuBot::new);
        addType("flamefrags", "FlameFrags", FlameFragsBot::new);
        addType("parrotx2", "ParrotX2", ParrotX2Bot::new);
        addType("manepear", "Manepear", ManepearBot::new);
        for (BotType t : types) {
            loadSkin(t.skin());
        }
    }

    private void addType(String id, String defaultName, BotType.Factory factory) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("bots." + id);
        if (s != null && !s.getBoolean("enabled", true)) {
            return;
        }
        String name = s == null ? defaultName : s.getString("name", defaultName);
        String skin = s == null ? name : s.getString("skin", name);
        int weight = s == null ? 50 : Math.max(0, s.getInt("chance", 50));
        if (weight > 0) {
            types.add(new BotType(id, name, skin, weight, factory));
        }
    }

    private void loadSkin(String name) {
        Bukkit.createProfile(name).update().thenAccept(p -> {
            var builder = ResolvableProfile.resolvableProfile();
            if (p.getId() != null) {
                builder.uuid(p.getId());
            }
            if (p.getName() != null) {
                builder.name(p.getName());
            }
            for (ProfileProperty prop : p.getProperties()) {
                builder.addProperty(prop);
            }
            skins.put(name.toLowerCase(), builder.build());
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Не удалось загрузить скин " + name + ": " + ex);
            return null;
        });
    }

    public ResolvableProfile skin(String name) {
        return skins.get(name.toLowerCase());
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        removeAll();
    }

    public int removeAll() {
        int n = bots.size();
        for (PvpBot b : new ArrayList<>(bots.values())) {
            b.dispose();
        }
        bots.clear();
        return n;
    }

    public int killAll() {
        int n = 0;
        for (PvpBot bot : new ArrayList<>(bots.values())) {
            if (bot.isAlive()) {
                bot.kill();
                n++;
            }
        }
        return n;
    }

    public void mark(Entity e) {
        e.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isMarked(Entity e) {
        return e.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE);
    }

    public void markVictim(Player p, PvpBot bot) {
        victims.put(p.getUniqueId(), new Victim(Bukkit.getCurrentTick(), bot.id()));
    }

    public void scheduleSummon(UUID summoner) {
        long delay = Math.max(1L, (long) (plugin.getConfig().getDouble("summon-delay-seconds", 10) * 20));
        Bukkit.getScheduler().runTaskLater(plugin, () -> summon(summoner), delay);
    }

    private void summon(UUID summonerId) {
        Player owner = Bukkit.getPlayer(summonerId);
        if (owner == null || types.isEmpty()) {
            return;
        }
        Location spawn = behind(owner);
        for (BotType type : weightedOrder()) {
            PvpBot bot = null;
            try {
                bot = type.factory().create(plugin, this, type, summonerId);
                bot.spawn(spawn, false);
                if (bot.isAlive()) {
                    bots.put(bot.id(), bot);
                    Bukkit.broadcast(Component.text(type.name() + " joined the game", NamedTextColor.YELLOW));
                    return;
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("Не удалось призвать " + type.name() + ": " + t);
                t.printStackTrace();
                if (bot != null) {
                    try {
                        bot.dispose();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        owner.sendMessage(Component.text("Не удалось призвать бота, подробности в консоли", NamedTextColor.RED));
    }

    private List<BotType> weightedOrder() {
        List<BotType> pool = new ArrayList<>(types);
        List<BotType> out = new ArrayList<>();
        while (!pool.isEmpty()) {
            int total = 0;
            for (BotType t : pool) {
                total += t.weight();
            }
            int roll = random.nextInt(total);
            BotType pick = pool.get(0);
            for (BotType t : pool) {
                if (roll < t.weight()) {
                    pick = t;
                    break;
                }
                roll -= t.weight();
            }
            out.add(pick);
            pool.remove(pick);
        }
        return out;
    }

    private Location behind(Player owner) {
        Location ol = owner.getLocation();
        Vector back = ol.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-5);
        World w = ol.getWorld();
        int bx = (int) Math.floor(ol.getX() + back.getX());
        int bz = (int) Math.floor(ol.getZ() + back.getZ());
        int by = ol.getBlockY();
        for (int dy = 3; dy >= -4; dy--) {
            Block b = w.getBlockAt(bx, by + dy, bz);
            if (b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.UP).isPassable()
                    && !b.getRelative(BlockFace.DOWN).isPassable()) {
                Location l = new Location(w, bx + 0.5, by + dy, bz + 0.5);
                l.setDirection(ol.toVector().subtract(l.toVector()));
                return l;
            }
        }
        return ol.clone().add(back);
    }

    private void tick() {
        for (PvpBot b : new ArrayList<>(bots.values())) {
            if (!b.isAlive()) {
                bots.remove(b.id());
                b.dispose();
                continue;
            }
            try {
                b.tick();
            } catch (Throwable t) {
                plugin.getLogger().severe("Ошибка бота " + b.name() + ": " + t);
                t.printStackTrace();
                bots.remove(b.id());
                b.dispose();
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        PvpBot bot = bots.remove(e.getEntity().getUniqueId());
        if (bot == null) {
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        bot.dispose();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent e) {
        PvpBot bot = bots.get(e.getEntity().getUniqueId());
        if (bot != null) {
            bot.onAttacked(e);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        PvpBot bot = bots.get(e.getEntity().getUniqueId());
        if (bot != null) {
            bot.markHurt();
        }
    }

    @EventHandler
    public void onTotem(EntityResurrectEvent e) {
        PvpBot bot = bots.get(e.getEntity().getUniqueId());
        if (bot != null && !e.isCancelled()) {
            bot.onTotemPop();
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (isMarked(e.getEntity()) && !plugin.getConfig().getBoolean("cart-block-damage", false)) {
            e.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Victim v = victims.remove(dead.getUniqueId());
        if (v == null || Bukkit.getCurrentTick() - v.tick() > 200) {
            return;
        }
        Location loc = dead.getLocation();
        PvpBot bot = bots.get(v.botId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> placeLoser(loc, bot), 8L);
    }

    private void placeLoser(Location loc, PvpBot bot) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Block b = w.getBlockAt(loc);
        for (int i = 0; i < 3 && !b.isReplaceable() && !b.isPassable(); i++) {
            b = b.getRelative(BlockFace.UP);
        }
        for (int i = 0; i < 8 && !b.getRelative(BlockFace.DOWN).getType().isSolid(); i++) {
            if (b.getY() <= w.getMinHeight() + 1) {
                return;
            }
            b = b.getRelative(BlockFace.DOWN);
        }
        if (!b.isReplaceable() || b.isLiquid() || !b.getRelative(BlockFace.DOWN).getType().isSolid()) {
            return;
        }
        BlockFace face = BlockFace.SOUTH;
        if (bot != null && bot.isAlive()) {
            Location bl = bot.location();
            double dx = bl.getX() - (b.getX() + 0.5);
            double dz = bl.getZ() - (b.getZ() + 0.5);
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            int idx = (int) Math.round(yaw / 22.5);
            face = RING[((idx % 16) + 16) % 16];
            bot.swingHand();
        }
        b.setType(Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof Rotatable rot) {
            rot.setRotation(face);
            b.setBlockData(rot, false);
        }
        BlockState st = b.getState();
        if (st instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(1, Component.text("loser"));
            sign.setWaxed(true);
            sign.update(true, false);
        }
        w.playSound(b.getLocation(), "block.wood.place", org.bukkit.SoundCategory.BLOCKS, 1f, 1f);
    }
}
