#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const siteDir = path.join(rootDir, 'site');
const downloadsDir = path.join(siteDir, 'downloads');
const manifestPath = path.join(siteDir, 'latest.json');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const val = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : 'true';
    args[key] = val;
  }
  return args;
}

function readGradleMeta() {
  const gradlePath = path.join(rootDir, 'app', 'build.gradle');
  const text = fs.readFileSync(gradlePath, 'utf8');
  const codeMatch = text.match(/versionCode\s+(\d+)/);
  const nameMatch = text.match(/versionName\s+"([^"]+)"/);
  return {
    versionCode: codeMatch ? Number(codeMatch[1]) : 0,
    versionName: nameMatch ? nameMatch[1] : '',
  };
}

function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  const data = fs.readFileSync(filePath);
  hash.update(data);
  return { sha256: hash.digest('hex'), size: data.length };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const gradle = readGradleMeta();
  const versionName = (args['version-name'] || (args.tag ? args.tag.replace(/^v/i, '') : gradle.versionName)).trim();
  const tag = (args.tag || (versionName ? `v${versionName}` : '')).trim();
  const versionCode = Number(args['version-code'] || gradle.versionCode || 0);
  const repo = (args.repo || 'cao-gift/sdjtu-timetable').trim();
  const apkSrc = args.apk
    ? path.resolve(rootDir, args.apk)
    : path.join(rootDir, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');

  if (!tag || !versionName) {
    console.error('Error: missing --tag / versionName');
    process.exit(1);
  }
  if (!fs.existsSync(apkSrc)) {
    console.error(`Error: APK not found at ${apkSrc}`);
    process.exit(1);
  }

  let notes = args.notes || '';
  if (args['notes-file']) {
    notes = fs.readFileSync(path.resolve(rootDir, args['notes-file']), 'utf8').trim();
  }
  if (!notes && fs.existsSync(manifestPath)) {
    try {
      const prev = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
      if (prev.tag === tag && prev.notes) notes = prev.notes;
    } catch {
      // ignore invalid previous manifest
    }
  }

  const publishedAt = args['published-at'] || new Date().toISOString();
  const fileName = `lvlv-${tag}.apk`;

  fs.mkdirSync(downloadsDir, { recursive: true });
  for (const existing of fs.readdirSync(downloadsDir)) {
    if (existing.endsWith('.apk') && existing !== fileName) {
      fs.unlinkSync(path.join(downloadsDir, existing));
    }
  }

  const destApk = path.join(downloadsDir, fileName);
  fs.copyFileSync(apkSrc, destApk);
  const { sha256, size } = sha256File(destApk);

  const manifest = {
    schemaVersion: 1,
    tag,
    versionName,
    versionCode,
    name: `驴驴课表 ${tag}`,
    notes,
    apkUrl: `/downloads/${fileName}`,
    githubApkUrl: repo ? `https://github.com/${repo}/releases/download/${tag}/${fileName}` : '',
    pageUrl: '/',
    fileName,
    size,
    sha256,
    publishedAt,
  };

  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

  if (args['out-manifest']) {
    const outPath = path.resolve(rootDir, args['out-manifest']);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  }

  console.log(`Synced ${fileName} (${size} bytes, sha256=${sha256}) -> site/latest.json`);
}

main();
