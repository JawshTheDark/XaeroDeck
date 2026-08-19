# Security

XaeroDeck is built for anarchy-server players, so it assumes a hostile world.
This page is the threat model and the receipts. Don't trust it — verify it;
every claim below points at the code.

## "Is this a RAT?"

No. A RAT gives *someone else* remote access to *your* machine. XaeroDeck gives
*you* remote access to *your own* Minecraft client, from your own device, on
your own network. The differences that matter:

| RAT | XaeroDeck |
|---|---|
| Phones home to an attacker's server | Talks to exactly one thing: your companion app on your LAN |
| Hidden, obfuscated | Open source, unobfuscated, MIT |
| Controls are always-on and hidden | Side-effectful features are **off by default** and toggled in Meteor's GUI |
| Auth is whatever the attacker wants | Every control endpoint requires a pairing token that only exists in your `config/xaerodeck.json` |

## Architecture

Two components communicate, and only with each other:

1. **The mod** runs an HTTP server (`DeckServer.java`) bound on your machine,
   default port 8399. It makes **zero outbound connections**. The only network
   writes it ever performs are HTTP responses to clients that connected to it,
   and a UDP discovery beacon (`DiscoveryBeacon.java`) broadcast on your local
   subnet containing only the literal string `XAERODECK <port> <world-name>` —
   never the token, never coordinates.
2. **The Android app** connects to that server. It has no analytics, no
   accounts, no third-party SDKs, and talks to nothing else.

There is no relay, no cloud, no telemetry, no update check. `grep -rn "http"`
the source tree and you'll find only the LAN server itself.

## What's exposed, and to whom

- **Read-only endpoints** (map tiles, position, status): available to your LAN.
  If you play on networks you don't trust, firewall the port or don't open it —
  the app also works over `adb reverse` with no open port at all.
- **Control endpoints** (waypoint writes, Meteor toggling/settings, Baritone,
  chat send): require the `X-Deck-Token` header matching the token generated
  into `config/xaerodeck.json` on first run (`DeckConfig.java`). Without it,
  requests get `401`.
- **Opt-in gates on top of the token**: chat relay and remote control are also
  disabled entirely until you enable their modules in Meteor's XaeroDeck
  category (`ChatRelayModule`, `RemoteControlModule`) — the endpoints return
  `403` while off, token or not.

## Verifiable builds

Release artifacts are built by GitHub Actions from the tagged source — check
the `Build` workflow run attached to each release (from v0.1.1 of the mod and
v0.2.2 of the app onward). Or build it yourself: `./gradlew build`, byte-compare.

## Reporting

Found a hole? Open an issue or ping J_wsh. Given the audience, security reports
get fixed before features.
