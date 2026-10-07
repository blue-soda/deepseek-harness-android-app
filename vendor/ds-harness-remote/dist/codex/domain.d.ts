import type { CodexAppFrameData, CodexAppStreamClosedData } from '@dsh-remote/protocol';
import type { ResolvedCodexConfig } from '../config.js';
import type { PeerConnectionContext } from '../connection-controller.js';
import type { SafeLogger } from '../logging.js';
import { type CodexAppServerLike } from './app-server.js';
import { CodexPeerBridge, type PublishCodexFrame } from './peer-bridge.js';
type AppServerFactory = (binary: string, logger: SafeLogger) => CodexAppServerLike;
/**
 * Optional Codex business domain inside the existing Remote Plugin. It shares
 * Remote identity/transport with Harness, but owns its App Server process,
 * method policy, subscriptions, leases, and approval handles.
 */
export declare class CodexRemoteDomain {
    readonly config: ResolvedCodexConfig;
    private readonly logger;
    private readonly createAppServer;
    private readonly restartDelaysMs;
    private appServer?;
    private unsubscribeInbound?;
    private unsubscribeUnavailable?;
    private readonly peers;
    private readonly peerDeviceIds;
    private readonly turnOwners;
    private readonly approvals;
    private readonly permissionPresets;
    private approvalExpiryTimer?;
    private restartTimer?;
    private restartAttempt;
    private available;
    private closed;
    private state;
    private unavailableCode?;
    constructor(config: ResolvedCodexConfig, logger: SafeLogger, createAppServer?: AppServerFactory, restartDelaysMs?: readonly number[]);
    start(): Promise<void>;
    isAvailable(): boolean;
    status(): {
        enabled: boolean;
        available: boolean;
        state: 'disabled' | 'starting' | 'ready' | 'restarting' | 'unavailable';
        restartAttempt: number;
        error?: string;
    };
    /** Resolve a thread cwd for Host-owned workspace/terminal carriers. */
    resolveThreadWorkspace(connectionId: string, threadId: string): Promise<string | undefined>;
    createPeer(context: PeerConnectionContext, publish: PublishCodexFrame): CodexPeerBridge | undefined;
    call(connectionId: string, input: unknown): Promise<unknown>;
    respond(connectionId: string, input: unknown): Promise<{
        resolved: true;
    }>;
    detachPeer(connectionId: string): Promise<void>;
    close(): Promise<void>;
    /**
     * Launch the App Server within {@link CODEX_START_BUDGET_MS}.
     *
     * The losing side of the race keeps running until the disposal in start()'s catch stops it, so
     * it needs its own handler: an unhandled rejection here would surface as a process-level error
     * for what is only an optional feature being unavailable.
     * @returns nothing once a candidate is ready.
     */
    private launchWithinBudget;
    private launchAppServer;
    private launchAppServerCandidate;
    private handleAppServerUnavailable;
    private scheduleRestart;
    private restartAfterFailure;
    private disposeAppServer;
    private handleInbound;
    private rememberPermission;
    private changeThreadPermission;
    private publishPermission;
    private handleServerRequest;
    private readKnownThread;
    private readThreadForHistory;
    private readThreadTurns;
    private readThreadItems;
    private logHistoryFallback;
    private assertResultThreadAllowed;
    private claimTurn;
    private rememberTurnId;
    private hasSubscriber;
    private resolveUpstreamApproval;
    private expireApprovals;
    private scheduleApprovalExpiry;
    private requireAppServer;
    private callUpstream;
    private readWorkspaceAuthority;
    private findKnownThreadInList;
    private requireCodexWorkspacePath;
    private requireNewCodexProjectPath;
    private listCodexDirectory;
    private resolveCodexDirectory;
    private listCodexWorkspacePaths;
}
export type CodexDomainFrame = CodexAppFrameData | CodexAppStreamClosedData;
/**
 * Prefer Codex bundled with the desktop app when the user kept the default
 * command, so an app install works without a global CLI. Explicit binary
 * configuration is never rewritten or supplemented.
 */
export declare function codexBinaryCandidates(configured: string, hostPlatform?: NodeJS.Platform, userHome?: string): string[];
export {};
//# sourceMappingURL=domain.d.ts.map