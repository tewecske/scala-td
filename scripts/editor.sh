#!/usr/bin/env bash
# Opens the config editor (a separate window, not the game).
# It opens ./config.json if there is one, else the bundled config; Save writes
# ./config.json, which the game loads at start and when you press R.
#   scripts/editor.sh [config file]
set -euo pipefail
cd "$(dirname "$0")/.."
if [ $# -gt 0 ]; then sbt -batch "editor/run $(realpath "$1")"; else sbt -batch editor/run; fi
