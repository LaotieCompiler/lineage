#!/usr/bin/env python3
"""Optional differential check: golden expectations vs SQLGlot column lineage.

This is a second opinion, not an oracle. Only single-statement SELECT cases with a
catalog are compared. Differences (and SQLGlot parse/resolve errors) are printed for
manual review; the script exits 0 unless --fail-on-diff is passed.

Prerequisites:
    python3 -m venv target/sqlglot-venv
    target/sqlglot-venv/bin/pip install sqlglot

Usage:
    target/sqlglot-venv/bin/python scripts/differential-sqlglot.py
    target/sqlglot-venv/bin/python scripts/differential-sqlglot.py --fail-on-diff
"""

import argparse
import glob
import json
import os
import re
import sys

try:
    from sqlglot import exp, parse, parse_one
    from sqlglot.lineage import lineage
except ImportError:
    sys.exit("sqlglot is not installed; see the module docstring for setup")

DIALECTS = {
    "postgresql": "postgres",
    "mysql": "mysql",
    "mariadb": "mysql",
    "hive": "hive",
    "antspark": "spark",
    "sqlserver": "tsql",
    "oracle": "oracle",
    "sqlite": "sqlite",
    "db2": "db2",
    "hsql": "hive",
    "derby": "postgres",
}

EDGE = re.compile(r"^(.*?) -> (.*)$")
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*$")


def normalize(column):
    parts = []
    for part in column.strip().split("."):
        parts.append(part.strip().strip("`\"[]").lower())
    return ".".join(parts)


def short_column(column):
    parts = normalize(column).split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else parts[0]


def table_name(name):
    return normalize(str(name).split(".")[-1])


def schema_from_ddl(ddl, dialect):
    schema = {}
    for statement in parse(ddl, dialect=dialect):
        if not isinstance(statement, exp.Create) or statement.kind.upper() != "TABLE":
            continue
        target = statement.this
        if isinstance(target, exp.Schema):
            name = table_name(target.this)
            columns = {}
            for element in target.expressions:
                if isinstance(element, exp.ColumnDef):
                    kind = element.args.get("kind")
                    columns[element.name] = kind.sql() if kind is not None else "INT"
            schema[name] = columns
    return schema


def edge_sources(instructions, target):
    incoming = {}
    for line in instructions:
        match = EDGE.match(line)
        if not match:
            continue
        source, destination = match.group(1).strip(), match.group(2).strip()
        if "*" in source or "*" in destination:
            continue
        incoming.setdefault(destination, set()).add(source)

    leaves = set()
    seen = set()
    stack = [target]
    while stack:
        node = stack.pop()
        if node in seen:
            continue
        seen.add(node)
        parents = incoming.get(node)
        if parents:
            stack.extend(parents)
        else:
            leaves.add(short_column(node))
    return leaves


def output_candidates(column, sql, dialect):
    """Names SQLGlot may use for an output column (case and quoting differ)."""
    candidates = [column]
    try:
        tree = parse_one(sql, dialect=dialect)
    except Exception:  # noqa: BLE001 - lineage will report the real error
        return candidates
    for select in tree.selects:
        if select.alias_or_name.lower() == column.lower():
            candidates.append(select.alias_or_name)
            if isinstance(select, exp.Column):
                candidates.append(select.sql())
    seen = set()
    return [name for name in candidates
            if not (name.lower() in seen or seen.add(name.lower()))]


def sqlglot_sources(column, sql, schema, dialect):
    last_error = None
    for candidate in output_candidates(column, sql, dialect):
        try:
            node = lineage(candidate, sql, schema=schema, dialect=dialect)
        except Exception as error:  # noqa: BLE001 - try the next spelling
            last_error = error
            continue
        leaves = set()
        for item in node.walk():
            if item.downstream:
                continue
            name = short_column(item.name)
            if name != short_column(column) and not name.startswith("_"):
                leaves.add(name)
        return leaves
    raise last_error if last_error else RuntimeError("no output name for " + column)


def comparable(case):
    script = case.get("script", "").strip()
    first = re.split(r"\s+", script, maxsplit=1)[0].lower() if script else ""
    if first not in ("select", "with"):
        return False, "not a single SELECT"
    expect = case.get("expect") or []
    if len(expect) != 1 or expect[0].get("error"):
        return False, "not one successful statement"
    if ";" in script.rstrip().rstrip(";"):
        return False, "multiple statements"
    return True, ""


def compare_case(case):
    dialect = DIALECTS.get(case.get("dialect", "postgresql"))
    if dialect is None:
        return None, "unsupported dialect"
    schema = {}
    if case.get("catalog"):
        try:
            schema = schema_from_ddl(case["catalog"], dialect)
        except Exception as error:  # noqa: BLE001 - report and continue
            return None, "sqlglot schema error: %s" % error
    instructions = case["expect"][0].get("instructions") or []
    targets = sorted({
        line.split(" -> ", 1)[1].strip()
        for line in instructions
        if " -> " in line and line.split(" -> ", 1)[1].strip().startswith("temp0.")
    })
    comparisons = []
    if not targets:
        # A table-level wildcard: expand it through the catalog to compare columns.
        for source in sorted({
            line.split(" -> ", 1)[0].strip()
            for line in instructions
            if " -> " in line and line.split(" -> ", 1)[1].strip() == "temp0"
        }):
            if not source.endswith(".*"):
                continue
            table = table_name(source[:-2])
            for column in sorted(schema.get(table, {})):
                ours = {short_column("%s.%s" % (table, column))}
                try:
                    theirs = sqlglot_sources(column, case["script"], schema, dialect)
                except Exception as error:  # noqa: BLE001 - report and continue
                    comparisons.append((column, ours, "ERROR: %s" % error))
                    continue
                comparisons.append((column, ours, theirs))
        if not comparisons:
            return None, "table-level lineage without expandable columns"
    for target in targets:
        column = target.split(".", 1)[1]
        if not IDENTIFIER.match(column):
            continue
        ours = edge_sources(instructions, target)
        try:
            theirs = sqlglot_sources(column, case["script"], schema, dialect)
        except Exception as error:  # noqa: BLE001 - report and continue
            comparisons.append((column, ours, "ERROR: %s" % error))
            continue
        comparisons.append((column, ours, theirs))
    return comparisons, ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--golden", default="src/test/resources/golden")
    parser.add_argument("--fail-on-diff", action="store_true")
    args = parser.parse_args()

    if not os.path.isdir(args.golden):
        sys.exit("golden directory not found: %s" % args.golden)

    cases = 0
    compared = 0
    columns = 0
    matches = 0
    diffs = 0
    empty = 0
    skipped = []
    errors = []
    for path in sorted(glob.glob(os.path.join(args.golden, "*.json"))):
        with open(path, encoding="utf-8") as handle:
            group = json.load(handle)
        if isinstance(group, dict):
            group = [group]
        for case in group:
            cases += 1
            name = case.get("name", "<unnamed>")
            ok, reason = comparable(case)
            if not ok:
                skipped.append("%s (%s)" % (name, reason))
                continue
            comparisons, reason = compare_case(case)
            if comparisons is None:
                skipped.append("%s (%s)" % (name, reason))
                continue
            compared += 1
            for column, ours, theirs in comparisons:
                columns += 1
                if isinstance(theirs, str):
                    errors.append("%s.%s %s" % (name, column, theirs))
                elif ours == theirs:
                    matches += 1
                elif not theirs:
                    empty += 1
                    print("NO-LINEAGE %s.%s\n  ours     %s\n  sqlglot  [] "
                          "(construct not supported by SQLGlot)"
                          % (name, column, sorted(ours)))
                else:
                    diffs += 1
                    print("DIFF %s.%s\n  ours     %s\n  sqlglot  %s"
                          % (name, column, sorted(ours), sorted(theirs)))

    print("\nsummary: %d cases, %d compared, %d columns, %d match, %d diff, "
          "%d no-lineage, %d sqlglot errors, %d skipped"
          % (cases, compared, columns, matches, diffs, empty, len(errors),
             len(skipped)))
    if errors:
        print("sqlglot errors (not counted as diffs):")
        for error in errors:
            print("  " + error)
    if skipped:
        print("skipped (first 10):")
        for item in skipped[:10]:
            print("  " + item)
    return 1 if args.fail_on_diff and diffs else 0


if __name__ == "__main__":
    sys.exit(main())
