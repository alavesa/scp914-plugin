package fi.alavesa.scp914;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What SCP-914 does to PEOPLE. Any player inside the intake chamber when the
 * cycle completes emerges in the output chamber... changed:
 *
 *  Rough      - dust. A red carpet is all that reaches the output chamber.
 *  Coarse     - 3 hearts max and permanent darkness, until death.
 *  1:1        - with other players online: another player's skin (Class-D,
 *               consider your options). Alone: nothing at all.
 *  Fine       - night vision + Speed II, until death.
 *  Very Fine  - a super soldier: Strength, Speed II, Resistance II, night
 *               vision - and 3 minutes to live. The timer survives relogs;
 *               death of any kind resets everything; death BY the timer
 *               drops 16 Dust.
 */
public final class PlayerEffects implements Listener {

    private final Scp914Plugin plugin;
    private final Map<UUID, PlayerProfile> originalProfiles = new HashMap<>();
    private final Set<UUID> timerDeaths = new HashSet<>();

    public PlayerEffects(Scp914Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- refining

    public void refine(String setting, Player player, Location output) {
        Location arrive = output.clone();
        arrive.setDirection(player.getLocation().getDirection());
        player.teleport(arrive);
        player.getWorld().playSound(arrive, Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 0.9f);
        switch (setting) {
            case "rough" -> rough(player, output);
            case "coarse" -> coarse(player);
            case "one_to_one" -> oneToOne(player);
            case "fine" -> fine(player);
            case "very_fine" -> veryFine(player);
            default -> { }
        }
    }

    private void rough(Player player, Location output) {
        Location floor = output.clone();
        while (floor.getBlockY() > output.getBlockY() - 3 && floor.getBlock().getType() == Material.AIR
            && floor.clone().subtract(0, 1, 0).getBlock().getType() == Material.AIR) {
            floor.subtract(0, 1, 0);
        }
        if (floor.getBlock().getType() == Material.AIR) {
            floor.getBlock().setType(Material.RED_CARPET);
        }
        player.getWorld().playSound(output, Sound.BLOCK_SAND_BREAK, 1f, 0.6f);
        player.damage(1000.0);
    }

    private void coarse(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        attribute.setBaseValue(6.0);
        if (player.getHealth() > 6.0) player.setHealth(6.0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
            PotionEffect.INFINITE_DURATION, 0, false, false));
        player.getPersistentDataContainer().set(plugin.key("coarse"), PersistentDataType.BYTE, (byte) 1);
        player.sendActionBar(Component.text("Something is missing.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    private void oneToOne(Player player) {
        List<Player> others = new ArrayList<>(Bukkit.getOnlinePlayers());
        others.removeIf(other -> other.getUniqueId().equals(player.getUniqueId()));
        if (others.isEmpty()) {
            player.sendActionBar(Component.text("Nothing happens.", NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        Player target = others.get(ThreadLocalRandom.current().nextInt(others.size()));
        originalProfiles.putIfAbsent(player.getUniqueId(), player.getPlayerProfile());
        PlayerProfile disguise = Bukkit.createProfile(player.getUniqueId(), player.getName());
        disguise.setProperties(target.getPlayerProfile().getProperties());
        player.setPlayerProfile(disguise);
        player.sendActionBar(Component.text("The mirror would disagree with you now.",
            NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    private void fine(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
            PotionEffect.INFINITE_DURATION, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
            PotionEffect.INFINITE_DURATION, 1, false, false));
        player.sendActionBar(Component.text("Sharper. Faster.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    private void veryFine(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
            PotionEffect.INFINITE_DURATION, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
            PotionEffect.INFINITE_DURATION, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
            PotionEffect.INFINITE_DURATION, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
            PotionEffect.INFINITE_DURATION, 0, false, false));
        player.getPersistentDataContainer().set(plugin.key("vf_deadline"), PersistentDataType.LONG,
            System.currentTimeMillis() + 180_000L);
        player.sendActionBar(Component.text("Perfect. For now.", NamedTextColor.GRAY, TextDecoration.ITALIC));
    }

    // ------------------------------------------------------------- the clock

    /** Once per second: the Very Fine countdown (it survives relogging). */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long deadline = player.getPersistentDataContainer()
                .get(plugin.key("vf_deadline"), PersistentDataType.LONG);
            if (deadline == null) continue;
            long left = deadline - now;
            if (left <= 0) {
                timerDeaths.add(player.getUniqueId());
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SAND_BREAK, 1.2f, 0.5f);
                player.damage(1000.0);
            } else if (left <= 15_000L) {
                player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f,
                    left <= 5_000L ? 1.8f : 1.3f);
                if (left <= 10_000L) {
                    player.sendActionBar(Component.text("I can feel it coming apart.",
                        NamedTextColor.RED, TextDecoration.ITALIC));
                }
            }
        }
    }

    // ------------------------------------------------------------- resets

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        // dying at the end of the 3 minutes leaves 16 Dust in the drops
        if (timerDeaths.remove(player.getUniqueId())) {
            event.getDrops().add(RecipeStore.dust(16));
        }
        // any death resets the Very Fine clock (the effects die with you)
        player.getPersistentDataContainer().remove(plugin.key("vf_deadline"));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        var pdc = player.getPersistentDataContainer();
        if (pdc.has(plugin.key("coarse"), PersistentDataType.BYTE)) {
            pdc.remove(plugin.key("coarse"));
            var attribute = player.getAttribute(Attribute.MAX_HEALTH);
            attribute.setBaseValue(attribute.getDefaultValue());
        }
        PlayerProfile original = originalProfiles.remove(player.getUniqueId());
        if (original != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.setPlayerProfile(original));
        }
    }
}
