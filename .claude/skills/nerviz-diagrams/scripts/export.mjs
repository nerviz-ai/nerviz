// PNG (and, with --video, WebM) of a rendered view, for the site and for promotional video.
//
//   node .claude/skills/nerviz-diagrams/scripts/export.mjs <view> [--video] [--root <dir>]
//
// Reads docs/diagrams/<view>/<view>.svg (and .animated.svg for --video), writes
// docs/diagrams/<view>/dist/<view>.png (+ .webm) and, beside each, <file>.source-sha256: the
// SHA-256 of the SVG it came from, which `Diagrams.java check` compares in CI.
//
// No network at run time, no update check, no telemetry. The two modules come from the
// per-user cache that `npm ci --prefix` filled from this skill's package.json and lockfile:
// $XDG_CACHE_HOME/nerviz-diagrams, else ~/.cache/nerviz-diagrams. Playwright loads only
// with --video, and needs its browser already installed; this script never downloads one.
// Design: .claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md

import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const video = args.includes('--video');
const ri = args.indexOf('--root');
const root = resolve(ri >= 0 ? args[ri + 1] : (process.env.CLAUDE_PROJECT_DIR || '.'));
const view = args.find((a, i) => !a.startsWith('--') && (ri < 0 || i !== ri + 1));
const cache = join(process.env.XDG_CACHE_HOME || join(homedir(), '.cache'), 'nerviz-diagrams');

function die(msg) {
  console.error('❌ ' + msg);
  process.exit(2);
}

if (!view) die('usage: node export.mjs <view> [--video] [--root <dir>]');
if (!existsSync(join(cache, 'node_modules'))) {
  die(`the export modules are not installed in ${cache}. Run, once:\n` +
    `   mkdir -p "${cache}" && cp .claude/skills/nerviz-diagrams/package.json .claude/skills/nerviz-diagrams/package-lock.json "${cache}/" && ` +
    `npm ci --prefix "${cache}" --no-update-notifier --no-audit --no-fund`);
}
const need = createRequire(join(cache, 'package.json'));

const dir = join(root, 'docs', 'diagrams', view);
const svgPath = join(dir, `${view}.svg`);
const animPath = join(dir, `${view}.animated.svg`);
if (!existsSync(svgPath)) die(`${svgPath} is missing — run: java .claude/skills/nerviz-diagrams/scripts/Diagrams.java render`);

const read = (p) => readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
const sha = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
const dist = join(dir, 'dist');
mkdirSync(dist, { recursive: true });

function stamp(file, source) {
  writeFileSync(`${file}.source-sha256`, sha(read(source)) + '\n');
}

const svg = read(svgPath);
const { Resvg } = need('@resvg/resvg-js');
const m = /viewBox="0 0 (\d+) (\d+)"/.exec(svg);
if (!m) die(`${svgPath} has no viewBox`);
const png = new Resvg(svg, {
  fitTo: { mode: 'width', value: Number(m[1]) * 2 },
  font: { loadSystemFonts: true },
}).render().asPng();
const pngPath = join(dist, `${view}.png`);
writeFileSync(pngPath, png);
stamp(pngPath, svgPath);
console.log(`✅ ${pngPath} (${png.length} bytes)`);

if (video) {
  if (!existsSync(animPath)) die(`${animPath} is missing — run Diagrams.java render`);
  const { chromium } = need('playwright');
  const w = Number(m[1]);
  const h = Number(m[2]);
  const tmp = join(dist, '.recording');
  rmSync(tmp, { recursive: true, force: true });
  let browser;
  try {
    browser = await chromium.launch();
  } catch (e) {
    die('Playwright has no browser installed. `node "${cache}/node_modules/playwright/cli.js" install chromium` downloads one; this script never does.\n   ' + String(e.message).split('\n')[0]);
  }
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, recordVideo: { dir: tmp, size: { width: w, height: h } } });
  const page = await ctx.newPage();
  await page.setContent(`<!doctype html><body style="margin:0">${read(animPath).replace(/^<\?xml[^>]*>\s*/, '')}</body>`);
  const delays = [...read(animPath).matchAll(/animation-delay:([0-9.]+)s/g)].map((x) => Number(x[1]));
  await page.waitForTimeout((Math.max(0, ...delays) + 1.5) * 1000);
  await ctx.close();
  await browser.close();
  const rec = readdirSync(tmp).find((f) => f.endsWith('.webm'));
  if (!rec) die('Playwright wrote no recording');
  const out = join(dist, `${view}.webm`);
  renameSync(join(tmp, rec), out);
  rmSync(tmp, { recursive: true, force: true });
  stamp(out, animPath);
  console.log(`✅ ${out}`);
}
