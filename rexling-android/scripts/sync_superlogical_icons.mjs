#!/usr/bin/env node
// Sync the Superlogical icon catalog into the retained SVG sources.
//
// The catalog at https://icons.superlogical.com/ sits behind Discord sign-in, so
// it is read out of an already signed-in Chrome tab over the DevTools Protocol
// instead of an anonymous HTTP client. Nothing is scraped from a private API:
// the page fetches its own `icons.json` and `glyphs/<id>.svg`, and this script
// asks that same page to hand them over.
//
// Usage:
//   node scripts/sync_superlogical_icons.mjs                 # report only
//   node scripts/sync_superlogical_icons.mjs --write          # add missing icons
//   node scripts/sync_superlogical_icons.mjs --write --overwrite
//
// Without `--overwrite` only icons that have no retained source yet are written.
// Retained sources are kept as delivered; `--overwrite` rewrites the existing
// ones with the current catalog colors and glyph art. Tiles are always built
// from artwork/program-icons/rexling/card-template.svg, so the card geometry
// stays identical across the catalog.
//
// Prerequisites: Chrome is running with a remote debugging port (DevToolsActivePort
// in the Chrome profile directory, or --port), and the icon catalog tab is open.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE = path.join(ROOT, 'artwork/program-icons/superlogical');
const OVERLAY = path.join(ROOT, 'artwork/program-icons/rexling');
const TEMPLATE = path.join(OVERLAY, 'card-template.svg');
const METADATA = path.join(SOURCE, 'metadata.json');
const HOST = 'icons.superlogical.com';

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const option = (name, fallback) => {
  const index = argv.indexOf(name);
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback;
};
const write = flag('--write');
const overwrite = flag('--overwrite');
const port = option('--port', null);

function devtoolsURL() {
  if (port) return `http://127.0.0.1:${port}/json/version`;
  const profile = process.env.CHROME_PROFILE
    || path.join(process.env.HOME, 'Library/Application Support/Google/Chrome');
  const lines = fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').trim().split('\n');
  return { port: lines[0], path: lines[1] };
}

async function connect() {
  const target = devtoolsURL();
  let wsUrl;
  if (typeof target === 'string') {
    const response = await fetch(target);
    wsUrl = (await response.json()).webSocketDebuggerUrl;
  } else {
    wsUrl = `ws://127.0.0.1:${target.port}${target.path}`;
  }
  const ws = new WebSocket(wsUrl);
  const pending = new Map();
  let id = 0;
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    message.error ? entry.reject(new Error(JSON.stringify(message.error))) : entry.resolve(message.result);
  });
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const messageId = ++id;
    pending.set(messageId, { resolve, reject });
    ws.send(JSON.stringify(sessionId ? { id: messageId, method, params, sessionId } : { id: messageId, method, params }));
  });
  return { send, close: () => ws.close() };
}

async function catalogPage(cdp) {
  const { targetInfos } = await cdp.send('Target.getTargets');
  const page = targetInfos.find((info) => info.type === 'page' && info.url.includes(HOST));
  if (!page) throw new Error(`Open ${HOST} in Chrome first; no matching tab found`);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId: page.targetId, flatten: true });
  return sessionId;
}

async function evaluate(cdp, sessionId, expression) {
  const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails.exception || result.exceptionDetails));
  return result.result.value;
}

async function extract(cdp, sessionId) {
  return evaluate(cdp, sessionId, `(async () => {
    const response = await fetch('icons.json', { credentials: 'include' });
    if (!response.ok) throw new Error('icons.json: ' + response.status);
    const catalog = await response.json();
    const glyphs = {};
    for (const icon of catalog.icons) {
      const glyph = await fetch('glyphs/' + icon.id + '.svg', { credentials: 'include' });
      glyphs[icon.id] = glyph.ok ? await glyph.text() : null;
    }
    return { catalog, glyphs };
  })()`);
}

function buildTile(template, icon, glyph) {
  const payload = Buffer.from(glyph, 'utf8').toString('base64');
  const tile = template
    .replaceAll('@title', icon.title)
    .replaceAll('@slug', icon.id)
    .replaceAll('@background', icon.background.toLowerCase())
    .replaceAll('@foreground', (icon.foreground || '#ffffff').toLowerCase())
    .replaceAll('@glyph', `data:image/svg+xml;base64,${payload}`);
  if (/@(title|slug|background|foreground|glyph)/.test(tile)) throw new Error(`unfilled placeholder for ${icon.id}`);
  return tile;
}

const digest = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const rasterOnly = (glyph) => /<image\b[^>]*data:image\/(png|jpe?g)/.test(glyph);
const paintOnly = (text) => text
  .replace(/\b(fill|stroke|stop-color|color)="[^"]*"/g, '$1=""')
  .replace(/\b(fill|stroke|stop-color|color)\s*:\s*[^;"]+/g, '$1:');

function cardColor(tile) {
  const background = /width="58\.54" height="45\.54" fill="([^"]+)"/.exec(tile)?.[1];
  const foreground = /<g mask="url\(#logo-mask-0\)" opacity="1"><rect [^>]*fill="([^"]+)"/.exec(tile)?.[1];
  return { background: (background || '').toLowerCase(), foreground: (foreground || '').toLowerCase() };
}

const template = fs.readFileSync(TEMPLATE, 'utf8');
const metadata = JSON.parse(fs.readFileSync(METADATA, 'utf8'));
const cdp = await connect();
try {
  const sessionId = await catalogPage(cdp);
  const { catalog, glyphs } = await extract(cdp, sessionId);
  console.log(`catalog ${catalog.packageRelease?.version ?? '?'} (${catalog.packageRelease?.publishedAt ?? '?'}): ${catalog.icons.length} icons`);

  const added = [], stale = [], excluded = [], unchanged = [];
  for (const icon of catalog.icons) {
    const glyph = glyphs[icon.id];
    if (!glyph) { excluded.push([icon.id, 'glyph fetch failed']); continue; }
    if (rasterOnly(glyph)) { excluded.push([icon.id, 'raster logo (vector renderer cannot draw it)']); continue; }
    const tileFile = path.join(SOURCE, `${icon.id}.svg`);
    const glyphFile = path.join(SOURCE, 'glyphs', `${icon.id}.svg`);
    const tile = buildTile(template, icon, glyph);
    if (!fs.existsSync(tileFile) || !fs.existsSync(glyphFile)) { added.push([icon, tile, glyph]); continue; }
    const retained = fs.readFileSync(tileFile, 'utf8');
    const retainedGlyph = fs.readFileSync(glyphFile, 'utf8');
    const reasons = [];
    const colors = cardColor(retained);
    if (colors.background !== icon.background.toLowerCase()) reasons.push('background');
    if (colors.foreground !== (icon.foreground || '#ffffff').toLowerCase()) reasons.push('foreground');
    if (retainedGlyph !== glyph && paintOnly(retainedGlyph) !== paintOnly(glyph)) reasons.push('glyph art');
    if (reasons.length) stale.push([icon, reasons, tile, glyph]);
    else unchanged.push(icon.id);
  }

  console.log(`retained ${unchanged.length}, missing ${added.length}, drift ${stale.length}, excluded ${excluded.length}`);
  if (added.length) console.log(`missing: ${added.map(([icon]) => icon.id).join(', ')}`);
  if (stale.length) {
    const groups = new Map();
    for (const [icon, reasons] of stale) groups.set(reasons.join('+'), [...(groups.get(reasons.join('+')) || []), icon.id]);
    for (const [reasons, ids] of groups) console.log(`drift ${reasons} (${ids.length}): ${ids.slice(0, 8).join(', ')}${ids.length > 8 ? ', …' : ''}`);
  }
  for (const [id, reason] of excluded) console.log(`excluded ${id}: ${reason}`);

  if (!write) {
    console.log('report only; pass --write to add missing icons' + (stale.length ? ' and --overwrite to refresh drifting ones' : ''));
  } else {
    const pending = overwrite ? [...added, ...stale.map(([icon, , tile, glyph]) => [icon, tile, glyph])] : added;
    for (const [icon, tile, glyph] of pending) {
      fs.writeFileSync(path.join(SOURCE, `${icon.id}.svg`), tile);
      fs.writeFileSync(path.join(SOURCE, 'glyphs', `${icon.id}.svg`), glyph);
    }
    const tiles = fs.readdirSync(SOURCE).filter((name) => name.endsWith('.svg')).sort();
    const glyphNames = fs.readdirSync(path.join(SOURCE, 'glyphs')).filter((name) => name.endsWith('.svg')).sort();
    metadata.sha256 = Object.fromEntries(tiles.map((name) => [name.slice(0, -4), digest(path.join(SOURCE, name))]));
    metadata.glyph_sha256 = Object.fromEntries(glyphNames.map((name) => [name.slice(0, -4), digest(path.join(SOURCE, 'glyphs', name))]));
    // The catalog release travels with the digests so a sync cannot leave a stale version behind.
    const release = catalog.packageRelease;
    const version = release?.version?.replace(/^v/, '');
    if (version) metadata.catalog_version = version;
    if (release?.publishedAt) metadata.catalog_published_at = release.publishedAt;
    if (metadata.sync_note && version)
      metadata.sync_note = metadata.sync_note.replace(/\bcatalog v?\d+(?:\.\d+)* exports\b/, `catalog ${version} exports`);
    fs.writeFileSync(METADATA, JSON.stringify(metadata, null, 2) + '\n');
    console.log(`wrote ${pending.length} icons; ${tiles.length} tiles and ${glyphNames.length} glyphs are recorded in ${path.relative(ROOT, METADATA)}`);
    console.log('run scripts/build_program_vectors.py and commit app/src/main/assets/program_tiles.json');
  }
} finally {
  cdp.close();
}