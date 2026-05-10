# CobbleDollars Command Shops

Server-side NeoForge addon for CobbleDollars on Minecraft `1.21.1`.

This mod adds command-driven CobbleDollars shops with:
- persistent per-player stock
- configurable global or per-shop banks
- exact item matching through modern Minecraft data components
- conditional visibility and access rules
- hot config reload with `/cdshops reload`
- configurable server-only player feedback through `feedback.json`
- optional client-side UI enhancements when the same jar is installed on the client

It does not replace CobbleDollars, replace CobbleDollars NPCs, or move gameplay authority to the client. It only takes over sessions opened through this addon.

## Commands

- `/cdshops open <shop>`
- `/cdshops open <shop> <player>`
- `/cdshops reload`
- `/cdshops restock <shop> all <players>`
- `/cdshops restock <shop> offer <offer> <players>`
- `/cdshops stock <shop> <player>`
- `/cdshops list`
- `/cdshops where`

## Config

Config files are loaded from:

`config/cobbledollarscommandshops/`

The repo also ships example files under:

`run/config/cobbledollarscommandshops/`

The detailed format reference is in:

`config/cobbledollarscommandshops/CONFIG_GUIDE.md`

## Build

`gradlew build`
