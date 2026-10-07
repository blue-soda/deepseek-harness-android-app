import type { ApiProxy } from '@deepseek-ai/dsh-host-apiproxy/api';
import { ConnectionController } from './connection-controller.js';
import type { ResolvedConfig } from './config.js';
import type { HostIdentity, IdentityStore } from './identity-store.js';
import type { SafeLogger } from './logging.js';
import { type FileViewerHostServiceLike } from './file-viewer-bridge.js';
import { type DeviceAuthorization, type OAuthProvider, type OAuthQrPollResult, type OAuthQrSession } from './server-api.js';
import type { LocalTypertGateway } from './typert-gateway-contract.js';
import type { AuthenticatedPeerChannel } from './types.js';
import { CodexRemoteDomain } from './codex/domain.js';
import type { PublishCodexFrame } from './codex/peer-bridge.js';
import { type RemoteTerminalSpawner } from './codex-workspace-bridge.js';
export interface HostConnectedClient {
    deviceId: string;
    name: string;
    platform?: string;
    mode?: 'LAN' | 'P2P' | 'TURN' | 'Relay';
}
export interface HostRemoteStatus {
    deviceId?: string;
    configured: boolean;
    online: boolean;
    reconnecting: boolean;
    /** True until start() has wired the Server connection, so the UI can tell it from offline. */
    starting: boolean;
    lastActiveAt?: number;
    error?: string;
    account?: string;
    authorized: boolean;
    accountRequired: boolean;
    /** Set when the user paused remote availability while staying signed in. */
    paused: boolean;
    connectedClients: HostConnectedClient[];
}
export declare class HostPluginRuntime {
    private readonly config;
    private readonly identities;
    private readonly apiProxy;
    private readonly logger;
    private readonly localGateway?;
    private readonly fileViewerHost?;
    /** PTY-backed terminal provider from the Host `subprocess` service, when present. */
    private readonly terminalSpawner?;
    readonly connections: ConnectionController;
    private readonly terminalOwners;
    private readonly loopbackHosts;
    private terminalEnabled;
    private loopbackPorts;
    private identity?;
    private readonly serverApi?;
    private serverConnection?;
    /**
     * Whether the user asked this machine to stay unreachable.
     *
     * Pausing keeps the credentials and the device identity: it only stops the
     * outbound connection, so resuming needs no re-authorization and consumes no
     * device identity, unlike clearing the authorization.
     */
    private paused;
    /**
     * Whether start() has finished wiring the Server connection.
     *
     * The Codex domain is optional business that waits on an external binary, so it runs in the
     * background; without this flag a Host that is merely still starting would report itself as
     * offline and look broken to the user and to other clients.
     */
    private starting;
    private harnessVersion?;
    private closed;
    private readonly codex;
    private readonly codexWorkspaceState;
    private localCodexPeer?;
    private localCodexPublish;
    constructor(config: ResolvedConfig, identities: IdentityStore, apiProxy: ApiProxy | undefined, logger: SafeLogger, localGateway?: LocalTypertGateway | undefined, fileViewerHost?: (() => FileViewerHostServiceLike | undefined) | undefined, 
    /** PTY-backed terminal provider from the Host `subprocess` service, when present. */
    terminalSpawner?: RemoteTerminalSpawner | undefined);
    setTerminalEnabled(enabled: boolean): void;
    setLoopbackPorts(ports: readonly number[]): void;
    private createLoopbackHost;
    start(): Promise<void>;
    currentIdentity(): HostIdentity;
    acceptAuthenticatedPeer(channel: AuthenticatedPeerChannel): Promise<void>;
    hostStatus(): HostRemoteStatus;
    hasStoredAuthorization(): Promise<boolean>;
    private listConnectedClients;
    localHarnessVersion(): string | undefined;
    /**
     * Stop this machine from being reachable without releasing its authorization.
     *
     * Clearing the authorization revokes the device and rotates its identity, so a
     * user who only wants to stop being remotely reachable would have to authorize
     * again and would consume a device identity. Pausing closes the connection and
     * keeps both.
     */
    pauseHostConnection(): Promise<void>;
    /** Resume a paused connection with the same credentials and identity. */
    resumeHostConnection(): Promise<void>;
    isPaused(): boolean;
    reconnectHost(): void;
    startHostOAuthQrLogin(provider: OAuthProvider): Promise<OAuthQrSession>;
    pollHostOAuthQrLogin(qrId: string): Promise<OAuthQrPollResult>;
    /**
     * Stop this Host from being authorized, keeping its device identity.
     *
     * Revoking the device here would force a new identity on the next sign-in and
     * register a second device for the same installation. An account holds at most
     * 256 devices and the count only grows for new identities, so signing out
     * often enough could exhaust it. The credentials themselves are cleared by the
     * caller; this device simply stops authenticating.
     */
    clearHostAuthorization(): Promise<void>;
    authorizeHostAsOwned(accessToken: string, account?: string): Promise<DeviceAuthorization>;
    authorizeHostWithAccount(email: string, password: string): Promise<DeviceAuthorization>;
    authorizeHostWithCode(code: string): Promise<DeviceAuthorization>;
    revokePeer(deviceId: string): Promise<boolean>;
    codexStatus(): ReturnType<CodexRemoteDomain['status']>;
    codexCall(input: unknown): Promise<unknown>;
    codexRespond(input: unknown): Promise<{
        resolved: true;
    }>;
    codexOpenStream(input: unknown, publish: PublishCodexFrame): Promise<unknown>;
    codexCloseStream(input: unknown): Promise<unknown>;
    close(): Promise<void>;
    diagnostics(): {
        loaded: boolean;
        deviceId: string | undefined;
        identityValid: boolean;
        serverConfigured: boolean;
        serverOnline: boolean;
        serverError: string | undefined;
        online: boolean;
        activeConnections: number;
        peerDeviceId: string | undefined;
        peerDeviceIds: string[];
        trustedPeers: number;
        capabilities: string[];
        codex: {
            enabled: boolean;
            available: boolean;
            state: "disabled" | "starting" | "ready" | "restarting" | "unavailable";
            restartAttempt: number;
            error?: string;
        };
    };
    private createServerConnection;
    private readHarnessVersion;
    private hostCapabilities;
    private acpAvailable;
    private requireLocalCodexPeer;
}
//# sourceMappingURL=service.d.ts.map