package fi.alavesa.scp914;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The refinement book: one double chest per setting, 15 input -> output pairs
 * per page (columns 0-1, 3-4, 6-7; gray dividers between), page controls on
 * the bottom row. Filling a page? The next-page arrow always offers a fresh
 * one. Contents are saved on close or page turn.
 */
public final class RecipeUi implements Listener {

    private static final int[] INPUT_SLOTS;
    static {
        List<Integer> slots = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            slots.add(row * 9);
            slots.add(row * 9 + 3);
            slots.add(row * 9 + 6);
        }
        INPUT_SLOTS = slots.stream().mapToInt(Integer::intValue).toArray();
    }
    private static final int PREV = 45, INFO = 49, NEXT = 53;

    private static final class Holder implements InventoryHolder {
        final String setting;
        final int page;
        Inventory inventory;
        Holder(String setting, int page) { this.setting = setting; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final Scp914Plugin plugin;

    public RecipeUi(Scp914Plugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, String setting, int page) {
        int settingIndex = List.of(RecipeStore.SETTINGS).indexOf(setting);
        Holder holder = new Holder(setting, page);
        holder.inventory = Bukkit.createInventory(holder, 54,
            Component.text("SCP-914 - " + RecipeStore.SETTING_NAMES[settingIndex]
                + " (page " + (page + 1) + ")", NamedTextColor.DARK_AQUA));
        Inventory inv = holder.inventory;
        for (int slot = 0; slot < 54; slot++) inv.setItem(slot, divider());
        List<RecipeStore.Recipe> recipes = plugin.recipes().page(setting, page);
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            int in = INPUT_SLOTS[i];
            inv.setItem(in, i < recipes.size() ? recipes.get(i).input().clone() : null);
            inv.setItem(in + 1, i < recipes.size() ? recipes.get(i).output().clone() : null);
        }
        inv.setItem(PREV, button(Material.ARROW, page > 0
            ? "Page " + page : "This is the first page"));
        inv.setItem(INFO, button(Material.WRITABLE_BOOK,
            "Left slot IN, right slot OUT. Saved when closed."));
        inv.setItem(NEXT, button(Material.ARROW, "Page " + (page + 2)));
        player.openInventory(inv);
    }

    private ItemStack divider() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String label) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text(label, NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private boolean isPairSlot(int slot) {
        if (slot >= 45) return false;
        int column = slot % 9;
        return column == 0 || column == 1 || column == 3 || column == 4 || column == 6 || column == 7;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return; // own inv is free
        int slot = event.getSlot();
        if (isPairSlot(slot)) return; // editable area
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (slot == NEXT || (slot == PREV && holder.page > 0)) {
            saveFrom(holder);
            int page = slot == NEXT ? holder.page + 1 : holder.page - 1;
            plugin.getServer().getScheduler().runTask(plugin,
                () -> open(player, holder.setting, page));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) {
            saveFrom(holder);
        }
    }

    private void saveFrom(Holder holder) {
        List<RecipeStore.Recipe> recipes = new ArrayList<>();
        for (int in : INPUT_SLOTS) {
            ItemStack input = holder.inventory.getItem(in);
            ItemStack output = holder.inventory.getItem(in + 1);
            if (input != null && !input.getType().isAir()
                && output != null && !output.getType().isAir()) {
                recipes.add(new RecipeStore.Recipe(input.clone(), output.clone()));
            }
        }
        plugin.recipes().setPage(holder.setting, holder.page, recipes);
    }

    // ------------------------------------------------------------- the dial

    @EventHandler
    public void onControl(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction knob)) return;
        boolean dial = knob.getScoreboardTags().contains(MachineManager.TAG_DIAL);
        boolean key = knob.getScoreboardTags().contains(MachineManager.TAG_KEY);
        if (!dial && !key) return;
        event.setCancelled(true);
        String anchorId = knob.getPersistentDataContainer()
            .get(plugin.key("anchor"), PersistentDataType.STRING);
        if (anchorId == null) return;
        if (Bukkit.getEntity(UUID.fromString(anchorId)) instanceof Marker anchor) {
            if (dial) plugin.machines().cycleDial(event.getPlayer(), anchor);
            else plugin.machines().turnKey(event.getPlayer(), anchor);
        }
    }
}
