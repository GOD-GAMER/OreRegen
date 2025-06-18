/**
 * OreRegen Spigot Plugin
 * Author: LtHans
 * License: MIT
 * Year: 2025
 */

package com.example.oregen;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.enchantments.Enchantment;

public class OreRegenPlugin extends JavaPlugin implements Listener {

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
    private final Map<UUID, Particle> particleType = new HashMap<>();
    private final List<Particle> availableParticles = Arrays.asList(
        Particle.FLAME, Particle.VILLAGER_HAPPY, Particle.REDSTONE, Particle.HEART, Particle.CLOUD, Particle.CRIT,
        Particle.END_ROD, Particle.NOTE, Particle.SOUL, Particle.SOUL_FIRE_FLAME, Particle.SPELL_WITCH, Particle.TOTEM,
        Particle.PORTAL, Particle.DRAGON_BREATH, Particle.LAVA, Particle.WATER_SPLASH, Particle.SNOWBALL
    );

    // Per-area particle type and density settings
    private final Map<Area, Particle> areaParticleType = new HashMap<>();
    private final Map<Area, Integer> areaParticleDensity = new HashMap<>();

    // Optimization config values
    private int particleUpdateInterval;
    private boolean showParticlesToOwnersOnly;
    private int regenBatchSize;
    private int saveInterval;
    private int maxParticlesPerPlayer;
    private int maxTrackedBlocks;
    private int maxAreasPerPlayer;

    // Track players in corner selection mode
    private final Set<UUID> selectingCorners = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        particleUpdateInterval = getConfig().getInt("particle.update-interval", 5);
        showParticlesToOwnersOnly = getConfig().getBoolean("particle.show-to-owners-only", true);
        regenBatchSize = getConfig().getInt("regeneration.batch-size", 2);
        saveInterval = getConfig().getInt("regeneration.save-interval", 6000);
        maxParticlesPerPlayer = getConfig().getInt("particle.max-particles-per-player", 500);
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
        saveDataAsync(); // Use async save
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
            showAreaParticles(p, area);
            if (!playersInArea.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.YELLOW + "You entered: " + ChatColor.AQUA + area.name);
                playersInArea.add(p.getUniqueId());
            }
        } else {
            playersInArea.remove(p.getUniqueId());
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

    // Show area outline with super dense flame particles, filling all 6 faces from bedrock to build limit
    private void showAreaParticles(Player p, Area area) {
        int density = particleDensity.getOrDefault(p.getUniqueId(), 2); // Default medium
        if (density == 0) return; // Off
        Particle pt = particleType.getOrDefault(p.getUniqueId(), Particle.FLAME);
        int step = (density == 1) ? 4 : (density == 2) ? 2 : 1;
        int minX = Math.min(area.corner1.getBlockX(), area.corner2.getBlockX());
        int maxX = Math.max(area.corner1.getBlockX(), area.corner2.getBlockX());
        int minY = -63;
        int maxY = 320;
        int minZ = Math.min(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        int maxZ = Math.max(area.corner1.getBlockZ(), area.corner2.getBlockZ());
        World w = p.getWorld();
        int particleCount = 0;
        // Top and bottom faces
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (particleCount >= maxParticlesPerPlayer) return;
                w.spawnParticle(pt, x + 0.5, minY + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, x + 0.5, maxY + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                particleCount += 2;
            }
        }
        // Side faces (minX, maxX)
        for (int y = minY; y <= maxY; y += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (particleCount >= maxParticlesPerPlayer) return;
                w.spawnParticle(pt, minX + 0.5, y + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, maxX + 0.5, y + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                particleCount += 2;
            }
        }
        // Side faces (minZ, maxZ)
        for (int y = minY; y <= maxY; y += step) {
            for (int x = minX; x <= maxX; x += step) {
                if (particleCount >= maxParticlesPerPlayer) return;
                w.spawnParticle(pt, x + 0.5, y + 0.5, minZ + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, x + 0.5, y + 0.5, maxZ + 0.5, 2, 0, 0, 0, 0, null);
                particleCount += 2;
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
                if (area.contains(loc) || isNearAreaBoundary(loc, area, 3)) {
                    showAreaParticles(p, area);
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
        particleType.keySet().removeIf(uuid -> !online.contains(uuid));
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
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "Build Area Manager");
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
        // Set Corners (wand)
        ItemStack wandBtn = new ItemStack(Material.STICK);
        ItemMeta wandMeta = wandBtn.getItemMeta();
        if (wandMeta != null) {
            wandMeta.setDisplayName(ChatColor.GREEN + "Set Corners (Wand)");
            wandMeta.setLore(Arrays.asList(ChatColor.YELLOW + "Click to receive a wand", ChatColor.GRAY + "Left: Corner 1, Right: Corner 2"));
            wandBtn.setItemMeta(wandMeta);
        }
        gui.setItem(10, wandBtn);
        gui.setItem(16, null); // Remove old button
        // Name Area
        ItemStack name = new ItemStack(Material.NAME_TAG);
        ItemMeta nameMeta = name.getItemMeta();
        if (nameMeta != null) {
            nameMeta.setDisplayName(ChatColor.YELLOW + "Name Area");
            nameMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to set area name"));
            name.setItemMeta(nameMeta);
        }
        gui.setItem(12, name);
        // Save Area
        ItemStack save = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta saveMeta = save.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setDisplayName(ChatColor.AQUA + "Save Area");
            saveMeta.setLore(Collections.singletonList(ChatColor.GREEN + "Click to save your area"));
            save.setItemMeta(saveMeta);
        }
        gui.setItem(14, save);
        // Show Area Outline
        ItemStack show = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta showMeta = show.getItemMeta();
        if (showMeta != null) {
            showMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "Show Area Outline");
            showMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to preview your area"));
            show.setItemMeta(showMeta);
        }
        gui.setItem(13, show);

        // Particle density options with indicator
        int currentDensity = particleDensity.getOrDefault(player.getUniqueId(), 2);
        ItemStack densityLow = new ItemStack(Material.GRAY_DYE);
        ItemMeta dLowMeta = densityLow.getItemMeta();
        if (dLowMeta != null) {
            dLowMeta.setDisplayName(ChatColor.WHITE + "Particle Density: Low");
            dLowMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to set low density"));
            if (currentDensity == 1) dLowMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            densityLow.setItemMeta(dLowMeta);
        }
        gui.setItem(18, densityLow);
        ItemStack densityMed = new ItemStack(Material.LIGHT_GRAY_DYE);
        ItemMeta dMedMeta = densityMed.getItemMeta();
        if (dMedMeta != null) {
            dMedMeta.setDisplayName(ChatColor.WHITE + "Particle Density: Medium");
            dMedMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to set medium density"));
            if (currentDensity == 2) dMedMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            densityMed.setItemMeta(dMedMeta);
        }
        gui.setItem(19, densityMed);
        ItemStack densityHigh = new ItemStack(Material.WHITE_DYE);
        ItemMeta dHighMeta = densityHigh.getItemMeta();
        if (dHighMeta != null) {
            dHighMeta.setDisplayName(ChatColor.WHITE + "Particle Density: High");
            dHighMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to set high density"));
            if (currentDensity == 3) dHighMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            densityHigh.setItemMeta(dHighMeta);
        }
        gui.setItem(20, densityHigh);
        // Particle type options
        int slot = 21;
        Particle currentType = particleType.getOrDefault(player.getUniqueId(), Particle.FLAME);
        for (Particle pt : availableParticles) {
            ItemStack ptItem = new ItemStack(Material.FIREWORK_STAR);
            ItemMeta ptMeta = ptItem.getItemMeta();
            if (ptMeta != null) {
                ptMeta.setDisplayName(ChatColor.AQUA + "Particle: " + pt.name());
                ptMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to select this particle"));
                if (pt == currentType) ptMeta.addEnchant(Enchantment.DURABILITY, 1, true);
                ptItem.setItemMeta(ptMeta);
            }
            gui.setItem(slot++, ptItem);
        }
        // Particles Off button
        ItemStack off = new ItemStack(Material.BARRIER);
        ItemMeta offMeta = off.getItemMeta();
        if (offMeta != null) {
            offMeta.setDisplayName(ChatColor.GRAY + "Particles: Off");
            offMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to turn off particles"));
            if (currentDensity == 0) offMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            off.setItemMeta(offMeta);
        }
        gui.setItem(23, off);
        // Delete Area button (active if player has an area)
        Area area = getPlayerArea(player.getUniqueId());
        ItemStack delete = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta delMeta = delete.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.RED + "Delete Build Area");
            delMeta.setLore(Collections.singletonList(ChatColor.DARK_RED + "Click to delete your area"));
            if (area != null) delMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            delete.setItemMeta(delMeta);
        }
        gui.setItem(26, delete);
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
        // Set Corner 1
        ItemStack setC1 = new ItemStack(Material.STICK);
        ItemMeta c1Meta = setC1.getItemMeta();
        if (c1Meta != null) {
            c1Meta.setDisplayName(ChatColor.GREEN + "Set Corner 1 (to your location)");
            List<String> lore1 = new ArrayList<>();
            lore1.add(ChatColor.GRAY + "Current: " + area.corner1.getBlockX() + ", " + area.corner1.getBlockY() + ", " + area.corner1.getBlockZ());
            lore1.add(ChatColor.YELLOW + "Click to set to your location");
            c1Meta.setLore(lore1);
            setC1.setItemMeta(c1Meta);
        }
        gui.setItem(0, setC1);
        // Set Corner 2
        ItemStack setC2 = new ItemStack(Material.STICK);
        ItemMeta c2Meta = setC2.getItemMeta();
        if (c2Meta != null) {
            c2Meta.setDisplayName(ChatColor.GREEN + "Set Corner 2 (to your location)");
            List<String> lore2 = new ArrayList<>();
            lore2.add(ChatColor.GRAY + "Current: " + area.corner2.getBlockX() + ", " + area.corner2.getBlockY() + ", " + area.corner2.getBlockZ());
            lore2.add(ChatColor.YELLOW + "Click to set to your location");
            c2Meta.setLore(lore2);
            setC2.setItemMeta(c2Meta);
        }
        gui.setItem(1, setC2);
        // Rename
        ItemStack rename = new ItemStack(Material.NAME_TAG);
        ItemMeta renameMeta = rename.getItemMeta();
        if (renameMeta != null) {
            renameMeta.setDisplayName(ChatColor.YELLOW + "Rename Area");
            rename.setItemMeta(renameMeta);
        }
        gui.setItem(2, rename);
        // Particle type
        Particle areaType = areaParticleType.getOrDefault(area, Particle.FLAME);
        ItemStack ptType = new ItemStack(Material.FIREWORK_STAR);
        ItemMeta ptTypeMeta = ptType.getItemMeta();
        if (ptTypeMeta != null) {
            ptTypeMeta.setDisplayName(ChatColor.AQUA + "Cycle Particle Type");
            if (areaType == areaParticleType.get(area)) ptTypeMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            ptType.setItemMeta(ptTypeMeta);
        }
        gui.setItem(3, ptType);
        // Particle density
        int areaDensity = areaParticleDensity.getOrDefault(area, 2);
        ItemStack ptDensity = new ItemStack(Material.GUNPOWDER);
        ItemMeta ptDenMeta = ptDensity.getItemMeta();
        if (ptDenMeta != null) {
            ptDenMeta.setDisplayName(ChatColor.AQUA + "Cycle Particle Density");
            if (areaDensity > 0) ptDenMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            ptDensity.setItemMeta(ptDenMeta);
        }
        gui.setItem(4, ptDensity);
        // Teleport to corners
        ItemStack tpC1 = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpC1Meta = tpC1.getItemMeta();
        if (tpC1Meta != null) {
            tpC1Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Teleport to Corner 1");
            tpC1.setItemMeta(tpC1Meta);
        }
        gui.setItem(5, tpC1);
        ItemStack tpC2 = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpC2Meta = tpC2.getItemMeta();
        if (tpC2Meta != null) {
            tpC2Meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Teleport to Corner 2");
            tpC2.setItemMeta(tpC2Meta);
        }
        gui.setItem(6, tpC2);
        // Transfer ownership
        ItemStack transfer = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta transferMeta = transfer.getItemMeta();
        if (transferMeta != null) {
            transferMeta.setDisplayName(ChatColor.BLUE + "Transfer Ownership");
            transfer.setItemMeta(transferMeta);
        }
        gui.setItem(7, transfer);
        // Delete
        ItemStack delete = new ItemStack(Material.BARRIER);
        ItemMeta delMeta = delete.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.RED + "Delete This Area");
            if (buildAreas.contains(area)) delMeta.addEnchant(Enchantment.DURABILITY, 1, true);
            delete.setItemMeta(delMeta);
        }
        gui.setItem(8, delete);
        // Config button
        ItemStack config = new ItemStack(Material.COMPARATOR);
        ItemMeta configMeta = config.getItemMeta();
        if (configMeta != null) {
            configMeta.setDisplayName(ChatColor.BLUE + "Plugin Config");
            config.setItemMeta(configMeta);
        }
        gui.setItem(25, config);
        // Force Regen button
        ItemStack forceRegen = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta frMeta = forceRegen.getItemMeta();
        if (frMeta != null) {
            frMeta.setDisplayName(ChatColor.RED + "Force Regenerate Area");
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
                int idx = availableParticles.indexOf(areaParticleType.getOrDefault(area, Particle.FLAME));
                idx = (idx + 1) % availableParticles.size();
                areaParticleType.put(area, availableParticles.get(idx));
                admin.sendMessage(ChatColor.AQUA + "Particle type set to: " + availableParticles.get(idx).name());
                saveDataAsync();
                refreshPlayerGUI(area.owner);
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
            case 4 -> { // Cycle particle density
                int density = areaParticleDensity.getOrDefault(area, 2);
                density = (density % 3) + 1;
                areaParticleDensity.put(area, density);
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
                areaParticleDensity.put(area, 0);
                admin.sendMessage(ChatColor.GRAY + "Particles turned off for this area.");
                refreshPlayerGUI(area.owner);
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(admin, area), 2L);
            }
        }
    }

    // Admin rename/transfer mode tracking
    private final Map<UUID, Area> adminRenameMode = new HashMap<>();
    private final Map<UUID, Area> adminTransferMode = new HashMap<>();

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
        } else if (adminTransferMode.containsKey(uuid)) {
            event.setCancelled(true);
            String newOwnerName = event.getMessage().trim();
            Area area = adminTransferMode.remove(uuid);
            OfflinePlayer newOwner = null;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                String opName = op.getName();
                if (opName != null && opName.equalsIgnoreCase(newOwnerName)) {
                    newOwner = op;
                    break;
                }
            }
            if (newOwner == null) {
                player.sendMessage(ChatColor.RED + "Player not found: " + newOwnerName);
                return;
            }
            area.owner = newOwner.getUniqueId();
            player.sendMessage(ChatColor.GREEN + "Area ownership transferred to: " + ChatColor.AQUA + newOwner.getName());
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
            sender.sendMessage(ChatColor.YELLOW + "Particle Type Map: " + particleType.size());
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
            case 13 -> { // Show Area Outline
                Area temp = new Area(uuid, "Preview", selection1.get(uuid), selection2.get(uuid));
                showAreaParticles(player, temp);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Area outline previewed.");
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
                if (idx >= 0 && idx < availableParticles.size()) {
                    particleType.put(uuid, availableParticles.get(idx));
                    player.sendMessage(ChatColor.AQUA + "Particle type set to: " + availableParticles.get(idx).name());
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
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            selection1.put(uuid, event.getClickedBlock().getLocation());
            player.sendMessage(ChatColor.GREEN + "Corner 1 set to: " + locString(event.getClickedBlock().getLocation()));
            event.setCancelled(true);
        } else if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
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
    // private final Map<UUID, Area> adminEditingArea = new HashMap<>(); // Tracks which area each admin is editing

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
}