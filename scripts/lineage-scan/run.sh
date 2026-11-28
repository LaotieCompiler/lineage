#!/usr/bin/env bash
# SQL lineage scanner.
#
#   ./scripts/lineage-scan/run.sh --input <project-dir> [--output output] [options]
#   ./scripts/lineage-scan/run.sh --ddl <dir> --sql <dir> [--sql <dir> ...] [--output output]
#   ./scripts/lineage-scan/run.sh <dialect> <ddl-dir> <sql-dir>... <out-dir>
#
# Relative paths are resolved against the caller's current directory. The scan
# writes CSV artifacts plus a static HTML report that reads lineage-data.js.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

mvn -q -f "$ROOT/pom.xml" -DskipTests compile
mvn -q -f "$ROOT/pom.xml" dependency:build-classpath \
  -Dmdep.outputFile="$ROOT/target/lineage-cp.txt"
CP="$(cat "$ROOT/target/lineage-cp.txt"):$ROOT/target/classes"

exec java -cp "$CP" com.laotie.cli.LineageScan "$@"
