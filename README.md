# IndianEconomy

IndianEconomy is a modular Paper plugin for a persistent Rupee economy, configurable item shop, and player marketplaces. Money is stored as integer paise (not floating point), and SQLite changes are serialized on a dedicated database executor.

## Compatibility

- Minecraft/Paper target: `26.3.build.249-stable` (stable Paper API artifact).
- Java: 25.
- Database: SQLite, with the JDBC driver bundled in the plugin JAR.
- The dependency is the stable Paper API artifact, verified from Paper's Maven repository. The plugin has not been started on a real Paper server.

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
| `/auction list`, `/auction sell <material> <amount> <starting-bid>`, `/auction bid <id> <amount>` | Create a 24-hour auction, place escrowed bids, and receive the item after settlement. |
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

Admin commands are also recorded in the SQLite audit log and surfaced in the Admin GUI. The GUI has economy, player, shop, auction, market, orders, transaction, daily, server, and database sections; each exposes a control, command, or validated chat prompt for its operations.

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

Reload validates candidate configuration before replacing runtime settings. SQLite uses schema versioning, prepared statements, account/transaction indexes, WAL mode, atomic transfers/order escrow, pending delivery records, integrity checks, and `VACUUM INTO` backups. Restore validates the backup and creates a pre-restore safety backup. Player sales, auction/market listing creation, and order fulfillment use a persisted item-operation journal: selected plain items are tagged and saved before the database intent is written; removal is saved before the intent is atomically credited or listed; pending intents are recovered on the owner's next join. Pending item deliveries are also saved with a marker before their database receipt is acknowledged.

## Verification and known limits

Automated tests cover money parsing, balance/payment behavior, persistence across database reopen, frozen-account payments, daily cooldown and snapshot resets, shop catalog validation, crash-persistent item-operation journals, exactly-once journal completion, escrowed order fulfillment, concurrent market purchases, auction bid validation/outbid refunds/expiry/admin removal, backup/restore, command declarations, and permission defaults.

This is not yet a claim of production readiness:

- No real Paper server, GUI event integration, process-kill failpoint test, or full command execution/permission integration suite was available/run.
- The journal avoids acknowledging money/listing changes until item removal is saved and recovers pending intents on login, but actual Paper player-data save durability, death/disconnect edge cases, and repeated server crashes still require live-server testing.
- Shop GUI interaction and inventory delivery protections compile, but need live Paper testing for inventory event ordering, PDC persistence, and client/server edge cases.
- Auction bids are fixed-term escrowed bids with an automatic 24-hour expiry; there is no configurable duration, extension rule, or bid history command.

The project has passed unit/build validation only. It is not claimed production-ready because live Paper integration, restart/crash fault injection, GUI and command-permission integration tests remain outstanding:

```sh
./gradlew clean test build sourceZip
```
