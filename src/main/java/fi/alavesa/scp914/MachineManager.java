package fi.alavesa.scp914;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Clockworks in the world - v2, the SCP:CB flow:
 *
 * Two barrier CHAMBERS flank the machine (intake left, output right), open at
 * the front. Items dropped in the intake chamber just sit there - the machine
 * takes them only when THE KEY (its own two-state model + interaction box,
 * separate from body and dial) is turned. Then: key model flips to "turned",
 * the body model swaps to its doors-closed state, both chamber mouths seal
 * with temporary barriers, and 15 seconds of machinery later the results
 * appear in the output chamber and everything reopens.
 *
 * On Rough and Coarse, anything without a recipe refines into "Dust".
 */
public final class MachineManager {

    public static final String TAG_ANCHOR = "scp914.anchor";
    public static final String TAG_PART = "scp914.part";
    public static final String TAG_BODY = "scp914.body";
    public static final String TAG_DIAL = "scp914.dial";
    public static final String TAG_KEY = "scp914.key";

    private static final int REFINE_TICKS = 15 * 20;
    private static final int WINDUP_TICKS = 3 * 20;

    /** The refinement blackout: the same full-screen glyph the blink uses. */
    private static final Title BLACKOUT = Title.title(
        Component.text("\uE000").font(Key.key("scp", "blink")), Component.empty(),
        Title.Times.times(java.time.Duration.ZERO,
            java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(300)));

    private static final class Job {
        List<ItemStack> inputs = new ArrayList<>();
        List<UUID> occupants = new ArrayList<>();
        int doorsAt;
        int doneAt;
        boolean sealed;
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
                plugin.getConfig().getString("dial.offset", "-0.9,1.0,1.9"));
            pdc.set(plugin.key("key-scale"), PersistentDataType.DOUBLE,
                plugin.getConfig().getDouble("key.scale", 1.0));
            pdc.set(plugin.key("key-offset"), PersistentDataType.STRING,
                plugin.getConfig().getString("key.offset", "0.9,1.0,1.9"));
            pdc.set(plugin.key("intake"), PersistentDataType.STRING,
                plugin.getConfig().getString("intake", "-3,0.4,0"));
            pdc.set(plugin.key("output"), PersistentDataType.STRING,
                plugin.getConfig().getString("output", "3,0.6,0"));
        });
        spawnParts(anchor);
        fillBarriers(anchor,
            plugin.getConfig().getInt("barriers.width", 5),
            plugin.getConfig().getInt("barriers.height", 3),
            plugin.getConfig().getInt("barriers.depth", 3));
        player.sendMessage(Component.text(
            "SCP-914 assembled: intake chamber left, output right. Set the dial, drop items in, turn the key.",
            NamedTextColor.AQUA));
    }

    private void spawnParts(Marker anchor) {
        for (Entity old : partsOf(anchor)) old.remove();
        Location at = anchor.getLocation();
        double scale = pdcDouble(anchor, "scale", 3.0);
        Vector offset = parseVector(pdcString(anchor, "offset", "0,1.5,0"));
        ItemDisplay body = spawnDisplay(anchor, at, Material.SMITHING_TABLE, "scp914_body",
            (float) scale, rotate(offset, at.getYaw()));
        body.addScoreboardTag(TAG_PART);
        body.addScoreboardTag(TAG_BODY);

        spawnControl(anchor, Material.COMPARATOR, "scp914_dial", TAG_DIAL,
            pdcDouble(anchor, "dial-scale", 1.0),
            pdcString(anchor, "dial-offset", "-0.9,1.0,1.9"));
        spawnControl(anchor, Material.TRIPWIRE_HOOK, "scp914_key", TAG_KEY,
            pdcDouble(anchor, "key-scale", 1.0),
            pdcString(anchor, "key-offset", "0.9,1.0,1.9"));
        applyDialAngle(anchor);
    }

    /** A small control (dial or key): its own model + its own interaction box. */
    private void spawnControl(Marker anchor, Material base, String cmd, String tag,
                              double scale, String offsetCsv) {
        Location at = anchor.getLocation();
        Vector offset = rotate(parseVector(offsetCsv), at.getYaw());
        ItemDisplay display = spawnDisplay(anchor, at, base, cmd, (float) scale, offset);
        display.addScoreboardTag(TAG_PART);
        display.addScoreboardTag(tag);
        Location box = at.clone().add(offset).subtract(0, 0.4, 0);
        Interaction knob = at.getWorld().spawn(box, Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.9f);
            i.setPersistent(true);
            i.addScoreboardTag(TAG_PART);
            i.addScoreboardTag(tag);
            i.getPersistentDataContainer().set(plugin.key("anchor"), PersistentDataType.STRING,
                anchor.getUniqueId().toString());
        });
        knob.getPersistentDataContainer().set(plugin.key("anchor"), PersistentDataType.STRING,
            anchor.getUniqueId().toString());
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
            display.setItemStack(modelItem(base, cmd));
        });
    }

    private ItemStack modelItem(Material base, String cmd) {
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setStrings(List.of(cmd));
        meta.setCustomModelDataComponent(component);
        item.setItemMeta(meta);
        return item;
    }

    private void setModelState(Marker anchor, String tag, Material base, String cmd) {
        for (Entity part : partsOf(anchor)) {
            if (part instanceof ItemDisplay display && part.getScoreboardTags().contains(tag)) {
                display.setItemStack(modelItem(base, cmd));
            }
        }
    }

    // ------------------------------------------------------------- barriers

    /**
     * The collision shell: a solid box under the body, plus two hollow
     * chambers (intake left, output right) whose mouths open toward the
     * front. Air-only, remembered exactly. The mouth positions are stored
     * separately - they get sealed while the machine runs.
     */
    public void fillBarriers(Marker anchor, int width, int height, int depth) {
        clearBarriers(anchor);
        List<String> placed = new ArrayList<>();
        List<String> mouths = new ArrayList<>();
        Location base = anchor.getLocation();
        float yaw = base.getYaw();
        int halfW = width / 2, halfD = depth / 2;

        Set<String> skip = new HashSet<>(); // chamber interiors stay open
        for (int side : new int[]{-1, 1}) {
            int cx = side * (halfW + 1);
            for (int dy = 0; dy <= 1; dy++) skip.add(cx + "," + dy + ",0");
        }
        // central body box
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = -halfD; dz <= halfD; dz++) {
                    placeBarrier(base, yaw, dx, dy, dz, placed, skip);
                }
            }
        }
        // the chambers: shell around a 1x2x1 interior, mouth open at front
        for (int side : new int[]{-1, 1}) {
            int cx = side * (halfW + 1);
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int ox = 0; ox <= 1; ox++) {
                        int dx = cx + side * ox;
                        boolean interior = ox == 0 && dz == 0 && dy <= 1;
                        boolean mouth = ox == 0 && dz == 1 && dy <= 1;
                        if (interior) continue;
                        if (mouth) {
                            mouths.add(worldKey(base, yaw, dx, dy, dz));
                            continue;
                        }
                        placeBarrier(base, yaw, dx, dy, dz, placed, skip);
                    }
                }
            }
        }
        var pdc = anchor.getPersistentDataContainer();
        pdc.set(plugin.key("barriers"), PersistentDataType.STRING, String.join(";", placed));
        pdc.set(plugin.key("mouths"), PersistentDataType.STRING, String.join(";", mouths));
    }

    private void placeBarrier(Location base, float yaw, int dx, int dy, int dz,
                              List<String> placed, Set<String> skip) {
        if (skip.contains(dx + "," + dy + "," + dz)) return;
        Block block = blockAt(base, yaw, dx, dy, dz);
        if (block.getType() == Material.AIR) {
            block.setType(Material.BARRIER);
            placed.add(block.getX() + "," + block.getY() + "," + block.getZ());
        }
    }

    private Block blockAt(Location base, float yaw, int dx, int dy, int dz) {
        Vector world = rotate(new Vector(dx, dy, dz), yaw);
        return base.clone().add(Math.round(world.getX()), dy, Math.round(world.getZ())).getBlock();
    }

    private String worldKey(Location base, float yaw, int dx, int dy, int dz) {
        Block block = blockAt(base, yaw, dx, dy, dz);
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    public void clearBarriers(Marker anchor) {
        removeStoredBarriers(anchor, "barriers");
        removeStoredBarriers(anchor, "seals");
        anchor.getPersistentDataContainer().remove(plugin.key("mouths"));
    }

    private void removeStoredBarriers(Marker anchor, String key) {
        String stored = pdcString(anchor, key, "");
        if (stored.isEmpty()) return;
        World world = anchor.getWorld();
        for (String entry : stored.split(";")) {
            if (entry.isEmpty()) continue;
            String[] parts = entry.split(",");
            Block block = world.getBlockAt(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getType() == Material.BARRIER) block.setType(Material.AIR);
        }
        anchor.getPersistentDataContainer().remove(plugin.key(key));
    }

    /** Seal both chamber mouths for the duration of a run. */
    private void sealChambers(Marker anchor) {
        String stored = pdcString(anchor, "mouths", "");
        if (stored.isEmpty()) return;
        List<String> placed = new ArrayList<>();
        World world = anchor.getWorld();
        for (String entry : stored.split(";")) {
            if (entry.isEmpty()) continue;
            String[] parts = entry.split(",");
            Block block = world.getBlockAt(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getType() == Material.AIR) {
                block.setType(Material.BARRIER);
                placed.add(entry);
            }
        }
        anchor.getPersistentDataContainer().set(plugin.key("seals"),
            PersistentDataType.STRING, String.join(";", placed));
    }

    private void unsealChambers(Marker anchor) {
        removeStoredBarriers(anchor, "seals");
    }

    // ------------------------------------------------------------- removal

    public int remove(Location near) {
        int removed = 0;
        for (Marker anchor : near.getWorld().getEntitiesByClass(Marker.class)) {
            if (!anchor.getScoreboardTags().contains(TAG_ANCHOR)) continue;
            if (anchor.getLocation().distanceSquared(near) > 64) continue;
            clearBarriers(anchor);
            for (Entity part : partsOf(anchor)) part.remove();
            jobs.remove(anchor.getUniqueId());
            anchor.remove();
            removed++;
        }
        return removed;
    }

    public List<Entity> partsOf(Marker anchor) {
        List<Entity> parts = new ArrayList<>();
        String id = anchor.getUniqueId().toString();
        for (Entity entity : anchor.getWorld().getNearbyEntities(anchor.getLocation(), 14, 14, 14)) {
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

    // ------------------------------------------------------------- controls

    public void cycleDial(Player player, Marker anchor) {
        if (jobs.containsKey(anchor.getUniqueId())) {
            player.sendActionBar(Component.text("The machine is running.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        int setting = (pdcInt(anchor, "setting", 2) + 1) % RecipeStore.SETTINGS.length;
        anchor.getPersistentDataContainer().set(plugin.key("setting"), PersistentDataType.INTEGER, setting);
        applyDialAngle(anchor);
        anchor.getWorld().playSound(anchor.getLocation(), Sound.BLOCK_COMPARATOR_CLICK, 1f, 0.7f);
        player.sendActionBar(Component.text("SCP-914: " + RecipeStore.SETTING_NAMES[setting],
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    private void applyDialAngle(Marker anchor) {
        int setting = pdcInt(anchor, "setting", 2);
        for (Entity part : partsOf(anchor)) {
            if (part instanceof ItemDisplay display && part.getScoreboardTags().contains(TAG_DIAL)) {
                display.setRotation(anchor.getLocation().getYaw() + (setting - 2) * 30f, 0);
            }
        }
    }

    /**
     * THE KEY. Turning it starts the wind-up: a few seconds of machinery
     * with the doors still OPEN - the window to climb in - and only then do
     * the doors close on whatever, and whoever, is in the intake.
     */
    public void turnKey(Player player, Marker anchor) {
        if (jobs.containsKey(anchor.getUniqueId())) {
            player.sendActionBar(Component.text("The machine is running.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        Job job = new Job();
        job.doorsAt = tick + WINDUP_TICKS;
        job.doneAt = job.doorsAt + REFINE_TICKS;
        jobs.put(anchor.getUniqueId(), job);
        setModelState(anchor, TAG_KEY, Material.TRIPWIRE_HOOK, "scp914_key_turned");
        Location at = anchor.getLocation();
        at.getWorld().playSound(at, Sound.BLOCK_TRIPWIRE_CLICK_ON, 1f, 0.6f);
        at.getWorld().playSound(at, Sound.BLOCK_GRINDSTONE_USE, 0.7f, 0.4f);
        player.sendActionBar(Component.text("The machine winds up.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    /** Wind-up over: the doors close on the intake's contents - and occupants. */
    private void seal(Marker anchor, Job job) {
        job.sealed = true;
        Location at = anchor.getLocation();
        Location intake = at.clone().add(rotate(parseVector(pdcString(anchor, "intake", "-3,0.4,0")), at.getYaw()));
        for (Entity entity : at.getWorld().getNearbyEntities(intake, 1.4, 1.4, 1.4)) {
            if (entity instanceof Item item) {
                job.inputs.add(item.getItemStack().clone());
                item.remove(); // NOW they disappear - not before
            }
        }
        for (Player inside : intake.getNearbyPlayers(1.4)) {
            job.occupants.add(inside.getUniqueId());
        }
        setModelState(anchor, TAG_BODY, Material.SMITHING_TABLE, "scp914_body_closed");
        sealChambers(anchor);
        at.getWorld().playSound(at, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
        at.getWorld().playSound(at, Sound.BLOCK_PISTON_CONTRACT, 1f, 0.5f);
    }

    // ------------------------------------------------------------- refining

    /** Called every 10 ticks by the scheduler. */
    public void tickMachines() {
        tick += 10;
        for (World world : Bukkit.getWorlds()) {
            for (Marker anchor : world.getEntitiesByClass(Marker.class)) {
                if (!anchor.getScoreboardTags().contains(TAG_ANCHOR)) continue;
                Job job = jobs.get(anchor.getUniqueId());
                if (job == null) continue;
                if (!job.sealed && tick >= job.doorsAt) {
                    seal(anchor, job);
                } else if (!job.sealed) {
                    windupEffects(anchor);
                } else if (tick < job.doneAt) {
                    runningEffects(anchor);
                    blackout(anchor, job);
                } else {
                    finish(anchor, job);
                }
            }
        }
    }

    private void windupEffects(Marker anchor) {
        Location at = anchor.getLocation();
        at.getWorld().playSound(at, Sound.BLOCK_PISTON_EXTEND, 0.7f, 0.45f);
        at.getWorld().playSound(at, Sound.BLOCK_LEVER_CLICK, 0.5f, 0.6f);
        at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 2.6, 0), 3, 0.2, 0.2, 0.2, 0.01);
    }

    /** Whoever is sealed inside sees nothing for fifteen seconds. */
    private void blackout(Marker anchor, Job job) {
        if (tick % 20 != 0) return;
        for (UUID id : job.occupants) {
            Player inside = Bukkit.getPlayer(id);
            if (inside != null && inside.isOnline()
                && inside.getWorld() == anchor.getWorld()
                && inside.getLocation().distanceSquared(anchor.getLocation()) < 100) {
                inside.showTitle(BLACKOUT);
            }
        }
    }

    private void runningEffects(Marker anchor) {
        Location at = anchor.getLocation();
        Sound sound = switch ((tick / 10) % 4) {
            case 0 -> Sound.BLOCK_GRINDSTONE_USE;
            case 1 -> Sound.BLOCK_PISTON_EXTEND;
            case 2 -> Sound.BLOCK_ANVIL_STEP;
            default -> Sound.BLOCK_SMITHING_TABLE_USE;
        };
        at.getWorld().playSound(at, sound, 0.9f, 0.55f);
        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1.5, 0), 6, 1.4, 0.8, 1.4, 0.05);
        at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 2.6, 0), 2, 0.2, 0.2, 0.2, 0.01);
    }

    private void finish(Marker anchor, Job job) {
        jobs.remove(anchor.getUniqueId());
        Location at = anchor.getLocation();
        int setting = pdcInt(anchor, "setting", 2);
        String settingKey = RecipeStore.SETTINGS[setting];
        Vector outputOffset = rotate(parseVector(pdcString(anchor, "output", "3,0.6,0")), at.getYaw());
        Location output = at.clone().add(outputOffset);
        // the occupants sealed in at door-close emerge in the output booth
        for (UUID id : job.occupants) {
            Player inside = Bukkit.getPlayer(id);
            if (inside == null || !inside.isOnline() || inside.getWorld() != at.getWorld()) continue;
            inside.clearTitle();
            plugin.playerEffects().refine(settingKey, inside, output.clone().add(0, 0.2, 0));
        }
        for (ItemStack input : job.inputs) {
            ItemStack out = plugin.recipes().refine(settingKey, input);
            at.getWorld().dropItem(output, out, item -> {
                item.setPickupDelay(15);
                item.setVelocity(new Vector(0, 0.1, 0));
            });
        }
        unsealChambers(anchor);
        setModelState(anchor, TAG_KEY, Material.TRIPWIRE_HOOK, "scp914_key");
        setModelState(anchor, TAG_BODY, Material.SMITHING_TABLE, "scp914_body");
        at.getWorld().playSound(at, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.7f);
        at.getWorld().playSound(output, Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        at.getWorld().playSound(output, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------- helpers

    public void updateBody(Marker anchor, Double scale, Vector offset) {
        var pdc = anchor.getPersistentDataContainer();
        if (scale != null) pdc.set(plugin.key("scale"), PersistentDataType.DOUBLE, scale);
        if (offset != null) pdc.set(plugin.key("offset"), PersistentDataType.STRING, csv(offset));
        spawnParts(anchor);
    }

    public void updateControl(Marker anchor, String prefix, Double scale, Vector offset) {
        var pdc = anchor.getPersistentDataContainer();
        if (scale != null) pdc.set(plugin.key(prefix + "-scale"), PersistentDataType.DOUBLE, scale);
        if (offset != null) pdc.set(plugin.key(prefix + "-offset"), PersistentDataType.STRING, csv(offset));
        spawnParts(anchor);
    }

    public void updateZone(Marker anchor, String zone, Vector offset) {
        anchor.getPersistentDataContainer().set(plugin.key(zone), PersistentDataType.STRING, csv(offset));
    }

    private static String csv(Vector v) {
        return v.getX() + "," + v.getY() + "," + v.getZ();
    }

    private static float snapYaw(float yaw) {
        return Math.round(yaw / 90f) * 90f;
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

    private static Vector parseVector(String csvValue) {
        String[] parts = csvValue.split(",");
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
