'use strict';
const { contextBridge, ipcRenderer } = require('electron');

// Minimal, safe bridge — no Node in the renderer.
contextBridge.exposeInMainWorld('petus', {
  getAuth: () => ipcRenderer.invoke('auth:get'),
  login: () => ipcRenderer.invoke('auth:login'),
  logout: () => ipcRenderer.invoke('auth:logout'),
  play: () => ipcRenderer.invoke('game:play'),
  isInstalled: () => ipcRenderer.invoke('game:isInstalled'),
  gamesList: () => ipcRenderer.invoke('games:list'),
  gameStats: (id) => ipcRenderer.invoke('game:stats', id),
  verify: () => ipcRenderer.invoke('game:verify'),
  openFolder: () => ipcRenderer.invoke('game:openFolder'),
  copyIp: (ip) => ipcRenderer.invoke('mc:copyIp', ip),
  openExternal: (url) => ipcRenderer.invoke('open:external', url),
  minimize: () => ipcRenderer.send('win:minimize'),
  close: () => ipcRenderer.send('win:close'),
  onProgress: (cb) => {
    const handler = (_e, payload) => cb(payload);
    ipcRenderer.on('update:progress', handler);
    return () => ipcRenderer.removeListener('update:progress', handler);
  },
  onGameClosed: (cb) => {
    const handler = (_e, payload) => cb(payload);
    ipcRenderer.on('game:closed', handler);
    return () => ipcRenderer.removeListener('game:closed', handler);
  },
});
