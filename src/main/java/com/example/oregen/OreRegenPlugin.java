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
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.enchantments.Enchantment;

public class OreRegenPlugin extends JavaPlugin implements Listener, TabExecutor {

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
            this.world = loc.getWorld().getName();
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.type = type;
            this.breakTime = breakTime;
        }
        public Location getLocation() {
            World w = Bukkit.getWorld(world);
            return new Location(w, x, y, z);
        }
    }

    // Player selection state
    private final Map<UUID, Location> selection1 = new HashMap<>();
    private final Map<UUID, Location> selection2 = new HashMap<>();
    private final Map<UUID, String> areaNames = new HashMap<>();
    private final List<Area> buildAreas = new ArrayList<>();
    private final List<OreRecord> brokenOres = Collections.synchronizedList(new ArrayList<>());

    // For particle display
    private final Set<UUID> playersInArea = ConcurrentHashMap.newKeySet();

    // Particle settings per player
    private final Map<UUID, Integer> particleDensity = new HashMap<>(); // 1=Low, 2=Medium, 3=High
    private final Map<UUID, Particle> particleType = new HashMap<>();
    private final List<Particle> availableParticles = Arrays.asList(
        Particle.FLAME, Particle.VILLAGER_HAPPY, Particle.REDSTONE, Particle.HEART, Particle.CLOUD, Particle.CRIT
    );

    // Per-area particle type and density settings
    private final Map<Area, Particle> areaParticleType = new HashMap<>();
    private final Map<Area, Integer> areaParticleDensity = new HashMap<>();

    // Optimization config values
    private String defaultParticleDensity;
    private int particleUpdateInterval;
    private boolean showParticlesToOwnersOnly;
    private int regenBatchSize;
    private int saveInterval;
    private boolean unloadOfflineAreas;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        defaultParticleDensity = getConfig().getString("particle.default-density", "medium");
        particleUpdateInterval = getConfig().getInt("particle.update-interval", 5);
        showParticlesToOwnersOnly = getConfig().getBoolean("particle.show-to-owners-only", true);
        regenBatchSize = getConfig().getInt("regeneration.batch-size", 2);
        saveInterval = getConfig().getInt("regeneration.save-interval", 6000);
        unloadOfflineAreas = getConfig().getBoolean("data.unload-offline-areas", true);
        Bukkit.getPluginManager().registerEvents(this, this);
        loadData();
        startOreRegenTask();
        startParticleTask();
        // Register /buildarea command to open the GUI
        if (getCommand("buildarea") != null) {
            getCommand("buildarea").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) {
                    openBuildAreaGUI(player);
                    return true;
                }
                sender.sendMessage("Players only.");
                return true;
            });
        }
        // Register /buildareaadmin command for admin GUI
        if (getCommand("buildareaadmin") != null) {
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
        }
        // Schedule periodic data save
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, saveInterval, saveInterval);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // Block break event (track all blocks outside build areas)
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        Location loc = block.getLocation();
        if (isInAnyBuildArea(loc)) return; // Don't track inside build areas
        brokenOres.add(new OreRecord(loc, type, System.currentTimeMillis()));
    }

    // Particle display when player is inside or near their area
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Area area = getPlayerArea(p.getUniqueId());
        if (area == null) return;
        Location loc = p.getLocation();
        boolean inside = area.contains(loc);
        boolean near = isNearAreaBoundary(loc, area, 3);
        if (inside) {
            showAreaParticles(p, area);
            if (!playersInArea.contains(p.getUniqueId())) {
                p.sendMessage(ChatColor.YELLOW + "You entered: " + ChatColor.AQUA + area.name);
                playersInArea.add(p.getUniqueId());
            }
        } else {
            playersInArea.remove(p.getUniqueId());
        }
        // Debug: show when checking position
        // p.sendMessage(ChatColor.GRAY + "[DEBUG] Checked area: inside=" + inside + ", near=" + near);
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
        // Top and bottom faces
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                w.spawnParticle(pt, x + 0.5, minY + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, x + 0.5, maxY + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
            }
        }
        // Side faces (minX, maxX)
        for (int y = minY; y <= maxY; y += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                w.spawnParticle(pt, minX + 0.5, y + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, maxX + 0.5, y + 0.5, z + 0.5, 2, 0, 0, 0, 0, null);
            }
        }
        // Side faces (minZ, maxZ)
        for (int y = minY; y <= maxY; y += step) {
            for (int x = minX; x <= maxX; x += step) {
                w.spawnParticle(pt, x + 0.5, y + 0.5, minZ + 0.5, 2, 0, 0, 0, 0, null);
                w.spawnParticle(pt, x + 0.5, y + 0.5, maxZ + 0.5, 2, 0, 0, 0, 0, null);
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    // GUI for build area management with density/type/delete options
    private void openBuildAreaGUI(Player player) {
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
        // Set Corner 1
        ItemStack set1 = new ItemStack(Material.LIME_WOOL);
        ItemMeta set1Meta = set1.getItemMeta();
        if (set1Meta != null) {
            set1Meta.setDisplayName(ChatColor.GREEN + "Set Corner 1");
            Location c1 = selection1.get(player.getUniqueId());
            List<String> lore1 = new ArrayList<>();
            if (c1 != null) lore1.add(ChatColor.GRAY + "Current: " + c1.getBlockX() + ", " + c1.getBlockY() + ", " + c1.getBlockZ());
            lore1.add(ChatColor.YELLOW + "Click to set to your location");
            set1Meta.setLore(lore1);
            set1.setItemMeta(set1Meta);
        }
        gui.setItem(10, set1);
        // Set Corner 2
        ItemStack set2 = new ItemStack(Material.LIME_WOOL);
        ItemMeta set2Meta = set2.getItemMeta();
        if (set2Meta != null) {
            set2Meta.setDisplayName(ChatColor.GREEN + "Set Corner 2");
            Location c2 = selection2.get(player.getUniqueId());
            List<String> lore2 = new ArrayList<>();
            if (c2 != null) lore2.add(ChatColor.GRAY + "Current: " + c2.getBlockX() + ", " + c2.getBlockY() + ", " + c2.getBlockZ());
            lore2.add(ChatColor.YELLOW + "Click to set to your location");
            set2Meta.setLore(lore2);
            set2.setItemMeta(set2Meta);
        }
        gui.setItem(16, set2);
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
        // Delete Area
        ItemStack delete = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta delMeta = delete.getItemMeta();
        if (delMeta != null) {
            delMeta.setDisplayName(ChatColor.RED + "Delete Build Area");
            delMeta.setLore(Collections.singletonList(ChatColor.DARK_RED + "Click to delete your area"));
            delete.setItemMeta(delMeta);
        }
        gui.setItem(26, delete);
        player.openInventory(gui);
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
            if (slot >= 54) break;
        }
        admin.openInventory(gui);
    }

    // Handle admin GUI clicks
    @EventHandler
    public void onAdminAreaListClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!event.getView().getTitle().equals(ChatColor.DARK_RED + "All Build Areas")) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
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
            if (areaDensity == 0) ptDenMeta.addEnchant(Enchantment.DURABILITY, 1, true);
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
        editingArea.put(admin.getUniqueId(), area);
    }

    private final Map<UUID, Area> editingArea = new HashMap<>();

    @EventHandler
    public void onAdminAreaEditClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!event.getView().getTitle().startsWith(ChatColor.RED + "Edit Area: ")) return;
        event.setCancelled(true);
        Area area = editingArea.get(admin.getUniqueId());
        if (area == null) return;
        int slot = event.getRawSlot();
        switch (slot) {
            case 0 -> { // Set Corner 1
                area.corner1 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 1 set to your location.");
                saveData();
            }
            case 1 -> { // Set Corner 2
                area.corner2 = admin.getLocation();
                admin.sendMessage(ChatColor.GREEN + "Corner 2 set to your location.");
                saveData();
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
                saveData();
            }
            case 4 -> { // Cycle particle density
                int density = areaParticleDensity.getOrDefault(area, 2);
                density = (density % 3) + 1;
                areaParticleDensity.put(area, density);
                admin.sendMessage(ChatColor.AQUA + "Particle density set to: " + (density == 1 ? "Low" : density == 2 ? "Medium" : "High"));
                saveData();
            }
            case 5 -> { // Teleport to corner 1
                admin.teleport(area.corner1);
            }
            case 6 -> { // Teleport to corner 2
                admin.teleport(area.corner2);
            }
            case 7 -> { // Transfer ownership
                admin.closeInventory();
                admin.sendMessage(ChatColor.BLUE + "Type the new owner's player name in chat:");
                adminTransferMode.put(admin.getUniqueId(), area);
            }
            case 8 -> { // Delete
                buildAreas.remove(area);
                admin.sendMessage(ChatColor.RED + "Area deleted.");
                saveData();
                admin.closeInventory();
                editingArea.remove(admin.getUniqueId());
                Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaListGUI(admin), 2L);
            }
            case 18 -> { // Particles: Off
                areaParticleDensity.put(area, 0);
                admin.sendMessage(ChatColor.GRAY + "Particles turned off for this area.");
                openAdminAreaEditGUI(admin, area);
            }
            case 25 -> openAdminConfigGUI(admin);
            case 26 -> {
                // Force regenerate all tracked blocks in this area
                int count = 0;
                Iterator<OreRecord> it = brokenOres.iterator();
                while (it.hasNext()) {
                    OreRecord rec = it.next();
                    Location loc = rec.getLocation();
                    if (area.contains(loc)) {
                        Block block = loc.getBlock();
                        if (block.getType() == Material.AIR) {
                            block.setType(rec.type);
                            count++;
                        }
                        it.remove();
                    }
                }
                admin.sendMessage(ChatColor.GREEN + "Force regenerated " + count + " blocks in this area.");
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
            OfflinePlayer newOwner = Bukkit.getOfflinePlayer(newOwnerName);
            if (newOwner.getUniqueId().equals(area.owner)) {
                player.sendMessage(ChatColor.RED + "This area is already owned by " + newOwner.getName());
                return;
            }
            area.owner = newOwner.getUniqueId();
            player.sendMessage(ChatColor.GREEN + "Area ownership transferred to: " + ChatColor.AQUA + newOwner.getName());
            saveData();
            Bukkit.getScheduler().runTaskLater(this, () -> openAdminAreaEditGUI(player, area), 2L);
        }
    }

    // Opens the admin config GUI (stub, expand as needed)
    private void openAdminConfigGUI(Player admin) {
        admin.sendMessage(ChatColor.YELLOW + "[OreRegen] Config GUI is not yet implemented.");
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
}
