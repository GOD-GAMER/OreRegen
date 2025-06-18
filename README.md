# OreRegen Spigot Plugin

![OreRegen Banner](https://img.shields.io/badge/Minecraft-1.20%2B-green?style=for-the-badge) ![Java 17+](https://img.shields.io/badge/Java-17%2B-blue?style=for-the-badge) ![Version](https://img.shields.io/badge/Version-1.4-orange?style=for-the-badge)

> **OreRegen** is a powerful, modern Minecraft 1.20+ Spigot plugin for protected build areas, customizable particles, and advanced resource regeneration. All features are managed through beautiful, intuitive GUIs for both players and admins.

---

**Author:** LtHans

---

## ✨ Features
- **Visual GUIs:** Modern, color-coded, and icon-rich interfaces for all actions
- **Player Build Areas:** Define, name, save, and delete your protected cuboid area
- **Particle Customization:** Choose type, density, or turn off particles (with glowing indicators)
- **Instant Area Visualization:** See your area outline with a single click
- **Admin Tools:** Edit, rename, teleport, transfer, or delete any area via GUI
- **Player Head Icons:** Admin GUI shows each area as the owner's head
- **Force Regeneration:** Instantly restore all tracked blocks in any area
- **Live Plugin Config:** Adjust settings in-game with a dedicated config GUI
- **Performance Options:** Batch regeneration, particle throttling, and more
- **Auto-Save:** Data is saved periodically for safety

---

## 🕹️ Commands
- `/buildarea` — Open the player build area GUI
- `/buildareaadmin` — Open the admin management/config GUI *(requires `oregen.admin`)*

## 🛡️ Permissions
- `oregen.admin` — Full admin access to all build areas and plugin config

---

## 🚀 Quick Start
1. **Install:** Place the JAR in your `plugins` folder and restart your server
2. **Players:**
   - Use `/buildarea` to open the GUI
   - Set corners, name, save, and customize your area visually
   - Adjust or turn off particles for best experience
3. **Admins:**
   - Use `/buildareaadmin` to manage all areas (player heads as icons)
   - Edit, teleport, transfer, or delete any area
   - Force-regenerate blocks or open the config GUI for live settings

---

## 🎨 Usage Tips
- **Set Corners:** Stand at the location and click the green wool buttons; coordinates are shown in the GUI
- **Name Area:** Click the name tag, then type in chat
- **Save:** Click the emerald block after setting both corners and a name
- **Visualize:** Blaze powder shows a particle outline; adjust type/density for clarity
- **Particles Off:** Click the barrier icon to disable particles (glows when off)
- **Delete:** Red stained glass pane removes your area
- **Admin:** Click a player head to edit, force regen (diamond pickaxe), or open config (comparator)
- **Config:** Change settings live and save to `config.yml` in-game
- **Performance:** Lower density or turn off particles for best FPS

---

## ⚙️ Plugin Configuration (Admin Only)
- Open the config GUI from the admin area edit screen
- Adjust default particle density, regeneration batch size, save interval, and more
- Save changes directly to `config.yml` from in-game

---

## 📦 Requirements
- Minecraft **1.20+**
- Spigot or Paper server
- Java **17+**

---

## 🛠️ Installation
1. Build the plugin with `mvn clean package`
2. Place the generated JAR in your server's `plugins` folder
3. Restart your server

---

## 💬 Support
For help or feature requests, contact the plugin author or open an issue on your code repository.
