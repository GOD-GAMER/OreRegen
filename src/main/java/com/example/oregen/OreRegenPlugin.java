/**
 * OreRegen Spigot Plugin
 * Author: LtHans
 * License: MIT
 * Year: 2025
 * Version: 1.1.6 (bug fix update)
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
        public Area(UUID owner, String name, Location c1, Location c2) {
            this.owner = owner;
            this.name = name;
            this.corner1 = c1;
            this.corner2 = c2;
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
    private final List<Area> buildAreas = new ArrayList<>();
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
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        Location loc = block.getLocation();
        if (isInAnyBuildArea(loc)) return; // Don't track inside build areas
        brokenOres.add(new OreRecord(loc, type, System.currentTimeMillis()));
        enforceMaxTrackedBlocks();
    }

    // Particle display when player is inside or near their area
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Area area = getPlayerArea(p.getUniqueId());
        if (area == null) return;
        Location loc = p.getLocation();
        boolean inside = area.contains(loc);
        if (inside) {
            // Fix: get index from playerParticleIndex, then get Particle from SELECTABLE_PARTICLES
            int idx = playerParticleIndex.getOrDefault(p.getUniqueId(), 0);
            Particle currentType = SELECTABLE_PARTICLES.get(idx);
            showAreaParticles(p, area, currentType);
            if (!playersInArea.contains(p.getUniqueId())) {
                // Use action bar for entry notification
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(ChatColor.YELLOW + "Entered: " + ChatColor.AQUA + area.name));
                playersInArea.add(p.getUniqueId());
            }
        } else {
            if (playersInArea.contains(p.getUniqueId())) {
                // Use action bar for exit notification
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(ChatColor.RED + "Exited: " + ChatColor.AQUA + area.name));
                playersInArea.remove(p.getUniqueId());
            }
        }
    }

    // Helper: Is location in any build area?
    private boolean isInAnyBuildArea(Location loc) {
        for (Area area : buildAreas) {
            if (area.contains(loc)) return true;
        }
        return false;
    }

    // Helper: Get player's area
    private Area getPlayerArea(UUID uuid) {
        for (Area area : buildAreas) {
            if (area.owner.equals(uuid)) return area;
        }
        return null;
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
                Area area = getPlayerArea(p.getUniqueId());
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
            out.writeObject(new ArrayList<>(buildAreas));
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
            buildAreas.addAll((List<Area>) in.readObject());
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
        buildAreas.removeIf(area -> !online.contains(area.owner));
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
        for (Area area : buildAreas) {
            if (area.owner.equals(uuid)) count++;
        }
        return count < maxAreasPerPlayer;
    }

    // GUI for build area management with density/type/delete options
    private void openBuildAreaGUI(Player player) {
        if (!canAddArea(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You have reached the maximum number of build areas (" + maxAreasPerPlayer + ").");
            return;
        }
        int page = particlePage.getOrDefault(player.getUniqueId(), 0);
        Inventory gui = Bukkit.createInventory(null, 36, ChatColor.GOLD + "Build Area Manager");
        // Extravagant border: gold and purple glass panes
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
        for (int i = 0; i < 36; i++) {
            if (i < 9) gui.setItem(i, goldPane); // Top row
            else if (i > 26) gui.setItem(i, purplePane); // Bottom row
            else if (i % 9 == 0) gui.setItem(i, goldPane); // Left
            else if (i % 9 == 8) gui.setItem(i, purplePane); // Right
        }
        // Set Corners (wand)
        ItemStack wandBtn = new ItemStack(Material.NETHER_STAR);
        ItemMeta wandMeta = wandBtn.getItemMeta();
        if (wandMeta != null) {
            wandMeta.setDisplayName(ChatColor.GREEN + "Set Corners (Wand)");
            wandMeta.setLore(Arrays.asList(ChatColor.YELLOW + "Click to receive a magical wand!", ChatColor.GRAY + "Left: Corner 1, Right: Corner 2"));
            wandMeta.addEnchant(Enchantment.LUCK, 1, true);
            wandMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            wandBtn.setItemMeta(wandMeta);
        }
        gui.setItem(10, wandBtn);
        // Name Area
        ItemStack name = new ItemStack(Material.NAME_TAG);
        ItemMeta nameMeta = name.getItemMeta();
        if (nameMeta != null) {
            nameMeta.setDisplayName(ChatColor.YELLOW + "Name Your Area");
            nameMeta.setLore(Arrays.asList(ChatColor.AQUA + "Give your area a unique name!", ChatColor.GRAY + "Click to set area name"));
            nameMeta.addEnchant(Enchantment.LUCK, 1, true);
            nameMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            name.setItemMeta(nameMeta);
        }
        gui.setItem(12, name);
        // Save Area
        ItemStack save = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta saveMeta = save.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setDisplayName(ChatColor.AQUA + "Save Area");
            saveMeta.setLore(Arrays.asList(ChatColor.GREEN + "Lock in your magical zone!", ChatColor.GRAY + "Click to save your area"));
            saveMeta.addEnchant(Enchantment.LUCK, 1, true);
            saveMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            save.setItemMeta(saveMeta);
        }
        gui.setItem(14, save);
        // Particle density options with indicator
        int currentDensity = particleDensity.getOrDefault(player.getUniqueId(), 2);
        ItemStack densityLow = new ItemStack(Material.GRAY_DYE);
        ItemMeta dLowMeta = densityLow.getItemMeta();
        if (dLowMeta != null) {
            dLowMeta.setDisplayName(ChatColor.WHITE + "Particle Density: Low");
            dLowMeta.setLore(Arrays.asList(ChatColor.GRAY + "Minimal sparkles for subtlety."));
            if (currentDensity == 1) {
                dLowMeta.addEnchant(Enchantment.LUCK, 1, true);
                dLowMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            densityLow.setItemMeta(dLowMeta);
        }
        gui.setItem(18, densityLow);
        ItemStack densityMed = new ItemStack(Material.LIGHT_GRAY_DYE);
        ItemMeta dMedMeta = densityMed.getItemMeta();
        if (dMedMeta != null) {
            dMedMeta.setDisplayName(ChatColor.WHITE + "Particle Density: Medium");
            dMedMeta.setLore(Arrays.asList(ChatColor.GRAY + "A balanced amount of magic."));
            if (currentDensity == 2) {
                dMedMeta.addEnchant(Enchantment.LUCK, 1, true);
                dMedMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            densityMed.setItemMeta(dMedMeta);
        }
        gui.setItem(19, densityMed);
        ItemStack densityHigh = new ItemStack(Material.WHITE_DYE);
        ItemMeta dHighMeta = densityHigh.getItemMeta();
        if (dHighMeta != null) {
            dHighMeta.setDisplayName(ChatColor.WHITE + "Particle Density: High");
            dHighMeta.setLore(Arrays.asList(ChatColor.GRAY + "Maximum sparkle!"));
            if (currentDensity == 3) {
                dHighMeta.addEnchant(Enchantment.LUCK, 1, true);
                dHighMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            densityHigh.setItemMeta(dHighMeta);
        }
        gui.setItem(20, densityHigh);
        // Particle type options (extravagant icons)
        int idx = playerParticleIndex.getOrDefault(player.getUniqueId(), 0);
        Particle currentType = SELECTABLE_PARTICLES.get(idx);
        for (int i = 0; i < SELECTABLE_PARTICLES.size(); i++) {
            Particle pt = SELECTABLE_PARTICLES.get(i);
            Material icon;
            switch (pt) {
                case FLAME -> icon = Material.BLAZE_POWDER;
                case VILLAGER_HAPPY -> icon = Material.EMERALD;
                case REDSTONE -> icon = Material.REDSTONE_BLOCK;
                case HEART -> icon = Material.POPPY;
                case CLOUD -> icon = Material.WHITE_WOOL;
                case CRIT -> icon = Material.DIAMOND_SWORD;
                case END_ROD -> icon = Material.END_ROD;
                case NOTE -> icon = Material.NOTE_BLOCK;
                case PORTAL -> icon = Material.ENDER_EYE;
                default -> icon = Material.FIREWORK_STAR;
            }
            ItemStack ptItem = new ItemStack(icon);
            ItemMeta ptMeta = ptItem.getItemMeta();
            if (ptMeta != null) {
                ptMeta.setDisplayName(ChatColor.AQUA + "Particle: " + pt.name());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.LIGHT_PURPLE + "Click to select this magical effect!");
                if (pt == currentType) {
                    lore.add(ChatColor.GREEN + "(Selected)");
                    ptMeta.addEnchant(Enchantment.LUCK, 1, true);
                    ptMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                }
                ptMeta.setLore(lore);
                ptItem.setItemMeta(ptMeta);
            }
            gui.setItem(21 + i, ptItem);
        }
        // Particles Off button
        ItemStack off = new ItemStack(Material.BARRIER);
        ItemMeta offMeta = off.getItemMeta();
        if (offMeta != null) {
            offMeta.setDisplayName(ChatColor.DARK_GRAY + "Particles: Off");
            offMeta.setLore(Arrays.asList(ChatColor.GRAY + "No sparkles for this area."));
            if (currentDensity == 0) {
                offMeta.addEnchant(Enchantment.LUCK, 1, true);
                offMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            off.setItemMeta(offMeta);
        }
        gui.setItem(30, off);
        // Delete Area button (active if player has an area)
        Area area = getPlayerArea(player.getUniqueId());
        ItemStack delete = new ItemStack(Material.RED_STAINED_GLASS);
        ItemMeta delMeta = delete.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.RED + "Delete Build Area");
            delMeta.setLore(Arrays.asList(ChatColor.DARK_RED + "Click to delete your area!", ChatColor.GRAY + "This cannot be undone."));
            if (area != null) {
                delMeta.addEnchant(Enchantment.LUCK, 1, true);
                delMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            delete.setItemMeta(delMeta);
        }
        gui.setItem(32, delete);
        player.openInventory(gui);
        openGUIs.put(player.getUniqueId(), GUIType.PLAYER);
    }

    // Admin GUI: List all build areas
    private void openAdminAreaListGUI(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "All Build Areas");
        int slot = 0;
        for (Area area : buildAreas) {
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
        if (slot < 0 || slot >= buildAreas.size()) return;
        Area area = buildAreas.get(slot);
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
        for (int i = 0; i < 27; i++) {
            if (i < 9) gui.setItem(i, diamondPane); // Top row
            else if (i > 17) gui.setItem(i, bluePane); // Bottom row
            else if (i % 9 == 0) gui.setItem(i, diamondPane); // Left
            else if (i % 9 == 8) gui.setItem(i, bluePane); // Right
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
        gui.setItem(0, setC1);
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
        gui.setItem(1, setC2);
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
        gui.setItem(2, rename);
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
        gui.setItem(3, ptType);
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
        gui.setItem(4, ptDensity);
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
        gui.setItem(5, tpC1);
        ItemStack tpC2 = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpC2Meta = tpC2.getItemMeta();
        if (tpC2Meta != null) {
            tpC2Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Teleport to Corner 2");
            tpC2Meta.setLore(Arrays.asList(ChatColor.GRAY + "Zoom to the second corner!"));
            tpC2Meta.addEnchant(Enchantment.LUCK, 1, true);
            tpC2Meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            tpC2.setItemMeta(tpC2Meta);
        }
        gui.setItem(6, tpC2);
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
        gui.setItem(7, transfer);
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
        gui.setItem(8, delete);
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
        gui.setItem(25, config);
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
        gui.setItem(26, forceRegen);

        admin.openInventory(gui);
        openGUIs.put(admin.getUniqueId(), GUIType.ADMIN);
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
            case 0 -> { // Set Corner 1
                area.corner1 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 1 set to your location.");
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 1 -> { // Set Corner 2
                area.corner2 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 2 set to your location.");
                saveDataAsync();
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 2 -> { // Rename
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new area name in chat:");
                adminRenameMode.put(admin.getUniqueId(), area);
            }
            case 3 -> { // Cycle particle type
                int idx = playerParticleIndex.getOrDefault(area.owner, 0);
                idx = (idx + 1) % SELECTABLE_PARTICLES.size();
                playerParticleIndex.put(area.owner, idx);
                admin.sendMessage(ChatColor.AQUA + "Particle type set to: " + SELECTABLE_PARTICLES.get(idx).name());
                saveDataAsync();
                refreshPlayerGUI(area.owner);
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 4 -> { // Cycle particle density
                int density = particleDensity.getOrDefault(area.owner, 2);
                density = (density % 3) + 1;
                particleDensity.put(area.owner, density);
                admin.sendMessage(ChatColor.AQUA + "Particle density set to: " + (density == 1 ? "Low" : density == 2 ? "Medium" : "High"));
                saveDataAsync();
                refreshPlayerGUI(area.owner);
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 8 -> { // Delete
                buildAreas.remove(area);
                closeGUIsForArea(area);
                admin.sendMessage(ChatColor.RED + "Area deleted.");
                saveDataAsync();
                admin.closeInventory();
                adminEditingArea.remove(admin.getUniqueId());
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaListGUI(admin), 2L);
            }
            case 18 -> { // Particles: Off
                particleDensity.put(area.owner, 0);
                admin.sendMessage(ChatColor.GRAY + "Particles turned off for this area.");
                refreshPlayerGUI(area.owner);
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
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
        } else if (areaNames.containsKey(uuid)) {
            event.setCancelled(true);
            String newName = event.getMessage().trim();
            areaNames.put(uuid, newName);
            player.sendMessage(ChatColor.GREEN + "Area name set to: " + ChatColor.AQUA + newName);
            Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
        }
    }

    // Opens the admin config GUI (stub, expand as needed)
    private void openAdminConfigGUI(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.BLUE + "ResourceRegen Config");
        // Example: Toggle particles for all, adjust batch size, etc.
        ItemStack toggleParticles = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta toggleMeta = toggleParticles.getItemMeta();
        if (toggleMeta != null) {
            toggleMeta.setDisplayName(ChatColor.AQUA + "Show Particles to Owners Only: " + (showParticlesToOwnersOnly ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            toggleMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to toggle"));
            toggleParticles.setItemMeta(toggleMeta);
        }
        gui.setItem(10, toggleParticles);
        ItemStack batchSize = new ItemStack(Material.HOPPER);
        ItemMeta batchMeta = batchSize.getItemMeta();
        if (batchMeta != null) {
            batchMeta.setDisplayName(ChatColor.YELLOW + "Regen Batch Size: " + regenBatchSize);
            batchMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to increase (max 20)"));
            batchSize.setItemMeta(batchMeta);
        }
        gui.setItem(12, batchSize);
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
        if (!event.getView().getTitle().equals(ChatColor.GOLD + "Build Area Manager")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        UUID uuid = player.getUniqueId();
        int page = particlePage.getOrDefault(uuid, 0);
        int startIdx = page * PARTICLES_PER_PAGE;
        int endIdx = Math.min(startIdx + PARTICLES_PER_PAGE, SELECTABLE_PARTICLES.size());
        switch (slot) {
            case 10 -> { // Set Corners (Wand)
                ItemStack wand = new ItemStack(Material.STICK);
                ItemMeta meta = wand.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.GREEN + "ResourceRegen Wand");
                    meta.setLore(Arrays.asList(ChatColor.YELLOW + "Left click: Set Corner 1", ChatColor.YELLOW + "Right click: Set Corner 2"));
                    wand.setItemMeta(meta);
                }
                player.getInventory().addItem(wand);
                selectingCorners.add(uuid);
                player.closeInventory();
                player.sendMessage(ChatColor.AQUA + "You received the ResourceRegen Wand! Left/right click blocks to set corners.");
            }
            case 12 -> { // Name Area
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW + "Type the area name in chat:");
                areaNames.put(uuid, ""); // Start naming mode
            }
            case 14 -> { // Save Area
                Location c1 = selection1.get(uuid);
                Location c2 = selection2.get(uuid);
                String name = areaNames.getOrDefault(uuid, "");
                if (c1 == null || c2 == null || name.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Set both corners and name before saving.");
                    return;
                }
                if (!canAddArea(uuid)) {
                    player.sendMessage(ChatColor.RED + "You have reached the maximum number of build areas.");
                    return;
                }
                buildAreas.add(new Area(uuid, name, c1, c2));
                player.sendMessage(ChatColor.GREEN + "Area saved: " + ChatColor.AQUA + name);
                selection1.remove(uuid);
                selection2.remove(uuid);
                areaNames.remove(uuid);
                player.closeInventory();
            }
            case 18 -> { // Particle Density Low
                particleDensity.put(uuid, 1);
                player.sendMessage(ChatColor.GRAY + "Particle density set to low.");
                refreshAdminGUI(getPlayerArea(uuid));
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
            case 19 -> { // Particle Density Medium
                particleDensity.put(uuid, 2);
                player.sendMessage(ChatColor.GRAY + "Particle density set to medium.");
                refreshAdminGUI(getPlayerArea(uuid));
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
            case 20 -> { // Particle Density High
                particleDensity.put(uuid, 3);
                player.sendMessage(ChatColor.GRAY + "Particle density set to high.");
                refreshAdminGUI(getPlayerArea(uuid));
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
            case 21, 22 -> { // Particle Type
                int idx = slot - 21;
                if (idx >= 0 && idx < SELECTABLE_PARTICLES.size()) {
                    playerParticleIndex.put(uuid, idx);
                    player.sendMessage(ChatColor.AQUA + "Particle type set to: " + SELECTABLE_PARTICLES.get(idx).name());
                    refreshAdminGUI(getPlayerArea(uuid));
                }
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
            case 23 -> { // Particles Off
                particleDensity.put(uuid, 0);
                player.sendMessage(ChatColor.GRAY + "Particles turned off.");
                refreshAdminGUI(getPlayerArea(uuid));
                Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
            }
            case 26 -> { // Delete Area
                Area area = getPlayerArea(uuid);
                if (area != null) {
                    buildAreas.remove(area);
                    closeGUIsForArea(area);
                    player.sendMessage(ChatColor.RED + "Your build area has been deleted.");
                }
                player.closeInventory();
            }
            // Handle particle selection clicks
            default -> {
                if (slot >= 27 && slot < 27 + SELECTABLE_PARTICLES.size()) {
                    int selectedIndex = slot - 27;
                    playerParticleIndex.put(uuid, selectedIndex);
                    player.sendMessage(ChatColor.AQUA + "Particle type set to: " + SELECTABLE_PARTICLES.get(selectedIndex).name());
                    // Optionally, refresh the GUI or close it
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!selectingCorners.contains(uuid)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.STICK) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !(ChatColor.GREEN + "ResourceRegen Wand").equals(meta.getDisplayName())) return;
        // In PlayerInteractEvent, always check event.getClickedBlock() != null before using getLocation()
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            selection1.put(uuid, event.getClickedBlock().getLocation());
            player.sendMessage(ChatColor.GREEN + "Corner 1 set to: " + locString(event.getClickedBlock().getLocation()));
            event.setCancelled(true);
        } else if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            selection2.put(uuid, event.getClickedBlock().getLocation());
            player.sendMessage(ChatColor.GREEN + "Corner 2 set to: " + locString(event.getClickedBlock().getLocation()));
            event.setCancelled(true);
        } else {
            return;
        }
        // If both corners are set, remove wand and exit mode
        if (selection1.containsKey(uuid) && selection2.containsKey(uuid)) {
            player.getInventory().remove(item);
            selectingCorners.remove(uuid);
            player.sendMessage(ChatColor.AQUA + "Both corners set! You can now name and save your area.");
            Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(player), 2L);
        }
    }

    private String locString(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    // --- GUI tracking for synchronization ---
    private enum GUIType { PLAYER, ADMIN }
    private final Map<UUID, GUIType> openGUIs = new HashMap<>(); // Tracks which GUI is open for each player

    // Utility: Check if a player has a GUI open
    private boolean isGUIOpen(Player p, GUIType type) {
        return openGUIs.getOrDefault(p.getUniqueId(), null) == type;
    }

    // Utility: Refresh player GUI if open
    private void refreshPlayerGUI(UUID playerId) {
        Player p = Bukkit.getPlayer(playerId);
        if (p != null && isGUIOpen(p, GUIType.PLAYER)) {
            Bukkit.getScheduler().runTaskLater(this, () -> openBuildAreaGUI(p), 2L);
        }
    }
    // Utility: Refresh admin GUI if open for a specific area
    private void refreshAdminGUI(Area area) {
        for (Map.Entry<UUID, Area> entry : adminEditingArea.entrySet()) {
            if (entry.getValue() == area) {
                Player admin = Bukkit.getPlayer(entry.getKey());
                if (admin != null && isGUIOpen(admin, GUIType.ADMIN)) {
                    Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
                }
            }
        }
    }
    // Utility: Close GUIs for area deletion
    private void closeGUIsForArea(Area area) {
        // Close admin GUIs
        for (Map.Entry<UUID, Area> entry : adminEditingArea.entrySet()) {
            if (entry.getValue() == area) {
                Player admin = Bukkit.getPlayer(entry.getKey());
                if (admin != null && isGUIOpen(admin, GUIType.ADMIN)) {
                    admin.closeInventory();
                    admin.sendMessage(ChatColor.RED + "Area deleted. GUI closed.");
                }
            }
        }
        // Close player GUI if owner is online and has it open
        Player owner = Bukkit.getPlayer(area.owner);
        if (owner != null && isGUIOpen(owner, GUIType.PLAYER)) {
            owner.closeInventory();
            owner.sendMessage(ChatColor.RED + "Your build area has been deleted. GUI closed.");
        }
    }

    // Save data when a player leaves the server
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        saveDataAsync();
    }
}