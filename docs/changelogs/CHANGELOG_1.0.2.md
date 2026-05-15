Feature and reliability update

## Highlights

- Added audit stats commands for server admins.
- Added new default Cobblemon-themed shops when Cobblemon is installed.
- Improved the optional client-side accepted-items bank UI.
- Tightened command permissions and improved transaction safety.

## New

- New admin stats commands for audit history and top activity:
  - `/cdshops stats summary`
  - `/cdshops stats top`
  - `/cdshops stats shop`
  - `/cdshops stats player`
  - `/cdshops stats item`
- New default Cobblemon shops:
  - `trainer_supply`
  - `breeder_corner`
  - `night_market`
- Better accepted-items bank browsing on the client, with cleaner UI behavior and better JEI compatibility.

## Fixes

- Command permissions are now clearer:
  - public access stays limited to opening a shop for yourself, listing shops, and listing visibility state
  - other `/cdshops` commands now require admin permission level 2
- Custom shop buys and custom bank sells are now safer if something fails during the transaction.
- Deleted default shops are no longer recreated automatically as long as the `shops/` folder already exists.

## Notes

- Full technical details, tests, and implementation notes remain in the repository history and docs.
