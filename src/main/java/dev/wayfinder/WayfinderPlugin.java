package dev.wayfinder;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public final class WayfinderPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final int MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final String PREFIX = ChatColor.DARK_AQUA + "[Wayfinder] " + ChatColor.RESET;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,32}");
    private final List<Waypoint> waypoints = new ArrayList<>();
    private final Map<UUID, Waypoint> activeTargets = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();
    private File dataFile;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "waypoints.yml");
        loadWaypoints();
        Objects.requireNonNull(getCommand("wp")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        long interval = Math.max(5L, getConfig().getLong("update-interval-ticks", 10L));
        new BukkitRunnable() { @Override public void run() { updateNavigation(); } }.runTaskTimer(this, interval, interval);
        getLogger().info("Wayfinder menu system enabled; loaded " + waypoints.size() + " waypoints.");
    }

    @Override public void onDisable() { saveWaypoints(); activeTargets.clear(); pendingInputs.clear(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Use /wp in-game to open the Wayfinder menu."); return true; }
        if (!player.hasPermission("wayfinder.use")) { player.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use Wayfinder."); return true; }
        if (args.length == 0) { openMainMenu(player); return true; }
        // Keep /wp as the only documented command. Any argument also opens the menu for backward compatibility.
        openMainMenu(player);
        return true;
    }

    private void openMainMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.MAIN, 0, null);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_AQUA + "Wayfinder"); holder.inventory = inv;
        inv.setItem(20, item(Material.LODESTONE, ChatColor.GREEN + "Create Waypoint", "Save your current location"));
        inv.setItem(22, item(Material.COMPASS, ChatColor.AQUA + "My Waypoints", "Manage, rename, or remove your waypoints"));
        inv.setItem(24, item(Material.ENDER_EYE, ChatColor.LIGHT_PURPLE + "Public Waypoints", "Browse destinations shared with everyone"));
        inv.setItem(30, item(Material.RECOVERY_COMPASS, ChatColor.YELLOW + "Current Navigation", activeTargets.containsKey(player.getUniqueId()) ? "Click to stop navigation" : "No active destination"));
        inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Close", "Close this menu"));
        player.openInventory(inv);
    }

    private void openWaypointList(Player player, boolean publicList, int requestedPage) {
        List<Waypoint> entries = publicList ? publicWaypoints() : personalWaypoints(player.getUniqueId());
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        MenuHolder holder = new MenuHolder(publicList ? MenuType.PUBLIC_LIST : MenuType.PERSONAL_LIST, page, null);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, publicList ? ChatColor.DARK_AQUA + "Public Waypoints" : ChatColor.DARK_GREEN + "My Waypoints"); holder.inventory = inv;
        int from = page * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            Waypoint w = entries.get(i);
            Material icon = w.isPublic ? Material.ENDER_EYE : Material.COMPASS;
            inv.setItem(i - from, item(icon, (w.isPublic ? ChatColor.LIGHT_PURPLE + "[Public] " : ChatColor.GREEN + "[Personal] ") + w.name,
                    "World: " + w.world, "X: " + block(w.x) + " Y: " + block(w.y) + " Z: " + block(w.z), publicList ? "Click to navigate" : "Click to manage this waypoint"));
        }
        inv.setItem(45, item(Material.ARROW, ChatColor.WHITE + "Back", "Return to main menu"));
        inv.setItem(48, item(Material.ARROW, ChatColor.WHITE + "Previous page", "Page " + Math.max(1, page) + " / " + pageCount));
        inv.setItem(50, item(Material.ARROW, ChatColor.WHITE + "Next page", "Page " + Math.min(pageCount, page + 2) + " / " + pageCount));
        inv.setItem(53, item(Material.BARRIER, ChatColor.RED + "Close", "Close menu"));
        player.openInventory(inv);
    }
