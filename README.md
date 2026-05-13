# CobbleDollars Command Shops

Server-side NeoForge addon for CobbleDollars on Minecraft `1.21.1`.

This mod adds command-driven CobbleDollars shops with:
- persistent per-player stock
- configurable global or per-shop banks
- exact matching through `match.include` / `match.exclude` with `item`, `stack`, `tag`, and `mod`
- conditional visibility and access rules
- runtime shop visibility toggles through commands
- optional purchase bonuses when buying enough bundles in one transaction
- structured audit logs for buys, sells, and visibility changes
- hot config reload with `/cdshops reload`
- configurable server-only player feedback through `feedback.json`
- optional client-side UI enhancements when the same jar is installed on the client
- built-in English and French localized feedback

It does not replace CobbleDollars, replace CobbleDollars NPCs, or move gameplay authority to the client. It only takes over sessions opened through this addon.

## Commands

- `/cdshops open <shop>`
- `/cdshops open <shop> <player>`
- `/cdshops reload`
- `/cdshops restock <shop> all <players>`
- `/cdshops restock <shop> offer <offer> <players>`
- `/cdshops stock <shop> <player>`
- `/cdshops visibility list`
- `/cdshops visibility <shop> status`
- `/cdshops visibility <shop> enable`
- `/cdshops visibility <shop> disable [message]`
- `/cdshops list`
- `/cdshops where`

## Config

Config files are loaded from:

`config/cobbledollarscommandshops/`

The detailed format reference is in:

`config/cobbledollarscommandshops/CONFIG_GUIDE.md`

Transaction audit logs are written to:

`logs/cobbledollarscommandshops/transactions.jsonl`

License terms are in the root `LICENSE` file and are also bundled into the built jar.

## Build

`gradlew build`
