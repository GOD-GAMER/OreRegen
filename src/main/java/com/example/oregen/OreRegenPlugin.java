/**
 * OreRegen Spigot Plugin
 * Author: LtHans
 * License: MIT
 * Year: 2025
 * Version: 1.1.7 (bug fix update)
 */

package com.example.oregen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class OreRegenPlugin extends JavaPlugin implements Listener {

    // List of 9 selectable particles for border visualization
    private static final List<Particle> SELECTABLE_PARTICLES = Arrays.asList(
        Particle.FLAME,
        Particle.VILLAGER_HAPPY,
        Particle.REDSTONE,
        Particle.HEART,
        Particle.CLOUD,
        Particle.CRIT,
        Particle.END_ROD,
        Particle.NOTE,
        Particle.PORTAL
    );

    // Data structures
    private static class Area implements Serializable {
        UUID owner;
        String name;
        Location corner1, corner2;
        Set<UUID> trusted; // New field for trusted players
        public Area(UUID owner, String name, Location c1, Location c2) {
            this.owner = owner;
            this.name = name;
            this.corner1 = c1;
            this.corner2 = c2;
            this.trusted = new HashSet<>();
        }
        public boolean contains(Location loc) {
            int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
            int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
            int minY = -63;
            int maxY = 320;
            int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
            int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
            return loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                    && loc.getBlockY() >= minY && loc.getBlockY() <= maxY
                    && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
        }
        public boolean isTrusted(UUID player) {
            return owner.equals(player) || trusted.contains(player);
        }
        public void addTrusted(UUID player) {
            trusted.add(player);
        }
        public void removeTrusted(UUID player) {
            trusted.remove(player);
        }
        public Set<UUID> getTrusted() {
            return trusted;
        }
    }

    private static class OreRecord implements Serializable {
        String world;
        int x, y, z;
        Material type;
        long breakTime;
        public OreRecord(Location loc, Material type, long breakTime) {
            World worldObj = loc.getWorld();
            this.world = (worldObj != null) ? worldObj.getName() : "world";
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.type = type;
            this.breakTime = breakTime;
        }
        public Location getLocation() {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            return new Location(w, x, y, z);
        }
    }

    // Player selection state
    private final Map<UUID, Location> selection1 = new HashMap<>();
    private final Map<UUID, Location> selection2 = new HashMap<>();
    private final Map<UUID, String> areaNames = new HashMap<>(); // Restored for naming mode
    private final Map<UUID, List<Area>> buildAreas = new HashMap<>();
    private final List<OreRecord> brokenOres = Collections.synchronizedList(new ArrayList<>());

    // For particle display
    private final Set<UUID> playersInArea = ConcurrentHashMap.newKeySet();

    // Particle settings per player
    private final Map<UUID, Integer> particleDensity = new HashMap<>(); // 1=Low, 2=Medium, 3=High
    private final Map<UUID, Integer> playerParticleIndex = new HashMap<>(); // Store the selected particle index (0-8) for each player

    // Optimization config values
    private int particleUpdateInterval;
    private boolean showParticlesToOwnersOnly;
    private int regenBatchSize;
    private int saveInterval;
    private int maxTrackedBlocks;
    private int maxAreasPerPlayer;

    // Track players in corner selection mode
    private final Set<UUID> selectingCorners = new HashSet<>();

    // Track current particle page per player
    private final Map<UUID, Integer> particlePage = new HashMap<>();
    private static final int PARTICLES_PER_PAGE = 5;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        particleUpdateInterval = getConfig().getInt("particle.update-interval", 5);
        showParticlesToOwnersOnly = getConfig().getBoolean("particle.show-to-owners-only", true);
        regenBatchSize = getConfig().getInt("regeneration.batch-size", 2);
        saveInterval = getConfig().getInt("regeneration.save-interval", 6000);
        maxTrackedBlocks = getConfig().getInt("regeneration.max-tracked-blocks", 10000);
        maxAreasPerPlayer = getConfig().getInt("area.max-areas-per-player", 3);
        Bukkit.getPluginManager().registerEvents(this, this);
        loadDataAsync(); // Use async load
        startOreRegenTask();
        startParticleTask();
        // Register /buildarea command to open the GUI
        getCommand("buildarea").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                openBuildAreaGUI(player);
                return true;
            }
            sender.sendMessage("Players only.");
            return true;
        });
        // Register /buildareaadmin command for admin GUI
        getCommand("buildareaadmin").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                if (!player.hasPermission("oregen.admin")) {
                    player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                openAdminAreaListGUI(player);
                return true;
            }
            sender.sendMessage("Players only.");
            return true;
        });
        // Schedule periodic async data save
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            saveData();
            unloadOfflinePlayerData();
        }, saveInterval, saveInterval);
    }

    @Override
    public void onDisable() {
        saveData(); // Use sync save to ensure data is written before shutdown
    }

    // Block break event (track all blocks outside build areas)
    // Protection: Only owner or trusted can break blocks in their area
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        for (List<Area> areas : buildAreas.values()) {
            for (Area area : areas) {
                if (area.contains(loc)) {
                    if (!area.isTrusted(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "You are not trusted in this area.");
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
        // Don't track inside build areas
        if (isInAnyBuildArea(loc)) return;
        brokenOres.add(new OreRecord(loc, event.getBlock().getType(), System.currentTimeMillis()));
        enforceMaxTrackedBlocks();
    }

    // Particle display and entry/exit notifications for multiple areas
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        Location loc = p.getLocation();
        List<Area> areas = getPlayerAreas(uuid);
        boolean found = false;
        Area insideArea = null;
        for (Area area : areas) {
            if (area.contains(loc)) {
                found = true;
                insideArea = area;
                break;
            }
        }
        if (found && insideArea != null) {
            int idx = playerParticleIndex.getOrDefault(uuid, 0);
            Particle currentType = SELECTABLE_PARTICLES.get(idx);
            showAreaParticles(p, insideArea, currentType);
            if (!playersInArea.contains(uuid)) {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(ChatColor.YELLOW + "Entered: " + ChatColor.AQUA + insideArea.name));
                playersInArea.add(uuid);
            }
        } else {
            if (playersInArea.contains(uuid)) {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(ChatColor.RED + "Exited your build area"));
                playersInArea.remove(uuid);
            }
        }
    }

    // Helper: Is location in any build area?
    private boolean isInAnyBuildArea(Location loc) {
        for (List<Area> areas : buildAreas.values()) {
            for (Area area : areas) {
                if (area.contains(loc)) return true;
            }
        }
        return false;
    }

    // Helper: Get all areas for a player
    private List<Area> getPlayerAreas(UUID uuid) {
        return buildAreas.getOrDefault(uuid, Collections.emptyList());
    }

    // Helper: Get the first area for a player (for legacy compatibility)
    private Area getFirstPlayerArea(UUID uuid) {
        List<Area> areas = getPlayerAreas(uuid);
        return areas.isEmpty() ? null : areas.get(0);
    }

    // Helper: Is player within N blocks of area boundary?
    private boolean isNearAreaBoundary(Location loc, Area area, int distance) {
        int minX = Math.min(area.corner1.getBlockX(), area.corner2.getBlockX());
        int maxX = Math.max(area.corner1.getBlockX(), area.corner2.getBlockX());
        int minY = -63;
        int maxY = 320;
        int minZ = Math.min(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        int maxZ = Math.max(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        boolean inside = x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        if (!inside) return false;
        return x - minX <= distance || maxX - x <= distance
                || y - minY <= distance || maxY - y <= distance
                || z - minZ <= distance || maxZ - z <= distance;
    }

    // Rebuilt: Show area outline with particles on all 6 faces, from bedrock to build limit
    // Remove old showAreaParticles method and replace with new one using SELECTABLE_PARTICLES
    // New method: showAreaParticles(Player p, Area area, Particle particle)
    private void showAreaParticles(Player p, Area area, Particle particle) {
        int density = particleDensity.getOrDefault(p.getUniqueId(), 2); // Default medium
        if (density == 0) return; // Off
        int step = (density == 1) ? 4 : (density == 2) ? 2 : 1;
        int minX = Math.min(area.corner1.getBlockX(), area.corner2.getBlockX());
        int maxX = Math.max(area.corner1.getBlockX(), area.corner2.getBlockX());
        int minY = -63;
        int maxY = 320;
        int minZ = Math.min(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        int maxZ = Math.max(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        World w = p.getWorld();
        Object particleData = null;
        if (particle == Particle.REDSTONE) {
            particleData = new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.0F);
        }
        // Top and bottom faces (Y = minY and maxY)
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                w.spawnParticle(particle, x + 0.5, minY + 0.5, z + 0.5, 1, 0, 0, 0, 0, particleData);
                w.spawnParticle(particle, x + 0.5, maxY + 0.5, z + 0.5, 1, 0, 0, 0, 0, particleData);
            }
        }
        // Four vertical faces (X = minX, X = maxX, Z = minZ, Z = maxZ)
        for (int y = minY; y <= maxY; y += step) {
            for (int x = minX; x <= maxX; x += step) {
                w.spawnParticle(particle, x + 0.5, y + 0.5, minZ + 0.5, 1, 0, 0, 0, 0, particleData);
                w.spawnParticle(particle, x + 0.5, y + 0.5, maxZ + 0.5, 1, 0, 0, 0, 0, particleData);
            }
            for (int z = minZ + step; z <= maxZ - step; z += step) {
                w.spawnParticle(particle, minX + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0, particleData);
                w.spawnParticle(particle, maxX + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0, particleData);
            }
        }
    }

    // Periodically check for ore regeneration
    private void startOreRegenTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            Iterator<OreRecord> it = brokenOres.iterator();
            int processed = 0;
            while (it.hasNext() && processed < regenBatchSize) {
                OreRecord rec = it.next();
                if (now - rec.breakTime >= 24 * 60 * 60 * 1000L) {
                    Location loc = rec.getLocation();
                    if (!isInAnyBuildArea(loc)) {
                        Block block = loc.getBlock();
                        if (block.getType() == Material.AIR) {
                            block.setType(rec.type);
                        }
                    }
                    it.remove();
                }
                processed++;
            }
        }, 20, 1);
    }

    // Periodically show area outline for players inside or near their area
    private void startParticleTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Area area = getFirstPlayerArea(p.getUniqueId());
                if (area == null) continue;
                if (showParticlesToOwnersOnly && !p.hasPermission("oregen.admin") && !area.owner.equals(p.getUniqueId())) continue;
                Location loc = p.getLocation();
                if (area.contains(loc) || isNearAreaBoundary(loc, area, 5)) {
                    int idx = playerParticleIndex.getOrDefault(p.getUniqueId(), 0);
                    Particle particle = SELECTABLE_PARTICLES.get(idx);
                    showAreaParticles(p, area, particle);
                }
            }
        }, 20, particleUpdateInterval);
    }

    // Data persistence
    private void saveData() {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(new File(getDataFolder(), "data.dat")))) {
            out.writeObject(new ArrayList<>(buildAreas.values()));
            out.writeObject(new ArrayList<>(brokenOres));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "[ResourceRegen] Data save/load error", e);
        }
    }
    @SuppressWarnings("unchecked")
    private void loadData() {
        File file = new File(getDataFolder(), "data.dat");
        if (!file.exists()) return;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            buildAreas.clear();
            List<Area> loadedAreas = (List<Area>) in.readObject();
            for (Area area : loadedAreas) {
                buildAreas.computeIfAbsent(area.owner, k -> new ArrayList<>()).add(area);
            }
            brokenOres.clear();
            brokenOres.addAll((List<OreRecord>) in.readObject());
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "[ResourceRegen] Data load error", e);
        }
    }

    // Async data save
    private void saveDataAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, this::saveData);
    }
    // Async data load
    private void loadDataAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, this::loadData);
    }

    // Unload data for offline players if enabled
    private void unloadOfflinePlayerData() {
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        buildAreas.keySet().removeIf(uuid -> !online.contains(uuid));
        // Optionally, also clear per-player settings
        particleDensity.keySet().removeIf(uuid -> !online.contains(uuid));
        playerParticleIndex.keySet().removeIf(uuid -> !online.contains(uuid));
        selection1.keySet().removeIf(uuid -> !online.contains(uuid));
        selection2.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    // Enforce max tracked blocks
    private void enforceMaxTrackedBlocks() {
        while (brokenOres.size() > maxTrackedBlocks) {
            brokenOres.remove(0);
        }
    }
    // Enforce max areas per player
    private boolean canAddArea(UUID uuid) {
        int count = 0;
        for (Area area : buildAreas.getOrDefault(uuid, Collections.emptyList())) {
            if (area.owner.equals(uuid)) count++;
        }
        return count < maxAreasPerPlayer;
    }

    // GUI for build area management with density/type/delete options, now supports multiple areas per player
    private void openBuildAreaGUI(Player player) {
        UUID uuid = player.getUniqueId();
        List<Area> areas = getPlayerAreas(uuid);
        int max = maxAreasPerPlayer;
        int size = 54; // 6 rows for more space
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.GOLD + "Your Build Areas");

        // Decorative border
        ItemStack goldPane = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta goldMeta = goldPane.getItemMeta();
        if (goldMeta != null) {
            goldMeta.setDisplayName(ChatColor.GOLD + "★");
            goldPane.setItemMeta(goldMeta);
        }
        ItemStack purplePane = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        ItemMeta purpleMeta = purplePane.getItemMeta();
        if (purpleMeta != null) {
            purpleMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "✦");
            purplePane.setItemMeta(purpleMeta);
        }
        for (int i = 0; i < size; i++) {
            if (i < 9) gui.setItem(i, goldPane);
            else if (i >= size - 9) gui.setItem(i, purplePane);
            else if (i % 9 == 0) gui.setItem(i, goldPane);
            else if (i % 9 == 8) gui.setItem(i, purplePane);
        }

        // List all areas (slots 10-43)
        int slot = 10;
        for (int i = 0; i < areas.size() && slot < 44; i++, slot++) {
            Area area = areas.get(i);
            ItemStack areaItem = new ItemStack(Material.GRASS_BLOCK);
            ItemMeta meta = areaItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + area.name);
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Owner: " + Bukkit.getOfflinePlayer(area.owner).getName(),
                    ChatColor.GRAY + "Corners: " + area.corner1.getBlockX() + "," + area.corner1.getBlockZ() + " to " + area.corner2.getBlockX() + "," + area.corner2.getBlockZ(),
                    ChatColor.YELLOW + "Click to manage this area"
                ));
                meta.addEnchant(Enchantment.LUCK, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                areaItem.setItemMeta(meta);
            }
            gui.setItem(slot, areaItem);
        }

        // Add button to create new area if under limit
        if (areas.size() < max) {
            ItemStack create = new ItemStack(Material.EMERALD_BLOCK);
            ItemMeta cMeta = create.getItemMeta();
            if (cMeta != null) {
                cMeta.setDisplayName(ChatColor.GREEN + "Create New Area");
                cMeta.setLore(Arrays.asList(ChatColor.YELLOW + "Click to start selecting a new area!"));
                cMeta.addEnchant(Enchantment.LUCK, 1, true);
                cMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                create.setItemMeta(cMeta);
            }
            gui.setItem(49, create); // Center bottom
        }

        player.openInventory(gui);
    }

    // Admin GUI: List all build areas
    private void openAdminAreaListGUI(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "All Build Areas");
        int slot = 0;
        for (Area area : buildAreas.values().stream().flatMap(List::stream).toList()) {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + area.name);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Owner: " + Bukkit.getOfflinePlayer(area.owner).getName());
                lore.add(ChatColor.GRAY + "Corners: " + area.corner1.getBlockX() + "," + area.corner1.getBlockY() + "," + area.corner1.getBlockZ() + " to " + area.corner2.getBlockX() + "," + area.corner2.getBlockY() + "," + area.corner2.getBlockZ());
                meta.setLore(lore);
                if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(area.owner));
                    item.setItemMeta(skullMeta);
                } else {
                    item.setItemMeta(meta);
                }
            }
            gui.setItem(slot++, item);
            if (slot >= 45) break; // Reserve last row for admin controls
        }
        // Force Regen All button
        ItemStack forceRegenAll = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta frMeta = forceRegenAll.getItemMeta();
        if (frMeta != null) {
            frMeta.setDisplayName(ChatColor.RED + "Force Regen All Resources");
            frMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Restore all tracked blocks outside build areas"));
            forceRegenAll.setItemMeta(frMeta);
        }
        gui.setItem(45, forceRegenAll);
        // Config button
        ItemStack config = new ItemStack(Material.COMPARATOR);
        ItemMeta configMeta = config.getItemMeta();
        if (configMeta != null) {
            configMeta.setDisplayName(ChatColor.BLUE + "Plugin Config");
            configMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Edit plugin settings"));
            config.setItemMeta(configMeta);
        }
        gui.setItem(53, config);
        admin.openInventory(gui);
    }

    // Handle admin GUI clicks
    @EventHandler
    public void onAdminAreaListClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!event.getView().getTitle().equals(ChatColor.DARK_RED + "All Build Areas")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == 45) { // Force Regen All
            int count = 0;
            Iterator<OreRecord> it = brokenOres.iterator();
            while (it.hasNext()) {
                OreRecord rec = it.next();
                Location loc = rec.getLocation();
                if (!isInAnyBuildArea(loc)) {
                    Block block = loc.getBlock();
                    if (block.getType() == Material.AIR) {
                        block.setType(rec.type);
                        count++;
                    }
                    it.remove();
                }
            }
            admin.sendMessage(ChatColor.GREEN + "Force regenerated " + count + " blocks outside all build areas.");
            return;
        }
        if (slot == 53) { // Config
            openAdminConfigGUI(admin);
            return;
        }
        // Build a flat list of all areas for display
        List<Area> allAreas = new ArrayList<>();
        for (List<Area> areas : buildAreas.values()) {
            allAreas.addAll(areas);
        }
        if (slot < 0 || slot >= allAreas.size()) return;
        Area area = allAreas.get(slot);
        openAdminAreaEditGUI(admin, area);
    }

    // Admin area edit GUI
    private void openAdminAreaEditGUI(Player admin, Area area) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.RED + "Edit Area: " + area.name);
        // Border with black stained glass
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(" ");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 27; i++) {
            if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) gui.setItem(i, border);
        }
// --- Create all buttons and panes first ---
        // Extravagant border: diamond and blue glass panes
        ItemStack diamondPane = new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemMeta diamondMeta = diamondPane.getItemMeta();
        if (diamondMeta != null) {
            diamondMeta.setDisplayName(ChatColor.AQUA + "✦");
            diamondPane.setItemMeta(diamondMeta);
        }
        ItemStack bluePane = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        ItemMeta blueMeta = bluePane.getItemMeta();
        if (blueMeta != null) {
            blueMeta.setDisplayName(ChatColor.BLUE + "★");
            bluePane.setItemMeta(blueMeta);
        }
        // Set Corner 1
        ItemStack setC1 = new ItemStack(Material.NETHER_STAR);
        ItemMeta c1Meta = setC1.getItemMeta();
        if (c1Meta != null) {
            c1Meta.setDisplayName(ChatColor.GREEN + "Set Corner 1 (to your location)");
            List<String> lore1 = new ArrayList<>();
            lore1.add(ChatColor.GRAY + "Current: " + area.corner1.getBlockX() + ", " + area.corner1.getBlockY() + ", " + area.corner1.getBlockZ());
            lore1.add(ChatColor.YELLOW + "Click to set to your location");
            c1Meta.setLore(lore1);
            c1Meta.addEnchant(Enchantment.LUCK, 1, true);
            c1Meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            setC1.setItemMeta(c1Meta);
        }
        // Set Corner 2
        ItemStack setC2 = new ItemStack(Material.NETHER_STAR);
        ItemMeta c2Meta = setC2.getItemMeta();
        if (c2Meta != null) {
            c2Meta.setDisplayName(ChatColor.GREEN + "Set Corner 2 (to your location)");
            List<String> lore2 = new ArrayList<>();
            lore2.add(ChatColor.GRAY + "Current: " + area.corner2.getBlockX() + ", " + area.corner2.getBlockY() + ", " + area.corner2.getBlockZ());
            lore2.add(ChatColor.YELLOW + "Click to set to your location");
            c2Meta.setLore(lore2);
            c2Meta.addEnchant(Enchantment.LUCK, 1, true);
            c2Meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            setC2.setItemMeta(c2Meta);
        }
        // Rename
        ItemStack rename = new ItemStack(Material.NAME_TAG);
        ItemMeta renameMeta = rename.getItemMeta();
        if (renameMeta != null) {
            renameMeta.setDisplayName(ChatColor.YELLOW + "Rename Area");
            renameMeta.setLore(Arrays.asList(ChatColor.AQUA + "Give this area a legendary name!"));
            renameMeta.addEnchant(Enchantment.LUCK, 1, true);
            renameMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            rename.setItemMeta(renameMeta);
        }
        // Particle type
        ItemStack ptType = new ItemStack(Material.FIREWORK_STAR);
        ItemMeta ptTypeMeta = ptType.getItemMeta();
        if (ptTypeMeta != null) {
            ptTypeMeta.setDisplayName(ChatColor.AQUA + "Cycle Particle Type");
            ptTypeMeta.setLore(Arrays.asList(ChatColor.LIGHT_PURPLE + "Try all the magical effects!"));
            ptTypeMeta.addEnchant(Enchantment.LUCK, 1, true);
            ptTypeMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            ptType.setItemMeta(ptTypeMeta);
        }
        // Particle density
        ItemStack ptDensity = new ItemStack(Material.GUNPOWDER);
        ItemMeta ptDenMeta = ptDensity.getItemMeta();
        if (ptDenMeta != null) {
            ptDenMeta.setDisplayName(ChatColor.AQUA + "Cycle Particle Density");
            ptDenMeta.setLore(Arrays.asList(ChatColor.GRAY + "Adjust the sparkle power!"));
            ptDenMeta.addEnchant(Enchantment.LUCK, 1, true);
            ptDenMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            ptDensity.setItemMeta(ptDenMeta);
        }
        // Teleport to corners
        ItemStack tpC1 = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpC1Meta = tpC1.getItemMeta();
        if (tpC1Meta != null) {
            tpC1Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Teleport to Corner 1");
            tpC1Meta.setLore(Arrays.asList(ChatColor.GRAY + "Zoom to the first corner!"));
            tpC1Meta.addEnchant(Enchantment.LUCK, 1, true);
            tpC1Meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            tpC1.setItemMeta(tpC1Meta);
        }
        ItemStack tpC2 = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpC2Meta = tpC2.getItemMeta();
        if (tpC2Meta != null) {
            tpC2Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Teleport to Corner 2");
            tpC2Meta.setLore(Arrays.asList(ChatColor.GRAY + "Zoom to the second corner!"));
            tpC2Meta.addEnchant(Enchantment.LUCK, 1, true);
            tpC2Meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            tpC2.setItemMeta(tpC2Meta);
        }
        // Transfer ownership
        ItemStack transfer = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta transferMeta = transfer.getItemMeta();
        if (transferMeta != null) {
            transferMeta.setDisplayName(ChatColor.BLUE + "Transfer Ownership");
            transferMeta.setLore(Arrays.asList(ChatColor.GRAY + "Give this area to another hero!"));
            transferMeta.addEnchant(Enchantment.LUCK, 1, true);
            transferMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            transfer.setItemMeta(transferMeta);
        }
        // Delete
        ItemStack delete = new ItemStack(Material.BARRIER);
        ItemMeta delMeta = delete.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.RED + "Delete This Area");
            delMeta.setLore(Arrays.asList(ChatColor.DARK_RED + "Click to delete your area!", ChatColor.GRAY + "This cannot be undone."));
            delMeta.addEnchant(Enchantment.LUCK, 1, true);
            delMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            delete.setItemMeta(delMeta);
        }
        // Config button
        ItemStack config = new ItemStack(Material.COMPARATOR);
        ItemMeta configMeta = config.getItemMeta();
        if (configMeta != null) {
            configMeta.setDisplayName(ChatColor.BLUE + "Plugin Config");
            configMeta.setLore(Arrays.asList(ChatColor.GRAY + "Advanced settings for admins!"));
            configMeta.addEnchant(Enchantment.LUCK, 1, true);
            configMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            config.setItemMeta(configMeta);
        }
        // Force Regen button
        ItemStack forceRegen = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta frMeta = forceRegen.getItemMeta();
        if (frMeta != null) {
            frMeta.setDisplayName(ChatColor.RED + "Force Regenerate Area");
            frMeta.setLore(Arrays.asList(ChatColor.LIGHT_PURPLE + "Restore all resources in this area!"));
            frMeta.addEnchant(Enchantment.LUCK, 1, true);
            frMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            forceRegen.setItemMeta(frMeta);
        }
        // --- Place border and buttons for alignment ---
        for (int i = 0; i < 27; i++) {
            if (i < 9) gui.setItem(i, diamondPane); // Top row
            else if (i > 17) gui.setItem(i, bluePane); // Bottom row
            else if (i % 9 == 0) gui.setItem(i, diamondPane); // Left
            else if (i % 9 == 8) gui.setItem(i, bluePane); // Right
        }
        // Center row (row 2, slots 10-16): main actions
        gui.setItem(10, setC1);
        gui.setItem(11, setC2);
        gui.setItem(12, rename);
        gui.setItem(13, ptType);
        gui.setItem(14, ptDensity);
        gui.setItem(15, tpC1);
        gui.setItem(16, tpC2);
        // Bottom row (row 3, slots 18-24): admin actions
        gui.setItem(18, transfer);
        gui.setItem(20, delete);
        gui.setItem(22, config);
        gui.setItem(24, forceRegen);
        admin.openInventory(gui);
        adminEditingArea.put(admin.getUniqueId(), area);
    }

    private final Map<UUID, Area> adminEditingArea = new HashMap<>();

    @EventHandler
    public void onAdminAreaEditClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!event.getView().getTitle().startsWith(ChatColor.RED + "Edit Area: ")) return;
        event.setCancelled(true);
        Area area = adminEditingArea.get(admin.getUniqueId());
        if (area == null) return;
        int slot = event.getRawSlot();
        switch (slot) {
            case 10 -> { // Set Corner 1
                area.corner1 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 1 set to your location.");
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 11 -> { // Set Corner 2
                area.corner2 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 2 set to your location.");
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 12 -> { // Rename
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new area name in chat:");
                adminRenameMode.put(admin.getUniqueId(), area);
            }
            case 13 -> { // Cycle particle type
                int idx = playerParticleIndex.getOrDefault(area.owner, 0);
                idx = (idx + 1) % SELECTABLE_PARTICLES.size();
                playerParticleIndex.put(area.owner, idx);
                admin.sendMessage(ChatColor.AQUA + "Particle type set to: " + SELECTABLE_PARTICLES.get(idx).name());
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 14 -> { // Cycle particle density
                int density = particleDensity.getOrDefault(area.owner, 2);
                density = (density % 3) + 1;
                particleDensity.put(area.owner, density);
                admin.sendMessage(ChatColor.AQUA + "Particle density set to: " + (density == 1 ? "Low" : density == 2 ? "Medium" : "High"));
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 15 -> { // Teleport to Corner 1
                admin.teleport(area.corner1);
                admin.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to Corner 1.");
            }
            case 16 -> { // Teleport to Corner 2
                admin.teleport(area.corner2);
                admin.sendMessage(ChatColor.LIGHT_PURPLE + "Teleported to Corner 2.");
            }
            case 18 -> { // Transfer Ownership
                // (Stub) Add transfer logic here
                admin.sendMessage(ChatColor.BLUE + "Transfer ownership feature coming soon!");
            }
            case 20 -> { // Delete
                buildAreas.values().forEach(areas -> areas.remove(area));
                admin.sendMessage(ChatColor.RED + "Area deleted.");
                saveDataAsync();
                admin.closeInventory();
                adminEditingArea.remove(admin.getUniqueId());
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaListGUI(admin), 2L);
            }
            case 22 -> { // Config
                openAdminConfigGUI(admin);
            }
            case 24 -> { // Force Regen
                // (Stub) Add force regen logic here
                admin.sendMessage(ChatColor.LIGHT_PURPLE + "Force regen feature coming soon!");
            }
        }
    }

    // Admin rename/transfer mode tracking
    private final Map<UUID, Area> adminRenameMode = new HashMap<>();

    // Chat handler for area naming and admin commands
    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (adminRenameMode.containsKey(uuid)) {
            event.setCancelled(true);
            String newName = event.getMessage().trim();
            Area area = adminRenameMode.remove(uuid);
            area.name = newName;
            player.sendMessage(ChatColor.GREEN + "Area renamed to: " + ChatColor.AQUA + newName);
            saveData();
            Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(player, area), 2L);
            return;
        }
        if (areaNames.containsKey(uuid)) {
            event.setCancelled(true);
            String val = areaNames.get(uuid);
            if (val.endsWith("|rename")) {
                String oldName = val.substring(0, val.length() - 7);
                String newName = event.getMessage().trim();
                List<Area> areas = getPlayerAreas(uuid);
                Area area = null;
                for (Area a : areas) {
                    if (a.name.equals(oldName)) {
                        area = a;
                        break;
                    }
                }
                if (area != null) {
                    area.name = newName;
                    player.sendMessage(ChatColor.GREEN + "Area renamed to: " + ChatColor.AQUA + newName);
                } else {
                    player.sendMessage(ChatColor.RED + "Area not found.");
                }
                areaNames.remove(uuid);
                saveData();
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
                return;
            } else if (val.endsWith("|trust")) {
                String areaName = val.substring(0, val.length() - 6);
                String targetName = event.getMessage().trim();
                List<Area> areas = getPlayerAreas(uuid);
                final Area area = areas.stream().filter(a -> a.name.equals(areaName)).findFirst().orElse(null);
                if (area == null) {
                    player.sendMessage(ChatColor.RED + "Area not found.");
                } else {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    if (target == null || target.getUniqueId() == null) {
                        player.sendMessage(ChatColor.RED + "Player not found.");
                    } else if (area.getTrusted().contains(target.getUniqueId())) {
                        area.removeTrusted(target.getUniqueId());
                        player.sendMessage(ChatColor.YELLOW + "Removed trusted player: " + ChatColor.AQUA + targetName);
                    } else {
                        area.addTrusted(target.getUniqueId());
                        player.sendMessage(ChatColor.GREEN + "Added trusted player: " + ChatColor.AQUA + targetName);
                    }
                }
                areaNames.remove(uuid);
                saveData();
                if (area != null) {
                    Bukkit.getScheduler().runTaskLater(this, () -> openPlayerAreaEditGUI(player, area), 2L);
                }
                return;
            } else {
                // Normal area naming mode
                String newName = event.getMessage().trim();
                areaNames.put(uuid, newName);
                player.sendMessage(ChatColor.GREEN + "Area name set to: " + ChatColor.AQUA + newName);
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
        }
    }

    // Opens the admin config GUI (stub, expand as needed)
    private void openAdminConfigGUI(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.BLUE + "ResourceRegen Config");
        // Toggle particles for all
        ItemStack toggleParticles = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta toggleMeta = toggleParticles.getItemMeta();
        if (toggleMeta != null) {
            toggleMeta.setDisplayName(ChatColor.AQUA + "Show Particles to Owners Only: " + (showParticlesToOwnersOnly ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            toggleMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to toggle"));
            toggleParticles.setItemMeta(toggleMeta);
        }
        gui.setItem(10, toggleParticles);
        // Regen batch size
        ItemStack batchSize = new ItemStack(Material.HOPPER);
        ItemMeta batchMeta = batchSize.getItemMeta();
        if (batchMeta != null) {
            batchMeta.setDisplayName(ChatColor.YELLOW + "Regen Batch Size: " + regenBatchSize);
            batchMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to increase (max 20)"));
            batchSize.setItemMeta(batchMeta);
        }
        gui.setItem(12, batchSize);
        // Max tracked blocks
        ItemStack maxTracked = new ItemStack(Material.CHEST);
        ItemMeta maxTrackedMeta = maxTracked.getItemMeta();
        if (maxTrackedMeta != null) {
            maxTrackedMeta.setDisplayName(ChatColor.GOLD + "Max Tracked Blocks: " + maxTrackedBlocks);
            maxTrackedMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to increase (max 50000)"));
            maxTracked.setItemMeta(maxTrackedMeta);
        }
        gui.setItem(14, maxTracked);
        // Max areas per player
        ItemStack maxAreas = new ItemStack(Material.BOOK);
        ItemMeta maxAreasMeta = maxAreas.getItemMeta();
        if (maxAreasMeta != null) {
            maxAreasMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Max Areas Per Player: " + maxAreasPerPlayer);
            maxAreasMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to increase (max 10)"));
            maxAreas.setItemMeta(maxAreasMeta);
        }
        gui.setItem(16, maxAreas);
        // Save & Close
        ItemStack saveClose = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta saveCloseMeta = saveClose.getItemMeta();
        if (saveCloseMeta != null) {
            saveCloseMeta.setDisplayName(ChatColor.GREEN + "Save & Close");
            saveCloseMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Apply changes and close"));
            saveClose.setItemMeta(saveCloseMeta);
        }
        gui.setItem(31, saveClose);
        admin.openInventory(gui);
    }
    @EventHandler
    public void onConfigGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!event.getView().getTitle().equals(ChatColor.BLUE + "ResourceRegen Config")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == 10) {
            showParticlesToOwnersOnly = !showParticlesToOwnersOnly;
            admin.sendMessage(ChatColor.AQUA + "Show Particles to Owners Only: " + (showParticlesToOwnersOnly ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            openAdminConfigGUI(admin);
        } else if (slot == 12) {
            regenBatchSize = Math.min(regenBatchSize + 1, 20);
            admin.sendMessage(ChatColor.YELLOW + "Regen Batch Size set to: " + regenBatchSize);
            openAdminConfigGUI(admin);
        } else if (slot == 14) {
            maxTrackedBlocks = Math.min(maxTrackedBlocks + 1000, 50000);
            admin.sendMessage(ChatColor.GOLD + "Max Tracked Blocks set to: " + maxTrackedBlocks);
            openAdminConfigGUI(admin);
        } else if (slot == 16) {
            maxAreasPerPlayer = Math.min(maxAreasPerPlayer + 1, 10);
            admin.sendMessage(ChatColor.LIGHT_PURPLE + "Max Areas Per Player set to: " + maxAreasPerPlayer);
            openAdminConfigGUI(admin);
        } else if (slot == 31) {
            saveConfig();
            admin.sendMessage(ChatColor.GREEN + "Configuration saved.");
            admin.closeInventory();
        }
    }

    // Command tab completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("buildarea")) {
            return Arrays.asList("setcorner1", "setcorner2", "name", "save", "show", "density", "type", "delete");
        } else if (command.getName().equalsIgnoreCase("buildareaadmin")) {
            return Arrays.asList("list", "edit", "delete");
        }
        return Collections.emptyList();
    }

    // Debug command for plugin stats
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("oregendebug")) {
            sender.sendMessage(ChatColor.GOLD + "[OreRegen] Debug Info:");
            sender.sendMessage(ChatColor.YELLOW + "Build Areas: " + buildAreas.size());
            sender.sendMessage(ChatColor.YELLOW + "Tracked Ores: " + brokenOres.size());
            sender.sendMessage(ChatColor.YELLOW + "Online Players: " + Bukkit.getOnlinePlayers().size());
            sender.sendMessage(ChatColor.YELLOW + "Particle Density Map: " + particleDensity.size());
            sender.sendMessage(ChatColor.YELLOW + "Particle Type Map: " + playerParticleIndex.size());
            return true;
        }
        return false;
    }

    // Prevent item pickup/movement in player GUI
    @EventHandler
    public void onBuildAreaGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().getTitle().equals(ChatColor.GOLD + "Your Build Areas")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        UUID uuid = player.getUniqueId();
        List<Area> areas = getPlayerAreas(uuid);
        // Area slots: 10-43
        if (slot >= 10 && slot < 44 && slot - 10 < areas.size()) {
            Area area = areas.get(slot - 10);
            openPlayerAreaEditGUI(player, area);
            return;
        }
        // Create new area button
        if (slot == 49 && areas.size() < maxAreasPerPlayer) {
            selectingCorners.add(uuid);
            player.closeInventory();
            player.sendMessage(ChatColor.AQUA + "You are now selecting a new area. Use the wand to set corners.");
            return;
        }
    }

    // New: Player area edit GUI for a specific area
    private void openPlayerAreaEditGUI(Player player, Area area) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.AQUA + "Manage Area: " + area.name);
        // Border
        ItemStack border = new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(ChatColor.AQUA + "✦");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 27; i++) {
            if (i < 9 || i >= 18 || i % 9 == 0 || i % 9 == 8) gui.setItem(i, border);
        }
        // Rename
        ItemStack rename = new ItemStack(Material.NAME_TAG);
        ItemMeta rMeta = rename.getItemMeta();
        if (rMeta != null) {
            rMeta.setDisplayName(ChatColor.YELLOW + "Rename Area");
            rMeta.setLore(Arrays.asList(ChatColor.GRAY + "Click to rename this area."));
            rename.setItemMeta(rMeta);
        }
        gui.setItem(10, rename);
        // Delete
        ItemStack delete = new ItemStack(Material.RED_STAINED_GLASS);
        ItemMeta dMeta = delete.getItemMeta();
        if (dMeta != null) {
            dMeta.setDisplayName(ChatColor.RED + "Delete Area");
            dMeta.setLore(Arrays.asList(ChatColor.DARK_RED + "Click to delete this area!"));
            delete.setItemMeta(dMeta);
        }
        gui.setItem(16, delete);
        // Trust management
        ItemStack trust = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta tMeta = trust.getItemMeta();
        if (tMeta != null) {
            tMeta.setDisplayName(ChatColor.GREEN + "Trusted Players");
            List<String> lore = new ArrayList<>();
            if (area.getTrusted().isEmpty()) {
                lore.add(ChatColor.GRAY + "No trusted players.");
            } else {
                lore.add(ChatColor.YELLOW + "Trusted:");
                for (UUID t : area.getTrusted()) {
                    String name = Bukkit.getOfflinePlayer(t).getName();
                    lore.add(ChatColor.AQUA + "- " + (name != null ? name : t.toString()));
                }
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "Click to add/remove trusted players.");
            tMeta.setLore(lore);
            trust.setItemMeta(tMeta);
        }
        gui.setItem(13, trust);
        // Particle settings, etc. (future: add more per-area options)
        player.openInventory(gui);
    }

    // Trust management click handler
    @EventHandler
    public void onPlayerAreaEditGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(ChatColor.AQUA + "Manage Area: ")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        String areaName = title.substring((ChatColor.AQUA + "Manage Area: ").length());
        UUID uuid = player.getUniqueId();
        Area area = null;
        for (Area a : getPlayerAreas(uuid)) {
            if (a.name.equals(areaName)) {
                area = a;
                break;
            }
        }
        if (area == null) {
            player.sendMessage(ChatColor.RED + "Area not found.");
            player.closeInventory();
            return;
        }
        if (slot == 10) { // Rename
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Type the new name for this area in chat:");
            areaNames.put(uuid, area.name + "|rename");
        } else if (slot == 16) { // Delete
            buildAreas.getOrDefault(uuid, new ArrayList<>()).remove(area);
            player.sendMessage(ChatColor.RED + "Area deleted: " + ChatColor.AQUA + area.name);
            player.closeInventory();
            openBuildAreaGUI(player);
        } else if (slot == 13) { // Trust management
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Type the player name to add/remove as trusted for this area:");
            areaNames.put(uuid, area.name + "|trust");
        }
    }
}