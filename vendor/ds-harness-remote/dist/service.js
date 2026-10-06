import { LoopbackHost } from './loopback-host.js';
import { TerminalPolicy } from './terminal-policy.js';
import { randomUUID } from 'node:crypto';
import { createEvent } from '@dsh-remote/protocol';
import { ConnectionController } from './connection-controller.js';
import { harnessSessionGeneration, normalizeHarnessVersion, readHarnessDistributionVersion, selectHarnessVersion, } from './harness-version.js';
import { RpcRouter } from './rpc-router.js';
import { RemoteFileViewerBridge } from './file-viewer-bridge.js';
import { HostServerApi, ServerApiError, } from './server-api.js';
import { HostServerConnection } from './server-connection.js';
import { ServerCredentialStore } from './server-credentials.js';
import { HarnessApiBridge } from './harness-api-bridge.js';
import { HarnessRemoteBridge } from './harness-remote-bridge.js';
import { loadNodeRtcFactory } from './werift-rtc.js';
import { CodexRemoteDomain } from './codex/domain.js';
import { AcpGateway, StdioAcpAdapter } from './acp.js';
import { execFileSync } from 'node:child_process';
import { RpcError } from './safe-error.js';
import { CodexWorkspaceBridge, CodexWorkspaceState } from './codex-workspace-bridge.js';
export class HostPluginRuntime {
    config;
    identities;
    apiProxy;
    logger;
    localGateway;
    fileViewerHost;
    terminalSpawner;
    connections;
    terminalOwners = new Map();
    loopbackHosts = new Set();
    terminalEnabled;
    loopbackPorts;
    identity;
    serverApi;
    serverConnection;
    /**
     * Whether the user asked this machine to stay unreachable.
     *
     * Pausing keeps the credentials and the device identity: it only stops the
     * outbound connection, so resuming needs no re-authorization and consumes no
     * device identity, unlike clearing the authorization.
     */
    paused;
    harnessVersion;
    closed = false;
    codex;
    codexWorkspaceState = new CodexWorkspaceState();
    localCodexPeer;
    localCodexPublish = async () => undefined;
    constructor(config, identities, apiProxy, logger, localGateway, fileViewerHost, 
    /** PTY-backed terminal provider from the Host `subprocess` service, when present. */
    terminalSpawner) {
        this.config = config;
        this.identities = identities;
        this.apiProxy = apiProxy;
        this.logger = logger;
        this.localGateway = localGateway;
        this.fileViewerHost = fileViewerHost;
        this.terminalSpawner = terminalSpawner;
        this.terminalEnabled = config.terminal.enabled;
        this.loopbackPorts = [...config.loopback.ports];
        // Honour a pause recorded by an earlier run, so "do not connect me" is not
        // silently undone by restarting DSH.
        this.paused = config.hostControl?.paused === true;
        this.codex = new CodexRemoteDomain(config.codex, logger);
        this.connections = new ConnectionController(this.identities, (context, send) => {
            const harnessApi = this.apiProxy === undefined
                ? undefined
                : new HarnessApiBridge(this.apiProxy, (event, data) => send(createEvent(event, data)), undefined, this.logger, this.localGateway, this.harnessVersion);
            const harnessRemote = this.localGateway?.supportsCarrier === true
                ? new HarnessRemoteBridge(this.localGateway, (event, data) => send(createEvent(event, data)), this.logger, this.harnessVersion, new TerminalPolicy(() => this.terminalEnabled, context.peerDeviceId, this.terminalOwners), new CodexWorkspaceBridge((threadId, signal) => this.codex.resolveThreadWorkspace(context.connectionId, threadId), () => this.terminalEnabled, this.codexWorkspaceState, this.terminalSpawner))
                : undefined;
            const fileViewer = new RemoteFileViewerBridge(() => this.fileViewerHost?.(), this.logger);
            const codex = this.codex.createPeer(context, (event, data) => send(createEvent(event, data)));
            const adapters = (config.acp?.backends ?? []).filter(item => item.enabled && this.acpAvailable(item.command)).map(item => new StdioAcpAdapter(item));
            const acp = config.acp?.enabled && adapters.length > 0 ? new AcpGateway(adapters) : undefined;
            return new RpcRouter(harnessApi, undefined, this.logger, fileViewer, harnessRemote, () => this.hostCapabilities(), codex, acp, 
            // Handles and their lifetime belong to this connection; only policy is shared.
            this.createLoopbackHost());
        }, this.logger);
        if (config.serverUrl !== undefined) {
            this.serverApi = new HostServerApi(config.serverUrl, new ServerCredentialStore(identities.directory));
        }
    }
    setTerminalEnabled(enabled) {
        this.terminalEnabled = enabled;
    }
    setLoopbackPorts(ports) {
        this.loopbackPorts = [...ports];
        for (const loopback of this.loopbackHosts)
            loopback.setPorts(this.loopbackPorts);
    }
    createLoopbackHost() {
        let loopback;
        loopback = new LoopbackHost(() => this.loopbackPorts, () => this.loopbackHosts.delete(loopback));
        this.loopbackHosts.add(loopback);
        return loopback;
    }
    async start() {
        if (this.closed)
            throw new Error('remote runtime is closed');
        this.identity = await this.identities.loadOrCreate(this.config.deviceName);
        this.logger.info('host identity ready', {
            deviceId: shortId(this.identity.deviceId),
            fingerprint: this.identity.fingerprint,
            server: this.config.serverUrl ?? 'not configured',
        });
        await this.codex.start();
        if (this.serverApi !== undefined) {
            this.harnessVersion = await this.readHarnessVersion();
            this.serverApi.setHarnessVersion(this.harnessVersion);
            this.serverApi.bindIdentity(this.identity);
            this.serverConnection = this.createServerConnection(this.identity);
            // A paused installation stays quiet until the user resumes it; a restart
            // must not turn a paused machine reachable again.
            if (!this.paused)
                this.serverConnection.start();
        }
    }
    currentIdentity() {
        if (this.identity === undefined)
            throw new Error('remote runtime has not started');
        return this.identity;
    }
    acceptAuthenticatedPeer(channel) {
        this.currentIdentity();
        return this.connections.accept(channel);
    }
    hostStatus() {
        const error = this.serverConnection?.lastError();
        const authorization = this.serverApi?.currentAuthorization();
        return {
            ...(this.identity === undefined ? {} : { deviceId: this.identity.deviceId }),
            configured: this.serverApi !== undefined,
            online: this.serverConnection?.isOnline() ?? false,
            reconnecting: this.serverConnection?.isReconnecting() ?? false,
            ...(this.serverConnection?.lastActivity() === undefined
                ? {}
                : { lastActiveAt: this.serverConnection.lastActivity() }),
            ...(error === undefined ? {} : { error }),
            ...(authorization?.account === undefined ? {} : { account: authorization.account }),
            authorized: authorization !== undefined,
            accountRequired: error === 'ACCOUNT_AUTH_REQUIRED' || error === 'AUTH_INVALID' || error === 'TOKEN_EXPIRED',
            paused: this.paused,
            connectedClients: this.listConnectedClients(),
        };
    }
    async hasStoredAuthorization() {
        return this.serverApi?.hasStoredAuthorization() ?? false;
    }
    listConnectedClients() {
        return this.connections.connectedPeers().map(peer => {
            const trusted = this.identities.trustedPeer(peer.deviceId);
            return {
                deviceId: peer.deviceId,
                name: trusted?.name.trim() ?? '',
                ...(trusted === undefined ? {} : { platform: trusted.platform }),
                ...(peer.mode === undefined ? {} : { mode: peer.mode }),
            };
        });
    }
    localHarnessVersion() {
        return this.harnessVersion;
    }
    /**
     * Stop this machine from being reachable without releasing its authorization.
     *
     * Clearing the authorization revokes the device and rotates its identity, so a
     * user who only wants to stop being remotely reachable would have to authorize
     * again and would consume a device identity. Pausing closes the connection and
     * keeps both.
     */
    async pauseHostConnection() {
        this.paused = true;
        await this.serverConnection?.stop();
        this.logger.info('Host connection paused');
    }
    /** Resume a paused connection with the same credentials and identity. */
    async resumeHostConnection() {
        const wasPaused = this.paused;
        this.paused = false;
        this.serverConnection?.resume();
        if (wasPaused)
            this.logger.info('Host connection resumed');
    }
    isPaused() { return this.paused; }
    reconnectHost() {
        if (this.closed)
            throw new Error('remote runtime is closed');
        if (this.serverConnection === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Configure serverUrl before reconnecting.', false);
        }
        // Asking to reconnect is explicit: it lifts a pause rather than being
        // swallowed by it, so the button always has an effect.
        this.paused = false;
        this.serverConnection.reconnect();
    }
    async startHostOAuthQrLogin(provider) {
        if (this.serverApi === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Remote Host Server is unavailable.', false);
        }
        return this.serverApi.startOAuthQrLogin(provider);
    }
    async pollHostOAuthQrLogin(qrId) {
        if (this.serverApi === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Remote Host Server is unavailable.', false);
        }
        const result = await this.serverApi.pollOAuthQrLogin(this.currentIdentity(), qrId, async () => {
            await this.serverConnection?.stop();
            this.identity = await this.identities.reset(this.config.deviceName);
            this.serverApi.bindIdentity(this.identity);
            this.serverConnection = this.createServerConnection(this.identity);
            return this.identity;
        });
        if (result.status === 'complete') {
            this.serverConnection?.reconnect();
            this.logger.info('Host account authorized through OAuth QR login');
        }
        return result;
    }
    /**
     * Stop this Host from being authorized, keeping its device identity.
     *
     * Revoking the device here would force a new identity on the next sign-in and
     * register a second device for the same installation. An account holds at most
     * 256 devices and the count only grows for new identities, so signing out
     * often enough could exhaust it. The credentials themselves are cleared by the
     * caller; this device simply stops authenticating.
     */
    async clearHostAuthorization() {
        await this.serverConnection?.stop();
        // Clearing the stored credential is not enough: this API caches the
        // authorization in memory too, and the stale copy keeps reporting the Host as
        // authorized. That makes `authorizeHostByDefault()` skip re-authorization and
        // leaves the connection retrying tokens the Server no longer accepts.
        await this.serverApi?.clearAuthorization();
        // The connection survives, bound to the identity it keeps.
        if (this.serverApi !== undefined && this.identity !== undefined) {
            this.serverConnection = this.createServerConnection(this.identity);
        }
        this.logger.info('Host authorization cleared');
    }
    async authorizeHostAsOwned(accessToken, account) {
        if (this.serverApi === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Configure serverUrl before enabling Host access.', false);
        }
        let result;
        try {
            result = await this.serverApi.authorizeOwnedRole(this.currentIdentity(), accessToken, account);
        }
        catch (error) {
            if (!(error instanceof ServerApiError) || error.code !== 'DEVICE_REVOKED')
                throw error;
            await this.serverConnection?.stop();
            this.identity = await this.identities.reset(this.config.deviceName);
            this.serverApi.bindIdentity(this.identity);
            this.serverConnection = this.createServerConnection(this.identity);
            result = await this.serverApi.authorizeOwnedRole(this.identity, accessToken, account);
            this.logger.info('Rotated revoked Host identity before owned-device authorization');
        }
        // An authorization does not lift an explicit pause: the UI shows the paused
        // state with its own resume action, so the user's choice stays in force.
        if (!this.paused)
            this.serverConnection?.resume();
        this.logger.info('Host authorized as an owned device');
        return result;
    }
    async authorizeHostWithAccount(email, password) {
        if (this.serverApi === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Configure serverUrl before signing in.', false);
        }
        const result = await this.serverApi.authorizeWithAccount(this.currentIdentity(), email, password);
        // An authorization does not lift an explicit pause: the UI shows the paused
        // state with its own resume action, so the user's choice stays in force.
        if (!this.paused)
            this.serverConnection?.resume();
        this.logger.info('Host account authorized');
        return result;
    }
    async authorizeHostWithCode(code) {
        if (this.serverApi === undefined) {
            throw new ServerApiError('SERVER_NOT_CONFIGURED', 'Configure serverUrl before entering a Host registration code.', false);
        }
        const result = await this.serverApi.authorizeHostWithCode(this.currentIdentity(), code);
        // An authorization does not lift an explicit pause: the UI shows the paused
        // state with its own resume action, so the user's choice stays in force.
        if (!this.paused)
            this.serverConnection?.resume();
        this.logger.info('Host registration code authorized');
        return result;
    }
    async revokePeer(deviceId) {
        const revoked = await this.identities.revokePeer(deviceId);
        if (revoked)
            await this.connections.revoke(deviceId);
        return revoked;
    }
    codexStatus() {
        return this.codex.status();
    }
    codexCall(input) {
        return this.requireLocalCodexPeer().call(input);
    }
    codexRespond(input) {
        return this.requireLocalCodexPeer().respond(input);
    }
    codexOpenStream(input, publish) {
        this.localCodexPublish = publish;
        return this.requireLocalCodexPeer().openStream(input);
    }
    async codexCloseStream(input) {
        const peer = this.localCodexPeer;
        if (peer !== undefined)
            return peer.closeStream(input);
        const streamId = isPlainRecord(input) && typeof input.streamId === 'string' ? input.streamId : undefined;
        if (streamId === undefined)
            throw new RpcError('INVALID_MESSAGE', 'A Codex stream is required.');
        return { closed: false, streamId };
    }
    async close() {
        if (this.closed)
            return;
        this.closed = true;
        await this.serverConnection?.stop();
        await this.connections.close();
        await this.localCodexPeer?.closeAll();
        this.localCodexPeer = undefined;
        await this.codex.close();
        this.logger.info('host runtime stopped');
    }
    diagnostics() {
        return {
            loaded: this.identity !== undefined,
            deviceId: this.identity === undefined ? undefined : shortId(this.identity.deviceId),
            identityValid: this.identity !== undefined,
            serverConfigured: this.config.serverUrl !== undefined,
            serverOnline: this.serverConnection?.isOnline() ?? false,
            serverError: this.serverConnection?.lastError(),
            online: this.connections.isOnline(),
            activeConnections: this.connections.connectionCount(),
            peerDeviceId: this.connections.peerDeviceId() === undefined ? undefined : shortId(this.connections.peerDeviceId()),
            peerDeviceIds: this.connections.peerDeviceIds().map(shortId),
            trustedPeers: this.identities.listTrustedPeers().length,
            capabilities: this.hostCapabilities(),
            codex: this.codex.status(),
        };
    }
    createServerConnection(identity) {
        return new HostServerConnection(this.config, identity, this.identities, this.serverApi, this.connections, this.logger, undefined, this.config.forceRelay
            ? undefined
            : () => loadNodeRtcFactory({ routeTargets: this.config.serverUrl === undefined ? [] : [this.config.serverUrl] }), () => this.hostCapabilities(), this.harnessVersion);
    }
    async readHarnessVersion() {
        let reportedVersion;
        let errorCode;
        try {
            const response = await this.apiProxy?.host.describe({ rpcId: randomUUID(), payload: {} });
            if (response === undefined)
                throw new Error('ApiProxy is unavailable');
            if (!response.result.ok) {
                errorCode = response.result.error.code;
            }
            else {
                reportedVersion = normalizeHarnessVersion(response.result.value.version);
            }
        }
        catch {
            // Older Harness builds may not expose host.describe.
        }
        const distributionVersion = reportedVersion === undefined || reportedVersion === '0.0.1'
            ? await readHarnessDistributionVersion()
            : undefined;
        const version = selectHarnessVersion(reportedVersion, distributionVersion);
        if (version !== undefined)
            return version;
        this.logger.warn('Harness version is unavailable', errorCode === undefined ? undefined : { code: errorCode });
        return undefined;
    }
    hostCapabilities() {
        const capabilities = [];
        if (this.loopbackPorts.length > 0)
            capabilities.push('loopback.http-ws.v1');
        if (this.localGateway?.supportsCarrier === true) {
            capabilities.push(harnessSessionGeneration(this.harnessVersion) === 'v3' ? 'harness.remote.v3' : 'harness.remote.v1', 'harness.remote.transfer.v1');
        }
        if (this.localGateway?.supportsCarrier && this.terminalEnabled)
            capabilities.push('harness.terminal.v1');
        if (this.apiProxy !== undefined) {
            capabilities.push('harness.api.v1', 'harness.api.transfer.v1');
        }
        if (this.fileViewerHost?.() !== undefined)
            capabilities.push('fileviewer.read.v1');
        if (this.codex.isAvailable())
            capabilities.push('codex.appserver.v1', 'codex.appserver.transfer.v1');
        if (this.config.acp?.enabled)
            for (const item of this.config.acp.backends)
                if (item.enabled && this.acpAvailable(item.command))
                    capabilities.push(`agent.acp.v1.${item.id}`);
        return capabilities;
    }
    acpAvailable(command) {
        try {
            execFileSync(process.platform === 'win32' ? 'where' : 'which', [command], { stdio: 'ignore' });
            return true;
        }
        catch {
            return false;
        }
    }
    requireLocalCodexPeer() {
        if (!this.codex.isAvailable()) {
            throw new RpcError('CODEX_UNAVAILABLE', 'Local CodeX is disabled or unavailable on this Host.');
        }
        if (this.localCodexPeer !== undefined)
            return this.localCodexPeer;
        const identity = this.currentIdentity();
        const peer = this.codex.createPeer({
            connectionId: `loopback:${identity.deviceId}`,
            peerDeviceId: identity.deviceId,
        }, (event, data) => this.localCodexPublish(event, data));
        if (peer === undefined) {
            throw new RpcError('CODEX_UNAVAILABLE', 'Local CodeX is disabled or unavailable on this Host.');
        }
        this.localCodexPeer = peer;
        return peer;
    }
}
function shortId(value) { return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`; }
function isPlainRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=service.js.map