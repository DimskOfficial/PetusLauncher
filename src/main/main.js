'use strict';
const { app, BrowserWindow, ipcMain, session } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const cfg = require('./config');
const { ensureUpToDate } = require('./updater');

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
    width: 720,
    height: 460,
    resizable: false,
    fullscreenable: false,
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

// ---- Petus ID login -------------------------------------------------------
// Opens the site's handoff URL in a dedicated window. The site redirects to
// petus-launcher://auth?token=...; we intercept that navigation, capture the
// token, and never expose any password or secret to the client.
function startLogin() {
  return new Promise((resolve, reject) => {
    authWin = new BrowserWindow({
      width: 460,
      height: 620,
      parent: win,
      modal: true,
      title: 'Вход через Petus ID',
      autoHideMenuBar: true,
      webPreferences: { contextIsolation: true, nodeIntegration: false },
    });

    let done = false;
    const finish = (fn, arg) => {
      if (done) return;
      done = true;
      if (authWin) {
        authWin.destroy();
        authWin = null;
      }
      fn(arg);
    };

    const tryCapture = (url) => {
      if (!url || !url.startsWith(`${cfg.protocol}://`)) return false;
      try {
        const u = new URL(url);
        const token = u.searchParams.get('token');
        if (!token) throw new Error('no token in callback');
        const auth = {
          token,
          name: u.searchParams.get('name') || 'Player',
          account: u.searchParams.get('account') || '',
        };
        saveAuth(auth);
        finish(resolve, auth);
      } catch (e) {
        finish(reject, e);
      }
      return true;
    };

    // The custom-scheme navigation shows up as a failed/blocked request.
    authWin.webContents.on('will-redirect', (e, url) => {
      if (tryCapture(url)) e.preventDefault();
    });
    authWin.webContents.on('will-navigate', (e, url) => {
      if (tryCapture(url)) e.preventDefault();
    });
    authWin.webContents.on('did-fail-load', (_e, _code, _desc, url) => {
      tryCapture(url);
    });

    authWin.on('closed', () => {
      authWin = null;
      finish(reject, new Error('cancelled'));
    });

    authWin.loadURL(cfg.handoffUrl);
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
