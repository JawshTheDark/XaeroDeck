# XaeroDeck

Client-side Fabric mod (Minecraft 26.2) that streams your Xaero's WorldMap, live position,
entity radar, chat, and stats to companion devices over LAN — built for use with the
[XaeroDeck Android app](https://github.com/JawshTheDark/XaeroDeck-App).

## Features

- Map region tiles rendered from Xaero's own data (pixel-identical, CPU-only) with
  push invalidation the moment terrain changes, plus zoomed-out overview tiles
- 20Hz position/stats stream over SSE: speed, ping, TPS, HP, totems, elytra durability,
  active potion effects with countdowns
- Entity radar (players, friends via Meteor, hostile/neutral/passive mobs with
  Xaero-matching colors)
- Waypoints: list/add/delete from companion devices, opt-in `/sethome` auto-waypoints
- Opt-in chat relay (read + send) with full Minecraft color/formatting preserved
- Opt-in remote control: Meteor module toggling + settings editing, Baritone goto
- Meteor integration: own "XaeroDeck" category with map-server, chat-relay and
  remote-control modules; Mod Menu config screen otherwise
- Pairing-token auth on all control endpoints; UDP auto-discovery beacon

## Building

```
./gradlew build
```

Requires Java 25. All compile-time dependencies (Xaero's maps, Mod Menu, Meteor,
Baritone) resolve automatically from the Modrinth and Meteor mavens. Output lands
in `build/libs/`.

At runtime the mod requires [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)
1.44.2 and suggests [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) 26.4.2
(waypoints), [Meteor Client](https://meteorclient.com) (modules/remote control), and
Baritone (goto).

## HTTP API

Serves on port 8399 (configurable). `GET /api/status`, `/api/stream` (SSE),
`/api/tile/{dim}/{rx}/{rz}.png`, `/api/overview/...`, `/api/dimensions`, `/api/regions`,
`/api/waypoints` (GET/POST/DELETE), `/api/chat` (GET/POST, opt-in),
`/api/baritone` (POST, opt-in), `/api/meteor/*` (opt-in). Control endpoints require the
`X-Deck-Token` header — token lives in `config/xaerodeck.json`.
