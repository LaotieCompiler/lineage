#!/usr/bin/env bash
#
# Download the 22 TPC-H queries for TpchCorpusTest. The queries are covered by the
# TPC End User License Agreement and are therefore not committed to this repository.
#
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/target/tpch/queries"
base="https://raw.githubusercontent.com/duckdb/duckdb/main/extension/tpch/dbgen/queries"

mkdir -p "$dest"
for i in $(seq -w 1 22); do
    curl -fsSL "$base/q$i.sql" -o "$dest/q$i.sql"
done

echo "Downloaded 22 TPC-H queries to $dest"
echo "Run: mvn test -Dtest=TpchCorpusTest"
