'use strict';

const { contextBridge, ipcRenderer } = require('electron');

function subscribe(channel, callback) {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
}

contextBridge.exposeInMainWorld('univm', {
    getAppInfo: () => ipcRenderer.invoke('app:info'),

    listExamples: () => ipcRenderer.invoke('examples:list'),
    readExample: (relativePath) => ipcRenderer.invoke('examples:read', relativePath),
    saveExample: (relativePath, content) => ipcRenderer.invoke('examples:write', relativePath, content),

    run: (code, runId) => ipcRenderer.invoke('run:start', { code, runId }),
    sendInput: (text, runId) => ipcRenderer.invoke('run:input', { text, runId }),
    stop: () => ipcRenderer.invoke('run:stop'),

    onRunOutput: (callback) => subscribe('run:output', callback),
    onRunExit: (callback) => subscribe('run:exit', callback),
    onShortcut: (callback) => subscribe('shortcut', callback),
    onMaximizedChange: (callback) => subscribe('window:maximized', callback),

    windowControls: {
        minimize: () => ipcRenderer.send('window:minimize'),
        toggleMaximize: () => ipcRenderer.invoke('window:toggle-maximize'),
        close: () => ipcRenderer.send('window:close'),
        isMaximized: () => ipcRenderer.invoke('window:is-maximized')
    }
});
