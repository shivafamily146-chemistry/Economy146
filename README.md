# IndianEconomy

IndianEconomy is a modular Paper plugin for a persistent Rupee economy, configurable item shop, and player marketplaces. Money is stored as integer paise (not floating point), and SQLite changes are serialized on a dedicated database executor.

## Compatibility

- Minecraft/Paper target: `26.3-pre-2.build.0-alpha` (a Paper preview API artifact; this is **not** a stable Paper 26.3 release).
- Java: 25.
- Database: SQLite, with the JDBC driver bundled in the plugin JAR.
- The plugin has not been started on a real Paper server. Use a matching preview server and test on a disposable world/database first.

## Build and artifacts

```sh
./gradlew clean test build sourceZip
```

Outputs:

- Plugin: `build/libs/IndianEconomy.jar`
- Source archive: `build/IndianEconomy-source.zip`

GitHub Actions runs the clean build/tests and uploads both files as the `IndianEconomy-release` workflow artifact. The Paper API is provided by the server and is not bundled.

## Install

1. Build the project or download the JAR and source ZIP from a successful GitHub Actions run.
2. Copy `IndianEconomy.jar` into the matching Paper server's `plugins/` directory.
3. Start the server. Configuration and the SQLite database are created under `plugins/IndianEconomy/`.
4. Stop the test server and back up the generated database before upgrading or restoring data.

## Player commands

| Command | Purpose |
|---|---|
| `/help` | Show player command help. |
| `/bal [player]`, `/balance [player]`, `/money [player]` | View your balance or a known player's balance. |
| `/pay <player> <amount>` | Transfer Rupees to a known online/offline account. |
| `/baltop` | Show the top 10 balances. |
| `/daily` | Claim the configured cooldown-protected reward. |
| `/shop` | Open categories; select an item to preview quantity and confirm a purchase. |
| `/shop search` then chat, or `/shop search <query>` | Search the catalog. |
| `/buy <material> <amount>` | Buy directly from the catalog. |
| `/sell <material> <amount>` | Sell eligible inventory items. |
| `/sellall` | Sell all eligible items in storage. |
| `/auction list`, `/auction sell <material> <amount> <total-price>`, `/auction buy <id>` | Create and purchase fixed-price auction listings. |
| `/market list`, `/market sell <material> <amount> <total-price>`, `/market buy <id>` | Create and purchase market listings. |
| `/orders list`, `/orders create <material> <amount> <total-budget>`, `/orders fulfill <id>`, `/orders cancel <id>` | Create/fill/cancel escrow-funded player orders. |

Amounts are positive Rupee values with at most two decimal places. Item quantities and transaction limits are validated.

## Admin commands

Every `/ie` command requires `indianeconomy.admin`, in addition to the granular permission noted below where applicable.

### Economy and players

- `/ie balance <player>`
- `/ie give <player> <amount>` (`indianeconomy.give`)
- `/ie take <player> <amount> confirm` (`indianeconomy.take`)
- `/ie set <player> <amount> confirm` and `/ie reset <player> confirm` (`indianeconomy.set`)
- `/ie pay <player> <amount>`
- `/ie giveall <amount> [confirm]` (`indianeconomy.give`), `/ie removeall <amount> [confirm]` (`indianeconomy.take`)
- `/ie baltop` and `/ie baltop reset [confirm]`
- `/ie stats`
- `/ie history <player>`, `/ie transactions`, `/ie paylog <player>`, `/ie inspect [player]`
- `/ie freeze <player>` and `/ie unfreeze <player>`

Operations that can reset or remove data use a review/confirmation step and report the affected account/record snapshot.

### Shop

- `/ie shoplist`, `/ie additem <material>`, `/ie removeitem <material> [confirm]`
- `/ie setprice <material> <buy-price> <sell-price>`, `/ie setbuy <material> <price>`, `/ie setsell <material> <price>`
- `/ie shopreload`, `/ie shopclear [confirm]`, `/ie shop`

These require `indianeconomy.shop.manage`. Shop data is configured in `shop.yml` and `prices.yml`.

### Daily rewards, marketplaces, and server/database

- `/ie setdaily <amount>` and `/ie daily info|cooldown <hours>|reset <player> [confirm]|resetall [confirm]`
- `/ie auction list|remove <id> confirm|clear confirm` (`indianeconomy.auction.manage`)
- `/ie market list|remove <id> confirm|clear confirm` (`indianeconomy.market.manage`)
- `/ie orders list|cancel <id> confirm|clear confirm` (`indianeconomy.orders.manage`)
- `/ie save`, `/ie backup`, `/ie restore <backup-file.db> [confirm]`, `/ie debug`
- `/ie maintenance on|off`, `/ie reload` (`indianeconomy.reload`), `/ie version`
- `/ie admin` opens the admin overview GUI.

Admin listings are fixed-price sales; the auction implementation does not include bidding or timed expiry. Admin commands are also recorded in the SQLite audit log and surfaced in the Admin GUI.

## Permissions

| Permission | Default | Scope |
|---|---|---|
| `indianeconomy.use` | Everyone | Basic economy commands |
| `indianeconomy.shop` | Everyone | Shop and buy |
| `indianeconomy.sell` | Everyone | Sell commands |
| `indianeconomy.pay` | Everyone | Player payments |
| `indianeconomy.admin` | Operators | `/ie` administration and Admin GUI |
| `indianeconomy.reload` | Operators | Configuration reload |
| `indianeconomy.give` | Operators | Economy credits |
| `indianeconomy.take` | Operators | Economy debits |
| `indianeconomy.set` | Operators | Balance set/reset |
| `indianeconomy.shop.manage` | Operators | Shop configuration |
| `indianeconomy.auction.manage` | Operators | Auction moderation |
| `indianeconomy.market.manage` | Operators | Market moderation |
| `indianeconomy.orders.manage` | Operators | Order moderation |

Basic permissions do not imply any admin permission.

## Configuration and persistence

- `config.yml`: start balance, daily reward/cooldown, maximum transaction, database filename, maintenance mode.
- `messages.yml`: player-facing message templates.
- `shop.yml`: categories and sell blacklist.
- `prices.yml`: per-material buy/sell prices; zero disables that side.

Reload validates candidate configuration before replacing runtime settings. SQLite uses schema versioning, prepared statements, account/transaction indexes, WAL mode, atomic transfers/order escrow, pending delivery records, integrity checks, and `VACUUM INTO` backups. Restore validates the backup and creates a pre-restore safety backup.

## Verification and known limits

Automated tests cover money parsing, balance/payment behavior, persistence across database reopen, frozen-account payments, daily cooldown and snapshot resets, shop catalog validation, auction/market/order database flows, backup/restore, command declarations, and permission defaults.

This is not yet a claim of production readiness:

- No real Paper server, GUI event integration, restart integration, or full command execution/permission integration suite was available/run.
- Shop GUI interaction and inventory delivery protections compile, but require live Paper testing for inventory event behavior and client/server edge cases.
- Selling and marketplace listing creation currently cross Bukkit inventory changes and SQLite operations; a process crash in the narrow interval between item removal and database commit can still require manual recovery. Do not use valuable items on a production server until this crash window is eliminated and restart-tested.
- GUI navigation is an overview/review surface; full admin mutations are available through the command workflows rather than every management operation being exposed as a GUI control.
- The requested API is a preview artifact, not a released stable Paper 26.3 API.

The project is considered build/test validated only when the commands below finish successfully. A successful unit test/build does not establish real-server compatibility:

```sh
./gradlew clean test build sourceZip
```
