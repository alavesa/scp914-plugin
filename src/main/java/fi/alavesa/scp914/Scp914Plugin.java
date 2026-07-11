package fi.alavesa.scp914;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class Scp914Plugin extends JavaPlugin {

    private static final Map<String, String> SETTING_ALIASES = Map.of(
        "rough", "rough", "coarse", "coarse", "1:1", "one_to_one",
        "oneto1", "one_to_one", "fine", "fine", "veryfine", "very_fine");

    private RecipeStore recipes;
    private MachineManager machines;
    private RecipeUi ui;

    @Override
    public void onEnable() {
        getConfig().addDefault("body.scale", 3.0);
        getConfig().addDefault("body.offset", "0,1.5,0");
        getConfig().addDefault("dial.scale", 1.0);
        getConfig().addDefault("dial.offset", "-0.9,1.0,1.9");
        getConfig().addDefault("key.scale", 1.0);
        getConfig().addDefault("key.offset", "0.9,1.0,1.9");
        getConfig().addDefault("intake", "-3,0.4,0");
        getConfig().addDefault("output", "3,0.6,0");
        getConfig().addDefault("barriers.width", 5);
        getConfig().addDefault("barriers.height", 3);
        getConfig().addDefault("barriers.depth", 3);
        getConfig().options().copyDefaults(true);
        saveConfig();
        recipes = new RecipeStore(this);
        recipes.load();
        machines = new MachineManager(this);
        ui = new RecipeUi(this);
        getServer().getPluginManager().registerEvents(ui, this);
        getServer().getScheduler().runTaskTimer(this, machines::tickMachines, 40L, 10L);
        getLogger().info("Scp914 enabled");
    }

    public RecipeStore recipes() { return recipes; }
    public MachineManager machines() { return machines; }
    public NamespacedKey key(String name) { return new NamespacedKey(this, name); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scp914.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "place" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                machines.place(player);
                return true;
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                int removed = machines.remove(player.getLocation());
                sender.sendMessage(Component.text("Removed " + removed + " machine(s), barriers cleared.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "recipes" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 2) return usage(sender);
                String setting = SETTING_ALIASES.get(args[1].toLowerCase(Locale.ROOT));
                if (setting == null) return error(sender, "Settings: rough, coarse, 1:1, fine, veryfine");
                ui.open(player, setting, 0);
                return true;
            }
            case "set" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 3) return usage(sender);
                Marker anchor = machines.nearestAnchor(player.getLocation(), 12);
                if (anchor == null) return error(sender, "No SCP-914 within 12 blocks.");
                try {
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "scale" -> machines.updateBody(anchor, Double.parseDouble(args[2]), null);
                        case "dial-scale" -> machines.updateControl(anchor, "dial", Double.parseDouble(args[2]), null);
                        case "key-scale" -> machines.updateControl(anchor, "key", Double.parseDouble(args[2]), null);
                        case "offset" -> machines.updateBody(anchor, null, vector(args));
                        case "dial-offset" -> machines.updateControl(anchor, "dial", null, vector(args));
                        case "key-offset" -> machines.updateControl(anchor, "key", null, vector(args));
                        case "intake" -> machines.updateZone(anchor, "intake", vector(args));
                        case "output" -> machines.updateZone(anchor, "output", vector(args));
                        default -> { return error(sender,
                            "set scale|dial-scale|key-scale <v> or set offset|dial-offset|key-offset|intake|output <x> <y> <z>"); }
                    }
                } catch (Exception e) {
                    return error(sender, "Numbers, please.");
                }
                sender.sendMessage(Component.text("Applied to the nearest SCP-914.", NamedTextColor.AQUA));
                return true;
            }
            case "barriers" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (args.length < 4) return usage(sender);
                Marker anchor = machines.nearestAnchor(player.getLocation(), 12);
                if (anchor == null) return error(sender, "No SCP-914 within 12 blocks.");
                try {
                    machines.fillBarriers(anchor, Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    return error(sender, "Numbers, please.");
                }
                sender.sendMessage(Component.text("Barrier box refilled.", NamedTextColor.AQUA));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    private Vector vector(String[] args) {
        return new Vector(Double.parseDouble(args[2]),
            Double.parseDouble(args[3]), Double.parseDouble(args[4]));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> filter(Stream.of("place", "remove", "recipes", "set", "barriers"), args[0]);
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "recipes" -> filter(Stream.of("rough", "coarse", "1:1", "fine", "veryfine"), args[1]);
                case "set" -> filter(Stream.of("scale", "dial-scale", "key-scale", "offset",
                    "dial-offset", "key-offset", "intake", "output"), args[1]);
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/scp914 place | remove | recipes <setting> | set scale|dial-scale|offset|dial-offset|intake|output ... | barriers <w> <h> <d>",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
