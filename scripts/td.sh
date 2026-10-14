#!/usr/bin/env bash
# One headless JVM for any number of commands, so nothing restarts between runs.
#   scripts/td.sh repl                 read commands from the terminal until 'quit'
#   scripts/td.sh batch <file>         run every line of a script, one per command
#   scripts/td.sh bench --bot spender ...   any single command, same as sbt headless/run
# Commands are the same as in `sbt "headless/run ..."`. Quote arguments that have spaces.
# The classpath is cached in headless/target/classpath.txt; the first run creates it.
# Delete that file when library dependencies change. After editing code run
#   sbt "headless/compile"
# first; the cached classpath points at the same compiled classes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cp_file="$root/headless/target/classpath.txt"
cd "$root"
if [ ! -f "$cp_file" ]; then
  echo "first run: building headless and caching its classpath (one-off, takes a minute)" >&2
  mkdir -p "$(dirname "$cp_file")"
  sbt -batch "headless/compile" "export headless/Test/fullClasspath" | tail -1 > "$cp_file"
fi
exec java -cp "$(cat "$cp_file")" td.HeadlessMain "$@"
