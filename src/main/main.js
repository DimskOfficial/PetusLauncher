'use strict';
const { app, BrowserWindow, ipcMain, session } = require('electron');
const path = require('path');
const fs = require('fs');
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

// ---- Petus ID login -------------------------------------------------------
// Opens the site's handoff URL in a dedicated window. After the user signs in
// through Petus ID, the site lands back on /api/launcher/handoff, whose page
// carries the game token in the DOM (window.__PETUS_AUTH__ + <meta> tags). We
// read it out with executeJavaScript — this is reliable, unlike relying on a
// renderer-initiated navigation to petus-launcher:// (modern Chromium swallows
// custom-scheme navigations before will-navigate/will-redirect can fire). The
// custom-scheme handlers are kept only as a belt-and-suspenders fallback.
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

    const acceptAuth = (auth) => {
      if (!auth || !auth.token) return false;
      saveAuth(auth);
      finish(resolve, auth);
      return true;
    };

    // Fallback: capture a petus-launcher://auth?token=... navigation if one
    // ever does surface (older Electron, real-browser deep link relay, etc.).
    const tryCaptureUrl = (url) => {
      if (!url || !url.startsWith(`${cfg.protocol}://`)) return false;
      try {
        const u = new URL(url);
        acceptAuth({
          token: u.searchParams.get('token') || '',
          name: u.searchParams.get('name') || 'Player',
          account: u.searchParams.get('account') || '',
        });
      } catch (e) {
        finish(reject, e);
      }
      return true;
    };

    // Primary path: whenever a page finishes loading, if it is the handoff page
    // it exposes window.__PETUS_AUTH__ = { token, name, account }. Read it.
    const scrapeToken = (reason) => {
      if (done || !authWin) return;
      const url = authWin.webContents.getURL() || '';
      console.log(`[auth] scrape (${reason}) url=${url}`);
      authWin.webContents
        .executeJavaScript(
          '(function(){' +
            'try{' +
            'if(window.__PETUS_AUTH__ && window.__PETUS_AUTH__.token) return window.__PETUS_AUTH__;' +
            'var t=document.querySelector(\'meta[name="petus-token"]\');' +
            'if(t) return {token:t.content,' +
            'name:decodeURIComponent((document.querySelector(\'meta[name="petus-name"]\')||{}).content||\'\'),' +
            'account:(document.querySelector(\'meta[name="petus-account"]\')||{}).content||\'\'};' +
            'return {__nohit:true, href:location.href, title:document.title, ' +
            'body:(document.body?document.body.innerText.slice(0,200):\'\')};' +
            '}catch(e){return {__err:String(e)};}' +
          '})()',
          true
        )
        .then((res) => {
          if (res && res.token) {
            console.log('[auth] token captured, len=' + res.token.length);
            acceptAuth(res);
          } else {
            console.log('[auth] no token: ' + JSON.stringify(res));
          }
        })
        .catch((e) => {
          console.log('[auth] executeJavaScript failed: ' + e);
        });
    };

    authWin.webContents.on('did-finish-load', () => scrapeToken('did-finish-load'));
    authWin.webContents.on('did-navigate', (_e, url) => {
      console.log(`[auth] did-navigate -> ${url}`);
      scrapeToken('did-navigate');
    });
    authWin.webContents.on('did-navigate-in-page', () => scrapeToken('did-navigate-in-page'));

    authWin.webContents.on('will-redirect', (e, url) => {
      console.log(`[auth] will-redirect -> ${url}`);
      if (tryCaptureUrl(url)) e.preventDefault();
    });
    authWin.webContents.on('will-navigate', (e, url) => {
      console.log(`[auth] will-navigate -> ${url}`);
      if (tryCaptureUrl(url)) e.preventDefault();
    });
    authWin.webContents.on('did-fail-load', (_e, code, desc, url) => {
      console.log(`[auth] did-fail-load ${code} ${desc} -> ${url}`);
      tryCaptureUrl(url);
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
