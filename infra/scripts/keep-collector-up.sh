#!/bin/zsh
# Start Colima and the collector, then keep the Mac awake while it is plugged in.
# Closing the lid can still sleep the machine. Leave it open and on power.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
cd "$ROOT"
colima start
docker compose up -d
exec caffeinate -ims
