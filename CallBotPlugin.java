package me.prime.callbot;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CallBotPlugin extends JavaPlugin {

    private HornItem horn;
    private BotManager bots;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getInt("config-version", 1) < 2) {
            saveResource("config.yml", true);
            reloadConfig();
        }
        horn = new HornItem(this);
        bots = new BotManager(this);
        getServer().getPluginManager().registerEvents(bots, this);
        getServer().getPluginManager().registerEvents(new HornListener(this, horn, bots), this);

        PluginCommand cmd = getCommand("callhorn");
        if (cmd != null) {
            CallCommand handler = new CallCommand(horn, bots);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        bots.start();
    }

    @Override
    public void onDisable() {
        if (bots != null) {
            bots.shutdown();
        }
    }
}
