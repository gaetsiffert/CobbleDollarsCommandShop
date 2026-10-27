# CobbleDollars Command Shops 1.1.1

Better money tracking for server admins, plus fixes for empty shops and banks.

## What's new

- **Track payments between players.** See who sent money, who received it, and
  both players' balances before and after the payment. Recording is on by default.
- **Keep a history of admin balance changes.** Money added, removed or set through
  CobbleDollars commands now appears in the Audit tab, including who made the
  change and how much money actually moved. Recording is on by default.
- **Choose what else to record.** Battle rewards and purchases/sales at regular
  CobbleDollars NPC shops can also be tracked. These three options are **off by
  default**; enable them individually under **Settings / Audit**.
- **Get a clearer picture of player activity.** Player profiles show incoming and
  outgoing money by source. Find a payment using either player's name and open
  both profiles from its details. Transfers are counted separately from money
  added to or removed from the economy.

## Fixed

- Empty shops no longer show unwanted default items, including when their last
  offer is removed or hidden by conditions.
- Banks with nothing to sell no longer highlight empty inventory slots in green
  or show items that cannot actually be sold.
- Damaged audit entries no longer distort money totals and activity statistics.
- The empty-shop and bank fixes also work for players without the client addon.

## Updating

- Update your server and any clients using the addon to **1.1.1**. Other players
  can continue using the shops without installing it.
- Your existing shops, settings, player stocks and audit history remain compatible.
- Requires Minecraft **1.21.1**, NeoForge **21.1.230+** and CobbleDollars
  **2.0.0+Beta-6.1+1.21.1** or newer. For CobbleDollars Beta 5.1, use addon **1.0.2**.
