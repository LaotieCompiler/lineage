#!/usr/bin/env node
/**
 * Render a self-contained HTML lineage report from the CSV artifacts produced by
 * the ODPS lineage scan.
 *
 * Usage:
 *   node scripts/lineage-report/render.js <artifacts-dir> [output.html]
 *
 * Expected files in <artifacts-dir>:
 *   statements.csv, table_lineage.csv, column_lineage.csv, schema_repairs.csv
 */
'use strict';

const fs = require('fs');
const path = require('path');

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; }
        else quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      row.push(field); field = '';
    } else if (ch === '\n') {
      row.push(field); field = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else if (ch !== '\r') {
      field += ch;
    }
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows;
}

function readCsv(file) {
  if (!fs.existsSync(file)) return [];
  const rows = parseCsv(fs.readFileSync(file, 'utf8'));
  if (rows.length === 0) return [];
  const header = rows[0].map(h => h.trim());
  return rows.slice(1).filter(r => r.length > 1 || (r[0] || '').trim() !== '')
    .map(r => {
      const obj = {};
      header.forEach((h, i) => { obj[h] = (r[i] || '').trim(); });
      return obj;
    });
}

function main() {
  const args = process.argv.slice(2);
  if (args.length < 1) {
    console.error('Usage: node scripts/lineage-report/render.js <artifacts-dir> [output.html]');
    process.exit(2);
  }
  const dir = path.resolve(args[0]);
  const out = path.resolve(args[1] || path.join(dir, 'lineage-report.html'));

  const statements = readCsv(path.join(dir, 'statements.csv'));
  const tableLineage = readCsv(path.join(dir, 'table_lineage.csv'));
  const depPath = path.join(dir, 'table_dependencies.csv');
  const tableDependencies = fs.existsSync(depPath) ? readCsv(depPath) : tableLineage;
  const columnLineage = readCsv(path.join(dir, 'column_lineage.csv'));
  const repairs = readCsv(path.join(dir, 'schema_repairs.csv'));

  const toNums = rows => rows.map(r => {
    if (r.target_columns !== undefined) r.target_columns = Number(r.target_columns) || 0;
    if (r.column_edges !== undefined) r.column_edges = Number(r.column_edges) || 0;
    if (r.statement !== undefined) r.statement = Number(r.statement) || 0;
    if (r.instructions !== undefined) r.instructions = Number(r.instructions) || 0;
    if (r.source_is_wildcard !== undefined) r.source_is_wildcard = r.source_is_wildcard === 'true';
    return r;
  });

  const data = {
    generatedAt: new Date().toISOString(),
    sourceDir: dir,
    statements: toNums(statements),
    tableLineage: toNums(tableLineage),
    tableDependencies: toNums(tableDependencies),
    columnLineage: toNums(columnLineage).map(r => ({
      ...r,
      source_is_wildcard: r.source_is_wildcard === true || r.source_is_wildcard === 'true'
    })),
    repairs: toNums(repairs)
  };

  const template = fs.readFileSync(path.join(__dirname, 'template.html'), 'utf8');
  const payload = JSON.stringify(data).replace(/</g, '\\u003c');
  const html = template.replace('/*__LINEAGE_DATA__*/null', payload);
  fs.writeFileSync(out, html, 'utf8');

  const ok = data.statements.filter(s => s.status === 'OK').length;
  console.log(`Rendered ${data.statements.length} statements (${ok} OK), ` +
    `${data.tableDependencies.length} direct table edges, ${data.tableLineage.length} end-to-end table edges, ${data.columnLineage.length} column edges.`);
  console.log(`Report: ${out}`);
}

main();
