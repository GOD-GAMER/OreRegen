# Changelog

All notable changes to this project will be documented in this file.

## [1.1.2] - 2025-06-18
### Fixed
- Removed invalid particle (FIREWORK) for Spigot 1.20+ compatibility
- Admin GUI: Force Regen All and Config buttons now work from the main admin GUI
- Config GUI is now accessible and functional
- Force Regen All restores all tracked blocks outside build areas only
- More particle types available and particles always show while in your area

## [1.1.1] - 2025-06-18
### Fixed
- Admin GUI now works: fixed editing state tracking and event handling
- Build area saving is now reliable (areas save with name as expected)
- Removed duplicate/unused admin editing map to prevent GUI bugs

## [1.1.0] - 2025-06-18
### Added
- Full synchronization between player and admin GUIs: any change in one is instantly reflected in the other (particle type, density, area deletion, etc.)
- Improved README with more details, usage tips, and visual polish

### Changed
- Polished and clarified GUI feedback for all shared settings
- Updated versioning to reflect new features

## [1.0.2] - 2025-06-10
### Added
- Advanced player/admin GUIs for area management
- Wand workflow for setting area corners
- Per-area and per-player particle customization
- Force regeneration, admin config GUI (stub), and player head icons
- Async data saving/loading, memory management, debug commands
- Visual feedback (enchanted effect) for all active options

### Changed
- Cleaned up code, removed unused imports, improved null checks
- Updated documentation and versioning scheme

## [1.0.0] - 2025-06-01
### Added
- Initial release: build area protection, block regeneration, basic GUIs

---

See the README for full feature descriptions and usage tips.
