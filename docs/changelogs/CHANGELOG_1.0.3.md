Compatibility update for Minecraft 1.21.1 / NeoForge

## Changes

- Updated the CobbleDollars integration to Beta 6.1: new shop/bank API, immutable offers,
  inline references, and updated network packets.
- Preserved existing commands, JSON configuration files, per-player stock, restock rules,
  access conditions, purchase bonuses, audit logs, and optional client installation.
- Preserved bundle quantities, prices, and item components. The optional client tooltips
  display the bundle size from server metadata, including after native stock updates.
- Preserved exact-stack bank overrides and generic item fallbacks with the new bank rules.
- Empty custom banks remain empty instead of falling back to the native global bank.
- Updated the optional client tooltips and bank UI for the new native offers and menus.

## Compatibility

- Minecraft 1.21.1 / NeoForge 21.1.227
- CobbleDollars 2.0.0+Beta-6.1+1.21.1 (minimum supported version)
- Cobblemon 1.8.2
- Kotlin for Forge 5.12.0
- Easy NPC and Config UI 7.15.0, with Easy Model Entities 2.5.0
- CustomNPCs Unofficial NeoForge 1.21.1.20251230

NPC mods remain optional and open shops through commands. No NPC API dependency was added.
The existing optional JEI integration is retained.

For CobbleDollars Beta 5.1, keep addon version 1.0.2. Update the optional client addon
alongside the server addon when moving to Beta 6.1.

Native Beta 6 offer icons represent unit stacks. Without the optional client addon,
the native screen uses that presentation while purchases still deliver configured bundles.

The addon configuration format is unchanged. CobbleDollars itself migrates its native
shop/bank data when updating; back up the world and configuration before that migration.
