#!/bin/bash
# Paper server launcher.
set -euo pipefail
cd "$(dirname "$0")"

JAR="paper-26.2-103.jar"
PORT=25565

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
exec java -Xms2G -Xmx4G -jar "$JAR" --nogui
