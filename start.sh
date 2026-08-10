#!/bin/bash
# Paper server launcher.
set -euo pipefail
cd "$(dirname "$0")"

# Version comes from paper.env — the single source of truth, shared with
# sample-plugins/build.gradle.kts so the server and the plugin API can't drift.
# shellcheck source=paper.env
source ./paper.env
JAR="paper-${PAPER_MC_VERSION}-${PAPER_BUILD}.jar"
PORT=25565

# ── Heap ──────────────────────────────────────────────────────────────────────
# Defaults are the production values. Override with environment variables rather
# than by editing them here:
#
#   MC_HEAP_MIN=1G MC_HEAP_MAX=2G ./start.sh
#
# Why not just edit the numbers? This script is rsynced to the droplet on every
# deploy (see docs/DEPLOYMENT.md), so a heap lowered for laptop testing would
# silently follow it to production and starve the real server.
HEAP_MIN="${MC_HEAP_MIN:-2G}"
HEAP_MAX="${MC_HEAP_MAX:-4G}"

if [ ! -f "$JAR" ]; then
  echo "Missing $JAR"
  echo
  echo "paper.env says version ${PAPER_MC_VERSION}, build ${PAPER_BUILD}, but that"
  echo "jar isn't here. Either download it (see README.md → Installing from"
  echo "scratch) or correct paper.env to match the jar you have:"
  ls -1 paper-*.jar 2>/dev/null | sed 's/^/  /' || echo "  (no paper jars found)"
  exit 1
fi

# ── Pre-flight: is a server already running? ──────────────────────────────────
# Minecraft locks world/session.lock so two processes can never write the same
# world at once. Starting a second one fails with a "already locked" stack trace
# that doesn't say what to actually do about it. Catch it here instead.
existing="$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null || true)"

if [ -n "$existing" ]; then
  echo "A server is already running on port $PORT (PID $existing)."
  echo "Only one server can use the world at a time."
  echo

  if [ -t 0 ]; then
    # Interactive terminal: offer to handle it.
    read -r -p "Stop it and restart? [y/N] " reply
    case "$reply" in
      [yY]*)
        echo "Stopping PID $existing (saving world)..."
        kill -TERM $existing
        while kill -0 $existing 2>/dev/null; do sleep 1; done
        echo "Stopped."
        echo
        ;;
      *)
        echo "Left it running. Nothing started."
        exit 1
        ;;
    esac
  else
    # Non-interactive (script, CI): don't guess, just explain.
    echo "Stop it first:  ./stop.sh"
    exit 1
  fi
fi

# `exec` replaces this shell with Java so signals (Ctrl+C, kill, stop.sh) reach
# the server directly. Without it the script would be signalled and Java would
# survive as an orphan still holding the world lock.
echo "Heap: -Xms$HEAP_MIN -Xmx$HEAP_MAX"
exec java -Xms"$HEAP_MIN" -Xmx"$HEAP_MAX" -jar "$JAR" --nogui
