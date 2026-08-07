# Building a Custom Admin Panel

Design notes for a web panel to manage this server. Nothing here is built yet — this is the
research and the shape of the decision.

---

## The headline: you don't need to build the server half

Minecraft ships a **Server Management Protocol** (added in snapshot 25w35a). It's already in your
`server.properties`, currently switched off:

```properties
management-server-enabled=false
management-server-host=localhost
management-server-port=0
management-server-secret=UaraA6UBH03SuuRlfsDVAzGvl7wqHB6Ej6ehivP0
management-server-tls-enabled=true
```

That secret was auto-generated on first boot. It's a real credential — it's why `.gitignore`
excludes `server.properties`.

**What it is:** a **WebSocket** endpoint speaking **JSON-RPC 2.0**, authenticated with a bearer
token. Not a REST API — a persistent bidirectional connection, which means the server pushes
events to you rather than you polling for them.

```json
{"method":"minecraft:allowlist/add","id":1,"params":[[{"name":"jeb_"}]]}
```

**What it can do:**

| Area | Capability |
|---|---|
| Players | List connected, kick |
| Allowlist | Get, set, add, remove, clear |
| Operators | Query, add, remove |
| Bans | Player bans with expiry; IP bans |
| Server | Status, save, stop, broadcast system messages |
| Settings | Difficulty, gamemode, max players, MOTD, view distance, ~20 more — at runtime |
| Gamerules | Query and update |

**Events it pushes:** player join/leave, allowlist and ban changes, operator changes, gamerule
updates, and lifecycle events (started, stopping, saving, saved).

That last part is what makes it worth using. A panel built on this reflects reality live, with no
polling loop and no log scraping.

---

## The three options

### 1. Management Protocol — the default choice

Built into the server, no plugin, structured typed data, real-time events.

Limits: it does what it does. There is no "give player X an item" or "teleport to spawn", and it
knows nothing about your plugin's data.

### 2. RCON — the legacy option

Also built in (`enable-rcon`, port 25575). A remote console: you send command strings, you get the
console output back as text.

```
rcon> list
There are 2 of a max of 20 players online: alice, bob
```

Its strength is total reach — anything typeable in the console works, including your own plugin
commands. Its weakness is that everything is unstructured text you must parse, and the format is
not a stable contract.

Worth enabling **alongside** the Management Protocol as an escape hatch for the arbitrary-command
case. Set a strong `rcon.password`; the protocol is unencrypted, so never expose it beyond
localhost.

### 3. A custom plugin exposing your own API

This is the only way to reach anything game-specific: your `PlayerStatsSample` counters, custom
items, a minigame's state. You'd embed a small HTTP server in a plugin and define endpoints for
exactly what you need.

Most real panels end up here eventually, but only for the parts the first two can't reach.

### Choosing

| Need | Use |
|---|---|
| Players, bans, ops, allowlist, settings, live events | Management Protocol |
| Run an arbitrary console command | RCON |
| Your plugin's own data | Custom plugin API |
| Start the server when it's stopped | Neither — see below |

**The gap worth planning for:** every option above talks to a *running* server. None of them can
start one that's stopped, or tail logs, or edit `server.properties`. Those need something at the
OS level — your backend running `./start.sh`, or a systemd/launchd unit it controls. Decide early
whether the panel manages the *process* or just the *game*, because it changes the deployment
shape entirely.

---

## Shape of it

```
┌─────────────┐   HTTPS + your auth   ┌──────────────┐
│   Browser   │ ────────────────────► │   Backend    │
│  (React)    │ ◄──────────────────── │  (Node/TS)   │
└─────────────┘   WebSocket (live)    └──────┬───────┘
                                             │
                    ┌────────────────────────┼──────────────────────┐
                    │ ws + JSON-RPC          │ RCON                 │ HTTP
                    ▼                        ▼                      ▼
          ┌──────────────────┐    ┌──────────────────┐   ┌──────────────────┐
          │ Management       │    │ RCON :25575      │   │ Your plugin's    │
          │ Protocol         │    │ (arbitrary cmds) │   │ own endpoints    │
          └──────────────────┘    └──────────────────┘   └──────────────────┘
                    └────────────── all on localhost ────────────────┘
```

**The backend is not optional.** The management secret is a full-control credential — putting it
in browser JavaScript hands the server to anyone who opens devtools. The backend holds the secret,
authenticates *your* users separately, and is the only thing that talks to Minecraft.

It also lets you keep one persistent connection to the server and fan events out to however many
browser tabs are open.

### Stack

Node/TypeScript is the path of least resistance: [`mc-server-management`](https://github.com/aternosorg/mc-server-management)
already wraps the protocol.

```typescript
import {WebSocketConnection, MinecraftServer} from 'mc-server-management';

const connection = await WebSocketConnection.connect(
  "wss://localhost:<management-port>",
  process.env.MC_MANAGEMENT_SECRET
);
const server = new MinecraftServer(connection);

const status = await server.getStatus();
server.on(Notifications.PLAYER_JOINED, (player) => { /* push to UI */ });
```

Any language works — it's just WebSocket and JSON-RPC — but this saves writing the method layer.

### Build order

1. **Enable the protocol.** Set `management-server-enabled=true` and a real `management-server-port` (it's `0` now, meaning disabled). Keep `management-server-host=localhost`.
2. **Backend connects, one read-only call.** Prove auth works via `getStatus()`.
3. **Read-only dashboard.** Online players, TPS, uptime. Useful immediately, and can't break anything.
4. **Subscribe to events.** Live join/leave without polling.
5. **First write action.** Kick a player — small, reversible, exercises the whole path.
6. **Grow.** Bans, allowlist, ops, settings, gamerules.
7. **Add RCON** when you first need a command the protocol lacks.
8. **Add a plugin endpoint** when you first need your own game data.

Stopping after step 3 already gives you something worth having.

---

## Security

This panel controls a server and, through it, a machine. The management secret is equivalent to
full admin.

- **Keep it in an environment variable**, not in code and not in git. `server.properties` is already gitignored — keep it that way.
- **Bind to localhost.** `management-server-host=localhost` means only processes on this machine can reach it. Leave it there and let your backend be the only door.
- **Never expose the raw protocol to the internet.** If you need remote access, put your authenticated backend on the public side, or reach the machine over a VPN/SSH tunnel — not the Minecraft port.
- **Keep `management-server-tls-enabled=true`.** Bearer tokens over plaintext are readable by anything on the path.
- **Authenticate your own users separately.** The management secret authenticates the *backend to Minecraft*; it says nothing about who is using your panel.
- **Log every write action** with who performed it. When something odd happens in game, you'll want to know whether the panel did it.
- **RCON is unencrypted** — localhost only, always, and only with a strong password.

---

## Sources

- [Minecraft Server Management Protocol — Minecraft Wiki](https://minecraft.wiki/w/Minecraft_Server_Management_Protocol)
- [aternosorg/mc-server-management](https://github.com/aternosorg/mc-server-management) — TypeScript client
- [server.properties — PaperMC Docs](https://docs.papermc.io/paper/reference/server-properties/)
- [server.properties — Minecraft Wiki](https://minecraft.wiki/w/Server.properties)
