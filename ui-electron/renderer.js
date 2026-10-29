'use strict';

const api = window.univm;
const $ = (id) => document.getElementById(id);

const dom = {
    app: $('app'),
    titlebar: $('titlebar'),
    windowMinimize: $('window-minimize'),
    windowMaximize: $('window-maximize'),
    windowClose: $('window-close'),
    windowFileLabel: $('window-file-label'),
    saveButton: $('save-button'),
    runButton: $('run-button'),
    stopButton: $('stop-button'),
    engineState: $('engine-state'),
    engineLabel: $('engine-label'),
    appVersion: $('app-version'),
    refreshTree: $('refresh-tree'),
    tree: $('file-tree'),
    treeCount: $('tree-count'),
    tabs: $('tabs'),
    breadcrumbs: $('breadcrumbs'),
    editor: $('editor'),
    emptyState: $('empty-state'),
    loadingOverlay: $('loading-overlay'),
    cursorStatus: $('cursor-status'),
    languageStatus: $('language-status'),
    terminal: $('terminal'),
    terminalInput: $('terminal-input'),
    terminalState: $('terminal-state'),
    terminalSplitter: $('terminal-splitter'),
    clearTerminal: $('clear-terminal'),
    copyTerminal: $('copy-terminal'),
    statusMessage: $('status-message'),
    runtimeLabel: $('runtime-label'),
    backendLabel: $('backend-label'),
    platformLabel: $('platform-label')
};

const state = {
    monaco: null,
    editor: null,
    tabs: [],
    active: null,
    running: false,
    runId: null,
    stopping: false,
    terminalChars: 0,
    treeRoot: null,
    maxTerminalHeight: 0
};

const FILE_TYPES = {
    uni: { language: 'univm', label: 'UniVM', icon: 'U', kind: 'uni' },
    py: { language: 'python', label: 'Python', icon: 'Py', kind: 'py' },
    c: { language: 'cpp', label: 'C', icon: 'C', kind: 'c' },
    h: { language: 'cpp', label: 'C', icon: 'C', kind: 'c' },
    cpp: { language: 'cpp', label: 'C++', icon: 'C+', kind: 'cpp' },
    cc: { language: 'cpp', label: 'C++', icon: 'C+', kind: 'cpp' },
    cxx: { language: 'cpp', label: 'C++', icon: 'C+', kind: 'cpp' },
    lua: { language: 'lua', label: 'Lua', icon: 'L', kind: 'lua' },
    rs: { language: 'rust', label: 'Rust', icon: 'R', kind: 'rs' },
    lisp: { language: 'scheme', label: 'Lisp', icon: 'λ', kind: 'lisp' },
    json: { language: 'json', label: 'JSON', icon: '{}', kind: 'default' },
    md: { language: 'markdown', label: 'Markdown', icon: 'M', kind: 'default' }
};

const KEYWORDS = [
    'let', 'func', 'fn', 'if', 'else', 'while', 'for', 'return', 'break', 'continue',
    'true', 'false', 'null', 'int', 'string', 'array', 'main', 'print'
];

const BUILTINS = [
    ['print', 'print(value)', 'Print a value to the terminal.'],
    ['input', 'input(prompt)', 'Read a line from stdin. In the GUI an input dialog is opened.'],
    ['toInt', 'toInt(text)', 'Parse a string as a 64-bit integer, returning 0 on failure.'],
    ['random', 'random(min, max)', 'Return a random integer in the inclusive range.'],
    ['str', 'str(value)', 'Convert a value to a string.'],
    ['len', 'len(value)', 'Return the length of a string or array.'],
    ['time', 'time()', 'Return the current time in milliseconds.'],
    ['read_file', 'read_file(path)', 'Read a UTF-8 file through the UniVM standard library.'],
    ['write_file', 'write_file(path, text)', 'Write a UTF-8 file through the UniVM standard library.'],
    ['concat', 'concat(a, b)', 'Concatenate two strings.'],
    ['str_int', 'str_int(value)', 'Convert an integer to a string.'],
    ['to_int', 'to_int(text)', 'Parse an integer from a string.']
];

function extensionOf(name) {
    const index = name.lastIndexOf('.');
    return index >= 0 ? name.slice(index + 1).toLowerCase() : '';
}

function fileType(name) {
    return FILE_TYPES[extensionOf(name)] || { language: 'plaintext', label: 'Text', icon: '·', kind: 'default' };
}

function fileIcon(name) {
    const type = fileType(name);
    const icon = document.createElement('span');
    icon.className = `file-icon ext-${type.kind}`;
    icon.textContent = type.icon;
    icon.setAttribute('aria-hidden', 'true');
    return icon;
}

function setStatus(text, tone) {
    dom.statusMessage.textContent = text;
    dom.statusMessage.dataset.tone = tone || '';
}

function setEngine(mode, label) {
    dom.engineState.classList.toggle('running', mode === 'running');
    dom.engineState.classList.toggle('stopping', mode === 'stopping');
    dom.engineState.classList.toggle('error', mode === 'error');
    dom.engineLabel.textContent = label;
}

function setTerminalState(text, tone) {
    dom.terminalState.textContent = text;
    dom.terminalState.className = 'terminal-state';
    if (tone) dom.terminalState.classList.add(tone);
}

function updateWindowTitle() {
    const name = state.active ? state.active.rel : 'Welcome';
    dom.windowFileLabel.textContent = name === 'Welcome' ? name : `examples/${name}`;
    dom.windowFileLabel.title = name;
    document.title = state.active ? `${state.active.name} — UniVM Studio` : 'UniVM Studio';
}

function updateCursorStatus(position) {
    if (!position) {
        dom.cursorStatus.textContent = 'Ln 1, Col 1';
        return;
    }
    dom.cursorStatus.textContent = `Ln ${position.lineNumber}, Col ${position.column}`;
}

function updateLanguageStatus() {
    if (!state.active) {
        dom.languageStatus.textContent = 'UniVM';
        return;
    }
    dom.languageStatus.textContent = fileType(state.active.name).label;
}

function updateBreadcrumbs() {
    dom.breadcrumbs.textContent = '';
    const examples = document.createElement('span');
    examples.className = 'breadcrumb-muted';
    examples.textContent = 'examples';
    dom.breadcrumbs.appendChild(examples);

    if (!state.active) {
        const separator = document.createElement('span');
        separator.className = 'breadcrumb-separator';
        separator.textContent = '›';
        dom.breadcrumbs.appendChild(separator);
        const hint = document.createElement('span');
        hint.className = 'breadcrumb-active';
        hint.textContent = 'select a file';
        dom.breadcrumbs.appendChild(hint);
        return;
    }

    const parts = state.active.rel.split('/');
    parts.forEach((part, index) => {
        const separator = document.createElement('span');
        separator.className = 'breadcrumb-separator';
        separator.textContent = '›';
        dom.breadcrumbs.appendChild(separator);

        const item = document.createElement('span');
        item.className = index === parts.length - 1 ? 'breadcrumb-file' : 'breadcrumb-muted';
        if (index === parts.length - 1) {
            item.appendChild(fileIcon(part));
        }
        const text = document.createElement('span');
        text.textContent = part;
        item.appendChild(text);
        dom.breadcrumbs.appendChild(item);
    });
}

function updateActionStates() {
    dom.saveButton.disabled = !state.active;
    dom.runButton.disabled = state.running || !state.active;
    dom.stopButton.disabled = !state.running || state.stopping;
}

async function saveActiveFile() {
    if (!state.active) {
        setStatus('Open a file before saving', 'warning');
        return false;
    }

    const tab = state.active;
    if (!tab.dirty) {
        setStatus(`No changes to save in ${tab.name}`);
        return true;
    }

    const content = tab.model.getValue();
    setStatus(`Saving examples/${tab.rel}...`);
    try {
        await api.saveExample(tab.rel, content);
        tab.originalContent = content;
        tab.dirty = tab.model.getValue() !== tab.originalContent;
        renderTabs();
        setStatus(`Saved examples/${tab.rel}`, 'success');
        return true;
    } catch (error) {
        appendTerm(`\n[save] Unable to save ${tab.rel}: ${error.message}\n`, 'error');
        setStatus(`Failed to save ${tab.name}`, 'error');
        return false;
    }
}

function setEmpty(visible) {
    dom.emptyState.classList.toggle('hidden', !visible);
}

function showEmptyState() {
    state.active = null;
    if (state.editor) state.editor.setModel(null);
    setEmpty(true);
    renderTabs();
    updateWindowTitle();
    updateBreadcrumbs();
    updateLanguageStatus();
    updateActionStates();
    updateCursorStatus(null);
}

/* -------------------------------------------------------------------------- */
/* Terminal                                                                   */
/* -------------------------------------------------------------------------- */

function terminalCursor() {
    const cursor = document.createElement('span');
    cursor.className = 'term-cursor';
    cursor.setAttribute('aria-hidden', 'true');
    return cursor;
}

function ensureTerminalCursor() {
    if (!dom.terminal.querySelector('.term-cursor')) {
        dom.terminal.appendChild(terminalCursor());
    }
}

function appendTerm(text, kind = 'stdout') {
    if (text === undefined || text === null || text === '') return;
    const cursor = dom.terminal.querySelector('.term-cursor');
    const span = document.createElement('span');
    span.className = `term-line ${kind}`;
    span.textContent = String(text);
    if (cursor) dom.terminal.insertBefore(span, cursor);
    else dom.terminal.appendChild(span);

    state.terminalChars += String(text).length;
    while (state.terminalChars > 350000 && dom.terminal.firstChild) {
        if (dom.terminal.firstChild === cursor) break;
        const first = dom.terminal.firstChild;
        state.terminalChars -= (first.textContent || '').length;
        dom.terminal.removeChild(first);
    }
    ensureTerminalCursor();
    dom.terminal.scrollTop = dom.terminal.scrollHeight;
}

function clearTerminal() {
    dom.terminal.textContent = '';
    state.terminalChars = 0;
    ensureTerminalCursor();
}

function terminalText() {
    return dom.terminal ? dom.terminal.innerText : '';
}

function setTerminalInputEnabled(enabled) {
    dom.terminalInput.disabled = !enabled;
    if (!enabled) dom.terminalInput.value = '';
}

async function sendTerminalInput() {
    if (!state.running || !state.runId || dom.terminalInput.disabled) return;
    const text = dom.terminalInput.value;
    dom.terminalInput.value = '';
    appendTerm(`${text}\n`, 'stdin');
    try {
        const accepted = await api.sendInput(text, state.runId);
        if (!accepted) {
            appendTerm('[stdin] the process is not accepting input.\\n', 'warning');
        }
    } catch (error) {
        appendTerm(`[stdin] ${error.message}\\n`, 'error');
    }
    dom.terminalInput.focus();
}

function setTerminalRunning(running) {
    if (running) {
        setTerminalState(state.stopping ? 'stopping' : 'running', state.stopping ? 'stopping' : 'running');
    } else {
        setTerminalState('idle', '');
    }
}

/* -------------------------------------------------------------------------- */
/* File tree                                                                  */
/* -------------------------------------------------------------------------- */

function chevron(open) {
    const span = document.createElement('span');
    span.className = `tree-chevron${open ? ' open' : ''}`;
    span.innerHTML = '<svg viewBox="0 0 16 16" width="10" height="10" aria-hidden="true"><path d="M6 3.5l5 4.5-5 4.5" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    return span;
}

function folderIcon() {
    const span = document.createElement('span');
    span.className = 'tree-folder-icon';
    span.innerHTML = '<svg viewBox="0 0 18 18" width="15" height="15" aria-hidden="true"><path d="M2.2 5.1h5l1.3 1.6h7.3v7.7H2.2z" fill="rgba(86,217,255,.08)" stroke="#5aafc9" stroke-width="1.15" stroke-linejoin="round"/><path d="M2.2 5.1v8.4" fill="none" stroke="#5aafc9" stroke-width="1.15"/></svg>';
    return span;
}

function countFiles(node) {
    if (node.type === 'file') return 1;
    return (node.children || []).reduce((sum, child) => sum + countFiles(child), 0);
}

function renderTree(root) {
    state.treeRoot = root;
    dom.tree.textContent = '';
    const fragment = document.createDocumentFragment();

    function renderNodes(nodes, parent, depth) {
        for (const node of nodes) {
            if (node.type === 'directory') {
                const open = depth < 2;
                const row = document.createElement('div');
                row.className = 'tree-row directory';
                row.style.setProperty('--depth', String(depth));
                row.setAttribute('role', 'treeitem');
                row.setAttribute('aria-expanded', String(open));
                row.appendChild(chevron(open));
                row.appendChild(folderIcon());

                const name = document.createElement('span');
                name.className = 'file-name';
                name.textContent = node.name;
                row.appendChild(name);

                const count = document.createElement('span');
                count.className = 'file-badge';
                count.textContent = String(countFiles(node));
                row.appendChild(count);

                const children = document.createElement('div');
                children.className = `tree-children${open ? '' : ' collapsed'}`;
                renderNodes(node.children || [], children, depth + 1);

                row.addEventListener('click', () => {
                    const nextOpen = children.classList.contains('collapsed');
                    children.classList.toggle('collapsed', !nextOpen);
                    row.setAttribute('aria-expanded', String(nextOpen));
                    row.querySelector('.tree-chevron').classList.toggle('open', nextOpen);
                });

                parent.appendChild(row);
                parent.appendChild(children);
            } else {
                const row = document.createElement('div');
                row.className = 'tree-row file';
                row.style.setProperty('--depth', String(depth));
                row.dataset.rel = node.rel;
                row.setAttribute('role', 'treeitem');
                row.tabIndex = 0;
                row.appendChild(chevron(false));
                row.lastChild.style.visibility = 'hidden';
                row.appendChild(fileIcon(node.name));

                const name = document.createElement('span');
                name.className = 'file-name';
                name.textContent = node.name;
                row.appendChild(name);

                const ext = document.createElement('span');
                ext.className = 'file-badge';
                ext.textContent = extensionOf(node.name).toUpperCase();
                row.appendChild(ext);

                row.addEventListener('click', () => openFile(node));
                row.addEventListener('keydown', (event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        openFile(node);
                    }
                });
                parent.appendChild(row);
            }
        }
    }

    renderNodes(root.children || [], fragment, 0);
    dom.tree.appendChild(fragment);
    dom.treeCount.textContent = `${countFiles(root)} files`;
}

function updateTreeActive() {
    const activeRel = state.active ? state.active.rel : null;
    dom.tree.querySelectorAll('.tree-row.file').forEach((row) => {
        row.classList.toggle('active', row.dataset.rel === activeRel);
    });
}

function findFirstFile(node) {
    if (node.type === 'file') return node;
    for (const child of node.children || []) {
        const found = findFirstFile(child);
        if (found) return found;
    }
    return null;
}

function findFileByName(node, name) {
    if (node.type === 'file' && node.name === name) return node;
    for (const child of node.children || []) {
        const found = findFileByName(child, name);
        if (found) return found;
    }
    return null;
}

async function loadExamples() {
    setStatus('Loading examples...');
    try {
        const root = await api.listExamples();
        renderTree(root);
        setStatus(`Loaded ${countFiles(root)} example files`);
        if (state.tabs.length === 0) {
            const preferred = findFileByName(root, 'hello_zh.uni') || findFirstFile(root);
            if (preferred) await openFile(preferred);
        } else if (state.active) {
            updateTreeActive();
        }
    } catch (error) {
        appendTerm(`\n[explorer] ${error.message}\n`, 'error');
        setStatus('Unable to load examples', 'error');
    }
}

/* -------------------------------------------------------------------------- */
/* Tabs and editor                                                            */
/* -------------------------------------------------------------------------- */

function renderTabs() {
    dom.tabs.textContent = '';
    for (const tab of state.tabs) {
        const element = document.createElement('div');
        element.className = `tab${tab === state.active ? ' active' : ''}`;
        element.setAttribute('role', 'tab');
        element.setAttribute('aria-selected', String(tab === state.active));
        element.title = `examples/${tab.rel}`;

        element.appendChild(fileIcon(tab.name));

        const name = document.createElement('span');
        name.className = 'tab-name';
        name.textContent = tab.name;
        element.appendChild(name);

        if (tab.dirty) {
            const dot = document.createElement('span');
            dot.className = 'tab-dirty';
            dot.title = 'Unsaved changes';
            element.appendChild(dot);
        }

        const close = document.createElement('button');
        close.className = 'tab-close';
        close.type = 'button';
        close.title = 'Close';
        close.setAttribute('aria-label', `Close ${tab.name}`);
        close.textContent = '×';
        close.addEventListener('click', (event) => {
            event.stopPropagation();
            closeTab(tab);
        });
        element.appendChild(close);

        element.addEventListener('click', () => activateTab(tab));
        dom.tabs.appendChild(element);
    }

    const activeElement = dom.tabs.querySelector('.tab.active');
    if (activeElement) activeElement.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function activateTab(tab) {
    if (!state.editor || !tab) return;
    if (state.active && state.active !== tab && state.editor.getModel()) {
        state.active.viewState = state.editor.saveViewState();
    }

    state.active = tab;
    state.editor.setModel(tab.model);
    if (tab.viewState) state.editor.restoreViewState(tab.viewState);
    setEmpty(false);
    renderTabs();
    updateWindowTitle();
    updateBreadcrumbs();
    updateLanguageStatus();
    updateCursorStatus(state.editor.getPosition());
    updateTreeActive();
    updateActionStates();
    state.editor.focus();
}

function closeTab(tab) {
    if (tab.dirty) {
        const discard = window.confirm(`Discard unsaved changes in ${tab.name}?`);
        if (!discard) return;
    }

    const index = state.tabs.indexOf(tab);
    if (index < 0) return;
    state.tabs.splice(index, 1);
    tab.model.dispose();

    if (state.active === tab) {
        const next = state.tabs[Math.min(index, state.tabs.length - 1)];
        if (next) {
            state.active = null;
            activateTab(next);
        } else {
            showEmptyState();
        }
    } else {
        renderTabs();
    }
}

async function openFile(node) {
    const existing = state.tabs.find((tab) => tab.rel === node.rel);
    if (existing) {
        activateTab(existing);
        return;
    }

    setStatus(`Opening ${node.name}...`);
    try {
        const data = await api.readExample(node.rel);
        const type = fileType(data.name);
        const uri = state.monaco.Uri.from({
            scheme: 'univm',
            authority: 'workspace',
            path: `/${data.rel}`
        });
        const model = state.monaco.editor.createModel(data.content, type.language, uri);
        const tab = {
            rel: data.rel,
            name: data.name,
            language: type.language,
            model,
            originalContent: data.content,
            dirty: false,
            viewState: null
        };

        model.onDidChangeContent(() => {
            const dirty = model.getValue() !== tab.originalContent;
            if (dirty !== tab.dirty) {
                tab.dirty = dirty;
                renderTabs();
            }
        });

        state.tabs.push(tab);
        activateTab(tab);
        setStatus(`Opened examples/${data.rel}`);
    } catch (error) {
        appendTerm(`\n[explorer] Unable to open ${node.rel}: ${error.message}\n`, 'error');
        setStatus(`Failed to open ${node.name}`, 'error');
    }
}

/* -------------------------------------------------------------------------- */
/* Run / stop                                                                 */
/* -------------------------------------------------------------------------- */

function newRunId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID();
    }
    return `run-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

async function runCode() {
    if (state.running) return;
    if (!state.active) {
        setStatus('Open a file before running', 'warning');
        return;
    }

    const tab = state.active;
    const code = tab.model.getValue();
    const runId = newRunId();

    state.running = true;
    state.stopping = false;
    state.runId = runId;

    clearTerminal();
    appendTerm('> java -cp ../out Main temp.uni --run\n', 'command');
    appendTerm(`── ${tab.name} ─────────────────────────────────────────\n`, 'meta');
    setEngine('running', 'Java process running');
    setTerminalState('running', 'running');
    setStatus(`Running examples/${tab.rel}...`);
    setTerminalInputEnabled(true);
    updateActionStates();

    try {
        const result = await api.run(code, runId);
        if (result && result.pid) {
            setEngine('running', `Java pid ${result.pid}`);
        }
    } catch (error) {
        appendTerm(`\n[launcher] ${error.message}\n`, 'error');
        state.running = false;
        state.runId = null;
        state.stopping = false;
        setEngine('error', 'Run failed');
        setTerminalState('error', 'error');
        setStatus('Run failed', 'error');
        setTerminalInputEnabled(false);
        updateActionStates();
    }
}

async function stopRun() {
    if (!state.running || state.stopping) return;
    state.stopping = true;
    setEngine('stopping', 'Stopping Java process...');
    setTerminalState('stopping', 'stopping');
    setStatus('Stopping Java process...', 'warning');
    setTerminalInputEnabled(false);
    appendTerm('\n[stop] terminating Java process tree...\n', 'warning');
    updateActionStates();
    try {
        const stopped = await api.stop();
        if (!stopped) {
            setStatus('Process already exited', 'warning');
        }
    } catch (error) {
        appendTerm(`\n[launcher] stop failed: ${error.message}\n`, 'error');
        setEngine('error', 'Stop failed');
        setTerminalState('error', 'error');
        setStatus('Stop failed', 'error');
    }
    updateActionStates();
}

function handleRunOutput(payload) {
    if (!payload || payload.runId !== state.runId) return;
    const kind = payload.stream === 'stderr' ? 'stderr' : 'stdout';
    appendTerm(payload.data, kind);
}

function handleRunExit(payload) {
    if (!payload || payload.runId !== state.runId) return;

    const wasStopped = payload.stopped;
    const failed = payload.code !== 0 && !wasStopped;
    state.running = false;
    state.stopping = false;
    state.runId = null;
    setTerminalInputEnabled(false);

    if (wasStopped) {
        appendTerm('\n[stopped] Java process terminated by user.\n', 'warning');
        setStatus('Process stopped', 'warning');
        setEngine('ready', 'Java backend ready');
        setTerminalState('stopped', '');
    } else if (payload.error) {
        appendTerm(`\n[failed] ${payload.error}\n`, 'error');
        setStatus('Java process failed to start', 'error');
        setEngine('error', 'Java process failed');
        setTerminalState('error', 'error');
    } else if (failed) {
        appendTerm(`\n[exit ${payload.code}] process finished with errors.\n`, 'error');
        setStatus(`Exited with code ${payload.code}`, 'error');
        setEngine('error', 'Last run failed');
        setTerminalState(`exit ${payload.code}`, 'error');
    } else {
        appendTerm(`\n[exit 0] completed in ${payload.durationMs} ms.\n`, 'success');
        setStatus(`Run completed in ${payload.durationMs} ms`, 'success');
        setEngine('ready', 'Java backend ready');
        setTerminalState('exit 0', '');
    }

    updateActionStates();
}

/* -------------------------------------------------------------------------- */
/* Chrome, shortcuts and terminal controls                                    */
/* -------------------------------------------------------------------------- */

function setMaximized(maximized) {
    document.body.classList.toggle('maximized', Boolean(maximized));
    dom.windowMaximize.title = maximized ? 'Restore' : 'Maximize';
}

function wireChrome() {
    dom.windowMinimize.addEventListener('click', () => api.windowControls.minimize());
    dom.windowMaximize.addEventListener('click', async () => {
        setMaximized(await api.windowControls.toggleMaximize());
    });
    dom.windowClose.addEventListener('click', () => api.windowControls.close());
    dom.titlebar.addEventListener('dblclick', (event) => {
        if (event.target.closest('button')) return;
        api.windowControls.toggleMaximize().then(setMaximized);
    });

    api.onMaximizedChange(setMaximized);
    api.windowControls.isMaximized().then(setMaximized);
    api.onShortcut((payload) => {
        if (!payload) return;
        if (payload.action === 'run') runCode();
        if (payload.action === 'stop') stopRun();
        if (payload.action === 'save') saveActiveFile();
    });

    api.onRunOutput(handleRunOutput);
    api.onRunExit(handleRunExit);
}

function terminalPanelHeight() {
    const value = getComputedStyle(document.documentElement).getPropertyValue('--terminal-height').trim();
    return parseFloat(value) || 218;
}

function wireTerminal() {
    dom.terminalInput.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        sendTerminalInput();
    });

    dom.clearTerminal.addEventListener('click', () => {
        clearTerminal();
        setStatus('Terminal cleared');
    });
    dom.copyTerminal.addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(terminalText());
            setStatus('Terminal copied to clipboard', 'success');
        } catch (error) {
            setStatus('Clipboard unavailable', 'error');
        }
    });

    let dragStartY = 0;
    let dragStartHeight = 0;
    let dragging = false;

    dom.terminalSplitter.addEventListener('pointerdown', (event) => {
        dragging = true;
        dragStartY = event.clientY;
        dragStartHeight = terminalPanelHeight();
        state.maxTerminalHeight = Math.min(560, Math.max(180, window.innerHeight * 0.62));
        dom.terminalSplitter.setPointerCapture(event.pointerId);
        document.body.classList.add('resizing-terminal');
        event.preventDefault();
    });

    dom.terminalSplitter.addEventListener('pointermove', (event) => {
        if (!dragging) return;
        const delta = dragStartY - event.clientY;
        const height = Math.max(105, Math.min(state.maxTerminalHeight, dragStartHeight + delta));
        document.documentElement.style.setProperty('--terminal-height', `${height}px`);
    });

    const endDrag = () => {
        if (!dragging) return;
        dragging = false;
        document.body.classList.remove('resizing-terminal');
    };
    dom.terminalSplitter.addEventListener('pointerup', endDrag);
    dom.terminalSplitter.addEventListener('pointercancel', endDrag);

    dom.terminal.addEventListener('click', () => {
        dom.terminal.scrollTop = dom.terminal.scrollHeight;
    });
}

async function loadAppInfo() {
    try {
        const info = await api.getAppInfo();
        dom.appVersion.textContent = `v${info.version}`;
        dom.backendLabel.textContent = `UniVM ${info.version}`;
        dom.runtimeLabel.textContent = `Electron ${info.electron}`;
        dom.platformLabel.textContent = info.platform === 'win32' ? 'Windows'
            : info.platform === 'darwin' ? 'macOS' : 'Linux';
    } catch {
        // The version labels already contain sensible fallbacks.
    }
}

/* -------------------------------------------------------------------------- */
/* Monaco                                                                     */
/* -------------------------------------------------------------------------- */

function registerUnivmLanguage(monaco) {
    monaco.languages.register({
        id: 'univm',
        extensions: ['.uni'],
        aliases: ['UniVM', 'univm']
    });

    monaco.languages.setLanguageConfiguration('univm', {
        comments: {
            lineComment: '//',
            blockComment: ['/*', '*/']
        },
        brackets: [['{', '}'], ['[', ']'], ['(', ')']],
        autoClosingPairs: [
            { open: '{', close: '}' },
            { open: '[', close: ']' },
            { open: '(', close: ')' },
            { open: '"', close: '"' },
            { open: "'", close: "'" }
        ],
        surroundingPairs: [
            { open: '{', close: '}' },
            { open: '[', close: ']' },
            { open: '(', close: ')' },
            { open: '"', close: '"' },
            { open: "'", close: "'" }
        ],
        folding: {
            markers: {
                start: /^\s*\/\*/,
                end: /\*\//
            }
        },
        indentationRules: {
            increaseIndentPattern: /^.*\{[^}"']*$/,
            decreaseIndentPattern: /^\s*\}/
        }
    });

    monaco.languages.setMonarchTokensProvider('univm', {
        defaultToken: '',
        tokenPostfix: '.univm',
        keywords: KEYWORDS,
        builtins: BUILTINS.map(([name]) => name),
        operators: ['+', '-', '*', '/', '%', '=', '==', '!=', '<', '<=', '>', '>=', '&&', '||', '!'],
        symbols: /[=><!~?:&|+\-*\/%^]+/,
        tokenizer: {
            root: [
                [/[a-zA-Z_]\w*/, {
                    cases: {
                        '@keywords': 'keyword',
                        '@builtins': 'predefined',
                        '@default': 'identifier'
                    }
                }],
                [/\d+(\.\d+)?/, 'number'],
                [/"/, { token: 'string.quote', bracket: '@open', next: '@string' }],
                [/'([^'\\]|\\.)*'/, 'string'],
                [/\/\/.*$/, 'comment'],
                [/#.*$/, 'comment'],
                [/\/\*/, 'comment', '@comment'],
                [/[{}()\[\]]/, '@brackets'],
                [/[<>](?!@symbols)/, '@brackets'],
                [/[=+\-*\/%!&|<>?:]+/, 'operator'],
                [/[;,.]/, 'delimiter']
            ],
            comment: [
                [/[^\/*]+/, 'comment'],
                [/\*\//, 'comment', '@pop'],
                [/[\/*]/, 'comment']
            ],
            string: [
                [/[^\\"]+/, 'string'],
                [/\\./, 'string.escape'],
                [/"/, { token: 'string.quote', bracket: '@close', next: '@pop' }]
            ]
        }
    });

    monaco.languages.registerCompletionItemProvider('univm', {
        triggerCharacters: ['(', '.'],
        provideCompletionItems: () => {
            const word = { start: 1, end: 1 };
            const suggestions = [];

            for (const keyword of KEYWORDS) {
                suggestions.push({
                    label: keyword,
                    kind: monaco.languages.CompletionItemKind.Keyword,
                    insertText: keyword,
                    detail: 'keyword',
                    range: word
                });
            }

            for (const [name, signature, documentation] of BUILTINS) {
                suggestions.push({
                    label: name,
                    kind: monaco.languages.CompletionItemKind.Function,
                    insertText: name === 'print' ? 'print ${1:value};' : `${name}($1)`,
                    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                    detail: signature,
                    documentation,
                    range: word
                });
            }

            const snippets = [
                ['let', 'let ${1:name} = ${2:0};', 'Declare a variable'],
                ['func', 'func ${1:name}(${2:args}) {\n\t$0\n}', 'Declare a function'],
                ['if', 'if (${1:condition}) {\n\t$0\n}', 'If statement'],
                ['while', 'while (${1:condition}) {\n\t$0\n}', 'While loop'],
                ['for', 'for (${1:init}; ${2:condition}; ${3:step}) {\n\t$0\n}', 'For loop']
            ];
            for (const [label, insertText, documentation] of snippets) {
                suggestions.push({
                    label,
                    kind: monaco.languages.CompletionItemKind.Snippet,
                    insertText,
                    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                    detail: 'snippet',
                    documentation,
                    range: word
                });
            }

            return { suggestions };
        }
    });

    monaco.languages.registerHoverProvider('univm', {
        provideHover: (model, position) => {
            const word = model.getWordAtPosition(position);
            if (!word) return null;
            const entry = BUILTINS.find(([name]) => name === word.word);
            if (!entry) return null;
            return {
                contents: [
                    { value: `**${entry[0]}**` },
                    { value: `\`${entry[1]}\`` },
                    { value: entry[2] }
                ]
            };
        }
    });
}

function monacoTheme() {
    return {
        base: 'vs-dark',
        inherit: true,
        rules: [
            { token: 'comment', foreground: '5D7083', fontStyle: 'italic' },
            { token: 'keyword', foreground: '7FB8FF', fontStyle: 'bold' },
            { token: 'predefined', foreground: '66D9F1' },
            { token: 'number', foreground: 'F2C97D' },
            { token: 'string', foreground: '9BE3AE' },
            { token: 'string.escape', foreground: 'C6F6D5' },
            { token: 'operator', foreground: 'B7C5D8' },
            { token: 'delimiter', foreground: '8D9DB3' },
            { token: 'type', foreground: 'C5A3FF' },
            { token: 'identifier', foreground: 'D7E2F0' }
        ],
        colors: {
            'editor.background': '#0D1117',
            'editor.foreground': '#D7E2F0',
            'editorLineNumber.foreground': '#344154',
            'editorLineNumber.activeForeground': '#7D9ABD',
            'editorCursor.foreground': '#56D9FF',
            'editor.selectionBackground': '#1D3D5C',
            'editor.inactiveSelectionBackground': '#152A3D',
            'editor.lineHighlightBackground': '#0F1620',
            'editor.lineHighlightBorder': '#00000000',
            'editorIndentGuide.background1': '#182130',
            'editorIndentGuide.activeBackground1': '#2B3C51',
            'editorBracketHighlight.foreground1': '#56D9FF',
            'editorBracketHighlight.foreground2': '#C5A3FF',
            'editorBracketHighlight.foreground3': '#58E0A2',
            'editorBracketHighlight.foreground4': '#F2C97D',
            'editorGutter.background': '#0D1117',
            'editorWidget.background': '#111823',
            'editorWidget.border': '#253244',
            'editorSuggestWidget.background': '#111823',
            'editorSuggestWidget.border': '#253244',
            'editorSuggestWidget.selectedBackground': '#1B2C40',
            'editorHoverWidget.background': '#111823',
            'editorHoverWidget.border': '#253244',
            'editorOverviewRuler.border': '#00000000',
            'editorError.foreground': '#FF6B81',
            'editorWarning.foreground': '#F3C77B',
            'editorInfo.foreground': '#56D9FF',
            'editor.findMatchBackground': '#3B5D7A',
            'editor.findMatchHighlightBackground': '#20394F',
            'editorBracketMatch.background': '#183249',
            'editorBracketMatch.border': '#56D9FF',
            'scrollbarSlider.background': '#26324480',
            'scrollbarSlider.hoverBackground': '#34445B',
            'scrollbarSlider.activeBackground': '#46617F',
            'minimap.background': '#0D1117'
        }
    };
}

function startMonaco(monaco) {
    state.monaco = monaco;
    monaco.editor.defineTheme('univm-studio', monacoTheme());
    registerUnivmLanguage(monaco);

    state.editor = monaco.editor.create(dom.editor, {
        theme: 'univm-studio',
        automaticLayout: true,
        fontFamily: '"Cascadia Code", "JetBrains Mono", "Cascadia Mono", Consolas, monospace',
        fontSize: 13.5,
        lineHeight: 22,
        fontLigatures: true,
        letterSpacing: 0.2,
        minimap: {
            enabled: true,
            renderCharacters: false,
            maxColumn: 80,
            scale: 1
        },
        scrollBeyondLastLine: false,
        smoothScrolling: true,
        cursorBlinking: 'smooth',
        cursorSmoothCaretAnimation: 'on',
        cursorWidth: 2,
        renderLineHighlight: 'all',
        roundedSelection: true,
        selectionHighlight: true,
        occurrencesHighlight: 'singleFile',
        bracketPairColorization: { enabled: true },
        guides: { bracketPairs: 'active', indentation: true },
        stickyScroll: { enabled: true, maxLineCount: 3 },
        padding: { top: 16, bottom: 24 },
        lineNumbersMinChars: 4,
        folding: true,
        showFoldingControls: 'mouseover',
        glyphMargin: false,
        lineDecorationsWidth: 12,
        tabSize: 4,
        insertSpaces: true,
        detectIndentation: false,
        wordWrap: 'off',
        scrollbar: {
            verticalScrollbarSize: 10,
            horizontalScrollbarSize: 10,
            useShadows: false
        },
        overviewRulerBorder: false,
        hideCursorInOverviewRuler: true,
        contextmenu: true,
        mouseWheelZoom: true,
        renderWhitespace: 'selection',
        unicodeHighlight: {
            ambiguousCharacters: false,
            nonBasicASCII: false,
            invisibleCharacters: true,
            includeComments: false,
            includeStrings: false
        },
        semanticHighlighting: { enabled: true }
    });

    state.editor.onDidChangeCursorPosition((event) => updateCursorStatus(event.position));
    state.editor.onDidChangeModel(() => updateLanguageStatus());
    state.editor.onDidChangeConfiguration(() => state.editor.layout());
    state.editor.setModel(null);

    dom.loadingOverlay.classList.add('hidden');
    setEngine('ready', 'Java backend ready');
    setStatus('Ready');
    updateActionStates();
    loadExamples();
}

function handleMonacoError(error) {
    console.error(error);
    dom.loadingOverlay.innerHTML = '<div class="empty-state"><h1>Monaco failed to load</h1><p>Check ui-electron/node_modules and reload the window.</p></div>';
    appendTerm(`\n[monaco] ${error && error.message ? error.message : String(error)}\n`, 'error');
    setStatus('Monaco failed to load', 'error');
}

/* -------------------------------------------------------------------------- */
/* Boot                                                                       */
/* -------------------------------------------------------------------------- */

function boot() {
    wireChrome();
    wireTerminal();
    loadAppInfo();

    dom.saveButton.addEventListener('click', saveActiveFile);
    dom.runButton.addEventListener('click', runCode);
    dom.stopButton.addEventListener('click', stopRun);
    dom.refreshTree.addEventListener('click', async () => {
        try {
            await loadExamples();
            setStatus('Explorer refreshed');
        } catch (error) {
            appendTerm(`\n[explorer] ${error.message}\n`, 'error');
        }
    });

    ensureTerminalCursor();
    appendTerm('UniVM Studio // Java execution terminal\n', 'meta');
    appendTerm('Select an example, edit it, then press F5 to run.\n\n', 'meta');
    setTerminalState('idle', '');

    window.addEventListener('error', (event) => {
        if (event.message) appendTerm(`\n[renderer] ${event.message}\n`, 'error');
    });
    window.addEventListener('unhandledrejection', (event) => {
        const reason = event.reason && event.reason.message ? event.reason.message : String(event.reason);
        appendTerm(`\n[renderer] ${reason}\n`, 'error');
    });

    window.require.config({
        paths: { vs: '/monaco/min/vs' }
    });
    window.require(['vs/editor/editor.main'], startMonaco, handleMonacoError);
}

if (new URLSearchParams(window.location.search).get('smoke') === '1') {
    window.__univmSmoke = {
        state: () => ({
            monacoReady: Boolean(state.monaco && state.editor),
            tabs: state.tabs.map((tab) => ({ rel: tab.rel, name: tab.name, dirty: tab.dirty, language: tab.language })),
            saveEnabled: !dom.saveButton.disabled,
            active: state.active ? state.active.rel : null,
            running: state.running,
            stopping: state.stopping,
            inputEnabled: !dom.terminalInput.disabled,
            status: dom.statusMessage.textContent,
            terminalState: dom.terminalState.textContent,
            terminal: terminalText()
        }),
        setCode: (code) => {
            if (!state.active) throw new Error('No active tab.');
            state.active.model.setValue(String(code));
        },
        run: runCode,
        stop: stopRun,
        save: saveActiveFile
    };
}

boot();
