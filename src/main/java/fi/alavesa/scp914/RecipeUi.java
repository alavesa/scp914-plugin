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
 * The refinement book, laid out so it cannot be misread: ONE RECIPE PER ROW.
 * Input in the left slot, an arrow in the middle, output in the right slot -
 * five rows per page, page controls on the bottom row, and the next-page
 * arrow always offers a fresh page. Complete rows are saved on close or page
 * turn (with a count, so you know it happened); half-filled rows are handed
 * back to you rather than silently discarded.
 */
public final class RecipeUi implements Listener {

    private static final int ROWS = 5;
    private static final int PREV = 45, INFO = 49, NEXT = 53;

    private static final class Holder implements InventoryHolder {
        final String setting;
        final int page;
        Inventory inventory;
        boolean saved;
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
        for (int slot = ROWS * 9; slot < 54; slot++) inv.setItem(slot, filler());
        List<RecipeStore.Recipe> recipes = plugin.recipes().page(setting, page);
        for (int row = 0; row < ROWS && row < recipes.size(); row++) {
            RecipeStore.Recipe recipe = recipes.get(row);
            inv.setItem(row * 9, recipe.input().clone());
            for (int i = 0; i < 8 && i < recipe.outputs().size(); i++) {
                inv.setItem(row * 9 + 1 + i, recipe.outputs().get(i).clone());
            }
        }
        inv.setItem(PREV, button(Material.ARROW,
            page > 0 ? "<- Page " + page : "This is the first page"));
        inv.setItem(INFO, button(Material.WRITABLE_BOOK,
            "Leftmost slot = input. The rest of the row = outputs; one is drawn at random."));
        inv.setItem(NEXT, button(Material.ARROW, "Page " + (page + 2) + " ->"));
        player.openInventory(inv);
    }

    private ItemStack filler() {
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

    private boolean isEditable(int slot) {
        return slot < ROWS * 9; // every row slot is live: input col 0, outputs 1-8
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            // own inventory stays free, but block shift-clicks: vanilla would
            // dump the item into an arbitrary top slot
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        int slot = event.getSlot();
        if (isEditable(slot)) return;
        event.setCancelled(true);
        if (slot == NEXT || (slot == PREV && holder.page > 0)) {
            save(holder, player);
            int page = slot == NEXT ? holder.page + 1 : holder.page - 1;
            plugin.getServer().getScheduler().runTask(plugin,
                () -> open(player, holder.setting, page));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder
            && event.getPlayer() instanceof Player player) {
            save(holder, player);
        }
    }

    private void save(Holder holder, Player player) {
        if (holder.saved) return;
        holder.saved = true;
        List<RecipeStore.Recipe> recipes = new ArrayList<>();
        List<ItemStack> strays = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            ItemStack input = holder.inventory.getItem(row * 9);
            boolean hasIn = input != null && !input.getType().isAir();
            List<ItemStack> outputs = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                ItemStack out = holder.inventory.getItem(row * 9 + i);
                if (out != null && !out.getType().isAir()) outputs.add(out.clone());
            }
            if (hasIn && !outputs.isEmpty()) {
                recipes.add(new RecipeStore.Recipe(input.clone(), outputs));
            } else {
                // half a recipe is not a recipe - hand it back, never delete it
                if (hasIn) strays.add(input.clone());
                strays.addAll(outputs);
            }
        }
        plugin.recipes().setPage(holder.setting, holder.page, recipes);
        for (ItemStack stray : strays) {
            player.getInventory().addItem(stray).values().forEach(left ->
                player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        int settingIndex = List.of(RecipeStore.SETTINGS).indexOf(holder.setting);
        player.sendMessage(Component.text("SCP-914: saved " + recipes.size() + " recipe(s) on "
            + RecipeStore.SETTING_NAMES[settingIndex] + " page " + (holder.page + 1)
            + (strays.isEmpty() ? "" : " - " + strays.size() + " incomplete item(s) returned to you"),
            NamedTextColor.AQUA));
    }

    // ------------------------------------------------------------- controls

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
