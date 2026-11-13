package me.prime.callbot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CallCommand implements CommandExecutor, TabCompleter {

    private final HornItem horn;
    private final BotManager bots;

    public CallCommand(HornItem horn, BotManager bots) {
        this.horn = horn;
        this.bots = bots;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "/callhorn give [игрок] | clear", NamedTextColor.GRAY);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!sender.hasPermission("callbot.give")) {
                    msg(sender, "Нет прав", NamedTextColor.RED);
                    return true;
                }
                Player target = null;
                if (args.length > 1) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player p) {
                    target = p;
                }
                if (target == null) {
                    msg(sender, "Игрок не найден", NamedTextColor.RED);
                    return true;
                }
                for (ItemStack rest : target.getInventory().addItem(horn.create()).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                }
                msg(sender, "Рог Call выдан: " + target.getName(), NamedTextColor.YELLOW);
            }
            case "clear" -> {
                if (!sender.hasPermission("callbot.clear")) {
                    msg(sender, "Нет прав", NamedTextColor.RED);
                    return true;
                }
                int n = bots.removeAll();
                msg(sender, "Убрано ботов: " + n, NamedTextColor.YELLOW);
            }
            default -> msg(sender, "/callhorn give [игрок] | clear", NamedTextColor.GRAY);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "clear")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    private void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }
}
