// Per-game stats (playtime, last launch, install size) + integrity checking.
// Persisted as stats.json in the launcher's userData dir.
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { app } = require('electron');

const statsPath = () => path.join(app.getPath('userData'), 'stats.json');
const integrityPath = () => path.join(app.getPath('userData'), 'integrity.json');

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}
function writeJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
}

function loadStats() {
  return readJson(statsPath(), {});
}
function saveStats(all) {
  writeJson(statsPath(), all);
}

// Get a game's stats block, creating a default.
function gameStats(gameId) {
  const all = loadStats();
  return all[gameId] || { playSeconds: 0, lastPlayed: 0, sizeBytes: 0 };
}

// Add elapsed seconds to a game's total playtime and stamp the launch time.
function addPlaytime(gameId, seconds, launchedAt) {
  const all = loadStats();
  const s = all[gameId] || { playSeconds: 0, lastPlayed: 0, sizeBytes: 0 };
  s.playSeconds += Math.max(0, Math.round(seconds));
  s.lastPlayed = launchedAt || Date.now();
  all[gameId] = s;
  saveStats(all);
}

function setLastPlayed(gameId, when) {
  const all = loadStats();
  const s = all[gameId] || { playSeconds: 0, lastPlayed: 0, sizeBytes: 0 };
  s.lastPlayed = when;
  all[gameId] = s;
  saveStats(all);
}

// Recursively total the byte size of a directory.
function dirSize(dir) {
  let total = 0;
  let stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      const p = path.join(cur, e.name);
      if (e.isDirectory()) stack.push(p);
      else {
        try {
          total += fs.statSync(p).size;
        } catch {
          /* ignore */
        }
      }
    }
  }
  return total;
}

function updateSize(gameId, dir) {
  const all = loadStats();
  const s = all[gameId] || { playSeconds: 0, lastPlayed: 0, sizeBytes: 0 };
  s.sizeBytes = dirSize(dir);
  all[gameId] = s;
  saveStats(all);
  return s.sizeBytes;
}

// ---- Integrity -----------------------------------------------------------
// Snapshot sha256 of key files (the exe + every mod). Compared on launch so a
// tampered install is detected. We snapshot right after install/update.
function keyFiles(gameDir, exeName) {
  const files = [path.join(gameDir, exeName)];
  const modsDir = path.join(gameDir, 'geode', 'mods');
  try {
    for (const f of fs.readdirSync(modsDir)) {
      if (f.endsWith('.geode')) files.push(path.join(modsDir, f));
    }
  } catch {
    /* no mods dir */
  }
  return files;
}

function hashFile(file) {
  const buf = fs.readFileSync(file);
  return crypto.createHash('sha256').update(buf).digest('hex');
}

function snapshotIntegrity(gameId, gameDir, exeName) {
  const map = {};
  for (const f of keyFiles(gameDir, exeName)) {
    try {
      map[path.relative(gameDir, f)] = hashFile(f);
    } catch {
      /* ignore missing */
    }
  }
  const all = readJson(integrityPath(), {});
  all[gameId] = map;
  writeJson(integrityPath(), all);
  return map;
}

// Returns { ok, changed: [relPaths] }. ok=true when nothing was tampered with.
function verifyIntegrity(gameId, gameDir, exeName) {
  const all = readJson(integrityPath(), {});
  const snap = all[gameId];
  if (!snap) return { ok: true, changed: [], noSnapshot: true };
  const changed = [];
  for (const [rel, expected] of Object.entries(snap)) {
    const abs = path.join(gameDir, rel);
    let actual = null;
    try {
      actual = hashFile(abs);
    } catch {
      actual = null;
    }
    if (actual !== expected) changed.push(rel);
  }
  return { ok: changed.length === 0, changed };
}

module.exports = {
  gameStats,
  addPlaytime,
  setLastPlayed,
  updateSize,
  snapshotIntegrity,
  verifyIntegrity,
};
