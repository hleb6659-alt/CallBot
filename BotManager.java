package me.prime.callbot;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BotManager implements Listener {

    private record Victim(int tick, UUID botId, String typeId) {}

    private record PortalBlock(Block block, BlockData old) {}

    private record Portal(Location center, int expire, List<PortalBlock> blocks) {}

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
    private final File skinFile;
    private final YamlConfiguration skinCache;
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final List<Portal> portals = new ArrayList<>();

    public BotManager(CallBotPlugin plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "call_bot_mark");
        this.skinFile = new File(plugin.getDataFolder(), "skins.yml");
        this.skinCache = YamlConfiguration.loadConfiguration(skinFile);
        addType("theo", "TheoBaldTheBird", TheoBot::new);
        addType("wemmbu", "Wemmbu", WemmbuBot::new);
        addType("flamefrags", "FlameFrags", FlameFragsBot::new);
        addType("parrotx2", "ParrotX2", ParrotX2Bot::new);
        addType("manepear", "Manepear", ManepearBot::new);
        addType("shoebilly", "ShoeBilly_", ShoeBillyBot::new);
        addType("spook", "SpokeIsHere", SpookBot::new);
        addType("lettuce", "lettuceK", LettuceBot::new);
        addType("clownpierce", "ClownPierce", ClownPierceBot::new);
        for (BotType t : types) {
            loadSkin(t, null, 0);
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

    public void ensureSkin(BotType t) {
        if (t.skin() == null || t.skin().isEmpty() || skins.containsKey(t.skin().toLowerCase())) {
            return;
        }
        loadSkin(t, null, 0);
    }

    public void reloadSkins(CommandSender sender) {
        int have = 0;
        for (BotType t : types) {
            if (skins.containsKey(t.skin().toLowerCase())) {
                have++;
                continue;
            }
            note(sender, "Запрашиваю скин " + t.skin() + "...", NamedTextColor.GRAY);
            loadSkin(t, sender, 0);
        }
        note(sender, "Скины уже загружены: " + have + " из " + types.size(), NamedTextColor.YELLOW);
    }

    private void note(CommandSender s, String text, NamedTextColor color) {
        if (s != null) {
            s.sendMessage(Component.text(text, color));
        }
    }

    private void loadSkin(BotType t, CommandSender notify, int attempt) {
        String name = t.skin();
        String key = name.toLowerCase();
        if (attempt > 0 && skins.containsKey(key)) {
            return;
        }
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("bots." + t.id());
        String manual = s == null ? "" : s.getString("skin-value", "");
        if (!manual.isEmpty()) {
            applySkin(name, manual, s.getString("skin-signature", ""), null);
            plugin.getLogger().info("Скин " + name + " взят из конфига");
            return;
        }
        String cached = skinCache.getString("skins." + key + ".value", "");
        if (!cached.isEmpty()) {
            applySkin(name, cached, skinCache.getString("skins." + key + ".signature", ""),
                    skinCache.getString("skins." + key + ".id", ""));
            plugin.getLogger().info("Скин " + name + " взят из кэша skins.yml");
            note(notify, "Скин " + name + " взят из кэша", NamedTextColor.GREEN);
            return;
        }
        if (!loading.add(key)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String[] r = fetchMojang(name);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading.remove(key);
                    skinCache.set("skins." + key + ".id", r[0]);
                    skinCache.set("skins." + key + ".value", r[1]);
                    skinCache.set("skins." + key + ".signature", r[2]);
                    try {
                        skinCache.save(skinFile);
                    } catch (IOException ex) {
                        plugin.getLogger().warning("Не удалось сохранить skins.yml: " + ex);
                    }
                    applySkin(name, r[1], r[2], r[0]);
                    plugin.getLogger().info("Скин " + name + " загружен с серверов Mojang");
                    note(notify, "Скин " + name + " загружен", NamedTextColor.GREEN);
                });
            } catch (Exception ex) {
                String why = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading.remove(key);
                    plugin.getLogger().warning("Скин " + name + ": " + why + " (попытка " + (attempt + 1) + ")");
                    note(notify, "Скин " + name + ": " + why, NamedTextColor.RED);
                    if (attempt == 0) {
                        loadSkinViaBukkit(name);
                    }
                    if (attempt < 3) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> loadSkin(t, null, attempt + 1), 1200L);
                    }
                });
            }
        });
    }

    private String[] fetchMojang(String name) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpResponse<String> first = client.send(HttpRequest.newBuilder(URI.create(
                "https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (first.statusCode() == 404 || first.statusCode() == 204) {
            throw new IllegalStateException("такого ника нет в Minecraft, проверь написание");
        }
        if (first.statusCode() != 200) {
            throw new IllegalStateException("Mojang ответил HTTP " + first.statusCode());
        }
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").matcher(first.body());
        if (!m.find()) {
            throw new IllegalStateException("не удалось прочитать ответ Mojang");
        }
        String id = m.group(1);
        HttpResponse<String> second = client.send(HttpRequest.newBuilder(URI.create(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (second.statusCode() != 200) {
            throw new IllegalStateException("сервер текстур ответил HTTP " + second.statusCode()
                    + " (возможно, лимит запросов, повторю позже)");
        }
        Matcher v = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(second.body());
        Matcher sg = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"").matcher(second.body());
        if (!v.find()) {
            throw new IllegalStateException("у ника нет текстур");
        }
        return new String[]{id, v.group(1), sg.find() ? sg.group(1) : ""};
    }

    private void applySkin(String name, String value, String signature, String id) {
        UUID uuid;
        if (id != null && id.length() == 32) {
            uuid = UUID.fromString(id.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
        } else {
            uuid = UUID.nameUUIDFromBytes(("callbot:" + name).getBytes(StandardCharsets.UTF_8));
        }
        var builder = ResolvableProfile.resolvableProfile();
        builder.uuid(uuid);
        builder.name(name);
        builder.addProperty(new ProfileProperty("textures", value,
                signature == null || signature.isEmpty() ? null : signature));
        skins.put(name.toLowerCase(), builder.build());
    }

    private void loadSkinViaBukkit(String name) {
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
            plugin.getLogger().info("Скин " + name + (p.getProperties().isEmpty()
                    ? ": текстур не найдено, проверь ник" : " загружен (запасной способ)"));
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
        for (Portal p : portals) {
            restore(p);
        }
        portals.clear();
    }

    public int removeAll() {
        int n = bots.size();
        for (PvpBot b : new ArrayList<>(bots.values())) {
            b.dispose();
        }
        bots.clear();
        return n;
    }

    public void register(PvpBot bot) {
        bots.put(bot.id(), bot);
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
        victims.put(p.getUniqueId(), new Victim(Bukkit.getCurrentTick(), bot.id(), bot.typeId()));
    }

    public void scheduleSummon(UUID summoner) {
        long delay = Math.max(1L, plugin.getConfig().getLong("bot-delay-ticks", 60L));
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

    private void restore(Portal p) {
        for (int i = p.blocks().size() - 1; i >= 0; i--) {
            PortalBlock pb = p.blocks().get(i);
            pb.block().setBlockData(pb.old(), false);
        }
    }

    private void tickPortals() {
        if (portals.isEmpty()) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        Iterator<Portal> it = portals.iterator();
        while (it.hasNext()) {
            Portal p = it.next();
            if (now >= p.expire()) {
                restore(p);
                it.remove();
            }
        }
    }

    public Location openPortal(Player owner, int lifeTicks) {
        Location ol = owner.getLocation();
        World w = ol.getWorld();
        Vector f = ol.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) {
            f = new Vector(0, 0, 1);
        }
        boolean zDom = Math.abs(f.getZ()) >= Math.abs(f.getX());
        int sx = zDom ? 0 : (f.getX() > 0 ? 1 : -1);
        int sz = zDom ? (f.getZ() > 0 ? 1 : -1) : 0;
        int cx = ol.getBlockX() - sx * 6;
        int cz = ol.getBlockZ() - sz * 6;
        int gy = Integer.MIN_VALUE;
        for (int dy = 4; dy >= -6; dy--) {
            Block b = w.getBlockAt(cx, ol.getBlockY() + dy, cz);
            if (b.isPassable() && !b.isLiquid() && !b.getRelative(BlockFace.DOWN).isPassable()) {
                gy = b.getY();
                break;
            }
        }
        if (gy == Integer.MIN_VALUE) {
            return null;
        }
        List<PortalBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                int px = zDom ? cx - 1 + i : cx;
                int pz = zDom ? cz : cz - 1 + i;
                Block b = w.getBlockAt(px, gy + j, pz);
                if (b.getState() instanceof TileState) {
                    return null;
                }
                blocks.add(new PortalBlock(b, b.getBlockData()));
            }
        }
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                Block b = blocks.get(idx++).block();
                boolean frame = i == 0 || i == 3 || j == 0 || j == 4;
                if (frame) {
                    b.setType(Material.OBSIDIAN, false);
                } else {
                    b.setType(Material.NETHER_PORTAL, false);
                    if (b.getBlockData() instanceof Orientable o) {
                        o.setAxis(zDom ? Axis.X : Axis.Z);
                        b.setBlockData(o, false);
                    }
                }
            }
        }
        double ix = zDom ? cx + 1.0 : cx + 0.5;
        double iz = zDom ? cz + 0.5 : cz + 1.0;
        Location inside = new Location(w, ix, gy + 1, iz);
        inside.setDirection(ol.toVector().subtract(inside.toVector()));
        portals.add(new Portal(inside.clone(), Bukkit.getCurrentTick() + lifeTicks, blocks));
        return inside;
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        Location from = e.getFrom();
        for (Portal p : portals) {
            if (p.center().getWorld() == from.getWorld() && p.center().distanceSquared(from) < 81) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (isMarked(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    private void tick() {
        tickPortals();
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
        e.getDrops().addAll(bot.loot());
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
        if (v == null || Bukkit.getCurrentTick() - v.tick() > 200 || !"theo".equals(v.typeId())) {
            return;
        }
        Location loc = dead.getLocation();
        PvpBot bot = bots.get(v.botId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> buildGrave(loc, bot), 8L);
    }

    private boolean free(Block b) {
        return b.isReplaceable() && !b.isLiquid();
    }

    private void buildGrave(Location loc, PvpBot bot) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Block base = w.getBlockAt(loc);
        for (int i = 0; i < 3 && !base.isReplaceable() && !base.isPassable(); i++) {
            base = base.getRelative(BlockFace.UP);
        }
        for (int i = 0; i < 8 && !base.getRelative(BlockFace.DOWN).getType().isSolid(); i++) {
            if (base.getY() <= w.getMinHeight() + 1) {
                return;
            }
            base = base.getRelative(BlockFace.DOWN);
        }
        if (!free(base) || !base.getRelative(BlockFace.DOWN).getType().isSolid()) {
            return;
        }
        BlockFace dir = BlockFace.SOUTH;
        if (bot != null && bot.isAlive()) {
            Location bl = bot.location();
            double dx = (base.getX() + 0.5) - bl.getX();
            double dz = (base.getZ() + 0.5) - bl.getZ();
            if (Math.abs(dx) > Math.abs(dz)) {
                dir = dx > 0 ? BlockFace.EAST : BlockFace.WEST;
            } else {
                dir = dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            }
            bot.swingHand();
        }
        Block mound1 = base.getRelative(dir);
        Block mound2 = mound1.getRelative(dir);
        Block signSpot = base.getRelative(dir.getOppositeFace());

        base.setType(Material.STONE_BRICKS, false);
        if (free(mound1)) {
            mound1.setType(Material.COARSE_DIRT, false);
        }
        if (free(mound2)) {
            mound2.setType(Material.COARSE_DIRT, false);
        }
        boolean signed = false;
        if (free(signSpot)) {
            signSpot.setType(Material.OAK_WALL_SIGN, false);
            if (signSpot.getBlockData() instanceof Directional d) {
                d.setFacing(dir.getOppositeFace());
                signSpot.setBlockData(d, false);
            }
            signed = writeLoser(signSpot);
        }
        Block top = base.getRelative(BlockFace.UP);
        if (free(top)) {
            if (signed) {
                top.setType(Material.STONE_BRICK_SLAB, false);
            } else {
                top.setType(Material.OAK_SIGN, false);
                writeLoser(top);
            }
        }
        w.playSound(base.getLocation(), "block.stone.place", org.bukkit.SoundCategory.BLOCKS, 1f, 1f);
    }

    private boolean writeLoser(Block b) {
        BlockState st = b.getState();
        if (st instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(1, Component.text("loser"));
            sign.setWaxed(true);
            sign.update(true, false);
            return true;
        }
        return false;
    }
}
