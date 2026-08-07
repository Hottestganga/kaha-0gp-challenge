# 0GP Race

**Developer: Ganga**

0GP Race is a RuneLite external plugin for timed zero-GP races. Players earn score from legitimate in-race wealth generation while imported value from outside the race is debited by the ledger.

## v1.1b local multiplayer alpha

The plugin remains a normal RuneLite plugin. This build adds a temporary local multiplayer backend only for development testing before the API is hosted publicly.

Current features include:

- Create Race / Join Race / Leave Race from the RuneLite sidebar.
- Variable race duration and starting GP allowance.
- Logged-in playtime timer that pauses on logout.
- NPC/PvP loot, pickpocket/container, thieving and world-spawn provenance foundations.
- Race-owned inventory/bank ledger.
- Pre-race bank withdrawals debit score and returning imported items refunds the debit.
- Negative balance disqualification.
- Timestamped activity log and race statistics.
- Live multiplayer room/player list with score, timer state and login state syncing roughly every two seconds.

## Local multiplayer test server

Open `local-server` and run `run-server.bat`. Keep that window open while testing.

In RuneLite plugin settings use:

- Multiplayer enabled: ON
- Multiplayer API URL: `http://127.0.0.1:8787`

Create a race on one development client, then enter that room code in Join Race on another client/account. See `V1.1B-MULTIPLAYER-TEST.txt` for two-PC/LAN instructions.

The local server is development-only. It has no authentication and stores rooms in memory. v1.1c will move this API to hosted infrastructure.

## Development

Use IntelliJ IDEA with the RuneLite external-plugin project and Java 11 target. Run:

`./gradlew.bat clean build`

then:

`./gradlew.bat run`

## v1.1c hosted multiplayer alpha
The RuneLite plugin can now use the included deployable `hosted-server`. Deploy it to a public host, then paste its HTTPS URL into **Multiplayer API URL** in the plugin settings. See `hosted-server/README.md`.
