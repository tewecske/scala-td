#!/usr/bin/env bash
# Opens the simulator window: set up a start point (level, unlocked towers,
# talents, draft picks, lives lost), let a bot play it many times and save the
# setup as a file that `scripts/sim.sh setup <file>` runs too.
#   scripts/simulator.sh [setup file]
set -euo pipefail
cd "$(dirname "$0")/.."
if [ $# -gt 0 ]; then sbt -batch "editor/runMain td.SimulatorApp $(realpath "$1")"; else sbt -batch "editor/runMain td.SimulatorApp"; fi
