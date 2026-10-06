import type { TypertRpcResult } from './typert-gateway-contract.js';
export declare class CodexWorkspaceState {
    readonly terminals: Map<string, TerminalContext>;
}
export type CodexCwdResolver = (threadId: string, signal: AbortSignal) => Promise<string | undefined>;
interface ShellSpec {
    path: string;
    args: string[];
    name: string;
}
/** Request for one Host-owned terminal process. */
export interface RemoteTerminalSpec {
    argv: readonly string[];
    cwd: string;
    cols: number;
    rows: number;
    terminalType: string;
    env: Record<string, string>;
}
/** Terminal process surface the bridge needs; PTY-backed when the Host provides one. */
export interface RemoteTerminalProcess {
    output: AsyncIterable<string>;
    write(data: string): void | Promise<void>;
    resize(cols: number, rows: number): void | Promise<void>;
    terminate(): void | Promise<void>;
    completed: Promise<{
        exitCode: number | null;
    }>;
}
export type RemoteTerminalSpawner = (spec: RemoteTerminalSpec) => Promise<RemoteTerminalProcess>;
/** Structural view of the Host `subprocess` service this carrier can use. */
export interface HostSubprocessLike {
    spawnTerminal(spec: Record<string, unknown>): Promise<unknown>;
}
interface TerminalContext {
    sessionId: string;
    id: string;
    title: string;
    shell: ShellSpec;
    cwd: string;
    cols: number;
    rows: number;
    process?: RemoteTerminalProcess;
    /** Attachment that currently owns input; absent while nobody is attached. */
    controllerId?: string;
    /** Monotonic across the Host terminal lifetime and never reset on reconnect. */
    sequence: number;
    /** Bounded raw output replayed to a reconnecting emulator. */
    screen: string[];
    screenBytes: number;
    truncated: boolean;
    subscribers: Set<AsyncQueue<unknown>>;
    state: 'running' | 'exited' | 'failed';
    exitCode: number | null;
    error?: string;
}
declare class AsyncQueue<T> implements AsyncIterable<T> {
    private values;
    private waiters;
    private ended;
    push(value: T): void;
    end(): void;
    next(): Promise<IteratorResult<T>>;
    [Symbol.asyncIterator](): AsyncIterator<T>;
}
/** Host-owned CodeX file and terminal carrier used by Harness Remote RPC. */
export declare class CodexWorkspaceBridge {
    private readonly resolveCwd;
    private readonly terminalEnabled;
    private readonly terminals;
    private readonly ownedSubscribers;
    private readonly spawnTerminal;
    constructor(resolveCwd: CodexCwdResolver, terminalEnabled: () => boolean, state?: CodexWorkspaceState, spawnTerminal?: RemoteTerminalSpawner);
    isCodeXScope(value: unknown): boolean;
    call(endpoint: string, payload: unknown, signal: AbortSignal): Promise<TypertRpcResult | undefined>;
    open(endpoint: string, payload: unknown, signal: AbortSignal): Promise<AsyncIterable<unknown> | undefined>;
    closeAll(): Promise<void>;
    private fileCall;
    private watchChanges;
    /**
     * `terminal/retain` only acknowledges the retention window: it never takes
     * input ownership and never replays output. Recovery reads the next snapshot.
     */
    private retain;
    /** `terminal/follow` takes input ownership and starts with a screen snapshot. */
    private follow;
    private terminalCall;
    private createTerminal;
    /** Streams process output as ordered `output` frames, then reports the exit. */
    private pump;
    private appendScreen;
    private rootFor;
    private safePath;
    private emit;
    private endSubscribers;
    private requireTerminal;
    private assertTerminalEnabled;
    private disposeTerminal;
}
/** Default terminal process: a plain child process, used when the Host exposes no PTY provider. */
export declare function pipeTerminalSpawner(): RemoteTerminalSpawner;
/**
 * Terminal process backed by the Host `subprocess` service, which owns PTY
 * allocation, containment, and process-range termination.
 */
export declare function subprocessTerminalSpawner(subprocess: HostSubprocessLike): RemoteTerminalSpawner;
export {};
//# sourceMappingURL=codex-workspace-bridge.d.ts.map