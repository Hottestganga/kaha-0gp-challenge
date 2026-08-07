# Kaha 0GP Race

RuneLite Plugin Hub project for timed zero-GP loot races.

## Current milestone

This repository now contains a real RuneLite external-plugin project with:

- a RuneLite sidebar panel;
- an **Open Dashboard** button;
- room and dashboard settings;
- NPC and PvP loot-event matching;
- ground-item **Take** click matching; and
- inventory-increase confirmation before a pickup is submitted.

The public race server and hosted dashboard are the next milestone. Tracking is disabled by default until a valid HTTPS dashboard/server URL and room code are configured.

## Scoring rule

Players start at 0 GP. The future server will value accepted item IDs and quantities. The plugin submits an item only after RuneLite reports eligible NPC/PvP loot, the player clicks **Take**, and the matching quantity enters inventory.

## Development

Use IntelliJ IDEA and Java 11. Open the repository as a Gradle project, then run the Gradle `run` task.

## Data sent when tracking is enabled

Room code, logged-in RuneScape display name, item ID, quantity, and source (`NPC` or `PVP`) are sent to the configured HTTPS server.
