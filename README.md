# ✈ XaeroDeck

**Turn your Minecraft client into a command deck.** XaeroDeck is a Fabric mod that
streams your [Xaero's WorldMap](https://modrinth.com/mod/xaeros-world-map) — live
position, radar, chat, stats, and seed intelligence — to a tablet or phone on your
LAN, and lets that device fly your character back.

Built for anarchy servers. Zero external connections. Everything dangerous is
opt-in and token-locked.

> 📱 Companion app: **[XaeroDeck-App](https://github.com/JawshTheDark/XaeroDeck-App)** (Android, tactical-HUD UI)

---

## Table of contents

- [What it does](#what-it-does)
- [Install](#install)
- [The Meteor modules](#the-meteor-modules)
- [Live map streaming](#live-map-streaming)
- [Seed intelligence](#seed-intelligence)
- [Autopilot](#autopilot)
- [Xaero map integration](#xaero-map-integration)
- [Chat, notifications and telemetry](#chat-notifications-and-telemetry)
- [HTTP API](#http-api)
- [Security model](#security-model)
- [Building](#building)

---

## What it does

| | Feature | Details |
|---|---|---|
| 🗺 | **Live map streaming** | Pixel-identical Xaero map tiles over HTTP with push invalidation — terrain appears on your tablet within ~100 ms of rendering in-game |
| 📍 | **Position + radar** | 20 Hz position stream, entity radar (players, Meteor friends, mobs in Xaero's colors), potion effects with countdowns, speed/ping/TPS/HP/totem/elytra telemetry |
| 🌍 | **All dimensions** | Overworld, nether (including cave-layer caches), and end — browsable remotely and offline, with three zoom levels of tile pyramid |
| 🔮 | **Worldgen oracle** | Fingerprints which MC version generated each chunk and flags player-modified terrain in red, by simulating worldgen from the world seed |
| 🏛 | **Structure overlay** | Chunkbase-class seed predictions: 18 structure types, strongholds, end gateways, slime chunks — each individually toggleable |
| 🌱 | **Seed auto-capture** | Stores seeds per server; picks them up automatically when [SeedcrackerX](https://modrinth.com/mod/seedcrackerx) cracks one |
| ✈ | **Autopilot** | Steering-only elytra autopilot for ElytraFly users: fly-to, multi-point routes, looping orbits, smooth spirals, area automapping — with corner anticipation and speed-aware turn leads |
| 🎛 | **Meteor remote control** | Toggle any Meteor module and edit its settings (sliders, dropdowns, the works) from the companion device |
| 💬 | **Chat relay** | Read and send chat remotely with full Minecraft color rendering — strictly opt-in |
| 🏠 | **/sethome watcher** | Opt-in: auto-creates a Xaero waypoint whenever you run `/sethome` |
| 📡 | **Auto-discovery** | UDP beacon so companion devices find your PC without typing an IP |
| 🔐 | **Pairing token** | All control endpoints require a generated secret; side-effectful features are additionally opt-in |

## Install

1. Drop `xaerodeck-*.jar` from [Releases](https://github.com/JawshTheDark/XaeroDeck/releases)
   into your mods folder. **Requires:** Fabric for Minecraft 26.2, Fabric API, and
   Xaero's WorldMap. **Plays best with:** Xaero's Minimap (waypoints),
   [Meteor Client](https://meteorclient.com) (modules + remote control), Baritone
   (walking goto), SeedcrackerX (seed capture).
2. Install the [companion app](https://github.com/JawshTheDark/XaeroDeck-App/releases)
   on an Android device (built for tablets).
3. Same WiFi + allow the port through your PC firewall (default 8399 — e.g.
   `sudo ufw allow 8399/tcp`). The app finds your PC automatically via the
   discovery beacon.
4. For control features, copy the `token` from `config/xaerodeck.json` into the
   app's CONFIG panel. Without it, other devices on your network can see that a
   server exists and nothing more.

## The Meteor modules

XaeroDeck registers its own **XaeroDeck category** in Meteor's GUI:

| Module | Default | What it gates |
|---|---|---|
| `map-server` | on (auto-start) | The HTTP server itself; port + stream rate + `/sethome` watcher settings |
| `deck-autopilot` | off | All flight: fly-to, routes, orbits, spirals — plus turn-speed / pitch / arrival tuning |
| `remote-control` | off | Meteor module toggling/settings and Baritone from companion devices |
| `chat-relay` | off | Chat reading *and* sending from companion devices |

No Meteor? A Mod Menu config screen and `config/xaerodeck.json` cover the basics.

## Live map streaming

- Tiles are rendered CPU-side through Xaero's own export pipeline — colors are
  pixel-identical to the in-game map, including XaeroPlus highlights (NewChunks,
  portals) baked in.
- **Push invalidation**: a mixin fires the instant Xaero rebuilds a tile, and the
  changed region IDs ride the SSE stream — companion devices refetch exactly what
  changed. ETag/304 caching keeps unchanged tiles free.
- **Three-level tile pyramid**: 512-block regions, 2048-block overviews, and
  4096-block super-tiles for deep zoom-outs across millions of blocks.
- **Every dimension**: non-current dimensions are served straight from Xaero's
  on-disk caches, including per-layer nether cave caches (newest layer wins).

## Seed intelligence

Seeds are stored **per server** (keyed by Xaero world id) and captured
automatically from SeedcrackerX chat output — or entered manually in the app.
The [community seed database](https://github.com/19MisterX98/SeedcrackerX) has
most big servers already.

- **ERA overlay** — simulates terrain per configured MC version list
  (e.g. `1.18.2`, `1.19.2`) and colors each chunk by which era's worldgen it
  matches. Amber pockets in blue terrain = launch-era exploration. Red =
  **player-modified**: heights or surface blocks no natural generation explains.
- **MARKERS overlay** — structure positions computed from the seed with biome
  verification: fortress, bastion, monument, mansion, village, outpost, buried
  treasure, desert/jungle temples, witch hut, igloo, shipwreck, ocean ruin,
  ruined portals (both dimensions), end city — plus all 128 **strongholds**
  (computed once in the background), the fixed **end gateway** ring, and
  **slime chunks** (computed on-device from the seed). Each type toggles
  individually, and the **generation version is selectable per server**
  (CONFIG → STRUCTURE VERSION in the app) — pick whatever version the server's
  terrain was generated on, just like the version dropdown on Chunkbase.
- ⚠ Custom worldgen (Terralith etc.) breaks era/structure simulation — but slime
  chunks and end gateways are pure seed RNG and stay accurate.

## Autopilot

Steering-only by design: it eases your **look angle** (like turning the mouse)
and virtually holds **W** while [Meteor ElytraFly](https://meteorclient.com)
provides propulsion. No movement packets of its own — it composes with whatever
flight you already use, and auto-enables/restores ElytraFly around flights.

- **Fly-to** a tapped point or waypoint
- **Routes**: multi-point paths, optional infinite loop
- **Orbits**: ellipse drawn/stretched on the companion app, flown as a loop
- **Spirals**: smooth Archimedean sweep with exact ring spacing — the classic
  basefinding pattern with no corners to overshoot
- **Automap**: lawnmower coverage of a framed area at render-distance spacing
- **Flight quality**: measures your real speed, computes turn radius, and hands
  off to the next leg early (`R·tan(θ/2)`) so flown arcs hug corners; arrival
  radius auto-scales to route spacing so tiny orbits work
- On-ground it releases all controls — you only ever hand over the stick while
  gliding, and `cancel` returns it instantly

## Xaero map integration

Right-click anywhere on Xaero's fullscreen map:

```
✈ Deck: Fly here
✈ Deck: Orbit here
✈ Deck: Spiral here
✈ Deck: Cancel flight     (while flying)
```

Same engine, same opt-in gates as the app.

## Chat, notifications and telemetry

- **Notifications**: client-system messages (Meteor's notifier, other mods) are
  captured with full color spans and streamed to companion devices — with a quiet
  window after server joins so mod init spam doesn't flood your tablet.
- **Chat relay** (opt-in): full chat both ways with Minecraft formatting.
- **Telemetry**: speed (bps), ping, TPS estimate, HP, totem count, elytra
  durability, active potion effects with countdowns.
- **Death alert**: position-stamped, vibrates the companion device.

## HTTP API

All endpoints are LAN-only, served by the mod. 🔒 = requires `X-Deck-Token`.

| Endpoint | Description |
|---|---|
| `GET /api/status` | Position, stats, effects, radar, autopilot state |
| `GET /api/stream` | SSE: status at configured Hz + notifications, chat, dirty regions |
| `GET /api/tile/{dim}/{x}/{z}.png` | 512-block region tile (ETag) |
| `GET /api/overview[2]/{dim}/{x}/{z}.png` | 2048 / 4096-block overview tiles |
| `GET /api/dimensions`, `/api/regions` | Dimension + explored region listing |
| `GET /api/oracle/tile/…`, `/api/oracle/legend` | Worldgen oracle overlays |
| `GET`/🔒`POST /api/oracle/config` | Per-world seed + era versions |
| `GET /api/seed/features?dim=&x0=&z0=&x1=&z1=` | Structure predictions |
| `GET`/🔒`POST`/🔒`DELETE /api/waypoints` | Xaero waypoint read / create / delete |
| 🔒 `POST /api/baritone` | `goto`, `flyto`, `route`, `spiral`, `area`, `cancel` |
| 🔒 `GET/POST /api/meteor/*` | Module list, toggle, settings read/write |
| 🔒 `GET/POST /api/chat` | Chat log + send (also gated by chat-relay opt-in) |
| `GET /api/notifications?after=` | Notification ring buffer |

## Security model

Read [SECURITY.md](SECURITY.md) — short version: one LAN server, zero outbound
connections, discovery beacon carries no secrets, all control endpoints demand
the pairing token, and everything side-effectful is *additionally* opt-in via
Meteor modules. Release jars are built by GitHub Actions from tagged source.

## Building

```bash
./gradlew build
```

Java 25. All dependencies (Xaero's maps, Mod Menu, Meteor, Baritone, seedfinding
libraries) resolve from public mavens — no manual jar wrangling. Output lands in
`build/libs/`.

## License

MIT. Xaero's mods, Meteor, and Baritone are their authors' — this mod ships
none of their code.
