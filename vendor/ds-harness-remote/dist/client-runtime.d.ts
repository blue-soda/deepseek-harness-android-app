import type { ApiProxy, RpcResult } from '@deepseek-ai/dsh-host-apiproxy/api';
import type { CodexAppFrameData, CodexAppStreamClosedData } from '@dsh-remote/protocol';
import { RemoteClientCore } from '@dsh-remote/client-core';
import { type RtcPeerConnectionFactory } from '@dsh-remote/webrtc';
import { type HarnessMode } from './api-proxy-switch.js';
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
export declare class ClientModeRuntime {
    private readonly config;
    private readonly identities;
    private readonly server;
    private readonly logger;
    private readonly host?;
    private readonly rtcFactoryProvider;
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
    private codexVirtual?;
    private readonly proxySwitch?;
    private readonly gatewaySwitch;
    private readonly codexStreams;
    private connectionProgress?;
    private connectionProgressRun;
    private closed;
    constructor(config: ResolvedConfig, identities: IdentityStore, server: ClientServerApi, apiProxy: ApiProxy | undefined, typertGateway: TypertGatewayLike, logger: SafeLogger, host?: HostAuthorizationControl | undefined, rtcFactoryProvider?: (options?: WeriftFactoryOptions) => Promise<RtcPeerConnectionFactory | undefined>);
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