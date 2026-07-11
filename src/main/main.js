'use strict';
const { app, BrowserWindow, ipcMain, session, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const { spawn } = require('child_process');
const cfg = require('./config');
const { ensureUpToDate, isGameInstalled } = require('./updater');

let win = null;
let authWin = null;

// Persisted auth state (token + display name) so returning users skip login.
const authStatePath = path.join(app.getPath('userData'), 'auth.json');

function loadAuth() {
  try {
    return JSON.parse(fs.readFileSync(authStatePath, 'utf8'));
  } catch {
    return null;
  }
}
function saveAuth(auth) {
  fs.writeFileSync(authStatePath, JSON.stringify(auth, null, 2));
}
function clearAuth() {
  try {
    fs.rmSync(authStatePath, { force: true });
  } catch {
    /* ignore */
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: 760,
    height: 500,
    resizable: false,
    fullscreenable: false,
    frame: false, // custom title bar (VK-style), no OS chrome
    title: 'PetusLauncher',
    backgroundColor: '#e9edf3',
    icon: path.join(__dirname, '..', '..', 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

// ---- Petus ID login (external browser + loopback) -------------------------
// Instead of an embedded window (where the user had to sign in again), we open
// the handoff URL in the user's DEFAULT browser — where they're already signed
// into Petus ID — and receive the game token back on a temporary localhost
// server. This is the standard desktop-OAuth "loopback" pattern.
function startLogin() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      let u;
      try {
        u = new URL(req.url, 'http://127.0.0.1');
      } catch {
        res.writeHead(400).end('bad request');
        return;
      }
      if (u.pathname !== '/cb') {
        res.writeHead(404).end('not found');
        return;
      }
      const token = u.searchParams.get('token') || '';
      const name = u.searchParams.get('name') || 'Player';
      const account = u.searchParams.get('account') || '';

      // Friendly page the user sees in their browser after logging in.
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(
        '<!doctype html><meta charset="utf-8"><title>PetusLauncher</title>' +
          '<style>body{font-family:Tahoma,sans-serif;background:#e9edf3;color:#333;text-align:center;padding:60px}' +
          'h3{color:#2b587a}</style>' +
          (token
            ? '<h3>Готово!</h3><p>Можно вернуться в лаунчер PetusGDPS — вход выполнен.</p>'
            : '<h3>Ошибка входа</h3><p>Токен не получен. Попробуй ещё раз из лаунчера.</p>')
      );

      cleanup();
      if (token) {
        const auth = { token, name, account };
        saveAuth(auth);
        resolve(auth);
      } else {
        reject(new Error('no_token'));
      }
    });

    let settled = false;
    let timer = null;
    const cleanup = () => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      try {
        server.close();
      } catch {
        /* ignore */
      }
    };
    const fail = (e) => {
      cleanup();
      reject(e);
    };

    server.on('error', fail);

    // Listen on a random free loopback port, then open the browser.
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      const redirect = `http://127.0.0.1:${port}/cb`;
      const url = `${cfg.handoffUrl}?redirect=${encodeURIComponent(redirect)}`;
      console.log(`[auth] opening browser -> ${url}`);
      shell.openExternal(url);

      // Give the user a few minutes to complete the login, then give up.
      timer = setTimeout(() => fail(new Error('timeout')), 5 * 60 * 1000);
    });
  });
}


// ---- Game launch ----------------------------------------------------------
// A pending level from a petusgdps://play?level=<id> deep link (site "Play").
let pendingPlayLevel = 0;

// Write the token (and any pending Play level) where the mod reads it, then
// spawn the game.
function launchGame(auth) {
  fs.mkdirSync(path.dirname(cfg.tokenFile), { recursive: true });
  const payload = {
    token: auth.token,
    name: auth.name,
    account: auth.account,
    ts: Date.now(),
  };
  if (pendingPlayLevel > 0) payload.play = pendingPlayLevel;
  fs.writeFileSync(cfg.tokenFile, JSON.stringify(payload, null, 2));
  pendingPlayLevel = 0;

  const exe = path.join(cfg.gameDir, cfg.exeName);
  if (!fs.existsSync(exe)) throw new Error('Игра не установлена.');

  const child = spawn(exe, [], { cwd: cfg.gameDir, detached: true, stdio: 'ignore' });
  child.unref();
}

// Parse petusgdps://play?level=<id> and remember the level for the next launch.
function handleGameDeepLink(url) {
  if (!url || !url.startsWith(`${cfg.gameProtocol}://`)) return false;
  try {
    const u = new URL(url);
    const lvl = parseInt(u.searchParams.get('level') || '0', 10);
    if (lvl > 0) {
      pendingPlayLevel = lvl;
      return true;
    }
  } catch {
    /* ignore */
  }
  return false;
}

// ---- IPC ------------------------------------------------------------------
ipcMain.handle('auth:get', () => loadAuth());

ipcMain.handle('auth:login', async () => {
  const auth = await startLogin();
  return auth;
});

ipcMain.handle('auth:logout', () => {
  clearAuth();
  return true;
});

ipcMain.handle('game:play', async (_evt) => {
  const auth = loadAuth();
  if (!auth) throw new Error('not_authed');

  const send = (stage, data) => win && win.webContents.send('update:progress', { stage, ...data });
  await ensureUpToDate(send);
  launchGame(auth);
  return true;
});

// Whether the game is already installed (renderer shows Install vs Play).
ipcMain.handle('game:isInstalled', () => isGameInstalled());

// Frameless window controls.
ipcMain.on('win:minimize', () => win && win.minimize());
ipcMain.on('win:close', () => win && win.close());

// Single-instance + protocol registration (so the OS can hand deep links back).
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_e, argv) => {
    // A protocol invocation arrives as a CLI arg on Windows.
    const authDeep = argv.find((a) => a.startsWith(`${cfg.protocol}://`));
    if (authDeep && authWin) authWin.webContents.emit('will-navigate', { preventDefault() {} }, authDeep);

    // Site "Play": petusgdps://play?level=<id> — remember it and, if already
    // signed in, launch straight into the game.
    const gameDeep = argv.find((a) => a.startsWith(`${cfg.gameProtocol}://`));
    if (gameDeep && handleGameDeepLink(gameDeep) && loadAuth() && win) {
      win.webContents.send('deeplink:play', { level: pendingPlayLevel });
    }

    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(() => {
    // Register both protocols with the OS.
    for (const scheme of [cfg.protocol, cfg.gameProtocol]) {
      if (process.defaultApp) {
        app.setAsDefaultProtocolClient(scheme, process.execPath, [path.resolve(process.argv[1] || '.')]);
      } else {
        app.setAsDefaultProtocolClient(scheme);
      }
    }

    // Cold-start deep link (launcher opened by the OS from a petusgdps:// link).
    const coldDeep = process.argv.find((a) => a.startsWith(`${cfg.gameProtocol}://`));
    if (coldDeep) handleGameDeepLink(coldDeep);

    createWindow();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
