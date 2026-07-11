// Auto-update: on launch, compare the CDN manifest version with what's
// installed locally. If newer, download the archive, unpack it into the game
// directory, and record the new version — no user action required.

'use strict';
const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const AdmZip = require('adm-zip');
const cfg = require('./config');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Retry a promise-returning fn a few times (core can drop connections; the CDN
// can briefly negative-cache a just-uploaded object as 403).
async function withRetry(fn, { tries = 5, delay = 1500, label = 'request' } = {}) {
  let lastErr;
  for (let i = 1; i <= tries; i++) {
    try {
      return await fn();
    } catch (e) {
      lastErr = e;
      if (i < tries) await sleep(delay);
    }
  }
  throw new Error(`${label} failed after ${tries} tries: ${lastErr && lastErr.message ? lastErr.message : lastErr}`);
}

function fetchJsonOnce(url) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('http:') ? http : https;
    lib
      .get(url, (res) => {
        if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return resolve(fetchJsonOnce(res.headers.location));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`manifest HTTP ${res.statusCode}`));
        }
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(body));
          } catch (e) {
            reject(e);
          }
        });
      })
      .on('error', reject);
  });
}

function fetchJson(url) {
  return withRetry(() => fetchJsonOnce(url), { label: 'manifest' });
}

function downloadOnce(url, dest, onProgress) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('http:') ? http : https;
    const file = fs.createWriteStream(dest);
    lib
      .get(url, (res) => {
        if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          file.close();
          fs.rmSync(dest, { force: true });
          return resolve(downloadOnce(res.headers.location, dest, onProgress));
        }
        if (res.statusCode !== 200) {
          res.resume();
          file.close();
          fs.rmSync(dest, { force: true });
          return reject(new Error(`download HTTP ${res.statusCode}`));
        }
        const total = parseInt(res.headers['content-length'] || '0', 10);
        let got = 0;
        res.on('data', (chunk) => {
          got += chunk.length;
          if (total && onProgress) onProgress(got / total);
        });
        res.pipe(file);
        file.on('finish', () => file.close(() => resolve()));
      })
      .on('error', (err) => {
        file.close();
        fs.rmSync(dest, { force: true });
        reject(err);
      });
  });
}

function download(url, dest, onProgress) {
  return withRetry(() => downloadOnce(url, dest, onProgress), { tries: 5, delay: 2500, label: 'download' });
}

function readInstalled() {
  try {
    return JSON.parse(fs.readFileSync(cfg.versionFile, 'utf8'));
  } catch {
    return { version: null };
  }
}

function writeInstalled(version) {
  fs.mkdirSync(path.dirname(cfg.versionFile), { recursive: true });
  fs.writeFileSync(cfg.versionFile, JSON.stringify({ version, updatedAt: Date.now() }, null, 2));
}

/**
 * Ensure the newest game build is installed.
 * `emit(stage, data)` reports progress to the UI: "check" | "download" |
 * "install" | "uptodate" | "ready" | "error".
 * Returns { version } on success.
 */
async function ensureUpToDate(emit) {
  emit('check');
  let manifest;
  try {
    manifest = await fetchJson(cfg.updateManifest);
  } catch (e) {
    // Offline / CDN down: fall back to whatever is installed, if anything.
    const installed = readInstalled();
    if (installed.version) {
      emit('ready', { version: installed.version, offline: true });
      return { version: installed.version };
    }
    emit('error', { message: 'Не удалось проверить обновления и игра не установлена.' });
    throw e;
  }

  // manifest: { version: "1.2.0", url: "https://cdn.../game/petusgdps-1.2.0.zip" }
  const installed = readInstalled();
  if (installed.version === manifest.version && fs.existsSync(path.join(cfg.gameDir, cfg.exeName))) {
    emit('uptodate', { version: manifest.version });
    emit('ready', { version: manifest.version });
    return { version: manifest.version };
  }

  emit('download', { version: manifest.version, progress: 0 });
  fs.mkdirSync(cfg.gameDir, { recursive: true });
  const tmpZip = path.join(cfg.gameDir, `.update-${manifest.version}.zip`);
  await download(manifest.url, tmpZip, (p) => emit('download', { version: manifest.version, progress: p }));

  emit('install', { version: manifest.version });
  const zip = new AdmZip(tmpZip);
  zip.extractAllTo(cfg.gameDir, /* overwrite */ true);
  fs.rmSync(tmpZip, { force: true });

  writeInstalled(manifest.version);
  emit('ready', { version: manifest.version });
  return { version: manifest.version };
}

// True when a game executable is present on disk.
function isGameInstalled() {
  try {
    return fs.existsSync(path.join(cfg.gameDir, cfg.exeName));
  } catch {
    return false;
  }
}

module.exports = { ensureUpToDate, readInstalled, isGameInstalled };
