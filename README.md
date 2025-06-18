# ResourceRegen Spigot Plugin

![ResourceRegen Banner](https://img.shields.io/badge/Minecraft-1.20%2B-green?style=for-the-badge) ![Java 17+](https://img.shields.io/badge/Java-17%2B-blue?style=for-the-badge) ![Version](https://img.shields.io/badge/Version-1.1.0-orange?style=for-the-badge)

> **ResourceRegen** is a powerful, modern Minecraft 1.20+ Spigot plugin for protected build areas, customizable particles, and advanced resource regeneration. All features are managed through beautiful, intuitive GUIs for both players and admins.

---

**Author:** LtHans

---

## ✨ Features
- **Visual GUIs:** Modern, color-coded, and icon-rich interfaces for all actions
- **Player Build Areas:** Define, name, save, and delete your protected cuboid area
- **Set Corners with Wand:** Use a single GUI button to receive a wand; left/right click blocks to set corners, wand is removed after both are set *(NEW)*
- **Particle Customization:** Choose type, density, or turn off particles (with glowing indicators)
- **Instant Area Visualization:** See your area outline with a single click
- **Admin Tools:** Edit, rename, teleport, transfer, or delete any area via GUI
- **Player Head Icons:** Admin GUI shows each area as the owner's head
- **Force Regeneration:** Instantly restore all tracked blocks in any area
- **Live Plugin Config:** Adjust settings in-game with a dedicated config GUI
- **Performance Options:** Batch regeneration, particle throttling, and more
- **Auto-Save:** Data is saved periodically for safety
- **Full GUI Sync:** Any change in player or admin GUI is instantly reflected in the other *(NEW in 1.1.0!)*

---

## 🕹️ Commands
| Command              | Description                                 | Permission         |
|---------------------|---------------------------------------------|--------------------|
| `/buildarea`        | Open the player build area GUI              | (all players)      |
| `/buildareaadmin`   | Open the admin management/config GUI        | `oregen.admin`     |

## 🛡️ Permissions
- `oregen.admin` — Full admin access to all build areas and plugin config

---

## 🚀 Quick Start
1. **Install:** Place the JAR in your `plugins` folder and restart your server
2. **Players:**
   - Use `/buildarea` to open the GUI
   - Click **Set Corners (Wand)**, then left/right click blocks to set corners
   - Name, save, and customize your area visually
   - Adjust or turn off particles for best experience
3. **Admins:**
   - Use `/buildareaadmin` to manage all areas (player heads as icons)
   - Edit, teleport, transfer, or delete any area
   - Force-regenerate blocks or open the config GUI for live settings

---

## 🎨 Usage Tips
- **Set Corners:** Click the green wand button in the GUI, then left/right click blocks to set corners. The wand is removed after both are set.
- **Name Area:** Click the name tag, then type in chat
- **Save:** Click the emerald block after setting both corners and a name
- **Visualize:** Blaze powder shows a particle outline; adjust type/density for clarity
- **Particles Off:** Click the barrier icon to disable particles (glows when off)
- **Delete:** Red stained glass pane removes your area
- **Admin:** Click a player head to edit, force regen (diamond pickaxe), or open config (comparator)
- **Config:** Change settings live and save to `config.yml` in-game
- **Performance:** Lower density or turn off particles for best FPS
- **Full Sync:** If an admin or player changes a setting, all open GUIs update instantly!

---

## 🛠️ Advanced Admin Features
- **Edit Any Area:** Rename, move corners, transfer ownership, or delete
- **Teleport:** Instantly teleport to any area's corners
- **Force Regeneration:** Restore all tracked blocks in an area with one click
- **Live Config GUI:** Adjust plugin settings without editing files
- **Visual Feedback:** All active options glow for clarity
- **Async Data Handling:** All saves/loads are async for performance

---

## 📝 Versioning
- **1.1.0:** Full GUI sync between player/admin, more polish
- **1.0.2:** Advanced GUIs, wand workflow, performance options
- **0.x.x:** Early features, bugfixes, and initial releases

---

## 📄 License
This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## 💬 Support & Feedback
For questions, suggestions, or bug reports, please open an issue or contact LtHans.
