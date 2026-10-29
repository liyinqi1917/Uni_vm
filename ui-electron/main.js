'use strict';

const { app, BrowserWindow, Menu, dialog, ipcMain, shell } = require('electron');
const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');

const APP_DIR = __dirname;
const ROOT_DIR = path.resolve(APP_DIR, '..');
const EXAMPLES_DIR = path.join(ROOT_DIR, 'examples');
const OUT_DIR = path.join(ROOT_DIR, 'out');
const TEMP_FILE = path.join(ROOT_DIR, 'temp_run.uni');
const MONACO_DIR = path.join(APP_DIR, 'node_modules', 'monaco-editor');
const ICON_PATH = path.join(APP_DIR, 'assets', 'icon.png');
const SMOKE_DIR = path.join(APP_DIR, '.smoke');
const APP_VERSION = require('./package.json').version;
const JAVA_COMMAND = process.env.UNIVM_JAVA || 'java';
const SMOKE_MODE = process.argv.includes('--smoke');

let mainWindow = null;
let staticServer = null;
let appOrigin = null;
let activeRun = null;
let runSerial = 0;

const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.map': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf'
};

const CONTENT_SECURITY_POLICY = [
    "default-src 'self'",
    "script-src 'self' 'unsafe-eval' blob:",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    "worker-src 'self' blob:",
    "connect-src 'self'"
].join('; ');

function resolveWithin(baseDir, relativePath) {
    const target = path.resolve(baseDir, relativePath);
    const relative = path.relative(baseDir, target);
    if (relative.startsWith('..') || path.isAbsolute(relative)) return null;
    return target;
}

function sendJson(res, status, body) {
    const data = Buffer.from(JSON.stringify(body));
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': data.length,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff'
    });
    res.end(data);
}

function sendText(res, status, text) {
    const data = Buffer.from(text, 'utf8');
    res.writeHead(status, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': data.length,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff'
    });
    res.end(data);
}

function streamFile(req, res, filePath) {
    let stat;
    try {
        stat = fs.statSync(filePath);
    } catch {
        sendText(res, 404, 'Not found');
        return;
    }
    if (!stat.isFile()) {
        sendText(res, 404, 'Not found');
        return;
    }

    const headers = {
        'Content-Type': MIME_TYPES[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
        'Content-Length': stat.size,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy': CONTENT_SECURITY_POLICY
    };
    res.writeHead(200, headers);
    if (req.method === 'HEAD') {
        res.end();
        return;
    }
    fs.createReadStream(filePath)
        .on('error', () => res.destroy())
        .pipe(res);
}

function handleStaticRequest(req, res) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
        sendText(res, 405, 'Method not allowed');
        return;
    }

    let pathname;
    let url;
    try {
        url = new URL(req.url, appOrigin || 'http://127.0.0.1');
        pathname = decodeURIComponent(url.pathname);
    } catch {
        sendText(res, 400, 'Bad request');
        return;
    }

    if (pathname === '/' || pathname === '/index.html') {
        pathname = '/index.html';
    }

    if (pathname === '/favicon.ico') {
        res.writeHead(204);
        res.end();
        return;
    }

    if (pathname.startsWith('/monaco/')) {
        const relative = pathname.slice('/monaco/'.length);
        const target = resolveWithin(MONACO_DIR, relative);
        if (!target) {
            sendText(res, 403, 'Forbidden');
            return;
        }
        streamFile(req, res, target);
        return;
    }

    if (pathname === '/index.html' || pathname === '/styles.css' || pathname === '/renderer.js') {
        streamFile(req, res, path.join(APP_DIR, pathname.slice(1)));
        return;
    }

    if (pathname.startsWith('/assets/')) {
        const target = resolveWithin(path.join(APP_DIR, 'assets'), pathname.slice('/assets/'.length));
        if (!target) {
            sendText(res, 403, 'Forbidden');
            return;
        }
        streamFile(req, res, target);
        return;
    }

    sendText(res, 404, 'Not found');
}

async function startStaticServer() {
    const server = http.createServer((req, res) => {
        try {
            handleStaticRequest(req, res);
        } catch (error) {
            console.error('[static]', error);
            if (!res.headersSent) sendText(res, 500, 'Internal server error');
            else res.destroy();
        }
    });

    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => {
            server.removeListener('error', reject);
            resolve();
        });
    });

    staticServer = server;
    const address = server.address();
    appOrigin = `http://127.0.0.1:${address.port}`;
    return appOrigin;
}

function compareTreeNodes(a, b) {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name, 'en', { numeric: true, sensitivity: 'base' });
}

async function readTree(dir) {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    const nodes = [];

    for (const entry of entries) {
        if (entry.name.startsWith('.')) continue;
        const fullPath = path.join(dir, entry.name);
        const relative = path.relative(EXAMPLES_DIR, fullPath).split(path.sep).join('/');

        if (entry.isDirectory()) {
            nodes.push({
                type: 'directory',
                name: entry.name,
                rel: relative,
                children: await readTree(fullPath)
            });
        } else if (entry.isFile()) {
            nodes.push({
                type: 'file',
                name: entry.name,
                rel: relative
            });
        }
    }

    nodes.sort(compareTreeNodes);
    return nodes;
}

async function readExample(relativePath) {
    if (typeof relativePath !== 'string' || relativePath.length === 0) {
        throw new Error('缺少文件路径。');
    }
    const target = resolveWithin(EXAMPLES_DIR, relativePath);
    if (!target) throw new Error('非法的文件路径。');

    const stat = await fsp.stat(target);
    if (!stat.isFile()) throw new Error('目标不是文件。');
    if (stat.size > 8 * 1024 * 1024) throw new Error('文件超过 8 MB，无法在编辑器中打开。');

    return {
        rel: relativePath.split(path.sep).join('/'),
        name: path.basename(target),
        size: stat.size,
        content: await fsp.readFile(target, 'utf8')
    };
}

async function writeExample(relativePath, content) {
    if (typeof relativePath !== 'string' || relativePath.length === 0) {
        throw new Error('缺少文件路径。');
    }
    if (typeof content !== 'string') {
        throw new Error('文件内容无效。');
    }
    if (Buffer.byteLength(content, 'utf8') > 8 * 1024 * 1024) {
        throw new Error('文件超过 8 MB，无法保存。');
    }

    const target = resolveWithin(EXAMPLES_DIR, relativePath);
    if (!target) throw new Error('非法的文件路径。');

    const stat = await fsp.stat(target);
    if (!stat.isFile()) throw new Error('目标不是文件。');

    await fsp.writeFile(target, content, 'utf8');
    return {
        rel: relativePath.split(path.sep).join('/'),
        name: path.basename(target),
        size: Buffer.byteLength(content, 'utf8')
    };
}

function emitToWindow(win, channel, payload) {
    if (!win || win.isDestroyed() || win.webContents.isDestroyed()) return;
    win.webContents.send(channel, payload);
}

function stopRunProcess(run) {
    if (!run || run.ended) return Promise.resolve(false);
    if (run.stopping) return Promise.resolve(true);
    run.stopping = true;

    const child = run.child;
    if (!child.pid) {
        try { child.kill(); } catch { /* already gone */ }
        return Promise.resolve(true);
    }

    if (process.platform === 'win32') {
        return new Promise((resolve) => {
            let settled = false;
            const finish = () => {
                if (settled) return;
                settled = true;
                resolve(true);
            };
            const killer = spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {
                windowsHide: true,
                stdio: 'ignore'
            });
            const timer = setTimeout(finish, 2500);
            killer.once('error', () => {
                clearTimeout(timer);
                try { child.kill(); } catch { /* already gone */ }
                finish();
            });
            killer.once('close', () => {
                clearTimeout(timer);
                finish();
            });
        });
    }

    try { child.kill('SIGTERM'); } catch { /* already gone */ }
    return Promise.resolve(true);
}

async function stopActiveRun() {
    const run = activeRun;
    if (!run) return false;
    await stopRunProcess(run);
    return true;
}

async function startRun(event, payload) {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win) throw new Error('找不到发起运行请求的窗口。');

    const code = payload && payload.code;
    if (typeof code !== 'string' || code.trim().length === 0) {
        throw new Error('编辑器内容为空。');
    }
    if (Buffer.byteLength(code, 'utf8') > 8 * 1024 * 1024) {
        throw new Error('源码超过 8 MB，拒绝执行。');
    }

    const requestedId = payload && payload.runId;
    const runId = typeof requestedId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(requestedId)
        ? requestedId
        : `run-${++runSerial}`;

    await stopActiveRun();

    if (!fs.existsSync(path.join(OUT_DIR, 'Main.class'))) {
        throw new Error('Java 后端尚未编译。请先运行 start-electron.bat，或执行 javac -encoding UTF-8 -d out src\\*.java。');
    }

    await fsp.writeFile(TEMP_FILE, code, 'utf8');

    // Run from the repository root: the compiled classes live in ./out and the
    // temporary source is written beside them. Using ../out here would point
    // outside the repository, so the classpath must stay relative to ROOT_DIR.
    const child = spawn(JAVA_COMMAND, [
        '-Dfile.encoding=UTF-8',
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-cp', 'out',
        'Main', 'temp_run.uni', '--run'
    ], {
        cwd: ROOT_DIR,
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
        env: { ...process.env, PYTHONIOENCODING: 'utf-8' }
    });

    const run = {
        id: runId,
        child,
        win,
        stopping: false,
        ended: false,
        spawnError: null,
        startedAt: Date.now()
    };
    activeRun = run;

    child.stdin.on('error', () => { /* The process may close before stdin is written. */ });
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (data) => {
        emitToWindow(win, 'run:output', { runId, stream: 'stdout', data });
    });
    child.stderr.on('data', (data) => {
        emitToWindow(win, 'run:output', { runId, stream: 'stderr', data });
    });
    child.on('error', (error) => {
        run.spawnError = error;
        emitToWindow(win, 'run:output', {
            runId,
            stream: 'stderr',
            data: `\n无法启动 Java 后端：${error.message}\n`
        });
    });
    child.on('close', (codeValue, signal) => {
        run.ended = true;
        if (activeRun === run) activeRun = null;
        emitToWindow(win, 'run:exit', {
            runId,
            code: codeValue,
            signal: signal || null,
            stopped: run.stopping,
            error: run.spawnError ? run.spawnError.message : null,
            durationMs: Date.now() - run.startedAt
        });
    });

    return { runId, pid: child.pid };
}

function wireWindowControls(win) {
    const notifyMaximized = () => {
        emitToWindow(win, 'window:maximized', win.isMaximized());
    };
    win.on('maximize', notifyMaximized);
    win.on('unmaximize', notifyMaximized);

    win.webContents.on('before-input-event', (event, input) => {
        if (input.type !== 'keyDown') return;

        if (input.key === 'F5') {
            event.preventDefault();
            emitToWindow(win, 'shortcut', { action: input.shift ? 'stop' : 'run' });
            return;
        }

        if ((input.control || input.meta) && !input.alt && !input.shift && input.key.toLowerCase() === 's') {
            event.preventDefault();
            emitToWindow(win, 'shortcut', { action: 'save' });
            return;
        }

        if (input.control && !input.alt && input.key.toLowerCase() === 'r') {
            event.preventDefault();
        }
    });
}

function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function readSmokeState(win) {
    return win.webContents.executeJavaScript('window.__univmSmoke ? window.__univmSmoke.state() : null');
}

async function captureSmoke(win, fileName) {
    for (let attempt = 0; attempt < 4; attempt += 1) {
        try {
            if (win.isMinimized()) win.restore();
            win.show();
            const image = await win.webContents.capturePage();
            await fsp.writeFile(path.join(SMOKE_DIR, fileName), image.toPNG());
            return;
        } catch (error) {
            if (attempt === 3) {
                console.warn(`[smoke] screenshot ${fileName} skipped: ${error.message}`);
                return;
            }
            await delay(250);
        }
    }
}

function pressF5(win, shift) {
    const modifiers = shift ? ['shift'] : [];
    win.webContents.sendInputEvent({ type: 'keyDown', keyCode: 'F5', modifiers });
    win.webContents.sendInputEvent({ type: 'keyUp', keyCode: 'F5', modifiers });
}

async function runSmokeTest(win) {
    const report = {
        startedAt: new Date().toISOString(),
        ok: false
    };
    const smokeSaveFile = path.join(EXAMPLES_DIR, '_smoke_save.uni');

    try {
        await fsp.mkdir(SMOKE_DIR, { recursive: true });

        let ready = false;
        for (let attempt = 0; attempt < 80; attempt += 1) {
            ready = await win.webContents.executeJavaScript(
                'Boolean(window.__univmSmoke && window.__univmSmoke.state().monacoReady)'
            );
            if (ready) break;
            await delay(250);
        }
        if (!ready) throw new Error('Monaco did not become ready.');

        await delay(900);
        const initial = await readSmokeState(win);
        report.initial = initial;
        await captureSmoke(win, '01-initial.png');
        if (!initial || initial.tabs.length === 0) throw new Error('No example file opened automatically.');
        if (initial.active !== 'hello_zh.uni') throw new Error(`Unexpected initial file: ${initial.active}`);

        await win.webContents.executeJavaScript(`(() => {
            const row = [...document.querySelectorAll('.tree-row.file')]
                .find((item) => item.dataset.rel === 'funcs.uni');
            if (!row) throw new Error('funcs.uni is missing from the explorer.');
            row.click();
            return true;
        })()`);
        await delay(700);
        const opened = await readSmokeState(win);
        report.opened = opened;
        if (!opened || opened.active !== 'funcs.uni') throw new Error('Explorer click did not open funcs.uni.');

        await win.webContents.executeJavaScript(`window.__univmSmoke.setCode('let i = 0;\\nwhile (1) {\\n  i = i + 1;\\n}\\n')`);
        pressF5(win, false);
        await delay(1400);
        const running = await readSmokeState(win);
        report.running = running;
        await captureSmoke(win, '02-running.png');
        if (!running || !running.running) throw new Error('F5 did not start the Java process.');

        pressF5(win, true);
        await delay(1400);
        const stopped = await readSmokeState(win);
        report.stopped = stopped;
        await captureSmoke(win, '03-stopped.png');
        if (!stopped || stopped.running) throw new Error('Shift+F5 did not terminate the Java process.');
        if (!stopped.terminal.includes('[stopped]')) throw new Error('Stopped marker is missing from the terminal.');

        await win.webContents.executeJavaScript(`window.__univmSmoke.setCode('let i = 0;\\nwhile (1) {\\n  i = i + 1;\\n}\\n')`);
        await win.webContents.executeJavaScript('document.getElementById("run-button").click()');
        await delay(1400);
        const runningByButton = await readSmokeState(win);
        report.runningByButton = runningByButton;
        await captureSmoke(win, '04-running-button.png');
        if (!runningByButton || !runningByButton.running) throw new Error('Run button did not start the Java process.');

        await win.webContents.executeJavaScript('document.getElementById("stop-button").click()');
        await delay(1400);
        const stoppedByButton = await readSmokeState(win);
        report.stoppedByButton = stoppedByButton;
        await captureSmoke(win, '05-stopped-button.png');
        if (!stoppedByButton || stoppedByButton.running) throw new Error('Stop button did not terminate the Java process.');

        await win.webContents.executeJavaScript(`window.__univmSmoke.setCode('print "smoke ok";\\n')`);
        pressF5(win, false);
        await delay(2200);
        const completed = await readSmokeState(win);
        report.completed = completed;
        await captureSmoke(win, '06-complete.png');
        if (!completed || completed.running) throw new Error('Normal run did not finish.');
        if (!completed.terminal.includes('smoke ok')) throw new Error('Program output is missing from the terminal.');
        if (!completed.terminal.includes('[exit 0]')) throw new Error('Successful exit marker is missing.');

        await win.webContents.executeJavaScript(`window.__univmSmoke.setCode('let answer = input(\"name? \");\\nprint \"hello \" + answer;\\n')`);
        pressF5(win, false);
        await delay(900);
        const awaitingInput = await readSmokeState(win);
        report.awaitingInput = awaitingInput;
        if (!awaitingInput || !awaitingInput.running) throw new Error('Interactive run did not start.');
        if (!awaitingInput.inputEnabled) throw new Error('Terminal stdin field is disabled while running.');

        await win.webContents.executeJavaScript(`(() => {
            const input = document.getElementById('terminal-input');
            input.value = 'smoke-user';
            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            return true;
        })()`);
        await delay(1400);
        const interactive = await readSmokeState(win);
        report.interactive = interactive;
        await captureSmoke(win, '07-stdin.png');
        if (!interactive || interactive.running) throw new Error('Interactive run did not finish.');
        if (!interactive.terminal.includes('hello smoke-user')) throw new Error('stdin value was not received by the process.');
        if (!interactive.terminal.includes('[exit 0]')) throw new Error('Interactive run did not exit cleanly.');

        await fsp.writeFile(smokeSaveFile, 'print "save-original";\n', 'utf8');
        await win.webContents.executeJavaScript('document.getElementById("refresh-tree").click()');
        await delay(700);
        await win.webContents.executeJavaScript(`(() => {
            const row = [...document.querySelectorAll('.tree-row.file')]
                .find((item) => item.dataset.rel === '_smoke_save.uni');
            if (!row) throw new Error('Save-smoke file is missing from the explorer.');
            row.click();
            return true;
        })()`);
        await delay(700);
        await win.webContents.executeJavaScript(`window.__univmSmoke.setCode('print "save-test";\\n')`);
        await delay(200);
        await win.webContents.executeJavaScript('document.getElementById("save-button").click()');
        await delay(800);

        const saved = await readSmokeState(win);
        const savedDiskContent = await fsp.readFile(smokeSaveFile, 'utf8');
        report.saved = saved;
        report.savedDiskContent = savedDiskContent;
        await captureSmoke(win, '08-save.png');
        const savedTab = saved && saved.tabs.find((tab) => tab.rel === '_smoke_save.uni');
        if (!savedTab || savedTab.dirty) throw new Error('Save did not clear the dirty state.');
        if (savedDiskContent !== 'print "save-test";\n') throw new Error('Save did not write the editor content to disk.');
        if (!saved.status.includes('Saved examples/_smoke_save.uni')) throw new Error('Save status was not reported.');

        report.ok = true;
        report.finishedAt = new Date().toISOString();
        console.log('[smoke] PASS');
    } catch (error) {
        report.error = error.stack || String(error);
        console.error('[smoke] FAIL', report.error);
    } finally {
        await stopActiveRun();
        await fsp.rm(smokeSaveFile, { force: true });
        await fsp.writeFile(path.join(SMOKE_DIR, 'report.json'), JSON.stringify(report, null, 2), 'utf8');
        await delay(300);
        app.exit(report.ok ? 0 : 1);
    }
}

function createWindow() {
    const win = new BrowserWindow({
        width: 1460,
        height: 920,
        minWidth: 1040,
        minHeight: 660,
        show: false,
        frame: false,
        backgroundColor: '#080b10',
        title: 'UniVM Studio',
        icon: ICON_PATH,
        webPreferences: {
            preload: path.join(APP_DIR, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            webSecurity: true,
            spellcheck: false,
            devTools: true
        }
    });

    mainWindow = win;
    Menu.setApplicationMenu(null);
    wireWindowControls(win);

    win.webContents.once('did-finish-load', () => {
        if (SMOKE_MODE) runSmokeTest(win);
    });
    win.loadURL(`${appOrigin}/index.html${SMOKE_MODE ? '?smoke=1' : ''}`);

    win.once('ready-to-show', () => {
        win.show();
        win.focus();
        if (process.argv.includes('--dev')) {
            win.webContents.openDevTools({ mode: 'detach' });
        }
    });

    win.on('closed', () => {
        if (activeRun && activeRun.win === win) {
            stopRunProcess(activeRun);
        }
        if (mainWindow === win) mainWindow = null;
    });

    win.webContents.setWindowOpenHandler(({ url }) => {
        if (/^https?:/i.test(url)) shell.openExternal(url);
        return { action: 'deny' };
    });

    win.webContents.on('will-navigate', (event, url) => {
        if (!appOrigin || !url.startsWith(`${appOrigin}/`)) event.preventDefault();
    });

    win.webContents.on('did-fail-load', (_event, errorCode, errorDescription) => {
        console.error(`[window] load failed ${errorCode}: ${errorDescription}`);
    });

    win.webContents.on('console-message', (...args) => {
        let level = 'log';
        let message = '';
        let source = '';
        if (args[1] && typeof args[1] === 'object') {
            const details = args[1];
            level = details.level ?? level;
            message = details.message ?? '';
            source = details.sourceId ? `${details.sourceId}:${details.lineNumber ?? 0}` : '';
        } else {
            level = args[1] ?? level;
            message = args[2] ?? '';
            source = args[4] ? `${args[4]}:${args[3] ?? 0}` : '';
        }
        if (message) console.log(`[renderer:${level}] ${message}${source ? ` (${source})` : ''}`);
    });

    win.webContents.on('render-process-gone', (_event, details) => {
        console.error('[renderer gone]', details);
    });
}

ipcMain.handle('app:info', () => ({
    version: APP_VERSION,
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
    platform: process.platform
}));

ipcMain.handle('examples:list', async () => {
    if (!fs.existsSync(EXAMPLES_DIR)) {
        throw new Error(`examples 目录不存在：${EXAMPLES_DIR}`);
    }
    return {
        type: 'directory',
        name: 'examples',
        rel: '',
        children: await readTree(EXAMPLES_DIR)
    };
});

ipcMain.handle('examples:read', async (_event, relativePath) => readExample(relativePath));
ipcMain.handle('examples:write', async (_event, relativePath, content) => writeExample(relativePath, content));

ipcMain.handle('run:start', async (event, payload) => startRun(event, payload));

ipcMain.handle('run:input', (event, payload) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const run = activeRun;
    if (!win || !run || run.win !== win || run.ended || run.stopping) return false;
    if (!payload || payload.runId !== run.id || typeof payload.text !== 'string') return false;
    if (Buffer.byteLength(payload.text, 'utf8') > 512 * 1024) {
        throw new Error('输入内容过长。');
    }
    if (!run.child.stdin || run.child.stdin.destroyed) return false;
    const line = payload.text.replace(/[\r\n]+/g, ' ');
    run.child.stdin.write(`${line}\n`, 'utf8');
    return true;
});

ipcMain.handle('run:stop', async () => stopActiveRun());

ipcMain.on('window:minimize', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.minimize();
});

ipcMain.handle('window:toggle-maximize', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win) return false;
    if (win.isMaximized()) win.unmaximize();
    else win.maximize();
    return win.isMaximized();
});

ipcMain.on('window:close', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.close();
});

ipcMain.handle('window:is-maximized', (event) => {
    return BrowserWindow.fromWebContents(event.sender)?.isMaximized() ?? false;
});

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (!mainWindow) return;
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.show();
        mainWindow.focus();
    });

    app.whenReady().then(async () => {
        app.setAppUserModelId('com.univm.studio');
        await startStaticServer();
        createWindow();

        app.on('activate', () => {
            if (BrowserWindow.getAllWindows().length === 0) createWindow();
        });
    }).catch((error) => {
        console.error(error);
        dialog.showErrorBox('UniVM Studio 启动失败', error.stack || String(error));
        app.quit();
    });
}

app.on('before-quit', () => {
    if (activeRun) {
        activeRun.stopping = true;
        try { activeRun.child.kill(); } catch { /* already gone */ }
    }
});

app.on('will-quit', () => {
    if (staticServer) {
        staticServer.close();
        staticServer = null;
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});
