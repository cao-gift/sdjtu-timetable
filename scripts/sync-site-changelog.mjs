#!/usr/bin/env node
/**
 * 解析根目录 CHANGELOG.md 并生成官网静态更新日志数据 site/changelog.json（并同步到 site/.edgeone/assets/）。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "..");
const src = path.join(root, "CHANGELOG.md");
const outPaths = [
  path.join(root, "site", "changelog.json"),
  path.join(root, "site", ".edgeone", "assets", "changelog.json"),
];

const md = fs.readFileSync(src, "utf8");
const entries = [];
let cur = null;

for (const rawLine of md.split(/\r?\n/)) {
  const line = rawLine.trim();
  const head = line.match(/^##\s+\[([^\]]+)\](?:\s*-\s*(\S+))?/);
  if (head) {
    cur = { version: head[1], date: head[2] || "", items: [] };
    entries.push(cur);
    continue;
  }
  if (!cur) continue;
  if (line.startsWith("### ")) {
    cur.items.push({ type: "heading", text: line.slice(4).trim() });
  } else if (line.startsWith("- ")) {
    cur.items.push({ type: "item", text: line.slice(2).trim() });
  }
}

const payload = JSON.stringify(
  { generatedAt: new Date().toISOString(), count: entries.length, releases: entries },
  null,
  2
);

for (const out of outPaths) {
  const dir = path.dirname(out);
  if (fs.existsSync(dir)) {
    fs.writeFileSync(out, payload + "\n", "utf8");
    console.log("Wrote", path.relative(root, out), `(${entries.length} releases)`);
  }
}
