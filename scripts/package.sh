#!/usr/bin/env bash
# Packages the game for Linux into target/dist (see project/Dist.scala).
# Needs JDK 17+ and sbt. On Windows use scripts\package.cmd instead.
set -euo pipefail
cd "$(dirname "$0")/.."
sbt -batch dist
