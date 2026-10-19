# CobbleDollars Command Shops 1.0.3

CobbleDollars Beta 6.1 compatibility for Minecraft 1.21.1 / NeoForge.

## Changes

- Updated shop/bank integration, network packets, optional client tooltips and bank UI
  for CobbleDollars Beta 6.1.
- Preserved commands, JSON configs, per-player stock, restock rules, access conditions,
  purchase bonuses and audit logs.
- Preserved bundle quantities, prices and item components; client tooltips keep bundle
  sizes accurate after stock updates.
- Preserved exact-stack bank overrides and generic item fallbacks. Empty custom banks
  no longer fall back to the native global bank.

## Compatibility

- Minecraft **1.21.1**, NeoForge **21.1.227**, CobbleDollars
  **2.0.0+Beta-6.1+1.21.1** (minimum), Cobblemon **1.8.2**, Kotlin for Forge **5.12.0**.
- Optional integrations: JEI; Easy NPC / Config UI **7.15.0** with Easy Model Entities
  **2.5.0**; CustomNPCs Unofficial NeoForge **1.21.1.20251230**. NPCs open shops through commands.

## Upgrade notes

- For CobbleDollars Beta 5.1, keep addon **1.0.2**. Update the optional client addon
  alongside the server when moving to Beta 6.1.
- Without the client addon, native icons show unit stacks; purchases still deliver
  configured bundles.
- Addon configs are unchanged. Back up the world and configs before CobbleDollars
  migrates its own shop/bank data.
