import type { ApiProxy, RpcResult } from '@deepseek-ai/dsh-host-apiproxy/api';
import type { CodexAppFrameData, CodexAppStreamClosedData } from '@dsh-remote/protocol';
import { RemoteClientCore } from '@dsh-remote/client-core';
import { type RtcPeerConnectionFactory } from '@dsh-remote/webrtc';
import { type HarnessMode } from './api-proxy-switch.js';
import { ClientTargetStore } from './client-target-store.js';
import type { ResolvedConfig } from './config.js';
import { type HostWebServerLike } from './control-route.js';
import type { IdentityStore } from './identity-store.js';
import type { TypertGatewayLike } from './typert-gateway-contract.js';
import type { SafeLogger } from './logging.js';
import { type CodexVirtualWorkspaceView } from './codex/virtual-harness.js';
import { ClientServerApi, type OAuthProvider } from './server-api.js';
import { type WeriftFactoryOptions } from './werift-rtc.js';
export interface RemoteHostFeatures {
    commandList: boolean;
    fileViewer: boolean;
    terminal: boolean;
    apiProxy: boolean;
    remoteGateway: boolean;
    sessionFormat?: 3;
    codex: boolean;
}
export interface RemoteDirectoryEntry {
    name: string;
    path: string;
    hidden: boolean;
}
export interface RemoteDirectoryListing {
    path: string;
    home: string;
    crumbs: RemoteDirectoryEntry[];
    entries: RemoteDirectoryEntry[];
    truncated: boolean;
}
export interface RemoteWorkspaceView {
    workspaceId: string;
    path: string;
    title: string;
}
export interface RemoteDeviceView {
    deviceId: string;
    name: string;
    platform: string;
    membershipId: string;
    online: boolean;
    lastSeenAt?: number;
    clientVersion?: string;
    harnessVersion?: string;
}
export interface HostConnectionRpc {
    handle(channel: string, handler: (endpoint: string, payload: unknown, signal: AbortSignal) => Promise<RpcResult<unknown>>, options: {
        authority: 'loopback' | 'trusted-host';
    }): () => Promise<void>;
}
export interface HostConnectionHandle {
    requestRejection?(request: {
        headers: Record<string, string | string[] | undefined>;
    }): number | undefined;
    rpc: HostConnectionRpc;
}
export interface HostAuthorizationControl {
    setTerminalEnabled?(enabled: boolean): void;
    setLoopbackPorts?(ports: readonly number[]): void;
    hostStatus(): {
        deviceId?: string;
        configured: boolean;
        online: boolean;
        reconnecting: boolean;
        lastActiveAt?: number;
        error?: string;
        account?: string;
        authorized: boolean;
        accountRequired: boolean;
        /** Whether the user asked this machine to stay unreachable while signed in. */
        paused?: boolean;
        connectedClients?: Array<{
            deviceId: string;
            name: string;
            platform?: string;
            mode?: 'LAN' | 'P2P' | 'TURN' | 'Relay';
        }>;
    };
    hasStoredAuthorization?(): Promise<boolean>;
    reconnectHost(): void;
    /** Stop being reachable without releasing the authorization. */
    pauseHostConnection?(): Promise<void>;
    /** Resume with the same credentials and device identity. */
    resumeHostConnection?(): Promise<void>;
    clearHostAuthorization(): Promise<void>;
    localHarnessVersion?(): string | undefined;
    authorizeHostAsOwned(accessToken: string, account?: string): Promise<unknown>;
    authorizeHostWithAccount(email: string, password: string): Promise<unknown>;
    authorizeHostWithCode(code: string): Promise<unknown>;
    codexStatus?(): {
        available: boolean;
    };
    codexCall?(input: unknown, signal?: AbortSignal): Promise<unknown>;
    codexRespond?(input: unknown, signal?: AbortSignal): Promise<{
        resolved: true;
    }>;
    codexOpenStream?(input: unknown, publish: (event: 'codex.app.frame' | 'codex.app.stream.closed', data: CodexAppFrameData | CodexAppStreamClosedData) => Promise<void>, signal?: AbortSignal): Promise<unknown>;
    codexCloseStream?(input: unknown): Promise<unknown>;
}
/**
 * Whether a proof of life failed to reach the peer.
 *
 * The check asks for an answer, not for success, so the two outcomes are told apart by where the
 * error came from. An answer arrives as an error carrying the peer's own code (METHOD_NOT_FOUND,
 * FEATURE_NOT_SUPPORTED, ...). A local failure is either one of the core's own codes above or a raw
 * transport error with no code at all - a send that failed on a socket this process has not noticed
 * is closed. Counting that second kind as liveness would make the whole check useless exactly when
 * it matters.
 * @param error - the error the check rejected with.
 * @returns true when no answer arrived, so the transport must be treated as lost.
 */
export declare function livenessProbeLost(error: unknown): boolean;
export declare class ClientModeRuntime {
    private readonly config;
    private readonly identities;
    private readonly server;
    private readonly logger;
    private readonly host?;
    private readonly rtcFactoryProvider;
    private readonly targetStore?;
    private preview?;
    private identity?;
    private connected?;
    /**
     * Set when a remote session dropped and the runtime fell back to local.
     *
     * The mode then reads 'local', which is what every "return to local" control is
     * gated on, so without this the user is left in a stale remote view with no way
     * back except signing out.
     */
    private fellBackToLocal;
    /**
     * Identifies the live reconnect loop.
     *
     * A dropped transport starts one; anything else that settles the connection —
     * the user returning to local, a logout, a fresh connect — bumps it so the loop
     * stops instead of fighting the newer decision.
     */
    private remoteReconnectRun;
    private pendingWorkspaceSelection?;
    /**
     * Transport a running fast reconnect is replacing.
     *
     * The rebuild opens its own control connection and the Server closes the older one for the same
     * device, so that close belongs to our own replacement. Reading it as a lost peer is what made the
     * fast level escalate itself into the fallback before it could ever succeed.
     */
    private supersededClient?;
    /**
     * True while a fast reconnect is rebuilding the link.
     *
     * The probe must not judge the link in the middle of its own replacement: the Server closes the
     * older connection for the device, so a check that ran then would read our replacement's side effect
     * as a second miss and escalate the level that is busy recovering. The rebuild's own outcome decides
     * first; only once it has settled does the cadence resume judging.
     */
    private fastRebuildInFlight;
    /**
     * The last workspace the user opened for a Host.
     *
     * Kept so a reconnect can republish it: re-selecting the workspace is what makes the native UI
     * re-read its session list, and without it a list poisoned by the outage stays wrong even after
     * the link is back.
     */
    private lastWorkspaceSelection?;
    private codexVirtual?;
    private readonly proxySwitch?;
    private readonly gatewaySwitch;
    private readonly codexStreams;
    private connectionProgress?;
    private connectionProgressRun;
    /**
     * Host a boot is trying to restore, reported to the UI while the retry loop runs.
     *
     * Nothing is connected yet, so without this the window would show the local shell while the
     * user is still looking at the remote workspace they left.
     */
    /**
     * Recovery in progress, and which kind.
     *
     * `restore` is a start that is reconnecting to the recorded target, `fast` keeps the session on
     * screen while its link is rebuilt, and `fallback` is the last resort that returns the user to
     * the local shell. The UI shows all three, so a reconnect is never invisible, and the retry loop
     * stops as soon as the user asks for local.
     */
    private reconnecting?;
    /** Periodic proof of life for the live remote session; absent while nothing is connected. */
    private livenessTimer?;
    private livenessInFlight;
    private livenessFailures;
    private closed;
    constructor(config: ResolvedConfig, identities: IdentityStore, server: ClientServerApi, apiProxy: ApiProxy | undefined, typertGateway: TypertGatewayLike, logger: SafeLogger, host?: HostAuthorizationControl | undefined, rtcFactoryProvider?: (options?: WeriftFactoryOptions) => Promise<RtcPeerConnectionFactory | undefined>, targetStore?: ClientTargetStore | undefined);
    start(): Promise<void>;
    /**
     * Whether this installation already holds stored Server credentials for its
     * Client identity.
     *
     * This is the local answer to "is this installation signed in", available
     * without contacting the Server, so the UI can choose its panel immediately
     * instead of inferring the answer from a failed network round trip.
     */
    hasStoredAuthorization(): Promise<boolean>;
    authorizeHostByDefault(): Promise<void>;
    registerControl(connection: HostConnectionHandle, webServer?: HostWebServerLike): () => Promise<void>;
    status(): Record<string, unknown>;
    private closePreview;
    private detailedStatus;
    devices(): Promise<RemoteDeviceView[]>;
    /**
     * Device discovery is exposed through the local app control route. When this
     * installation also runs a Host, keep that route closed after the Host's
     * Server credential has become terminally invalid. The Client credential can
     * remain usable for a short time after a revoke, so checking only
     * `ClientServerApi.listDevices()` would otherwise leak the device directory
     * from a Host that the user has already been told to re-authorize.
     */
    private assertHostAuthorizationForDeviceDiscovery;
    authorizeClientWithAccount(email: string, password: string): Promise<unknown>;
    startClientOAuthQrLogin(provider: OAuthProvider): Promise<unknown>;
    pollClientOAuthQrLogin(qrId: string): Promise<unknown>;
    /**
     * Stop this Client from being authorized, keeping its device identity.
     *
     * The device is deliberately *not* revoked and the identity is deliberately
     * *not* rotated. Signing out used to revoke the device, which forced a new
     * identity on the next sign-in and registered a second device for the same
     * installation; an account holds at most 256 devices, so signing out often
     * enough could exhaust it. Keeping the row also means the next sign-in reuses
     * it, and the server invalidates the previous tokens at that point.
     *
     * The cost, by choice: while signed out the device stays in the account and
     * its old tokens stay valid until the next sign-in or their expiry, so signing
     * out is no longer a way to cut a leaked token off immediately. Removing the
     * device for good is an operator action on the server's state file.
     */
    clearClientAuthorization(): Promise<void>;
    setHostAuthorization(enabled: boolean): Promise<unknown>;
    setMode(mode: HarnessMode, targetDeviceId?: string, signal?: AbortSignal): Promise<Record<string, unknown>>;
    /**
     * Prove the live remote link still answers, and act on the result.
     *
     * Only a missing answer counts as loss: the peer's own error (a refusal, an unknown method) came
     * back over the same link and therefore proves it is there.
     *
     * The two recovery levels differ in what the user keeps. The first miss rebuilds the link in
     * place, so the session, its workspace selection and the remote carriers all stay; the second
     * gives up and returns to the local shell.
     */
    private verifyRemoteLiveness;
    /**
     * Rebuild the link while the session stays on screen.
     *
     * This is what separates the two recovery levels: nothing is handed back to the local shell, so a
     * link that recovers does not cost the user the view they were working in.
     * @param targetDeviceId - the Host to rebuild the link to.
     */
    private enterFastReconnect;
    /**
     * Build a new transport for the session already on screen.
     *
     * The carriers hold the previous client, so they are rebound to the new one before the old client
     * is closed; leaving it open would keep pointing remote calls at a dead transport.
     * @param targetDeviceId - the Host to reconnect to.
     * @returns true when a new session is in place.
     */
    private reestablish;
    private rememberWorkspaceSelection;
    /**
     * Republish the workspace selection so the native UI re-reads its remote session list.
     *
     * The client half consumes status.workspaceSelection and reconnects that workspace; that refresh is
     * what replaces a list the outage had filled with local answers.
     * @param targetDeviceId - the Host the reconnect finished against.
     */
    private restoreWorkspaceSelection;
    private finishReconnect;
    /**
     * Check the live session now, outside the cadence.
     *
     * Used when the page becomes visible again, which is when a suspended client is most likely to be
     * holding a link that already ended.
     * @returns the status after the check.
     */
    verifyRemoteConnection(): Promise<Record<string, unknown>>;
    private armLivenessWatch;
    private stopLivenessWatch;
    /**
     * Shared cleanup for a session whose transport is gone.
     *
     * A close event and an exhausted liveness check must leave exactly the same state behind, so both
     * paths run this. The session is gone for good here, which is why the phase becomes 'fallback' and
     * the retry loop keeps the UI saying that it is reconnecting.
     * @param client - the client that was connected.
     * @param targetDeviceId - the Host it was bound to.
     */
    private handleRemoteTransportLost;
    /**
     * Re-establish a remote session whose transport closed.
     *
     * The UI keeps rendering the remote session after the transport is gone, so
     * without this the user faces a session that silently ignores everything and has
     * to exit and pick the Host again — which is what a suspended and resumed client
     * used to require every time. Retry the same Host with backoff; a loop that is
     * superseded, or one whose session came back another way, stops quietly.
     * @param targetDeviceId - the Host the dropped session was bound to.
     */
    private reconnectRemoteSession;
    /**
     * Persist the target a later boot may have to restore.
     *
     * A failure here must not fail a connect: the only cost is that a killed process comes back to
     * the local shell, which is where it would have been without this record.
     * @param target - the target to record.
     */
    private rememberTarget;
    /**
     * Reconnect to the target this device was last using, with the usual backoff.
     *
     * A resumed app can miss the transport close entirely - Android may reclaim the process - so
     * nothing would start the retry loop and the user would face a local shell behind a remote
     * workspace view. Restoring the recorded target gives that case the same loop a dropped
     * transport gets. A target recorded for another Server is left alone: it is not reachable
     * through the configured one.
     * @returns true when a retry loop was started.
     */
    restoreLastTarget(): Promise<boolean>;
    listRemoteDirectory(targetDeviceId: string, path?: string, signal?: AbortSignal): Promise<RemoteDirectoryListing>;
    listRemoteWorkspaces(targetDeviceId: string, signal?: AbortSignal): Promise<RemoteWorkspaceView[]>;
    openRemoteWorkspace(targetDeviceId: string, path: string, signal?: AbortSignal): Promise<Record<string, unknown>>;
    listCodexWorkspaces(targetDeviceId: string, signal?: AbortSignal): Promise<CodexVirtualWorkspaceView[]>;
    openCodexWorkspace(targetDeviceId: string, workspaceId: string, signal?: AbortSignal): Promise<Record<string, unknown>>;
    createCodexWorkspace(targetDeviceId: string, path: string, signal?: AbortSignal): Promise<Record<string, unknown>>;
    private consumeWorkspaceSelection;
    close(): Promise<void>;
    private callRemoteFileViewer;
    private activeRemote;
    private activeCodexRemote;
    private openCodexStream;
    private readonly publishLocalCodexFrame;
    private appendCodexFrame;
    private localCodexAvailable;
    private requireLocalCodex;
    private nextCodexFrames;
    private closeCodexStream;
    private closeCodexStreams;
    private selectRemoteTarget;
    private remoteTypertGateway;
    private selectCodexTarget;
    private closeCodexVirtual;
    private assertRemoteCompatible;
    private selectHarnessRemoteTransport;
    private assertLocalHarnessCarrierAvailable;
    private connect;
    private ensureConnected;
    private beginAttemptProgress;
    private updateConnectionProgress;
    private clearConnectionProgress;
    handleControl(endpoint: string, payload: unknown, signal: AbortSignal): Promise<RpcResult<unknown>>;
    private requireIdentity;
    private authorizeHostPeer;
}
export declare class ClientModeError extends Error {
    readonly code: string;
    readonly retryable: boolean;
    constructor(code: string, message: string, retryable?: boolean);
}
/** Conservative feature profile for Hosts that predate fine-grained capability discovery. */
export declare function remoteHostFeatures(clientVersion?: string): RemoteHostFeatures;
export declare function probeRemoteHostFeatures(client: RemoteClientCore, clientVersion?: string): Promise<RemoteHostFeatures>;
//# sourceMappingURL=client-runtime.d.ts.map