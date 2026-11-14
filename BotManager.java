package me.prime.callbot;

import net.kyori.adventure.text.Component;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
    private BukkitTask task;

    public BotManager(CallBotPlugin plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "call_bot_mark");
        addType("theo", "TheoBaldTheBird", TheoBot::new);
    }

    private void addType(String id, String defaultName, BotType.Factory factory) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("bots." + id);
        if (s != null && !s.getBoolean("enabled", true)) {
            return;
        }
        String name = s == null ? defaultName : s.getString("name", defaultName);
        String skin = s == null ? name : s.getString("skin", name);
        types.add(new BotType(id, name, skin, factory));
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
        if (types.isEmpty()) {
            return;
        }
        Player summoner = Bukkit.getPlayer(summonerId);
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (PvpBot.validTarget(p, summonerId)) {
                pool.add(p);
            }
        }
        Player anchor = pool.isEmpty() ? summoner : pool.get(random.nextInt(pool.size()));
        if (anchor == null) {
            return;
        }
        BotType type = types.get(random.nextInt(types.size()));
        PvpBot bot = type.factory().create(plugin, this, type, summonerId);
        World w = anchor.getWorld();
        Location spawn = null;
        boolean flying = false;
        if (!w.hasCeiling()) {
            int sim = Math.max(2, anchor.getSimulationDistance());
            double dist = Math.min(plugin.getConfig().getDouble("spawn-distance", 30), Math.max(14, sim * 16 - 24));
            for (int i = 0; i < 4 && spawn == null; i++) {
                double ang = random.nextDouble() * Math.PI * 2;
                double x = anchor.getX() + Math.cos(ang) * dist;
                double z = anchor.getZ() + Math.sin(ang) * dist;
                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);
                if (w.isChunkLoaded(bx >> 4, bz >> 4)) {
                    int ground = w.getHighestBlockYAt(bx, bz);
                    double y = Math.max(anchor.getY() + plugin.getConfig().getDouble("spawn-height", 18), ground + 12);
                    y = Math.min(y, w.getMaxHeight() - 3);
                    spawn = new Location(w, x, y, z);
                    flying = true;
                } else {
                    dist = Math.max(10, dist * 0.7);
                }
            }
        }
        if (spawn == null) {
            double ang = random.nextDouble() * Math.PI * 2;
            spawn = anchor.getLocation().add(Math.cos(ang) * 12, 0, Math.sin(ang) * 12);
        }
        bot.spawn(spawn, flying);
        if (bot.isAlive()) {
            bots.put(bot.id(), bot);
        }
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

    @EventHandler
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
