#!/usr/bin/env bash
# Starts headless simulations (bots playing the game, balancing, replays).
#   scripts/sim.sh                     print examples to copy
#   scripts/sim.sh --help              explain every command and option
#   scripts/sim.sh <command> --help    options of one command
#   scripts/sim.sh <command> [options] run it
# Recompiles first when sources changed since the last run.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
td="$root/scripts/td.sh"
stamp="$root/headless/target/sim-compiled"

commands=(run bench train balance stage gen-levels replay progress)

examples() {
  cat <<'EOF'
scripts/sim.sh — run headless simulations of Square Defense

Quick checks
  scripts/sim.sh bench --bot spender --level 1 --games 50
      the spender bot plays level 1 fifty times; prints wins, lives lost, gold
  scripts/sim.sh bench --bot spender --level 2 --games 50 --biome cube-farm
      level 2 in one biome (level 1 is always the plain starter biome)
  scripts/sim.sh bench --bot spender --level 1 --games 100 --rolled
      play the runs the game itself rolls (random biome and twist)
  scripts/sim.sh run --bot spender
      one full campaign, all levels, printed level by level

Watch games in the GUI (main menu > REPLAYS)
  scripts/sim.sh bench --bot spender --level 1 --games 50 --record 2
      saves the 2 best and 2 worst games as replays
  scripts/sim.sh run --bot spender --record
      saves the whole campaign as one replay

Balancing (writes data/balance/config.json and per-option CSVs next to it)
  scripts/sim.sh stage --level 1,2
      balance levels 1 and 2 against the spender with every unlock and draft pick
  scripts/sim.sh stage --level 1 --games 200 --first-low 0.7
      level 1 only, more games, require a 70% win rate
  scripts/sim.sh balance --pass local --level 3
      retune level 3's waves against a freshly trained explorer bot
  scripts/sim.sh bench --config data/balance/config.json --bot spender --level 1 --games 50
      play a balanced config instead of the bundled one

Learning bots
  scripts/sim.sh train --level 1 --batches 20
      train the explorer on level 1; its knowledge is saved and reused
  scripts/sim.sh bench --bot explorer-fresh --level 1 --games 50 --learn 10
      an explorer that learns from scratch for 10 batches, then is measured

Results
  scripts/sim.sh progress
      win rates, kills, leaks and game time per bot and level so far
  scripts/sim.sh replay data/replays/<file>.json
      check that a saved replay plays back exactly

Many commands in one go (one JVM, much faster)
  scripts/sim.sh batch scripts/stage-pipeline.txt
      run every line of a file as a command
  scripts/sim.sh repl
      type commands interactively; 'quit' to leave

Run 'scripts/sim.sh --help' for every command and option.
EOF
}

help_all() {
  cat <<'EOF'
usage: scripts/sim.sh [<command> [options]]

  (no arguments)          print examples
  --help                  this text: every command with all of its options
  <command> --help        the options of one command
  batch <file>            run each line of <file> as a command in one JVM ('#' starts a comment)
  repl                    read commands from the terminal until 'quit'

Bots (--bot): naive (fixed script), spender (spends all gold on the cheapest tower,
placed for the most road coverage), explorer (learns, keeps its knowledge between
runs in data/knowledge), explorer-fresh (learns from scratch, saves nothing).
Data goes to data/: replays, progress/games.csv, knowledge, balance.

Commands and their options
EOF
  local tmp
  tmp="$(mktemp)"
  for c in "${commands[@]}"; do echo "$c --help" >> "$tmp"; done
  # one JVM prints every command's own help, so this never drifts from the code
  "$td" batch "$tmp" | rename \
    | sed -e 's/^== \(.*\) --help$/\n========== \1 ==========/' -e '/^batch finished:/d'
  rm -f "$tmp"
}

# The program calls itself "headless"; here it is started as scripts/sim.sh.
rename() { sed -e "s|headless <command> --help|scripts/sim.sh <command> --help|" -e "s|usage: headless|usage: scripts/sim.sh|"; }

# Compile when any source changed since the last run (td.sh runs the compiled classes).
ensure_compiled() {
  if [ ! -f "$stamp" ] || [ -n "$(find core/src headless/src -name '*.scala' -newer "$stamp" -print -quit)" ] \
     || [ core/src/main/resources/config.json -nt "$stamp" ]; then
    echo "sources changed: compiling (sbt headless/compile)..." >&2
    sbt -batch -no-colors "headless/compile" > /dev/null
    mkdir -p "$(dirname "$stamp")"
    touch "$stamp"
  fi
}

case "${1:-}" in
  "")            examples ;;
  -h|--help|help) ensure_compiled; help_all ;;
  *)
    ensure_compiled
    case " $* " in
      *" --help "*|*" -h "*) "$td" "$@" | rename ;;
      *) exec "$td" "$@" ;;
    esac ;;
esac
