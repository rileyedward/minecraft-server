# Deployment

Moving this server from your machine to a DigitalOcean droplet, and keeping it updated afterwards.

For day-to-day operation once it's running, see the [Operations Guide](OPERATIONS.md). For local
setup, see the [README](../README.md).

---

## The model

Two machines, with different jobs:

| | |
|---|---|
| **Your laptop** — development | Write plugins, `./gradlew deploy`, test against a throwaway world |
| **The droplet** — production | Runs continuously, holds the world your friends actually play in |

Three things move between them, and it's worth being precise about which direction each one goes:

- **Source code** travels through git, both ways. Nothing new here.
- **Built artifacts** — `CustomPlugins.jar`, community jars, the Paper jar — get pushed laptop →
  droplet with `rsync`. They're gitignored, so git can't carry them.
- **World and player data** live *only* on the droplet and never come back down, except as backups
  you deliberately pull.

> **The one rule that matters: never rsync `world/` or `plugins/<PluginName>/` up to the droplet.**
> Your local `world/` is a test world. Copying it over production overwrites everything your friends
> have built, and there is no undo. The deploy commands below list files explicitly rather than
> syncing directories, specifically so this can't happen by accident.

---

## Sizing the droplet

`start.sh` currently requests `-Xms2G -Xmx4G`. That's *heap only* — the JVM also needs a few hundred
megabytes beyond it, and the OS needs its own. A 4 GB droplet cannot run a 4 GB heap.

| Droplet | Set `-Xmx` to | Good for |
|---|---|---|
| 4 GB / 2 vCPU | `2500M` | A handful of friends, modest view distance |
| 8 GB / 4 vCPU | `6G` | Comfortable headroom, room to grow |

Minecraft servers are far more sensitive to **single-core CPU speed** than to core count — the main
game loop is one thread. DigitalOcean's Premium Intel/AMD droplets are meaningfully better here than
the Regular tier, and worth the difference.

Edit the heap in `start.sh` on the droplet if you size down:

```bash
exec java -Xms2G -Xmx2500M -jar "$JAR" --nogui
```

Ubuntu 24.04 LTS is a fine base image.

---

## First-time droplet setup

### 1. A user for the server

Don't run a public-facing game server as root.

```bash
sudo adduser --system --group --home /opt/minecraft-server minecraft
sudo mkdir -p /opt/minecraft-server
sudo chown minecraft:minecraft /opt/minecraft-server
```

### 2. Java 25

Paper 26.2 requires Java 25, which is newer than Ubuntu's default repositories carry. Add
Adoptium's:

```bash
sudo apt update && sudo apt install -y wget gpg apt-transport-https lsof
sudo mkdir -p /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
  $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-25-jre
java --version
```

If `temurin-25-jre` isn't published for your Ubuntu release yet, [SDKMAN](https://sdkman.io/) is the
fallback. You only need the JRE — plugins are built on your laptop, not here.

`lsof` is in that install list because `start.sh` and `stop.sh` both use it to find the running
server. Without it they silently fail to detect one.

### 3. Firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 25565/tcp      # Minecraft
sudo ufw allow 24454/udp      # Simple Voice Chat
sudo ufw enable
```

Port 24454/UDP is easy to forget and produces a confusing symptom — the server looks healthy,
players connect fine, and voice chat just never works. Drop that line if you remove the plugin.

Do **not** open the RCON port (25575) if you enable it below. It stays on localhost.

---

## The first deploy

From your laptop, in the project root:

```bash
# 1. Build your plugin fresh
cd custom-plugins && ./gradlew deploy && cd ..

# 2. Scripts, config, and the Paper jar (~59 MB — only needed on version changes)
rsync -avz paper-26.2-103.jar paper.env start.sh stop.sh server.properties.example \
  minecraft@YOUR_DROPLET_IP:/opt/minecraft-server/

# 3. Every plugin jar
rsync -avz plugins/*.jar minecraft@YOUR_DROPLET_IP:/opt/minecraft-server/plugins/
```

Then on the droplet, create the files that are deliberately *not* synced because they're personal or
contain secrets:

```bash
cd /opt/minecraft-server
echo "eula=true" > eula.txt              # accepting the EULA is your own legal act
cp server.properties.example server.properties
```

Edit `server.properties` for production — see the checklist below — then start it once by hand to
confirm it boots and every plugin loads:

```bash
./start.sh
```

Watch for `Done (…)! For help, type "help"`, and check that all six plugins are listed with no
stack traces. Then `stop`, and set it up as a service.

---

## Running it as a service

Running from an SSH session means the server dies when you disconnect. systemd fixes that, and gets
you restart-on-crash and start-on-boot.

Create `/etc/systemd/system/minecraft.service`:

```ini
[Unit]
Description=Paper Minecraft Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=minecraft
Group=minecraft
WorkingDirectory=/opt/minecraft-server
ExecStart=/opt/minecraft-server/start.sh
Restart=on-failure
RestartSec=15s
KillSignal=SIGTERM
TimeoutStopSec=180

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now minecraft
systemctl status minecraft
```

This works cleanly because of two things the project already does. `start.sh` ends in `exec java`,
so Java becomes the main process and receives signals directly instead of leaving an orphan holding
the world lock. And `SIGTERM` is exactly what triggers Paper's shutdown hook — the same graceful
save `stop.sh` relies on. `TimeoutStopSec=180` gives a large world time to finish writing before
systemd escalates to `SIGKILL`.

Day to day:

```bash
sudo systemctl restart minecraft    # after deploying new jars
sudo systemctl stop minecraft
journalctl -u minecraft -f          # live console output
```

### Console access

`journalctl -f` shows you everything the console prints, but you can't type into it. For commands
like `op`, `whitelist add`, or `tps`, enable RCON in `server.properties`:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=generate-something-long-here
```

Leave 25575 closed in `ufw` so it's reachable only from the droplet itself. Then use a small client
such as [mcrcon](https://github.com/Tiiffi/mcrcon):

```bash
mcrcon -H 127.0.0.1 -P 25575 -p yourpassword -t    # interactive console
```

`rcon.password` is one of the credentials `server.properties.example` blanks out — don't let it
reach git. See [OPERATIONS.md → The server.properties problem](OPERATIONS.md#the-serverproperties-problem).

---

## Routine deploys

Most deploys are just your own plugin:

```bash
cd custom-plugins && ./gradlew deploy && cd ..
rsync -avz plugins/CustomPlugins.jar minecraft@YOUR_DROPLET_IP:/opt/minecraft-server/plugins/
ssh minecraft@YOUR_DROPLET_IP 'sudo systemctl restart minecraft'
```

Adding or updating a community plugin:

```bash
rsync -avz plugins/NewPlugin-1.2.3.jar minecraft@YOUR_DROPLET_IP:/opt/minecraft-server/plugins/
# remove the old version if you're upgrading — two versions of one plugin will fail to load
ssh minecraft@YOUR_DROPLET_IP 'rm /opt/minecraft-server/plugins/NewPlugin-1.2.2.jar'
```

Then record it in the README's installed-plugins table, so the two machines don't quietly drift.

Updating Paper means pushing the new jar and the `paper.env` that names it, together — see
[OPERATIONS.md → Update Paper](OPERATIONS.md#update-paper-to-a-new-build), and back up the world
first, because world format migrations are one-way.

### What never gets pushed

| Stays on the droplet | Why |
|---|---|
| `world/` | The real one. Your local copy is a test world. |
| `plugins/<PluginName>/` | Live plugin data — LuckPerms permissions, Multiverse's world registry |
| `server.properties` | Production settings differ, and it holds generated secrets |
| `ops.json`, `whitelist.json`, `banned-*.json` | Real player state |
| `logs/`, `cache/`, `libraries/`, `versions/` | Generated locally on each machine |

The deploy commands name files explicitly for this reason. If you ever reach for `rsync -a` on a
whole directory, add `--dry-run` first and read every line.

---

## Before you invite people

`server.properties` on the droplet, not your laptop:

```properties
white-list=true              # currently false — without this, anyone who finds the IP can join
online-mode=true             # already correct; never turn this off on a public server
motd=Our SMP                 # what shows in the multiplayer list
difficulty=normal
view-distance=8              # each step down is real CPU and bandwidth savings
simulation-distance=6
max-players=20
```

`white-list=true` is the one genuine change from your local config. Port 25565 gets scanned
continuously by bots that catalogue open Minecraft servers; a whitelist is what turns "anyone who
finds it" into "people you added." Add your friends once the server is up:

```
whitelist add TheirUsername
op YourUsername
```

Then give yourself and any co-admins permissions through LuckPerms rather than `op` — that's what
you installed it for, and it degrades more gracefully than handing out full operator.

---

## Backups

Two layers, because they fail differently.

**DigitalOcean snapshots** cover the whole droplet — enable weekly backups on the droplet itself.
They protect against losing the machine, but they're coarse and you can't pull a single world out of
one quickly.

**World tarballs** are the ones you'll actually use. A nightly cron on the droplet:

```bash
# crontab -e, as the minecraft user
0 4 * * * cd /opt/minecraft-server && tar -czf backups/world-$(date +\%Y\%m\%d).tar.gz world/ plugins/*/ && find backups/ -name 'world-*.tar.gz' -mtime +14 -delete
```

That includes `plugins/*/` so LuckPerms permissions and Multiverse's world registry come back with
the world — restoring a world without them leaves you with everyone's builds and nobody's
permissions.

This runs while the server is live, which risks catching a half-written chunk. For a friends server
that tradeoff is usually right. If you want certainty, the hot-backup procedure using `save-off` /
`save-on` is in [OPERATIONS.md → Back up the world](OPERATIONS.md#back-up-the-world) — just never
skip the `save-on`.

Pull a copy down periodically so the backups don't live only on the machine they're protecting:

```bash
rsync -avz minecraft@YOUR_DROPLET_IP:/opt/minecraft-server/backups/ ./backups/
```

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Unable to access jarfile` on boot | `paper.env` and the pushed jar disagree — `start.sh` prints which it expected |
| Service restarts in a loop | `journalctl -u minecraft -n 100` — usually EULA not accepted, or a plugin failing to load |
| Players can't connect, server looks fine | `ufw` missing 25565/tcp, or `white-list=true` and they're not on it |
| Voice chat never connects | 24454/UDP not open |
| `already locked` after a crash | A previous process still holds it — `sudo systemctl stop minecraft`, confirm with `lsof -i :25565` |
| Plugin loaded locally, fails on the droplet | Version drift — check the README table against `plugins/` on the droplet |
| Server killed under load | Heap larger than the droplet's RAM. Check `journalctl -k | grep -i oom` |
