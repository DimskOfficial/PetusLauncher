'use strict';
const { contextBridge, ipcRenderer } = require('electron');

// Minimal, safe bridge — no Node in the renderer.
contextBridge.exposeInMainWorld('petus', {
  getAuth: () => ipcRenderer.invoke('auth:get'),
  login: () => ipcRenderer.invoke('auth:login'),
  logout: () => ipcRenderer.invoke('auth:logout'),
  play: () => ipcRenderer.invoke('game:play'),
  isInstalled: () => ipcRenderer.invoke('game:isInstalled'),
  minimize: () => ipcRenderer.send('win:minimize'),
  close: () => ipcRenderer.send('win:close'),
  onProgress: (cb) => {
    const handler = (_e, payload) => cb(payload);
    ipcRenderer.on('update:progress', handler);
    return () => ipcRenderer.removeListener('update:progress', handler);
  },
});
