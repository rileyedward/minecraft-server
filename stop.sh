#!/bin/bash
# Gracefully stop the running Paper server from any terminal.
#
# Sends SIGTERM, which triggers Paper's shutdown hook: it saves every loaded
# chunk and player, closes the world, and releases world/session.lock. This is
# equivalent to typing `stop` in the server console.
set -euo pipefail
cd "$(dirname "$0")"

PORT=25565

pid="$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null || true)"

if [ -z "$pid" ]; then
  echo "No server running on port $PORT."
  exit 0
fi

echo "Stopping server (PID $pid)..."
kill -TERM $pid

while kill -0 $pid 2>/dev/null; do
  sleep 1
done

echo "Stopped. World saved and lock released."
