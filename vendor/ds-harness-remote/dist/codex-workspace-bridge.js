import { spawn } from 'node:child_process';
import { realpath, lstat, readdir, readFile, stat, watch } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve } from 'node:path';
import { RpcError } from './safe-error.js';
import { parseCodexSessionId } from './codex/session-id.js';
const MAX_READ_BYTES = 4 * 1024 * 1024;
const MAX_INPUT_BYTES = 64 * 1024;
const MAX_COLS = 240;
const MAX_ROWS = 100;
const MAX_TERMINALS = 256;
/** Replay journal bound for one terminal; the newest output always survives. */
const MAX_SCREEN_BYTES = 256 * 1024;
/** Advertised scrollback rows, matching the official terminal environment shape. */
const TERMINAL_SCROLLBACK = 2000;
const TERMINAL_TYPE = 'xterm-256color';
export class CodexWorkspaceState {
    terminals = new Map();
}
/** One shell this carrier can start; the client only ever picks from `terminal/shells`. */
const DEFAULT_SHELL = process.platform === 'win32'
    ? { path: 'cmd.exe', args: [], name: 'Command Prompt' }
    : { path: '/bin/sh', args: [], name: 'sh' };
class AsyncQueue {
    values = [];
    waiters = [];
    ended = false;
    push(value) {
        if (this.ended)
            return;
        const waiter = this.waiters.shift();
        if (waiter)
            waiter({ done: false, value });
        else
            this.values.push(value);
    }
    end() {
        this.ended = true;
        while (this.waiters.length)
            this.waiters.shift()({ done: true, value: undefined });
    }
    next() {
        const value = this.values.shift();
        if (value !== undefined)
            return Promise.resolve({ done: false, value });
        if (this.ended)
            return Promise.resolve({ done: true, value: undefined });
        return new Promise(resolve => this.waiters.push(resolve));
    }
    [Symbol.asyncIterator]() { return this; }
}
/** Host-owned CodeX file and terminal carrier used by Harness Remote RPC. */
export class CodexWorkspaceBridge {
    resolveCwd;
    terminalEnabled;
    terminals;
    ownedSubscribers = new Set();
    spawnTerminal;
    constructor(resolveCwd, terminalEnabled, state = new CodexWorkspaceState(), spawnTerminal = pipeTerminalSpawner()) {
        this.resolveCwd = resolveCwd;
        this.terminalEnabled = terminalEnabled;
        this.terminals = state.terminals;
        this.spawnTerminal = spawnTerminal;
    }
    isCodeXScope(value) {
        return parseCodexSessionId(value) !== undefined;
    }
    async call(endpoint, payload, signal) {
        const args = argsOf(payload);
        const scope = args.workspaceFileScopeId;
        const session = args.agentId ?? args.sessionId;
        const raw = scope ?? session;
        const codex = parseCodexSessionId(raw);
        if (codex === undefined) {
            if (typeof raw === 'string' && raw.startsWith('codex:'))
                throw new RpcError('CODEX_SESSION_INVALID', 'The CodeX session identifier is invalid.');
            return undefined;
        }
        if (endpoint.startsWith('workspaceFiles/'))
            return this.fileCall(endpoint, codex.sessionId, args, signal);
        if (endpoint.startsWith('terminal/'))
            return this.terminalCall(endpoint, codex.sessionId, args, signal);
        return undefined;
    }
    async open(endpoint, payload, signal) {
        const args = argsOf(payload);
        const raw = args.workspaceFileScopeId ?? args.agentId ?? args.sessionId;
        const codex = parseCodexSessionId(raw);
        if (codex === undefined) {
            if (typeof raw === 'string' && raw.startsWith('codex:'))
                throw new RpcError('CODEX_SESSION_INVALID', 'The CodeX session identifier is invalid.');
            return undefined;
        }
        if (endpoint === 'workspaceFiles/changes')
            return this.watchChanges(codex.sessionId, args, signal);
        if (endpoint === 'terminal/retain')
            return this.retain(codex.sessionId, args, signal);
        if (endpoint === 'terminal/follow')
            return this.follow(codex.sessionId, args, signal);
        return undefined;
    }
    async closeAll() {
        // A transport disconnect closes subscriptions but retains Host-owned terminal
        // processes so a reconnect can use terminal/retain, matching Harness policy.
        for (const queue of this.ownedSubscribers)
            queue.end();
        this.ownedSubscribers.clear();
    }
    async fileCall(endpoint, sessionId, args, signal) {
        const root = await this.rootFor(sessionId, signal);
        const path = typeof args.path === 'string' ? args.path : '.';
        const target = await this.safePath(root, path, endpoint === 'workspaceFiles/list');
        try {
            if (endpoint === 'workspaceFiles/list') {
                const entries = await readdir(target, { withFileTypes: true });
                const result = [];
                for (const entry of entries.slice(0, 500)) {
                    const item = join(target, entry.name);
                    const info = await lstat(item);
                    if (info.isSymbolicLink())
                        continue;
                    result.push({ name: entry.name, type: info.isDirectory() ? 'directory' : info.isFile() ? 'file' : 'other', ...(info.isFile() ? { size: info.size } : {}) });
                }
                return { ok: true, value: { path, entries: result, truncated: entries.length > 500 } };
            }
            const info = await stat(target);
            const absolutePath = target;
            const version = `${info.mtimeMs}:${info.size}`;
            if (endpoint === 'workspaceFiles/stat') {
                return { ok: true, value: { absolutePath, version, bytes: info.size } };
            }
            if (!info.isFile())
                throw new RpcError('CODEX_WORKSPACE_INVALID_PATH', 'The requested workspace path is not a file.');
            const offset = readOffset(args);
            const limit = readLimit(args);
            const bytes = await readFile(target);
            if (bytes.byteLength > MAX_READ_BYTES)
                throw new RpcError('CODEX_WORKSPACE_TOO_LARGE', 'The requested workspace file is too large.');
            if (endpoint === 'workspaceFiles/readBytes') {
                const slice = bytes.subarray(offset, Math.min(offset + limit, bytes.length));
                return {
                    ok: true,
                    value: {
                        absolutePath,
                        version,
                        bytes: bytes.length,
                        offset,
                        data: slice.toString('base64'),
                        eof: offset + slice.length >= bytes.length,
                    },
                };
            }
            const text = bytes.toString('utf8');
            const slice = text.slice(offset, offset + limit);
            return { ok: true, value: { absolutePath: target, version: `${info.mtimeMs}:${info.size}`, text: slice, offset, lines: slice.split('\n').length, eof: offset + slice.length >= text.length } };
        }
        catch (error) {
            if (error instanceof RpcError)
                throw error;
            throw new RpcError('CODEX_WORKSPACE_UNAVAILABLE', 'The CodeX workspace file is unavailable.');
        }
    }
    async watchChanges(sessionId, args, signal) {
        const root = await this.rootFor(sessionId, signal);
        const target = await this.safePath(root, typeof args.path === 'string' ? args.path : '.', true);
        const queue = new AsyncQueue();
        const watcher = watch(target, { recursive: false });
        const abort = () => { watcher.return?.(); queue.end(); };
        signal.addEventListener('abort', abort, { once: true });
        void (async () => {
            try {
                for await (const event of watcher)
                    queue.push({ type: event.eventType, path: event.filename ?? '' });
            }
            catch { /* stream close is reported by the gateway */ }
            finally {
                signal.removeEventListener('abort', abort);
                queue.end();
            }
        })();
        return queue;
    }
    /**
     * `terminal/retain` only acknowledges the retention window: it never takes
     * input ownership and never replays output. Recovery reads the next snapshot.
     */
    async retain(sessionId, args, signal) {
        this.assertTerminalEnabled();
        await this.rootFor(sessionId, signal);
        this.requireTerminal(sessionId, String(args.id));
        const queue = new AsyncQueue();
        queue.push({ type: 'retained' });
        this.ownedSubscribers.add(queue);
        signal.addEventListener('abort', () => { this.ownedSubscribers.delete(queue); queue.end(); }, { once: true });
        return queue;
    }
    /** `terminal/follow` takes input ownership and starts with a screen snapshot. */
    async follow(sessionId, args, signal) {
        this.assertTerminalEnabled();
        await this.rootFor(sessionId, signal);
        const attachmentId = typeof args.attachmentId === 'string' && args.attachmentId.length > 0 ? args.attachmentId : undefined;
        if (attachmentId === undefined)
            throw new RpcError('INVALID_MESSAGE', 'The terminal attachment is invalid.');
        const terminal = this.requireTerminal(sessionId, String(args.id));
        const queue = new AsyncQueue();
        if (terminal.controllerId !== attachmentId) {
            terminal.controllerId = attachmentId;
            // Existing followers keep their output but lose input to the new attachment.
            this.emit(terminal, { type: 'state', info: terminalInfo(terminal) });
        }
        queue.push({ type: 'snapshot', sequence: terminal.sequence, screen: screenOf(terminal), info: terminalInfo(terminal) });
        terminal.subscribers.add(queue);
        this.ownedSubscribers.add(queue);
        signal.addEventListener('abort', () => {
            terminal.subscribers.delete(queue);
            this.ownedSubscribers.delete(queue);
            queue.end();
            if (terminal.controllerId === attachmentId) {
                terminal.controllerId = undefined;
                this.emit(terminal, { type: 'state', info: terminalInfo(terminal) });
            }
        }, { once: true });
        return queue;
    }
    async terminalCall(endpoint, sessionId, args, signal) {
        this.assertTerminalEnabled();
        const cwd = await this.rootFor(sessionId, signal);
        if (endpoint === 'terminal/environment') {
            return { ok: true, value: { cwd, maxInputBytes: MAX_INPUT_BYTES, maxCols: MAX_COLS, maxRows: MAX_ROWS, scrollback: TERMINAL_SCROLLBACK } };
        }
        if (endpoint === 'terminal/shells')
            return { ok: true, value: [shellInfo(DEFAULT_SHELL)] };
        if (endpoint === 'terminal/list')
            return { ok: true, value: [...this.terminals.values()].filter(item => item.sessionId === sessionId).map(terminalInfo) };
        if (endpoint === 'terminal/create')
            return this.createTerminal(sessionId, cwd, args);
        const id = stringId(args.id);
        const terminal = this.requireTerminal(sessionId, id);
        if (endpoint === 'terminal/write') {
            const data = typeof args.data === 'string' ? args.data : '';
            if (Buffer.byteLength(data) > MAX_INPUT_BYTES)
                throw new RpcError('INVALID_MESSAGE', 'Terminal input is too large.');
            if (terminal.process === undefined)
                throw new RpcError('CODEX_TERMINAL_NOT_FOUND', 'The CodeX terminal is no longer available.');
            await terminal.process.write(data);
            return { ok: true };
        }
        if (endpoint === 'terminal/resize') {
            terminal.cols = bounded(args.cols, terminal.cols, MAX_COLS);
            terminal.rows = bounded(args.rows, terminal.rows, MAX_ROWS);
            await terminal.process?.resize(terminal.cols, terminal.rows);
            return { ok: true };
        }
        if (endpoint === 'terminal/rename') {
            if (typeof args.title === 'string' && args.title.length > 0)
                terminal.title = args.title.slice(0, 128);
            return { ok: true };
        }
        if (endpoint === 'terminal/close') {
            this.disposeTerminal(terminal);
            this.terminals.delete(`${sessionId}/${id}`);
            return { ok: true };
        }
        throw new RpcError('METHOD_NOT_FOUND', 'The requested terminal method does not exist.');
    }
    async createTerminal(sessionId, cwd, args) {
        const request = isRecord(args.request) ? args.request : {};
        const id = stringId(request.id);
        if ([...this.terminals.values()].some(item => item.sessionId === sessionId && item.id === id))
            throw new RpcError('REQUEST_CONFLICT', 'The terminal id is already active.');
        if (this.terminals.size >= MAX_TERMINALS)
            throw new RpcError('RATE_LIMITED', 'Too many remote terminals are active.', undefined, true);
        if (request.shellPath !== undefined && request.shellPath !== DEFAULT_SHELL.path)
            throw new RpcError('INVALID_MESSAGE', 'The requested shell is not available for this workspace.');
        const cols = bounded(request.cols, 80, MAX_COLS);
        const rows = bounded(request.rows, 24, MAX_ROWS);
        const terminal = {
            sessionId, id, title: DEFAULT_SHELL.name, shell: DEFAULT_SHELL, cwd, cols, rows,
            sequence: 0, screen: [], screenBytes: 0, truncated: false, subscribers: new Set(),
            state: 'running', exitCode: null,
        };
        this.terminals.set(`${sessionId}/${id}`, terminal);
        try {
            terminal.process = await this.spawnTerminal({
                argv: [DEFAULT_SHELL.path, ...DEFAULT_SHELL.args],
                cwd,
                cols,
                rows,
                terminalType: TERMINAL_TYPE,
                env: { DSH_SESSION_ID: sessionId },
            });
        }
        catch {
            this.terminals.delete(`${sessionId}/${id}`);
            throw new RpcError('CODEX_TERMINAL_UNAVAILABLE', 'The CodeX terminal could not be started.');
        }
        void this.pump(terminal, terminal.process);
        return { ok: true, value: terminalInfo(terminal) };
    }
    /** Streams process output as ordered `output` frames, then reports the exit. */
    async pump(terminal, process) {
        try {
            for await (const chunk of process.output) {
                if (chunk.length === 0)
                    continue;
                terminal.sequence += 1;
                this.appendScreen(terminal, chunk);
                this.emit(terminal, { type: 'output', sequence: terminal.sequence, data: chunk });
            }
        }
        catch {
            terminal.state = 'failed';
            terminal.error = 'The terminal output stream failed.';
        }
        const outcome = await process.completed;
        terminal.exitCode = outcome.exitCode;
        if (terminal.state === 'running')
            terminal.state = 'exited';
        this.emit(terminal, { type: 'state', info: terminalInfo(terminal) });
        this.endSubscribers(terminal);
    }
    appendScreen(terminal, chunk) {
        terminal.screen.push(chunk);
        terminal.screenBytes += Buffer.byteLength(chunk);
        while (terminal.screenBytes > MAX_SCREEN_BYTES && terminal.screen.length > 1) {
            terminal.screenBytes -= Buffer.byteLength(terminal.screen.shift());
            terminal.truncated = true;
        }
    }
    async rootFor(sessionId, signal) {
        const parsed = parseCodexSessionId(sessionId);
        if (!parsed)
            throw new RpcError('CODEX_SESSION_INVALID', 'The CodeX session identifier is invalid.');
        const cwd = await this.resolveCwd(parsed.threadId, signal);
        if (!cwd)
            throw new RpcError('CODEX_WORKSPACE_UNAVAILABLE', 'The CodeX thread has no available workspace.');
        try {
            const root = await realpath(cwd);
            const info = await stat(root);
            if (!info.isDirectory())
                throw new Error();
            return root;
        }
        catch {
            throw new RpcError('CODEX_WORKSPACE_UNAVAILABLE', 'The CodeX workspace is unavailable.');
        }
    }
    async safePath(root, path, directory) {
        // Native clients may pass the displayed absolute workspace path (not only
        // a relative child). Accept it only when lexical and canonical containment
        // still prove that it belongs to this authenticated workspace root.
        const candidate = isAbsolute(path) ? resolve(path) : resolve(root, path);
        const rel = relative(root, candidate);
        if (rel.startsWith('..') || isAbsolute(rel))
            throw new RpcError('CODEX_WORKSPACE_PATH_DENIED', 'The requested workspace path is outside the CodeX workspace.');
        try {
            const info = await lstat(candidate);
            if (info.isSymbolicLink())
                throw new Error();
            const canonical = await realpath(candidate);
            const canonicalRel = relative(root, canonical);
            if (canonicalRel.startsWith('..') || isAbsolute(canonicalRel))
                throw new Error();
            if (directory && !info.isDirectory())
                throw new Error();
            return canonical;
        }
        catch {
            throw new RpcError('CODEX_WORKSPACE_PATH_DENIED', 'The requested workspace path is unavailable.');
        }
    }
    emit(terminal, value) {
        for (const subscriber of terminal.subscribers)
            subscriber.push(value);
    }
    endSubscribers(terminal) {
        for (const subscriber of terminal.subscribers)
            subscriber.end();
        terminal.subscribers.clear();
    }
    requireTerminal(sessionId, id) {
        const terminal = this.terminals.get(`${sessionId}/${id}`);
        if (!terminal)
            throw new RpcError('CODEX_TERMINAL_NOT_FOUND', 'The CodeX terminal is no longer available.');
        return terminal;
    }
    assertTerminalEnabled() { if (!this.terminalEnabled())
        throw new RpcError('TERMINAL_DISABLED', 'Remote terminal is disabled on this Host.'); }
    disposeTerminal(terminal) {
        void Promise.resolve(terminal.process?.terminate()).catch(() => undefined);
        if (terminal.state === 'running') {
            // Followers see a terminal state before their stream ends, so a close from
            // another connection cannot leave a view stuck on "running".
            terminal.state = 'exited';
            this.emit(terminal, { type: 'state', info: terminalInfo(terminal) });
        }
        this.endSubscribers(terminal);
    }
}
/** Default terminal process: a plain child process, used when the Host exposes no PTY provider. */
export function pipeTerminalSpawner() {
    return async (spec) => {
        const child = spawn(spec.argv[0], spec.argv.slice(1), {
            cwd: spec.cwd,
            stdio: 'pipe',
            windowsHide: true,
            env: { ...process.env, ...spec.env },
        });
        const queue = new AsyncQueue();
        child.stdout.on('data', data => queue.push(Buffer.from(data).toString('utf8')));
        child.stderr.on('data', data => queue.push(Buffer.from(data).toString('utf8')));
        child.on('error', () => queue.end());
        const completed = new Promise(resolve => {
            child.on('error', () => { queue.end(); resolve({ exitCode: null }); });
            child.on('exit', code => { queue.end(); resolve({ exitCode: code }); });
        });
        return {
            output: queue,
            write: data => { child.stdin.write(data); },
            resize: () => undefined,
            terminate: () => { if (!child.killed)
                child.kill(); },
            completed,
        };
    };
}
/**
 * Terminal process backed by the Host `subprocess` service, which owns PTY
 * allocation, containment, and process-range termination.
 */
export function subprocessTerminalSpawner(subprocess) {
    return async (spec) => {
        const handle = await subprocess.spawnTerminal({
            argv: [...spec.argv],
            cwd: spec.cwd,
            cols: spec.cols,
            rows: spec.rows,
            terminalType: spec.terminalType,
            env: spec.env,
        });
        const queue = new AsyncQueue();
        handle.output.setEncoding?.('utf8');
        handle.output.on('data', chunk => queue.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8')));
        handle.output.once('close', () => queue.end());
        handle.output.once('error', () => queue.end());
        return {
            output: queue,
            write: data => handle.write(data),
            resize: (cols, rows) => handle.resize(cols, rows),
            terminate: () => handle.terminate(),
            completed: handle.done
                .then(outcome => ({ exitCode: typeof outcome?.exitCode === 'number' ? outcome.exitCode : null }))
                .catch(() => ({ exitCode: null })),
        };
    };
}
function shellInfo(shell) {
    return { path: shell.path, args: [...shell.args], name: shell.name };
}
/** Official WebTerminalInfo shape, including the attachment that owns input. */
function terminalInfo(terminal) {
    return {
        id: terminal.id,
        title: terminal.title,
        shell: shellInfo(terminal.shell),
        cwd: terminal.cwd,
        cols: terminal.cols,
        rows: terminal.rows,
        state: terminal.state,
        exitCode: terminal.exitCode,
        ...(terminal.error === undefined ? {} : { error: terminal.error }),
        ...(terminal.controllerId === undefined ? {} : { controllerId: terminal.controllerId }),
    };
}
/**
 * The carrier has no terminal emulator: the bounded raw journal is replayed into
 * the client emulator, which re-executes the same control sequences. A truncated
 * journal starts with a reset so a half-captured sequence cannot leak in.
 */
function screenOf(terminal) {
    return (terminal.truncated ? '\u001bc' : '') + terminal.screen.join('');
}
function argsOf(payload) { const value = isRecord(payload) ? payload : {}; return isRecord(value.args) ? value.args : value; }
function isRecord(value) { return typeof value === 'object' && value !== null && !Array.isArray(value); }
function stringId(value) { if (typeof value !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(value))
    throw new RpcError('INVALID_MESSAGE', 'The terminal identifier is invalid.'); return value; }
function bounded(value, fallback, max) { return typeof value === 'number' && Number.isInteger(value) && value > 0 ? Math.min(value, max) : fallback; }
function byteOrLineRange(args) {
    const options = isRecord(args.options) ? args.options : undefined;
    if (options !== undefined && isRecord(options.range))
        return options.range;
    return isRecord(args.range) ? args.range : args;
}
function readOffset(args) {
    const range = byteOrLineRange(args);
    return typeof range.offset === 'number' && Number.isInteger(range.offset) && range.offset >= 0 ? range.offset : 0;
}
function readLimit(args) {
    const range = byteOrLineRange(args);
    const value = typeof range.length === 'number' ? range.length : range.limit;
    return typeof value === 'number' && Number.isInteger(value) && value > 0 ? Math.min(value, MAX_READ_BYTES) : MAX_READ_BYTES;
}
//# sourceMappingURL=codex-workspace-bridge.js.map