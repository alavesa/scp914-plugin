package fi.alavesa.scp914;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The refinement table: per setting, pages of input -> output pairs, edited
 * through the in-game UI and persisted to recipes.yml. Matching is by item
 * type + custom_model_data strings, so the lab's custom items refine too.
 */
public final class RecipeStore {

    public static final String[] SETTINGS = {"rough", "coarse", "one_to_one", "fine", "very_fine"};
    public static final String[] SETTING_NAMES = {"Rough", "Coarse", "1:1", "Fine", "Very Fine"};

    /** One row: an input and its outputs - each output is one raffle ticket. */
    public record Recipe(ItemStack input, List<ItemStack> outputs) { }

    private final Scp914Plugin plugin;
    private final File file;
    /** setting -> pages -> recipes on that page */
    private final Map<String, List<List<Recipe>>> table = new HashMap<>();

    public RecipeStore(Scp914Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recipes.yml");
    }

    public static String matchKey(ItemStack item) {
        StringBuilder key = new StringBuilder(item.getType().name());
        if (item.hasItemMeta()) {
            CustomModelDataComponent cmd = item.getItemMeta().getCustomModelDataComponent();
            for (String s : cmd.getStrings()) key.append('|').append(s);
        }
        return key.toString();
    }

    public void load() {
        table.clear();
        for (String setting : SETTINGS) table.put(setting, new ArrayList<>());
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String setting : SETTINGS) {
            ConfigurationSection settingSection = yaml.getConfigurationSection(setting);
            if (settingSection == null) continue;
            List<List<Recipe>> pages = table.get(setting);
            for (String pageKey : settingSection.getKeys(false)) {
                List<Recipe> page = new ArrayList<>();
                ConfigurationSection pageSection = settingSection.getConfigurationSection(pageKey);
                if (pageSection == null) continue;
                for (String index : pageSection.getKeys(false)) {
                    ItemStack in = pageSection.getItemStack(index + ".input");
                    if (in == null) continue;
                    List<ItemStack> outs = new ArrayList<>();
                    for (Object raw : pageSection.getList(index + ".outputs", List.of())) {
                        if (raw instanceof ItemStack stack) outs.add(stack);
                    }
                    ItemStack legacy = pageSection.getItemStack(index + ".output");
                    if (legacy != null) outs.add(legacy); // pre-0.4.2 format
                    if (!outs.isEmpty()) page.add(new Recipe(in, outs));
                }
                pages.add(page);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (String setting : SETTINGS) {
            List<List<Recipe>> pages = table.get(setting);
            for (int p = 0; p < pages.size(); p++) {
                int index = 0;
                for (Recipe recipe : pages.get(p)) {
                    String base = setting + ".page" + p + "." + index++;
                    yaml.set(base + ".input", recipe.input());
                    yaml.set(base + ".outputs", recipe.outputs());
                }
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save recipes.yml: " + e.getMessage());
        }
    }

    public List<Recipe> page(String setting, int page) {
        List<List<Recipe>> pages = table.get(setting);
        while (pages.size() <= page) pages.add(new ArrayList<>());
        return pages.get(page);
    }

    public void setPage(String setting, int page, List<Recipe> recipes) {
        List<List<Recipe>> pages = table.get(setting);
        while (pages.size() <= page) pages.add(new ArrayList<>());
        pages.set(page, new ArrayList<>(recipes));
        while (!pages.isEmpty() && pages.get(pages.size() - 1).isEmpty()) {
            pages.remove(pages.size() - 1); // prune empty trailing pages
        }
        save();
    }

    public int pageCount(String setting) {
        return Math.max(1, table.get(setting).size());
    }

    /**
     * The refinement itself. ALL rows matching the input are collected (across
     * every page) and one is drawn at random - so the same input on several
     * rows becomes a chance table: three rows of "Level-1 -> Level-2" and one
     * of "Level-1 -> Omni" is a 25% Omni. A single row behaves as before.
     * Without any recipe: Rough and Coarse grind ANYTHING into Dust; the
     * other settings pass the item through unchanged.
     */
    public ItemStack refine(String setting, ItemStack input) {
        String key = matchKey(input);
        List<ItemStack> options = new ArrayList<>();
        for (List<Recipe> page : table.get(setting)) {
            for (Recipe recipe : page) {
                if (matchKey(recipe.input()).equals(key)) {
                    options.addAll(recipe.outputs());
                }
            }
        }
        if (!options.isEmpty()) {
            ItemStack out = options.get(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size())).clone();
            int total = out.getAmount() * input.getAmount();
            out.setAmount(Math.min(out.getMaxStackSize(), total));
            return out;
        }
        if (setting.equals("rough") || setting.equals("coarse")) {
            return dust(input.getAmount());
        }
        return input.clone();
    }

    /** What Rough and Coarse make of everything they don't recognize. */
    public static ItemStack dust(int amount) {
        ItemStack item = new ItemStack(Material.GUNPOWDER, Math.min(64, Math.max(1, amount)));
        var meta = item.getItemMeta();
        meta.itemName(Component.text("Dust", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
