package fi.alavesa.scp914;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Clockworks in the world. Each machine is an anchor marker carrying its
 * configuration in PDC, plus: the big body display (custom model, live
 * size/offset), a SEPARATE dial display with its own interaction box (so the
 * body model can be animated without five dial variants), and a box of
 * barrier blocks for collision - only ever placed into air, and remembered,
 * so removal restores exactly what was there.
 *
 * Refinement: item entities inside the intake zone are recognized, consumed
 * with a lot of gear noise, and ~4 seconds later the results clatter out of
 * the output booth according to the dial setting.
 */
public final class MachineManager {

    public static final String TAG_ANCHOR = "scp914.anchor";
    public static final String TAG_PART = "scp914.part";
    public static final String TAG_DIAL = "scp914.dial";

    private static final class Job {
        List<ItemStack> inputs = new ArrayList<>();
        int doneAt;
    }

    private final Scp914Plugin plugin;
    private final Map<UUID, Job> jobs = new HashMap<>();
    private int tick;

    public MachineManager(Scp914Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- placing

    public void place(Player player) {
        Location at = player.getLocation().clone();
        at.setPitch(0);
        at.setYaw(snapYaw(player.getLocation().getYaw()));
        Marker anchor = at.getWorld().spawn(at, Marker.class, m -> {
            m.setPersistent(true);
            m.addScoreboardTag(TAG_ANCHOR);
            var pdc = m.getPersistentDataContainer();
            pdc.set(plugin.key("setting"), PersistentDataType.INTEGER, 2); // 1:1
            pdc.set(plugin.key("scale"), PersistentDataType.DOUBLE,
                plugin.getConfig().getDouble("body.scale", 3.0));
            pdc.set(plugin.key("offset"), PersistentDataType.STRING,
                plugin.getConfig().getString("body.offset", "0,1.5,0"));
            pdc.set(plugin.key("dial-scale"), PersistentDataType.DOUBLE,
                plugin.getConfig().getDouble("dial.scale", 1.0));
            pdc.set(plugin.key("dial-offset"), PersistentDataType.STRING,
                plugin.getConfig().getString("dial.offset", "0,1.0,1.9"));
            pdc.set(plugin.key("intake"), PersistentDataType.STRING,
                plugin.getConfig().getString("intake", "-2.6,0.4,0"));
            pdc.set(plugin.key("output"), PersistentDataType.STRING,
                plugin.getConfig().getString("output", "2.6,0.6,0"));
        });
        spawnParts(anchor);
        fillBarriers(anchor,
            plugin.getConfig().getInt("barriers.width", 5),
            plugin.getConfig().getInt("barriers.height", 3),
            plugin.getConfig().getInt("barriers.depth", 3));
        player.sendMessage(Component.text(
            "SCP-914 assembled. The dial is at 1:1 - click it to change settings.", NamedTextColor.AQUA));
    }

    private void spawnParts(Marker anchor) {
        for (Entity old : partsOf(anchor)) old.remove();
        Location at = anchor.getLocation();
        double scale = pdcDouble(anchor, "scale", 3.0);
        Vector offset = parseVector(pdcString(anchor, "offset", "0,1.5,0"));
        ItemDisplay body = spawnDisplay(anchor, at, Material.SMITHING_TABLE, "scp914_body",
            (float) scale, rotate(offset, at.getYaw()));
        body.addScoreboardTag(TAG_PART);

        double dialScale = pdcDouble(anchor, "dial-scale", 1.0);
        Vector dialOffset = rotate(parseVector(pdcString(anchor, "dial-offset", "0,1.0,1.9")), at.getYaw());
        ItemDisplay dial = spawnDisplay(anchor, at, Material.COMPARATOR, "scp914_dial",
            (float) dialScale, dialOffset);
        dial.addScoreboardTag(TAG_PART);
        dial.addScoreboardTag(TAG_DIAL);
        Location dialLoc = at.clone().add(dialOffset);
        Interaction knob = at.getWorld().spawn(dialLoc.clone().subtract(0, 0.4, 0), Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.9f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_PART);
            i.addScoreboardTag(TAG_DIAL);
            i.getPersistentDataContainer().set(plugin.key("anchor"), PersistentDataType.STRING,
                anchor.getUniqueId().toString());
        });
        knob.getPersistentDataContainer().set(plugin.key("anchor"), PersistentDataType.STRING,
            anchor.getUniqueId().toString());
        applyDialAngle(anchor);
    }

    private ItemDisplay spawnDisplay(Marker anchor, Location at, Material base, String cmd,
                                     float scale, Vector offset) {
        Location loc = at.clone().add(offset);
        loc.setYaw(at.getYaw());
        return at.getWorld().spawn(loc, ItemDisplay.class, display -> {
            display.setPersistent(true);
            display.setShadowRadius(1.4f);
            display.setShadowStrength(0.7f);
            display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scale, scale, scale), new AxisAngle4f(0, 0, 0, 1)));
            display.getPersistentDataContainer().set(plugin.key("anchor"), PersistentDataType.STRING,
                anchor.getUniqueId().toString());
            ItemStack item = new ItemStack(base);
            ItemMeta meta = item.getItemMeta();
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setStrings(List.of(cmd));
            meta.setCustomModelDataComponent(component);
            item.setItemMeta(meta);
            display.setItemStack(item);
        });
    }

    /** Fill the machine's footprint with barriers - air only, and remembered. */
    public void fillBarriers(Marker anchor, int width, int height, int depth) {
        clearBarriers(anchor);
        List<String> placed = new ArrayList<>();
        Location base = anchor.getLocation();
        boolean alongX = isAlongX(base.getYaw());
        int w = alongX ? width : depth;
        int d = alongX ? depth : width;
        for (int dx = -w / 2; dx <= w / 2; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = -d / 2; dz <= d / 2; dz++) {
                    Block block = base.clone().add(dx, dy, dz).getBlock();
                    if (block.getType() == Material.AIR) {
                        block.setType(Material.BARRIER);
                        placed.add(block.getX() + "," + block.getY() + "," + block.getZ());
                    }
                }
            }
        }
        anchor.getPersistentDataContainer().set(plugin.key("barriers"),
            PersistentDataType.STRING, String.join(";", placed));
    }

    public void clearBarriers(Marker anchor) {
        String stored = pdcString(anchor, "barriers", "");
        if (stored.isEmpty()) return;
        World world = anchor.getWorld();
        for (String entry : stored.split(";")) {
            String[] parts = entry.split(",");
            Block block = world.getBlockAt(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getType() == Material.BARRIER) block.setType(Material.AIR);
        }
        anchor.getPersistentDataContainer().remove(plugin.key("barriers"));
    }

    public int remove(Location near) {
        int removed = 0;
        for (Marker anchor : near.getWorld().getEntitiesByClass(Marker.class)) {
            if (!anchor.getScoreboardTags().contains(TAG_ANCHOR)) continue;
            if (anchor.getLocation().distanceSquared(near) > 64) continue;
            clearBarriers(anchor);
            for (Entity part : partsOf(anchor)) part.remove();
            anchor.remove();
            removed++;
        }
        return removed;
    }

    public List<Entity> partsOf(Marker anchor) {
        List<Entity> parts = new ArrayList<>();
        String id = anchor.getUniqueId().toString();
        for (Entity entity : anchor.getWorld().getNearbyEntities(anchor.getLocation(), 12, 12, 12)) {
            String owner = entity.getPersistentDataContainer().get(plugin.key("anchor"), PersistentDataType.STRING);
            if (id.equals(owner)) parts.add(entity);
        }
        return parts;
    }

    public Marker nearestAnchor(Location near, double range) {
        Marker best = null;
        double bestDistance = range * range;
        for (Marker anchor : near.getWorld().getEntitiesByClass(Marker.class)) {
            if (!anchor.getScoreboardTags().contains(TAG_ANCHOR)) continue;
            double d = anchor.getLocation().distanceSquared(near);
            if (d < bestDistance) { bestDistance = d; best = anchor; }
        }
        return best;
    }

    // ------------------------------------------------------------- the dial

    public void cycleDial(Player player, Marker anchor) {
        int setting = (pdcInt(anchor, "setting", 2) + 1) % RecipeStore.SETTINGS.length;
        anchor.getPersistentDataContainer().set(plugin.key("setting"), PersistentDataType.INTEGER, setting);
        applyDialAngle(anchor);
        anchor.getWorld().playSound(anchor.getLocation(), Sound.BLOCK_COMPARATOR_CLICK, 1f, 0.7f);
        anchor.getWorld().playSound(anchor.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.5f);
        player.sendActionBar(Component.text("SCP-914: " + RecipeStore.SETTING_NAMES[setting],
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** The knob turns: -60..+60 degrees across the five settings. */
    private void applyDialAngle(Marker anchor) {
        int setting = pdcInt(anchor, "setting", 2);
        for (Entity part : partsOf(anchor)) {
            if (part instanceof ItemDisplay display && part.getScoreboardTags().contains(TAG_DIAL)) {
                display.setRotation(anchor.getLocation().getYaw() + (setting - 2) * 30f, 0);
            }
        }
    }

    // ------------------------------------------------------------- refining

    /** Called every 10 ticks by the scheduler. */
    public void tickMachines() {
        tick += 10;
        for (World world : Bukkit.getWorlds()) {
            for (Marker anchor : world.getEntitiesByClass(Marker.class)) {
                if (anchor.getScoreboardTags().contains(TAG_ANCHOR)) tickMachine(anchor);
            }
        }
    }

    private void tickMachine(Marker anchor) {
        Job job = jobs.get(anchor.getUniqueId());
        Location at = anchor.getLocation();
        Vector intakeOffset = rotate(parseVector(pdcString(anchor, "intake", "-2.6,0.4,0")), at.getYaw());
        Location intake = at.clone().add(intakeOffset);
        if (job == null) {
            List<Item> waiting = new ArrayList<>();
            for (Entity entity : at.getWorld().getNearbyEntities(intake, 1.1, 1.1, 1.1)) {
                if (entity instanceof Item item && item.getPickupDelay() < 30) waiting.add(item);
            }
            if (waiting.isEmpty()) return;
            job = new Job();
            job.doneAt = tick + 80; // ~4 seconds of machinery
            for (Item item : waiting) {
                job.inputs.add(item.getItemStack().clone());
                item.remove();
            }
            jobs.put(anchor.getUniqueId(), job);
            at.getWorld().playSound(intake, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, 0.8f);
            int count = job.inputs.stream().mapToInt(ItemStack::getAmount).sum();
            for (Player nearby : at.getNearbyPlayers(8)) {
                nearby.sendActionBar(Component.text("SCP-914 accepts " + count + " item(s).",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
            }
            return;
        }
        if (tick < job.doneAt) {
            // the gears do their work
            at.getWorld().playSound(at, tick % 20 == 0
                ? Sound.BLOCK_GRINDSTONE_USE : Sound.BLOCK_PISTON_EXTEND, 0.8f, 0.6f);
            at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1.2, 0), 5, 1.2, 0.6, 1.2, 0.05);
            return;
        }
        jobs.remove(anchor.getUniqueId());
        int setting = pdcInt(anchor, "setting", 2);
        String settingKey = RecipeStore.SETTINGS[setting];
        Vector outputOffset = rotate(parseVector(pdcString(anchor, "output", "2.6,0.6,0")), at.getYaw());
        Location output = at.clone().add(outputOffset);
        for (ItemStack input : job.inputs) {
            ItemStack result = plugin.recipes().refine(settingKey, input);
            ItemStack out = result != null ? result : input; // unknown: passes through
            at.getWorld().dropItem(output, out, item -> {
                item.setPickupDelay(20);
                item.setVelocity(outputOffset.clone().normalize().multiply(0.12).setY(0.15));
            });
        }
        at.getWorld().playSound(output, Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        at.getWorld().playSound(output, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------- helpers

    public void updateBody(Marker anchor, Double scale, Vector offset) {
        var pdc = anchor.getPersistentDataContainer();
        if (scale != null) pdc.set(plugin.key("scale"), PersistentDataType.DOUBLE, scale);
        if (offset != null) pdc.set(plugin.key("offset"), PersistentDataType.STRING,
            offset.getX() + "," + offset.getY() + "," + offset.getZ());
        spawnParts(anchor);
    }

    public void updateDial(Marker anchor, Double scale, Vector offset) {
        var pdc = anchor.getPersistentDataContainer();
        if (scale != null) pdc.set(plugin.key("dial-scale"), PersistentDataType.DOUBLE, scale);
        if (offset != null) pdc.set(plugin.key("dial-offset"), PersistentDataType.STRING,
            offset.getX() + "," + offset.getY() + "," + offset.getZ());
        spawnParts(anchor);
    }

    public void updateZone(Marker anchor, String zone, Vector offset) {
        anchor.getPersistentDataContainer().set(plugin.key(zone), PersistentDataType.STRING,
            offset.getX() + "," + offset.getY() + "," + offset.getZ());
    }

    private static float snapYaw(float yaw) {
        return Math.round(yaw / 90f) * 90f;
    }

    private static boolean isAlongX(float yaw) {
        int snapped = Math.floorMod(Math.round(yaw / 90f), 4);
        return snapped == 0 || snapped == 2; // facing south/north: width runs east-west
    }

    /** Rotate a machine-local offset (x = right, z = forward) by the yaw. */
    private static Vector rotate(Vector local, float yaw) {
        double radians = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        Vector right = new Vector(Math.cos(radians), 0, Math.sin(radians));
        return right.multiply(local.getX())
            .add(forward.multiply(local.getZ()))
            .add(new Vector(0, local.getY(), 0));
    }

    private static Vector parseVector(String csv) {
        String[] parts = csv.split(",");
        return new Vector(Double.parseDouble(parts[0]),
            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
    }

    private double pdcDouble(Marker anchor, String key, double fallback) {
        return anchor.getPersistentDataContainer().getOrDefault(plugin.key(key), PersistentDataType.DOUBLE, fallback);
    }

    private int pdcInt(Marker anchor, String key, int fallback) {
        return anchor.getPersistentDataContainer().getOrDefault(plugin.key(key), PersistentDataType.INTEGER, fallback);
    }

    private String pdcString(Marker anchor, String key, String fallback) {
        return anchor.getPersistentDataContainer().getOrDefault(plugin.key(key), PersistentDataType.STRING, fallback);
    }
}
