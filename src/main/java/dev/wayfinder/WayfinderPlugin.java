package dev.wayfinder;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class WayfinderPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,32}");
    private static final String PREFIX = ChatColor.DARK_AQUA + "[Wayfinder] " + ChatColor.RESET;
    private static final String[] COMPASS_DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final List<Waypoint> sharedWaypoints = new ArrayList<>();
    private final Map<UUID, List<Waypoint>> personalWaypoints = new HashMap<>();
    private final Map<UUID, Waypoint> activeTargets = new HashMap<>();

    private NamespacedKey compassKey;
    private File waypointsFile;
    private YamlConfiguration waypointsData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        compassKey = new NamespacedKey(this, "wayfinder_compass");
        waypointsFile = new File(getDataFolder(), "waypoints.yml");
        loadWaypoints();

        Objects.requireNonNull(getCommand("wp"), "Command 'wp' is missing from plugin.yml")
                .setExecutor(this);
        Objects.requireNonNull(getCommand("wp"), "Command 'wp' is missing from plugin.yml")
                .setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(5L, getConfig().getLong("update-interval-ticks", 10L));
        new BukkitRunnable() {
            @Override
            public void run() {
                updateNavigation();
            }
        }.runTaskTimer(this, interval, interval);

        getLogger().info("Wayfinder Compass enabled. Loaded " + sharedWaypoints.size()
                + " shared and " + personalWaypoints.values().stream().mapToInt(List::size).sum()
                + " personal waypoints.");
    }

    @Override
    public void onDisable() {
        saveWaypoints();
        activeTargets.clear();
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "item", "compass" -> handleGiveCompass(sender, args);
            case "set", "create" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    if (!player.hasPermission("wayfinder.use")) {
                        deny(player);
                    } else if (args.length < 2) {
                        player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp set <name>");
                    } else {
                        createWaypoint(player, false, joinArgs(args, 1));
                    }
                }
            }
            case "remove", "delete" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    if (!player.hasPermission("wayfinder.use")) {
                        deny(player);
                    } else if (args.length < 2) {
                        player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp remove <name>");
                    } else {
                        removeWaypoint(player, false, joinArgs(args, 1));
                    }
                }
            }
            case "list" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    if (!player.hasPermission("wayfinder.use")) deny(player);
                    else listWaypoints(player);
                }
            }
            case "shared" -> handleSharedCommand(sender, args);
            case "stop", "cancel" -> {
                Player player = requirePlayer(sender);
                if (player != null) {
                    Waypoint removed = activeTargets.remove(player.getUniqueId());
                    if (removed == null) player.sendMessage(PREFIX + ChatColor.GRAY + "You are not navigating to a waypoint.");
                    else player.sendMessage(PREFIX + ChatColor.GREEN + "Navigation stopped.");
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleGiveCompass(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("wayfinder.admin")) {
                deny(sender);
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(PREFIX + ChatColor.RED + "That player is not online.");
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "From the console, use: /wp item <online-player>");
            return;
        }

        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createCompass());
        for (ItemStack item : leftovers.values()) target.getWorld().dropItemNaturally(target.getLocation(), item);
        target.sendMessage(PREFIX + ChatColor.GREEN + "You received a Wayfinder Compass. Right-click it to choose a destination.");
        if (sender != target) sender.sendMessage(PREFIX + ChatColor.GREEN + "Gave a Wayfinder Compass to " + target.getName() + ".");
    }

    private void handleSharedCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("wayfinder.admin")) {
            deny(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Shared waypoints must be set or removed in-game by an admin.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp shared <set|remove|list> [name]");
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set", "create" -> {
                if (args.length < 3) player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp shared set <name>");
                else createWaypoint(player, true, joinArgs(args, 2));
            }
            case "remove", "delete" -> {
                if (args.length < 3) player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp shared remove <name>");
                else removeWaypoint(player, true, joinArgs(args, 2));
            }
            case "list" -> {
                if (sharedWaypoints.isEmpty()) {
                    player.sendMessage(PREFIX + ChatColor.GRAY + "There are no shared waypoints yet.");
                } else {
                    player.sendMessage(PREFIX + ChatColor.AQUA + "Shared waypoints:");
                    sharedWaypoints.stream().sorted(Comparator.comparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER))
                            .forEach(w -> player.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + w.name()
                                    + ChatColor.DARK_GRAY + " (" + w.world() + " " + block(w.x()) + ", "
                                    + block(w.y()) + ", " + block(w.z()) + ")"));
                }
            }
            default -> player.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /wp shared <set|remove|list> [name]");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_AQUA + "--- Wayfinder Compass ---");
        sender.sendMessage(ChatColor.AQUA + "/wp item" + ChatColor.GRAY + " - get the navigation compass");
        sender.sendMessage(ChatColor.AQUA + "/wp set <name>" + ChatColor.GRAY + " - save your current location privately");
        sender.sendMessage(ChatColor.AQUA + "/wp remove <name>" + ChatColor.GRAY + " - remove one of your locations");
        sender.sendMessage(ChatColor.AQUA + "/wp list" + ChatColor.GRAY + " - list your and shared locations");
        sender.sendMessage(ChatColor.AQUA + "/wp stop" + ChatColor.GRAY + " - stop active navigation");
        if (sender.hasPermission("wayfinder.admin")) {
            sender.sendMessage(ChatColor.AQUA + "/wp shared set <name>" + ChatColor.GRAY + " - create a server-wide waypoint");
            sender.sendMessage(ChatColor.AQUA + "/wp shared remove <name>" + ChatColor.GRAY + " - remove a shared waypoint");
            sender.sendMessage(ChatColor.AQUA + "/wp shared list" + ChatColor.GRAY + " - list shared waypoints");
            sender.sendMessage(ChatColor.AQUA + "/wp item <player>" + ChatColor.GRAY + " - give a compass to a player");
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage(PREFIX + ChatColor.RED + "This command must be used in-game.");
        return null;
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to do that.");
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) out.append(' ');
            out.append(args[i]);
        }
        return out.toString().trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>(List.of("item", "set", "remove", "list", "stop", "help"));
            if (sender.hasPermission("wayfinder.admin")) candidates.add("shared");
            return prefixMatches(candidates, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("shared") && sender.hasPermission("wayfinder.admin")) {
            return prefixMatches(List.of("set", "remove", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("item") && sender.hasPermission("wayfinder.admin")) {
            return prefixMatches(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> prefixMatches(List<String> candidates, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    // -------------------------------------------------------------------------
    // Waypoint creation, removal, persistence
    // -------------------------------------------------------------------------

    private void createWaypoint(Player player, boolean shared, String name) {
        if (!VALID_NAME.matcher(name).matches() || name.isBlank()) {
            player.sendMessage(PREFIX + ChatColor.RED + "Use 1-32 letters, numbers, spaces, hyphens, or underscores for the name.");
            return;
        }

        List<Waypoint> list = shared ? sharedWaypoints : personalWaypoints.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        int maximum = Math.max(1, getConfig().getInt(shared ? "max-shared-waypoints" : "max-personal-waypoints", shared ? 50 : 20));
        if (list.size() >= maximum) {
            player.sendMessage(PREFIX + ChatColor.RED + "This waypoint list is full (limit " + maximum + ").");
            return;
        }
        if (list.stream().anyMatch(w -> w.name().equalsIgnoreCase(name))) {
            player.sendMessage(PREFIX + ChatColor.RED + "A waypoint with that name already exists in this list.");
            return;
        }

        Location location = player.getLocation();
        Waypoint waypoint = new Waypoint(UUID.randomUUID().toString(), name, shared,
                player.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        list.add(waypoint);
        saveWaypoints();
        player.sendMessage(PREFIX + ChatColor.GREEN + (shared ? "Shared" : "Personal") + " waypoint saved: "
                + ChatColor.WHITE + name + ChatColor.GRAY + " at " + player.getWorld().getName() + " ("
                + block(location.getX()) + ", " + block(location.getY()) + ", " + block(location.getZ()) + ").");
    }

    private void removeWaypoint(Player player, boolean shared, String name) {
        List<Waypoint> list = shared ? sharedWaypoints : personalWaypoints.getOrDefault(player.getUniqueId(), List.of());
        Waypoint found = list.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (found == null) {
            player.sendMessage(PREFIX + ChatColor.RED + "No " + (shared ? "shared" : "personal")
                    + " waypoint named '" + name + "' was found.");
            return;
        }
        list.remove(found);
        activeTargets.entrySet().removeIf(entry -> sameWaypoint(entry.getValue(), found));
        saveWaypoints();
        player.sendMessage(PREFIX + ChatColor.GREEN + "Removed waypoint: " + found.name());
    }

    private static boolean sameWaypoint(Waypoint left, Waypoint right) {
        return left.shared() == right.shared() && left.id().equals(right.id());
    }

    private void listWaypoints(Player player) {
        List<Waypoint> personal = personalWaypoints.getOrDefault(player.getUniqueId(), List.of());
        if (personal.isEmpty() && sharedWaypoints.isEmpty()) {
            player.sendMessage(PREFIX + ChatColor.GRAY + "No waypoints saved yet. Use /wp set <name> or ask an admin to add a shared waypoint.");
            return;
        }
        player.sendMessage(ChatColor.DARK_AQUA + "--- Your Wayfinder Destinations ---");
        personal.stream().sorted(Comparator.comparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(w -> player.sendMessage(ChatColor.GREEN + "[Private] " + ChatColor.WHITE + w.name()
                        + ChatColor.GRAY + " - " + w.world() + " (" + block(w.x()) + ", " + block(w.y()) + ", " + block(w.z()) + ")"));
        sharedWaypoints.stream().sorted(Comparator.comparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(w -> player.sendMessage(ChatColor.AQUA + "[Shared] " + ChatColor.WHITE + w.name()
                        + ChatColor.GRAY + " - " + w.world() + " (" + block(w.x()) + ", " + block(w.y()) + ", " + block(w.z()) + ")"));
        player.sendMessage(ChatColor.GRAY + "Right-click your Wayfinder Compass to start navigating.");
    }

    private void loadWaypoints() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data directory.");
        }
        if (!waypointsFile.exists()) {
            try {
                if (!waypointsFile.createNewFile()) getLogger().warning("Could not create waypoints.yml.");
            } catch (IOException exception) {
                getLogger().severe("Could not create waypoints.yml: " + exception.getMessage());
            }
        }
        waypointsData = YamlConfiguration.loadConfiguration(waypointsFile);
        sharedWaypoints.clear();
        personalWaypoints.clear();

        for (Map<?, ?> raw : waypointsData.getMapList("shared")) {
            Waypoint waypoint = waypointFromMap(raw, true);
            if (waypoint != null) sharedWaypoints.add(waypoint);
        }

        ConfigurationSection personalSection = waypointsData.getConfigurationSection("personal");
        if (personalSection != null) {
            for (String uuidString : personalSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    List<Waypoint> values = new ArrayList<>();
                    for (Map<?, ?> raw : waypointsData.getMapList("personal." + uuidString)) {
                        Waypoint waypoint = waypointFromMap(raw, false);
                        if (waypoint != null) values.add(waypoint);
                    }
                    personalWaypoints.put(uuid, values);
                } catch (IllegalArgumentException exception) {
                    getLogger().warning("Skipping invalid player UUID in waypoints.yml: " + uuidString);
                }
            }
        }
    }

    private Waypoint waypointFromMap(Map<?, ?> map, boolean shared) {
        Object id = map.get("id");
        Object name = map.get("name");
        Object world = map.get("world");
        Object x = map.get("x");
        Object y = map.get("y");
        Object z = map.get("z");
        if (!(id instanceof String) || !(name instanceof String) || !(world instanceof String)
                || !(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;
        return new Waypoint((String) id, (String) name, shared, (String) world,
                ((Number) x).doubleValue(), ((Number) y).doubleValue(), ((Number) z).doubleValue());
    }

    private void saveWaypoints() {
        if (waypointsFile == null) return;
        YamlConfiguration data = new YamlConfiguration();
        List<Map<String, Object>> shared = new ArrayList<>();
        for (Waypoint waypoint : sharedWaypoints) shared.add(waypointToMap(waypoint));
        data.set("shared", shared);

        for (Map.Entry<UUID, List<Waypoint>> entry : personalWaypoints.entrySet()) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Waypoint waypoint : entry.getValue()) values.add(waypointToMap(waypoint));
            data.set("personal." + entry.getKey(), values);
        }
        try {
            data.save(waypointsFile);
            waypointsData = data;
        } catch (IOException exception) {
            getLogger().severe("Could not save waypoints.yml: " + exception.getMessage());
        }
    }

    private static Map<String, Object> waypointToMap(Waypoint waypoint) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", waypoint.id());
        map.put("name", waypoint.name());
        map.put("world", waypoint.world());
        map.put("x", waypoint.x());
        map.put("y", waypoint.y());
        map.put("z", waypoint.z());
        return map;
    }

    // -------------------------------------------------------------------------
    // Wayfinder Compass item and crossplay menu
    // -------------------------------------------------------------------------

    private ItemStack createCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Wayfinder Compass");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click to choose a destination.",
                ChatColor.DARK_GRAY + "Navigation only - no teleporting."));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWayfinderCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(compassKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler
    public void onCompassInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWayfinderCompass(event.getItem())) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("wayfinder.use")) {
            deny(player);
            return;
        }
        openWaypointMenu(player, 0);
    }

    private List<Waypoint> visibleWaypoints(Player player) {
        List<Waypoint> visible = new ArrayList<>(personalWaypoints.getOrDefault(player.getUniqueId(), List.of()));
        visible.addAll(sharedWaypoints);
        visible.sort(Comparator.comparing((Waypoint w) -> !w.shared())
                .thenComparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER));
        return visible;
    }

    private void openWaypointMenu(Player player, int requestedPage) {
        List<Waypoint> entries = visibleWaypoints(player);
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        WayfinderMenuHolder holder = new WayfinderMenuHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, ChatColor.DARK_AQUA + "Wayfinder - Destinations");
        holder.setInventory(inventory);

        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            Waypoint waypoint = entries.get(i);
            ItemStack option = new ItemStack(waypoint.shared() ? Material.ENDER_EYE : Material.COMPASS);
            ItemMeta meta = option.getItemMeta();
            meta.setDisplayName((waypoint.shared() ? ChatColor.AQUA + "[Shared] " : ChatColor.GREEN + "[Private] ") + waypoint.name());
            meta.setLore(List.of(ChatColor.GRAY + "World: " + waypoint.world(),
                    ChatColor.GRAY + "X: " + block(waypoint.x()) + "  Y: " + block(waypoint.y()) + "  Z: " + block(waypoint.z()),
                    ChatColor.YELLOW + "Click to navigate here"));
            option.setItemMeta(meta);
            inventory.setItem(i - from, option);
        }

        inventory.setItem(45, menuButton(Material.BOOK, ChatColor.AQUA + "Wayfinder", List.of(
                ChatColor.GRAY + "Choose a waypoint to begin navigation.",
                ChatColor.GRAY + "Personal and shared destinations are shown.")));
        inventory.setItem(47, menuButton(Material.ARROW, ChatColor.WHITE + "Previous page", List.of(ChatColor.GRAY + "Page " + Math.max(1, page) + " of " + pageCount)));
        inventory.setItem(48, menuButton(Material.BARRIER, ChatColor.RED + "Stop navigation", List.of(ChatColor.GRAY + "Clear the active destination.")));
        inventory.setItem(50, menuButton(Material.ARROW, ChatColor.WHITE + "Next page", List.of(ChatColor.GRAY + "Page " + Math.min(pageCount, page + 2) + " of " + pageCount)));
        inventory.setItem(53, menuButton(Material.OAK_DOOR, ChatColor.GRAY + "Close", List.of(ChatColor.GRAY + "Close this menu.")));
        player.openInventory(inventory);
    }

    private ItemStack menuButton(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WayfinderMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();

        if (slot >= 0 && slot < PAGE_SIZE) {
            List<Waypoint> entries = visibleWaypoints(player);
            int index = holder.page() * PAGE_SIZE + slot;
            if (index >= 0 && index < entries.size()) {
                Waypoint target = entries.get(index);
                activeTargets.put(player.getUniqueId(), target);
                player.closeInventory();
                player.sendMessage(PREFIX + ChatColor.GREEN + "Navigating to " + ChatColor.WHITE + target.name()
                        + ChatColor.GREEN + ". Follow the distance/direction indicator; particles appear when nearby.");
            }
        } else if (slot == 47 && holder.page() > 0) {
            openWaypointMenu(player, holder.page() - 1);
        } else if (slot == 50) {
            int count = visibleWaypoints(player).size();
            int pageCount = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
            if (holder.page() + 1 < pageCount) openWaypointMenu(player, holder.page() + 1);
        } else if (slot == 48) {
            activeTargets.remove(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(PREFIX + ChatColor.GREEN + "Navigation stopped.");
        } else if (slot == 53) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof WayfinderMenuHolder) event.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // Navigation indicator and player-specific particle marker
    // -------------------------------------------------------------------------

    private void updateNavigation() {
        if (activeTargets.isEmpty()) return;
        for (Map.Entry<UUID, Waypoint> entry : new ArrayList<>(activeTargets.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                activeTargets.remove(entry.getKey());
                continue;
            }
            Waypoint waypoint = entry.getValue();
            World targetWorld = Bukkit.getWorld(waypoint.world());
            if (targetWorld == null) {
                player.sendActionBar(Component.text("Waypoint " + waypoint.name() + " is in an unloaded or missing world: " + waypoint.world()));
                continue;
            }
            if (!player.getWorld().getUID().equals(targetWorld.getUID())) {
                player.sendActionBar(Component.text("Waypoint: " + waypoint.name() + " | Target world: " + waypoint.world()));
                continue;
            }

            Location here = player.getLocation();
            double dx = waypoint.x() - here.getX();
            double dy = waypoint.y() - here.getY();
            double dz = waypoint.z() - here.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String direction = compassDirection(dx, dz);
            String status;
            if (distance <= 3.0) status = "Arrived near " + waypoint.name() + " | /wp stop to clear";
            else status = waypoint.name() + " | " + Math.round(distance) + " blocks " + direction + " | /wp stop to clear";
            player.sendActionBar(Component.text(status));

            double markerRange = Math.max(8.0, getConfig().getDouble("marker-visible-range", 80.0));
            if (distance <= markerRange) showMarker(player, waypoint);
        }
    }

    private void showMarker(Player player, Waypoint waypoint) {
        double x = waypoint.x() + 0.5;
        double y = waypoint.y();
        double z = waypoint.z() + 0.5;

        // A short pillar plus a small particle ring; sent only to the player following this target.
        for (int i = 0; i < 6; i++) {
            player.spawnParticle(Particle.END_ROD, x, y + 0.2 + (i * 0.42), z, 1, 0, 0, 0, 0);
        }
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2.0 * i) / 8.0;
            double ringX = x + Math.cos(angle) * 0.8;
            double ringZ = z + Math.sin(angle) * 0.8;
            player.spawnParticle(Particle.HAPPY_VILLAGER, ringX, y + 0.15, ringZ, 1, 0, 0, 0, 0);
        }
    }

    private static String compassDirection(double dx, double dz) {
        // In Minecraft, negative Z is north and positive X is east.
        double bearing = (Math.toDegrees(Math.atan2(dx, -dz)) + 360.0) % 360.0;
        int index = ((int) Math.round(bearing / 45.0)) % 8;
        return COMPASS_DIRECTIONS[index];
    }

    private static int block(double coordinate) {
        return (int) Math.floor(coordinate);
    }
}
