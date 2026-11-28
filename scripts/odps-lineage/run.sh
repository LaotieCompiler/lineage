#!/usr/bin/env bash
# Generic ODPS/MaxCompute (Hive-compatible) lineage scanner.
#
#   ./scripts/odps-lineage/run.sh --input <project-dir> [--output output] [options]
#   ./scripts/odps-lineage/run.sh --ddl <dir> --sql <dir> [--sql <dir> ...] [--output output]
#   ./scripts/odps-lineage/run.sh <dialect> <ddl-dir> <sql-dir>... <out-dir>
#
# Relative paths are resolved against the caller's current directory. The scan
# writes CSV artifacts, then renders output/lineage-report.html (unless
# --no-render is given or Node.js is unavailable).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_ARGS=()
RENDER=1
for arg in "$@"; do
  case "$arg" in
    --no-render) RENDER=0 ;;
    *) JAVA_ARGS+=("$arg") ;;
  esac
done

mvn -q -f "$ROOT/pom.xml" -DskipTests compile
mvn -q -f "$ROOT/pom.xml" dependency:build-classpath \
  -Dmdep.outputFile="$ROOT/target/lineage-cp.txt"
CP="$(cat "$ROOT/target/lineage-cp.txt"):$ROOT/target/classes"
mkdir -p "$ROOT/target/odps-lineage-classes"
javac -cp "$CP" -d "$ROOT/target/odps-lineage-classes" \
  "$ROOT/scripts/odps-lineage/OdpsLineage.java"

if [[ ${#JAVA_ARGS[@]} -gt 0 ]]; then
  java -cp "$CP:$ROOT/target/odps-lineage-classes" OdpsLineage "${JAVA_ARGS[@]}"
else
  java -cp "$CP:$ROOT/target/odps-lineage-classes" OdpsLineage
fi

# --help / -h: the scanner already printed usage and exited successfully.
for arg in "${JAVA_ARGS[@]:-}"; do
  if [[ "$arg" == "-h" || "$arg" == "--help" ]]; then
    exit 0
  fi
done

# Resolve the output directory the same way the scanner does: --output wins,
# otherwise ./output; the legacy positional form keeps its last argument.
OUT="output"
if [[ ${#JAVA_ARGS[@]} -gt 0 ]]; then
  if [[ "${JAVA_ARGS[0]}" != -* ]]; then
    OUT="${JAVA_ARGS[${#JAVA_ARGS[@]}-1]}"
  else
    for ((i = 0; i < ${#JAVA_ARGS[@]}; i++)); do
      case "${JAVA_ARGS[$i]}" in
        -o|--output) OUT="${JAVA_ARGS[$((i + 1))]}" ;;
      esac
    done
  fi
fi

if [[ $RENDER -eq 1 ]] && command -v node >/dev/null 2>&1; then
  node "$ROOT/scripts/lineage-report/render.js" "$OUT" "$OUT/lineage-report.html"
elif [[ $RENDER -eq 1 ]]; then
  echo "Node.js not found; CSV artifacts are in $OUT. Install Node to render the HTML report." >&2
fi
