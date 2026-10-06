import { LoopbackPreview } from './loopback-preview.js';
import { CodexRemoteClient, RemoteClientCore } from '@dsh-remote/client-core';
import { AdaptiveTransport, stunOnlyIceServers, } from '@dsh-remote/webrtc';
import { ApiProxySwitch } from './api-proxy-switch.js';
import { ClientSecureTransport } from './client-secure-transport.js';
import { registerControlRoute } from './control-route.js';
import { uuidV7 } from './ids.js';
import { harnessSessionGeneration } from './harness-version.js';
import { RemoteHarnessApiProxy } from './remote-api-proxy.js';
import { RemoteTypertGateway } from './remote-typert-gateway.js';
import { codexProjectWorkspaceId, CodexVirtualHarness, discoverCodexVirtualWorkspaces, } from './codex/virtual-harness.js';
import { ServerApiError, } from './server-api.js';
import { TypertGatewaySwitch } from './typert-gateway-switch.js';
import { loadNodeRtcFactory } from './werift-rtc.js';
import { safeErrorCode } from './safe-error.js';
const REMOTE_COMMAND_LIST_MIN_VERSION = [0, 3, 16];
const REMOTE_FILE_VIEWER_MIN_VERSION = [0, 3, 17];
const DIRECT_WEBRTC_NEGOTIATE_TIMEOUT_MS = 12_000;
const DIRECT_LAN_PROGRESS_DISPLAY_MS = 1_400;
const HOST_AUTHORIZATION_ERRORS = new Set([
    'ACCOUNT_AUTH_REQUIRED',
    'AUTH_INVALID',
    'DEVICE_OWNERSHIP_REQUIRED',
    'DEVICE_REVOKED',
    'TOKEN_EXPIRED',
]);
export class ClientModeRuntime {
    config;
    identities;
    server;
    logger;
    host;
    rtcFactoryProvider;
    preview;
    identity;
    connected;
    /**
     * Set when a remote session dropped and the runtime fell back to local.
     *
     * The mode then reads 'local', which is what every "return to local" control is
     * gated on, so without this the user is left in a stale remote view with no way
     * back except signing out.
     */
    fellBackToLocal = false;
    /**
     * Identifies the live reconnect loop.
     *
     * A dropped transport starts one; anything else that settles the connection —
     * the user returning to local, a logout, a fresh connect — bumps it so the loop
     * stops instead of fighting the newer decision.
     */
    remoteReconnectRun = 0;
    pendingWorkspaceSelection;
    codexVirtual;
    proxySwitch;
    gatewaySwitch;
    codexStreams = new Map();
    connectionProgress;
    connectionProgressRun = 0;
    closed = false;
    constructor(config, identities, server, apiProxy, typertGateway, logger, host, rtcFactoryProvider = loadNodeRtcFactory) {
        this.config = config;
        this.identities = identities;
        this.server = server;
        this.logger = logger;
        this.host = host;
        this.rtcFactoryProvider = rtcFactoryProvider;
        this.proxySwitch = apiProxy === undefined ? undefined : new ApiProxySwitch(apiProxy);
        this.gatewaySwitch = new TypertGatewaySwitch(typertGateway);
        // Serve the local shell while no peer session is live. Otherwise a remote-mode
        // boot routes its own local services at a peer that may not be there, and the
        // shell never finishes activating.
        this.gatewaySwitch.setRemoteAvailability(() => this.connected !== undefined);
    }
    async start() {
        if (this.closed)
            throw new Error('client remote-mode runtime is closed');
        this.identity = await this.identities.loadOrCreate(this.config.deviceName);
        this.server.bindIdentity(this.identity);
        this.proxySwitch?.install();
        this.gatewaySwitch.install();
        this.logger.info('client remote-mode identity ready', {
            deviceId: shortId(this.identity.deviceId),
            fingerprint: this.identity.fingerprint,
        });
        // A previously authorized Client should make the local Host controllable
        // on startup as well. Do not register an anonymous Client just to probe:
        // only persisted Client credentials opt into this default.
        if (this.config.hostControl?.enabled !== false
            && this.host !== undefined
            && this.server.hasStoredAuthorization !== undefined) {
            try {
                if (await this.server.hasStoredAuthorization()
                    && (this.host.hasStoredAuthorization === undefined || !await this.host.hasStoredAuthorization())) {
                    await this.authorizeHostByDefault();
                }
            }
            catch (error) {
                this.logger.warn('automatic Host authorization failed', { code: safeErrorCode(error) });
            }
        }
    }
    /**
     * Whether this installation already holds stored Server credentials for its
     * Client identity.
     *
     * This is the local answer to "is this installation signed in", available
     * without contacting the Server, so the UI can choose its panel immediately
     * instead of inferring the answer from a failed network round trip.
     */
    async hasStoredAuthorization() {
        return this.server.hasStoredAuthorization === undefined
            ? false
            : await this.server.hasStoredAuthorization();
    }
    async authorizeHostByDefault() {
        try {
            if (this.host === undefined)
                return;
            if (this.config.hostControl?.enabled === false)
                return;
            const status = this.host.hostStatus();
            if (status.authorized)
                return;
            // Stored credentials the Server has already rejected must not block
            // re-authorization. A credential left over from another account — for
            // example after a Server state migration — would otherwise keep the Host
            // unregistered while the UI keeps telling the user to authorize again.
            const rejected = status.error !== undefined && HOST_AUTHORIZATION_ERRORS.has(status.error);
            if (!rejected && this.host.hasStoredAuthorization !== undefined && await this.host.hasStoredAuthorization())
                return;
            const credentials = await this.server.authenticate(this.requireIdentity());
            await this.host.authorizeHostAsOwned(credentials.accessToken, credentials.account);
        }
        catch (error) {
            this.logger.warn('automatic Host authorization failed', { code: safeErrorCode(error) });
        }
    }
    registerControl(connection, webServer) {
        return registerControlRoute(connection, (endpoint, payload, signal) => this.handleControl(endpoint, payload, signal), webServer);
    }
    status() {
        const targetStatus = this.gatewaySwitch.supportsCarrier()
            ? this.gatewaySwitch.status()
            : this.proxySwitch?.status() ?? this.gatewaySwitch.status();
        return {
            available: this.config.serverUrl !== undefined,
            identityReady: this.identity !== undefined,
            deviceId: this.identity?.deviceId,
            deviceName: this.identity?.name,
            serverUrl: this.config.serverUrl,
            ...targetStatus,
            connected: this.connected !== undefined,
            fellBackToLocal: this.fellBackToLocal,
            transport: this.connected?.client.getStats().mode ?? 'Disconnected',
            connectedTargetDeviceId: this.connected?.target.deviceId,
            preferredTransports: this.config.forceRelay ? ['relay'] : ['lan', 'p2p', 'turn', 'relay'],
            ...(this.connectionProgress === undefined ? {} : {
                connectionProgress: {
                    targetDeviceId: this.connectionProgress.targetDeviceId,
                    phase: this.connectionProgress.phase,
                    ...(this.connectionProgress.activeTransports === undefined
                        ? {}
                        : { activeTransports: [...this.connectionProgress.activeTransports] }),
                },
            }),
            remoteFeatures: this.connected?.features ?? remoteHostFeatures(),
            ...(this.pendingWorkspaceSelection === undefined
                ? {}
                : { workspaceSelection: { ...this.pendingWorkspaceSelection } }),
            backend: this.codexVirtual === undefined ? 'harness' : 'codex',
            hostAuthorizationAvailable: this.host !== undefined,
            ...(this.host === undefined ? {} : { host: this.host.hostStatus() }),
        };
    }
    async closePreview() {
        const preview = this.preview;
        this.preview = undefined;
        await preview?.close();
    }
    async detailedStatus() {
        const connected = this.connected;
        if (connected === undefined || this.identity === undefined)
            return this.status();
        const details = await connected.transport.connectionDetails();
        if (this.connected !== connected)
            return this.status();
        return {
            ...this.status(),
            network: {
                ...details,
                local: {
                    deviceId: this.identity.deviceId,
                    name: this.identity.name,
                    platform: process.platform,
                },
                remote: {
                    deviceId: connected.target.deviceId,
                    name: connected.target.name,
                    platform: connected.target.platform,
                },
            },
        };
    }
    async devices() {
        this.assertHostAuthorizationForDeviceDiscovery();
        this.requireIdentity();
        const serverDevices = await this.server.listDevices();
        const remoteDevices = serverDevices.filter(device => device.deviceId !== this.host?.hostStatus().deviceId);
        return Promise.all(remoteDevices.map(async (device) => {
            await this.authorizeHostPeer(device);
            const presence = await this.server.presenceFor(device.deviceId).catch(() => ({ online: false }));
            return { ...device, ...presence };
        }));
    }
    /**
     * Device discovery is exposed through the local app control route. When this
     * installation also runs a Host, keep that route closed after the Host's
     * Server credential has become terminally invalid. The Client credential can
     * remain usable for a short time after a revoke, so checking only
     * `ClientServerApi.listDevices()` would otherwise leak the device directory
     * from a Host that the user has already been told to re-authorize.
     */
    assertHostAuthorizationForDeviceDiscovery() {
        const status = this.host?.hostStatus();
        if (status === undefined || status.error === undefined || !HOST_AUTHORIZATION_ERRORS.has(status.error))
            return;
        const message = status.error === 'DEVICE_REVOKED'
            ? 'The local Host was revoked on the Server. Sign out and authorize this Host again.'
            : 'The local Host authorization is no longer valid. Sign out and authorize this Host again.';
        throw new ClientModeError(status.error, message);
    }
    async authorizeClientWithAccount(email, password) {
        let authorization;
        try {
            authorization = await this.server.authorizeWithAccount(this.requireIdentity(), email, password);
        }
        catch (error) {
            if (!(error instanceof ServerApiError) || error.code !== 'DEVICE_REVOKED')
                throw error;
            this.identity = await this.identities.reset(this.config.deviceName);
            this.server.bindIdentity(this.identity);
            authorization = await this.server.authorizeWithAccount(this.identity, email, password);
        }
        await this.authorizeHostByDefault();
        this.logger.info('Client account authorized');
        return authorization;
    }
    async startClientOAuthQrLogin(provider) {
        return this.server.startOAuthQrLogin(provider);
    }
    async pollClientOAuthQrLogin(qrId) {
        const result = await this.server.pollOAuthQrLogin(this.requireIdentity(), qrId, async () => {
            this.identity = await this.identities.reset(this.config.deviceName);
            this.server.bindIdentity(this.identity);
            this.logger.info('Rotated revoked Client identity before QR authorization retry');
            return this.identity;
        });
        if (result.status === 'complete')
            this.logger.info('Client account authorized with QR login');
        if (result.status === 'complete')
            await this.authorizeHostByDefault();
        return result;
    }
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
    async clearClientAuthorization() {
        const previous = this.connected;
        this.connected = undefined;
        this.connectionProgress = undefined;
        this.pendingWorkspaceSelection = undefined;
        await this.closeCodexVirtual();
        this.proxySwitch?.selectLocal();
        await this.closePreview();
        this.gatewaySwitch.selectLocal();
        await this.closeCodexStreams(previous?.client);
        await previous?.client.close().catch(() => undefined);
        // A sign-out ends the session for good, so no reconnect loop may keep trying.
        this.remoteReconnectRun += 1;
        // Clear the in-memory authorization as well as the stored credential. The
        // caller clears the credential file, but this API also caches the
        // authorization in memory, and a stale copy keeps reporting the Client as
        // authorized — which stops the Host from re-authorizing and leaves the
        // connection retrying tokens the Server no longer accepts.
        await this.server.clearAuthorization();
    }
    async setHostAuthorization(enabled) {
        if (this.host === undefined)
            throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
        if (!enabled) {
            await this.host.clearHostAuthorization();
            return this.status();
        }
        const credentials = await this.server.authenticate(this.requireIdentity());
        await this.host.authorizeHostAsOwned(credentials.accessToken, credentials.account);
        return this.status();
    }
    async setMode(mode, targetDeviceId, signal) {
        if (mode === 'local') {
            await this.closeCodexVirtual();
            this.proxySwitch?.selectLocal();
            await this.closePreview();
            this.gatewaySwitch.selectLocal();
            const previous = this.connected;
            this.connected = undefined;
            this.connectionProgress = undefined;
            this.pendingWorkspaceSelection = undefined;
            await this.closeCodexStreams(previous?.client);
            await previous?.client.close().catch(() => undefined);
            // Returning to local is the answer to a dropped session, so the record of
            // that drop must not outlive it: otherwise the exit affordances stay on
            // screen after the user has already acted on them. Stop any pending
            // reconnect too — the user asked for local, not for a retry.
            this.fellBackToLocal = false;
            this.remoteReconnectRun += 1;
            this.logger.info('Harness target switched', { mode: 'local' });
            return this.status();
        }
        if (targetDeviceId === undefined || targetDeviceId.length === 0) {
            throw new ClientModeError('INVALID_MESSAGE', 'A targetDeviceId is required for remote mode.');
        }
        const next = await this.connect(targetDeviceId, signal);
        try {
            this.assertRemoteCompatible(next);
        }
        catch (error) {
            this.clearConnectionProgress(next.progressRunId);
            await next.client.close().catch(() => undefined);
            throw error;
        }
        const previous = this.connected;
        await this.closePreview();
        this.connected = next;
        this.clearConnectionProgress(next.progressRunId);
        this.pendingWorkspaceSelection = undefined;
        await this.closeCodexVirtual();
        this.selectRemoteTarget(next);
        // A fresh remote session clears the record of an earlier dropped one.
        this.fellBackToLocal = false;
        await this.closeCodexStreams(previous?.client);
        await previous?.client.close().catch(() => undefined);
        this.logger.info('Harness target switched', { mode: 'remote', targetDeviceId: shortId(next.target.deviceId) });
        return this.status();
    }
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
    async reconnectRemoteSession(targetDeviceId) {
        const run = ++this.remoteReconnectRun;
        // Fast attempts first, then a steady low rate. Timers do not run while a
        // client is suspended, so a pending wait simply lands when it comes back —
        // which is what makes a backgrounded phone reconnect on its own. Keeping the
        // loop alive matters for the other order too: attempts spent while the network
        // was down must not leave the session dead until the user acts.
        const fastDelays = [1_000, 2_000, 4_000, 8_000, 15_000];
        let attempt = 0;
        for (;;) {
            const wait = fastDelays[attempt] ?? 30_000;
            await new Promise(resolve => { setTimeout(resolve, wait); });
            if (run !== this.remoteReconnectRun)
                return;
            if (this.connected !== undefined)
                return;
            try {
                await this.setMode('remote', targetDeviceId);
                this.logger.info('remote Harness session reconnected', { targetDeviceId: shortId(targetDeviceId) });
                return;
            }
            catch (error) {
                // Report the early attempts, then only occasionally: a Host that stays
                // away would otherwise fill the log every half minute.
                if (attempt < 3 || attempt % 10 === 0) {
                    this.logger.warn('remote Harness reconnect attempt failed', {
                        targetDeviceId: shortId(targetDeviceId),
                        attempt,
                        code: safeErrorCode(error),
                    });
                }
            }
            attempt += 1;
        }
    }
    async listRemoteDirectory(targetDeviceId, path, signal) {
        const remote = await this.ensureConnected(targetDeviceId, signal);
        if (remote.features.remoteGateway) {
            const value = await new RemoteTypertGateway(remote.client).invoke({
                namespace: 'directoryPicker',
                method: 'list',
                args: path === undefined ? {} : { path },
                ...(signal === undefined ? {} : { signal }),
            });
            return value;
        }
        const api = new RemoteHarnessApiProxy(remote.client).api;
        const response = await api.host.listDirectory({
            rpcId: `remote-directory-${Date.now()}`,
            payload: path === undefined ? {} : { path },
        }, signal ?? new AbortController().signal);
        return unwrapNativeResult(response);
    }
    async listRemoteWorkspaces(targetDeviceId, signal) {
        const remote = await this.ensureConnected(targetDeviceId, signal);
        if (remote.features.remoteGateway) {
            return readRemoteWorkspaceBaseline(new RemoteTypertGateway(remote.client), signal);
        }
        const api = new RemoteHarnessApiProxy(remote.client).api;
        const response = await api.workspace.list({
            rpcId: `remote-workspaces-${Date.now()}`,
            payload: {},
        });
        const value = unwrapNativeResult(response);
        return value.items;
    }
    async openRemoteWorkspace(targetDeviceId, path, signal) {
        if (path.trim() === '')
            throw new ClientModeError('INVALID_MESSAGE', 'A remote working directory is required.');
        const remote = await this.ensureConnected(targetDeviceId, signal);
        const transport = this.selectHarnessRemoteTransport(remote);
        let workspace;
        if (transport === 'remoteGateway') {
            workspace = await new RemoteTypertGateway(remote.client).invoke({
                namespace: 'workspace',
                method: 'create',
                args: { request: { path } },
                ...(signal === undefined ? {} : { signal }),
            });
        }
        else {
            const api = new RemoteHarnessApiProxy(remote.client).api;
            const response = await api.workspace.create({
                rpcId: `remote-workspace-${Date.now()}`,
                payload: { path },
            });
            workspace = unwrapNativeResult(response);
        }
        await this.closeCodexVirtual();
        this.selectRemoteTarget(remote, transport);
        const workspaceId = workspaceRecordId(workspace.workspace);
        this.pendingWorkspaceSelection = { targetDeviceId: remote.target.deviceId, workspaceId };
        this.logger.info('Remote workspace opened', { targetDeviceId: shortId(remote.target.deviceId) });
        return { ...this.status(), workspace };
    }
    async listCodexWorkspaces(targetDeviceId, signal) {
        const remote = await this.ensureConnected(targetDeviceId, signal);
        remote.features = await probeRemoteHostFeatures(remote.client, remote.clientVersion);
        if (!remote.features.codex) {
            throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'The selected Host does not provide CodeX workspaces.');
        }
        return discoverCodexVirtualWorkspaces(new CodexRemoteClient(remote.client), signal);
    }
    async openCodexWorkspace(targetDeviceId, workspaceId, signal) {
        const remote = await this.ensureConnected(targetDeviceId, signal);
        remote.features = await probeRemoteHostFeatures(remote.client, remote.clientVersion);
        if (!remote.features.codex) {
            throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'The selected Host does not provide CodeX workspaces.');
        }
        this.assertLocalHarnessCarrierAvailable();
        const virtual = CodexVirtualHarness.remote(remote.client, {
            deviceId: remote.target.deviceId,
            name: remote.target.name,
        }, harnessSessionGeneration(this.host?.localHarnessVersion?.()), new RemoteTypertGateway(remote.client));
        let workspace;
        try {
            workspace = await virtual.selectWorkspace(workspaceId, signal);
        }
        catch {
            await virtual.close();
            throw new ClientModeError('WORKSPACE_NOT_FOUND', 'The selected CodeX workspace is no longer available.');
        }
        await this.closeCodexVirtual();
        this.codexVirtual = virtual;
        this.selectCodexTarget(virtual, remote);
        const preferredSessionId = await virtual.preferredSessionId(signal);
        this.pendingWorkspaceSelection = {
            targetDeviceId: remote.target.deviceId,
            workspaceId,
            backend: 'codex',
            ...(preferredSessionId === undefined ? {} : { sessionId: preferredSessionId }),
        };
        this.logger.info('CodeX virtual workspace opened', { targetDeviceId: shortId(remote.target.deviceId) });
        return { ...this.status(), workspace };
    }
    async createCodexWorkspace(targetDeviceId, path, signal) {
        const trimmedPath = path.trim();
        if (trimmedPath === '')
            throw new ClientModeError('INVALID_MESSAGE', 'A CodeX project directory is required.');
        const remote = await this.ensureConnected(targetDeviceId, signal);
        remote.features = await probeRemoteHostFeatures(remote.client, remote.clientVersion);
        if (!remote.features.codex) {
            throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'The selected Host does not provide CodeX workspaces.');
        }
        this.assertLocalHarnessCarrierAvailable();
        const result = record(await new CodexRemoteClient(remote.client).request('project/create', {
            name: remoteWorkspaceTitle(trimmedPath),
            roots: [{ path: trimmedPath }],
            idempotencyKey: uuidV7(),
        }, signal));
        const project = record(result.project);
        if (typeof project.id !== 'string' || project.id.length === 0) {
            throw new ClientModeError('INVALID_MESSAGE', 'The Host returned an invalid CodeX project.');
        }
        return this.openCodexWorkspace(targetDeviceId, codexProjectWorkspaceId(project.id), signal);
    }
    consumeWorkspaceSelection(selection) {
        const pending = this.pendingWorkspaceSelection;
        if (pending?.targetDeviceId === selection.targetDeviceId
            && pending.workspaceId === selection.workspaceId
            && (pending.backend ?? 'harness') === (selection.backend ?? 'harness')) {
            this.pendingWorkspaceSelection = undefined;
        }
        return this.status();
    }
    async close() {
        await this.closePreview();
        if (this.closed)
            return;
        this.closed = true;
        this.proxySwitch?.selectLocal();
        await this.closePreview();
        this.gatewaySwitch.selectLocal();
        this.pendingWorkspaceSelection = undefined;
        await this.closeCodexVirtual();
        await this.closeCodexStreams(this.connected?.client);
        await this.connected?.client.close().catch(() => undefined);
        this.connected = undefined;
        this.connectionProgress = undefined;
        this.proxySwitch?.restore();
        this.gatewaySwitch.restore();
    }
    async callRemoteFileViewer(endpoint, payload, signal) {
        const remote = this.connected;
        if (remote === undefined || this.status().mode !== 'remote') {
            throw new ClientModeError('REMOTE_NOT_CONNECTED', 'No Remote Host is selected.', true);
        }
        if (!remote.features.fileViewer) {
            throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'The selected Remote Host does not support remote file viewing.');
        }
        return remote.client.rpc('fileviewer.call', { endpoint, payload }, signal);
    }
    activeRemote() {
        return this.connected;
    }
    activeCodexRemote() {
        const remote = this.activeRemote();
        if (remote === undefined)
            return undefined;
        if (!remote.features.codex) {
            return undefined;
        }
        return remote;
    }
    async openCodexStream(payload, signal) {
        const remote = this.activeCodexRemote();
        const value = record(payload);
        if (typeof value.streamId !== 'string' || value.streamId.length === 0 || value.streamId.length > 128
            || typeof value.threadId !== 'string' || value.threadId.length === 0) {
            throw new ClientModeError('INVALID_MESSAGE', 'A Codex stream and thread are required.');
        }
        if (this.codexStreams.has(value.streamId))
            throw new ClientModeError('REQUEST_CONFLICT', 'The Codex stream is already open.');
        let wake = () => undefined;
        const stream = {
            target: remote === undefined ? { kind: 'local' } : { kind: 'remote', client: remote.client },
            frames: [],
            unsubscribe: () => undefined,
            close: async () => {
                if (remote === undefined) {
                    await this.host?.codexCloseStream?.({ streamId: value.streamId }).catch(() => undefined);
                    return;
                }
                await remote.client.rpc('codex.app.stream.close', { streamId: value.streamId }).catch(() => undefined);
            },
            wake: () => wake(),
        };
        if (remote === undefined) {
            const host = this.requireLocalCodex();
            this.codexStreams.set(value.streamId, stream);
            try {
                const result = await host.codexOpenStream({ streamId: value.streamId, threadId: value.threadId }, this.publishLocalCodexFrame, signal);
                return result;
            }
            catch (error) {
                this.codexStreams.delete(value.streamId);
                stream.wake();
                throw error;
            }
        }
        stream.unsubscribe = remote.client.onEvent(event => {
            if (event.event === 'codex.app.frame' && isRecord(event.data) && event.data.streamId === value.streamId) {
                this.appendCodexFrame(stream, event.data);
            }
            if (event.event === 'codex.app.stream.closed' && isRecord(event.data) && event.data.streamId === value.streamId) {
                stream.closed = typeof event.data.reason === 'string' ? event.data.reason : 'closed';
                stream.wake();
            }
        });
        try {
            // Subscribe before opening the Host stream so the first App Server
            // notification cannot race past the loopback listener.
            await remote.client.rpc('codex.app.stream.open', { streamId: value.streamId, threadId: value.threadId }, signal);
        }
        catch (error) {
            stream.unsubscribe();
            throw error;
        }
        this.codexStreams.set(value.streamId, stream);
        return { opened: true, streamId: value.streamId, threadId: value.threadId };
    }
    publishLocalCodexFrame = async (event, data) => {
        const streamId = data.streamId;
        const stream = this.codexStreams.get(streamId);
        if (stream === undefined || stream.target.kind !== 'local')
            return;
        if (event === 'codex.app.frame') {
            this.appendCodexFrame(stream, data);
            return;
        }
        const closed = data;
        stream.closed = typeof closed.reason === 'string' ? closed.reason : 'closed';
        stream.wake();
    };
    appendCodexFrame(stream, data) {
        if (!isRecord(data) || !isRecord(data.frame) || typeof data.frame.method !== 'string')
            return;
        if (stream.frames.length >= 256) {
            stream.closed = 'overflow';
        }
        else {
            stream.frames.push({ method: data.frame.method, params: data.frame.params });
        }
        stream.wake();
    }
    localCodexAvailable() {
        return this.host?.codexStatus?.().available === true;
    }
    requireLocalCodex() {
        if (!this.localCodexAvailable()
            || this.host?.codexCall === undefined
            || this.host.codexRespond === undefined
            || this.host.codexOpenStream === undefined
            || this.host.codexCloseStream === undefined) {
            throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'Local CodeX is disabled or unavailable on this Host.');
        }
        return {
            codexCall: this.host.codexCall.bind(this.host),
            codexRespond: this.host.codexRespond.bind(this.host),
            codexOpenStream: this.host.codexOpenStream.bind(this.host),
            codexCloseStream: this.host.codexCloseStream.bind(this.host),
        };
    }
    async nextCodexFrames(payload, signal) {
        const value = record(payload);
        if (typeof value.streamId !== 'string')
            throw new ClientModeError('INVALID_MESSAGE', 'A Codex stream is required.');
        const stream = this.codexStreams.get(value.streamId);
        if (stream === undefined)
            throw new ClientModeError('STREAM_NOT_FOUND', 'The Codex stream is not open.');
        if (stream.frames.length === 0 && stream.closed === undefined)
            await waitForCodexFrames(stream, signal);
        const frames = stream.frames.splice(0, 100);
        return {
            streamId: value.streamId,
            frames,
            closed: stream.closed !== undefined,
            ...(stream.closed === undefined ? {} : { reason: stream.closed }),
        };
    }
    async closeCodexStream(payload) {
        const value = record(payload);
        if (typeof value.streamId !== 'string')
            throw new ClientModeError('INVALID_MESSAGE', 'A Codex stream is required.');
        const stream = this.codexStreams.get(value.streamId);
        if (stream === undefined)
            return { closed: false, streamId: value.streamId };
        this.codexStreams.delete(value.streamId);
        stream.unsubscribe();
        stream.wake();
        await stream.close();
        return { closed: true, streamId: value.streamId };
    }
    async closeCodexStreams(client) {
        const targets = [...this.codexStreams.entries()].filter(([, stream]) => (client === undefined || (stream.target.kind === 'remote' && stream.target.client === client)));
        await Promise.all(targets.map(async ([streamId, stream]) => {
            this.codexStreams.delete(streamId);
            stream.unsubscribe();
            stream.closed = 'peer-disconnected';
            stream.wake();
            await stream.close();
        }));
    }
    selectRemoteTarget(remote, transport = this.selectHarnessRemoteTransport(remote)) {
        const target = { deviceId: remote.target.deviceId, name: remote.target.name };
        if (transport === 'remoteGateway') {
            this.gatewaySwitch.selectRemote(this.remoteTypertGateway(remote), undefined, target);
            return;
        }
        this.proxySwitch?.selectRemote(new RemoteHarnessApiProxy(remote.client, remote.harnessVersion).api, target);
        this.gatewaySwitch.selectRemote(request => invokeRemoteCommand(remote.client, request), {
            execute: true,
            list: remote.features.commandList,
        }, target);
    }
    remoteTypertGateway(remote) {
        const localSessionGeneration = harnessSessionGeneration(this.host?.localHarnessVersion?.());
        return new RemoteTypertGateway(remote.client, localSessionGeneration === 'v3' && remote.features.sessionFormat !== 3 ? 'legacy-to-v3' : undefined, remote.harnessVersion);
    }
    selectCodexTarget(virtual, remote) {
        const target = { deviceId: remote.target.deviceId, name: remote.target.name };
        // The Harness answers every `/api` endpoint once it is the Codex target, so give it
        // the local carriers: everything outside the CodeX domain (the shell's settings
        // bootstrap, plugin registry and account reads) has to describe this installation.
        virtual.setLocalCarrier(this.gatewaySwitch.localCarrier());
        if (this.gatewaySwitch.supportsCarrier()) {
            this.gatewaySwitch.selectRemote(virtual, undefined, target);
            return;
        }
        this.proxySwitch.selectRemote(virtual.api, target);
        this.gatewaySwitch.selectRemote(request => virtual.invoke(request), { execute: true, list: true }, target);
    }
    async closeCodexVirtual() {
        const virtual = this.codexVirtual;
        this.codexVirtual = undefined;
        await virtual?.close();
    }
    assertRemoteCompatible(remote) {
        this.selectHarnessRemoteTransport(remote);
    }
    selectHarnessRemoteTransport(remote) {
        const localRemoteGateway = this.gatewaySwitch.supportsCarrier();
        const localSessionGeneration = harnessSessionGeneration(this.host?.localHarnessVersion?.());
        if (localRemoteGateway && remote.features.remoteGateway) {
            if (localSessionGeneration === 'v3' || remote.features.sessionFormat !== 3)
                return 'remoteGateway';
            throw new ClientModeError('HARNESS_VERSION_INCOMPATIBLE', 'The selected Host uses Harness Session V3, but this Client uses the legacy Typert Remote session format.');
        }
        if (this.proxySwitch !== undefined && remote.features.apiProxy)
            return 'apiProxy';
        throw new ClientModeError('HARNESS_VERSION_INCOMPATIBLE', localRemoteGateway
            ? 'The selected Host does not provide a compatible Harness Typert Remote Gateway transport.'
            : 'The selected Host does not provide the legacy Harness ApiProxy transport.');
    }
    assertLocalHarnessCarrierAvailable() {
        if (this.gatewaySwitch.supportsCarrier() || this.proxySwitch !== undefined)
            return;
        throw new ClientModeError('HARNESS_VERSION_INCOMPATIBLE', 'This Client does not provide a compatible Harness carrier for the selected remote workspace.');
    }
    async connect(targetDeviceId, signal) {
        signal?.throwIfAborted();
        const progressRunId = this.connectionProgressRun + 1;
        this.connectionProgressRun = progressRunId;
        this.connectionProgress = { runId: progressRunId, targetDeviceId, phase: 'checking-host' };
        const identity = this.requireIdentity();
        let client;
        try {
            const serverDevice = (await this.server.listDevices()).find(device => device.deviceId === targetDeviceId);
            if (serverDevice === undefined) {
                throw new ClientModeError('MEMBERSHIP_REQUIRED', 'The selected Host is not authorized for this account.');
            }
            this.updateConnectionProgress(progressRunId, 'authorizing-peer');
            const target = await this.authorizeHostPeer(serverDevice);
            const presence = await this.server.presenceFor(targetDeviceId);
            if (!presence.online)
                throw new ClientModeError('HOST_OFFLINE', 'The selected Host is offline.', true);
            const credentials = await this.server.authenticate(identity);
            const rtcFactory = this.config.forceRelay
                ? undefined
                : await this.rtcFactoryProvider({ routeTargets: [this.server.baseUrl] }).catch(() => undefined);
            if (!this.config.forceRelay && rtcFactory === undefined) {
                this.logger.warn('remote Harness WebRTC backend unavailable; using relay', {
                    targetDeviceId: shortId(target.deviceId),
                });
            }
            let webRtcFallback = false;
            const createTransport = (attempt) => new AdaptiveTransport(websocketUrl(this.server.baseUrl), {
                role: 'client',
                deviceId: identity.deviceId,
                accessToken: credentials.accessToken,
                targetDeviceId,
                forceRelay: this.config.forceRelay || attempt === 'relay',
                preferredTransports: preferredTransportsForAttempt(attempt),
                negotiateTimeoutMs: attempt === 'direct' ? DIRECT_WEBRTC_NEGOTIATE_TIMEOUT_MS : undefined,
                ...(rtcFactory === undefined || attempt === 'relay' ? {} : { rtcFactory }),
                fetchIceServers: async (connectionId) => iceServersForAttempt(attempt, await this.server.turnCredentials(connectionId)),
                onWebRtcFallback: (error, diagnostics) => {
                    webRtcFallback = true;
                    this.logger.warn(attempt === 'direct'
                        ? 'remote Harness direct WebRTC failed; trying TURN'
                        : 'remote Harness TURN WebRTC failed; using relay', {
                        targetDeviceId: shortId(target.deviceId),
                        attempt,
                        reason: diagnosticReason(error),
                    });
                    if (diagnostics !== undefined) {
                        this.logger.debug('remote Harness WebRTC fallback diagnostics', {
                            targetDeviceId: shortId(target.deviceId),
                            attempt,
                            ...webrtcDiagnosticsLogFields(diagnostics),
                        });
                    }
                },
            });
            const attempts = this.config.forceRelay || rtcFactory === undefined
                ? ['relay']
                : ['direct', 'turn', 'relay'];
            let transport;
            for (const attempt of attempts) {
                webRtcFallback = false;
                const stopProgressTimer = this.beginAttemptProgress(progressRunId, attempt);
                try {
                    transport = createTransport(attempt);
                    client = new RemoteClientCore(new ClientSecureTransport(transport, identity, target), 60_000);
                    await client.connect();
                    signal?.throwIfAborted();
                }
                finally {
                    stopProgressTimer();
                }
                if (attempt === 'relay' || !webRtcFallback)
                    break;
                await client.close();
                client = undefined;
                transport = undefined;
                if (attempt === 'turn') {
                    this.logger.info('remote Harness relay fallback re-established', {
                        targetDeviceId: shortId(target.deviceId),
                    });
                }
            }
            if (client === undefined || transport === undefined) {
                throw new ClientModeError('CONNECTION_FAILED', 'Unable to establish a remote transport.', true);
            }
            const connectedClient = client;
            const connectedTransport = transport;
            const connectedPreference = transportPreferenceForMode(connectedClient.getStats().mode);
            this.updateConnectionProgress(progressRunId, 'connected', connectedPreference === undefined ? undefined : [connectedPreference]);
            connectedClient.onClose(() => {
                if (this.connected?.client !== connectedClient)
                    return;
                void this.closePreview();
                this.connected = undefined;
                this.connectionProgress = undefined;
                this.pendingWorkspaceSelection = undefined;
                void this.closeCodexVirtual();
                this.proxySwitch?.selectLocal();
                this.gatewaySwitch.selectLocal();
                // The UI keeps whatever the remote session rendered and offers no exit
                // route once the mode reads 'local' again, so remember that the session
                // dropped. The card uses this to keep a way back to the local shell.
                this.fellBackToLocal = true;
                void connectedClient.close().catch(() => undefined);
                this.logger.warn('remote Harness transport closed; reconnecting', {
                    targetDeviceId: shortId(target.deviceId),
                });
                void this.reconnectRemoteSession(target.deviceId);
            });
            const connectionDetails = await connectedTransport.connectionDetails().catch(() => undefined);
            this.logger.info('remote Harness transport ready', {
                targetDeviceId: shortId(target.deviceId),
                transport: connectedClient.getStats().mode,
                ...(connectionDetails === undefined ? {} : {
                    preferredTransports: connectionDetails.preferredTransports,
                    negotiatedCapabilities: connectionDetails.negotiatedCapabilities,
                    webRtcEnabled: connectionDetails.webRtcEnabled,
                }),
            });
            if (connectionDetails?.webRtc?.diagnostics !== undefined) {
                this.logger.debug('remote Harness transport diagnostics', {
                    targetDeviceId: shortId(target.deviceId),
                    ...webrtcDiagnosticsLogFields(connectionDetails.webRtc.diagnostics),
                });
            }
            const features = await probeRemoteHostFeatures(connectedClient, serverDevice.clientVersion);
            return {
                client: connectedClient,
                target,
                transport: connectedTransport,
                features,
                progressRunId,
                ...(serverDevice.clientVersion === undefined ? {} : { clientVersion: serverDevice.clientVersion }),
                ...(serverDevice.harnessVersion === undefined ? {} : { harnessVersion: serverDevice.harnessVersion }),
            };
        }
        catch (error) {
            this.clearConnectionProgress(progressRunId);
            await client?.close().catch(() => undefined);
            throw error;
        }
    }
    async ensureConnected(targetDeviceId, signal) {
        if (this.connected?.target.deviceId === targetDeviceId)
            return this.connected;
        const next = await this.connect(targetDeviceId, signal);
        const previous = this.connected;
        await this.closePreview();
        this.connected = next;
        this.clearConnectionProgress(next.progressRunId);
        await previous?.client.close().catch(() => undefined);
        return next;
    }
    beginAttemptProgress(runId, attempt) {
        if (attempt !== 'direct') {
            this.updateConnectionProgress(runId, 'probing', [attempt]);
            return () => undefined;
        }
        this.updateConnectionProgress(runId, 'probing', ['lan']);
        // LAN and public P2P candidates are gathered inside one ICE attempt. The
        // linear Remote UI still needs a single active route, so advance the
        // visible cue if direct negotiation takes longer than a local probe.
        const timer = setTimeout(() => {
            if (this.connectionProgress?.runId === runId && this.connectionProgress.phase === 'probing') {
                this.updateConnectionProgress(runId, 'probing', ['p2p']);
            }
        }, DIRECT_LAN_PROGRESS_DISPLAY_MS);
        return () => clearTimeout(timer);
    }
    updateConnectionProgress(runId, phase, activeTransports) {
        if (this.connectionProgress?.runId !== runId)
            return;
        this.connectionProgress = {
            runId,
            targetDeviceId: this.connectionProgress.targetDeviceId,
            phase,
            ...(activeTransports === undefined ? {} : { activeTransports }),
        };
    }
    clearConnectionProgress(runId) {
        if (this.connectionProgress?.runId === runId)
            this.connectionProgress = undefined;
    }
    async handleControl(endpoint, payload, signal) {
        try {
            if (endpoint === 'status')
                return ok(await this.detailedStatus());
            if (endpoint === 'devices')
                return ok(await this.devices());
            if (endpoint === 'client.account.login') {
                const value = record(payload);
                if (typeof value.email !== 'string' || typeof value.password !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'Email and password are required.');
                }
                return ok(await this.authorizeClientWithAccount(value.email, value.password));
            }
            if (endpoint === 'client.account.qr.start') {
                const value = record(payload);
                const provider = value.provider ?? 'zhihu';
                if (provider !== 'zhihu' && provider !== 'github') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A supported OAuth provider is required.');
                }
                return ok(await this.startClientOAuthQrLogin(provider));
            }
            if (endpoint === 'client.account.qr.poll') {
                const value = record(payload);
                if (typeof value.qrId !== 'string' || value.qrId.length < 20) {
                    throw new ClientModeError('INVALID_MESSAGE', 'A QR login session is required.');
                }
                return ok(await this.pollClientOAuthQrLogin(value.qrId));
            }
            if (endpoint === 'preview.open') {
                const remote = this.activeRemote();
                if (remote === undefined)
                    throw new ClientModeError('TRANSPORT_CLOSED', 'Connect to a Remote Host first.');
                this.preview ??= new LoopbackPreview(remote.client);
                return ok(await this.preview.open(record(payload).port));
            }
            if (endpoint === 'directory.list') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string')
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host is required.');
                return ok(await this.listRemoteDirectory(value.targetDeviceId, typeof value.path === 'string' ? value.path : undefined, signal));
            }
            if (endpoint === 'workspaces.list') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string')
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host is required.');
                return ok(await this.listRemoteWorkspaces(value.targetDeviceId, signal));
            }
            if (endpoint === 'codex.workspaces.list') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string')
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host is required.');
                return ok(await this.listCodexWorkspaces(value.targetDeviceId, signal));
            }
            if (endpoint === 'workspace.open') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string' || typeof value.path !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host and working directory are required.');
                }
                return ok(await this.openRemoteWorkspace(value.targetDeviceId, value.path, signal));
            }
            if (endpoint === 'codex.workspace.open') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string' || typeof value.workspaceId !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host and CodeX Workspace are required.');
                }
                return ok(await this.openCodexWorkspace(value.targetDeviceId, value.workspaceId, signal));
            }
            if (endpoint === 'codex.workspace.create') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string' || typeof value.path !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host and CodeX project directory are required.');
                }
                return ok(await this.createCodexWorkspace(value.targetDeviceId, value.path, signal));
            }
            if (endpoint === 'workspace.selection.consume') {
                const value = record(payload);
                if (typeof value.targetDeviceId !== 'string' || typeof value.workspaceId !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host and Workspace are required.');
                }
                return ok(this.consumeWorkspaceSelection({
                    targetDeviceId: value.targetDeviceId,
                    workspaceId: value.workspaceId,
                    ...(value.backend === 'codex' ? { backend: 'codex' } : {}),
                    ...(typeof value.sessionId === 'string' ? { sessionId: value.sessionId } : {}),
                }));
            }
            if (endpoint === 'fileviewer.stat' || endpoint === 'fileviewer.readRange' || endpoint === 'fileviewer.list') {
                const method = endpoint === 'fileviewer.stat'
                    ? 'stat'
                    : endpoint === 'fileviewer.readRange' ? 'readRange' : 'list';
                return ok(await this.callRemoteFileViewer(method, payload, signal));
            }
            if (endpoint === 'codex.call') {
                const value = record(payload);
                if (typeof value.method !== 'string' || !('params' in value)) {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Codex method and params are required.');
                }
                const remote = this.activeCodexRemote();
                if (remote !== undefined)
                    return ok(await new CodexRemoteClient(remote.client).request(value.method, value.params, signal));
                const host = this.requireLocalCodex();
                return ok(await host.codexCall(value, signal));
            }
            if (endpoint === 'codex.probe') {
                const local = this.localCodexAvailable();
                let remoteSupported = false;
                const remote = this.activeRemote();
                if (remote !== undefined) {
                    try {
                        remote.features = await probeRemoteHostFeatures(remote.client, remote.clientVersion);
                        remoteSupported = remote.features.codex;
                    }
                    catch (error) {
                        if (!local)
                            throw error;
                    }
                }
                return ok({ supported: local || remoteSupported, local, remote: remoteSupported });
            }
            if (endpoint === 'codex.respond') {
                const value = record(payload);
                const remote = this.activeCodexRemote();
                if (remote !== undefined)
                    return ok(await remote.client.rpc('codex.app.respond', value, signal));
                const host = this.requireLocalCodex();
                return ok(await host.codexRespond(value, signal));
            }
            if (endpoint === 'codex.stream.open')
                return ok(await this.openCodexStream(payload, signal));
            if (endpoint === 'codex.stream.next')
                return ok(await this.nextCodexFrames(payload, signal));
            if (endpoint === 'codex.stream.close')
                return ok(await this.closeCodexStream(payload));
            if (endpoint === 'host.account.login') {
                if (this.host === undefined)
                    throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
                const value = record(payload);
                if (typeof value.email !== 'string' || typeof value.password !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'Email and password are required.');
                }
                return ok(await this.host.authorizeHostWithAccount(value.email, value.password));
            }
            if (endpoint === 'host.authorization.set') {
                const value = record(payload);
                if (typeof value.enabled !== 'boolean') {
                    throw new ClientModeError('INVALID_MESSAGE', 'Host authorization state is required.');
                }
                return ok(await this.setHostAuthorization(value.enabled));
            }
            if (endpoint === 'host.registration-code.submit') {
                if (this.host === undefined)
                    throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
                const value = record(payload);
                if (typeof value.code !== 'string' || value.code.trim() === '') {
                    throw new ClientModeError('INVALID_MESSAGE', 'A Host registration code is required.');
                }
                return ok(await this.host.authorizeHostWithCode(value.code));
            }
            if (endpoint === 'mode.set') {
                const value = record(payload);
                if (value.mode !== 'local' && value.mode !== 'remote')
                    throw new ClientModeError('INVALID_MESSAGE', 'Mode must be local or remote.');
                return ok(await this.setMode(value.mode, typeof value.targetDeviceId === 'string' ? value.targetDeviceId : undefined, signal));
            }
            throw new ClientModeError('METHOD_NOT_FOUND', 'The remote-mode control method does not exist.');
        }
        catch (error) {
            return fail(error);
        }
    }
    requireIdentity() {
        if (this.identity === undefined)
            throw new ClientModeError('IDENTITY_INVALID', 'The client identity is not ready.');
        return this.identity;
    }
    async authorizeHostPeer(serverDevice) {
        const descriptor = await this.server.deviceFor(serverDevice.deviceId);
        assertAuthorizedHost(serverDevice, descriptor);
        const existing = this.identities.trustedPeer(descriptor.deviceId);
        if (existing !== undefined && existing.publicKey !== descriptor.identityKey) {
            throw new ClientModeError('PEER_IDENTITY_MISMATCH', 'The authorized Host identity key changed unexpectedly.');
        }
        if (existing !== undefined
            && existing.membershipId === descriptor.membershipId
            && existing.name === descriptor.name
            && existing.platform === descriptor.platform) {
            return existing;
        }
        return this.identities.trustPeer({
            deviceId: descriptor.deviceId,
            name: descriptor.name,
            platform: descriptor.platform,
            publicKey: descriptor.identityKey,
            membershipId: descriptor.membershipId,
        });
    }
}
export class ClientModeError extends Error {
    code;
    retryable;
    constructor(code, message, retryable = false) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
}
function assertAuthorizedHost(listed, descriptor) {
    if (descriptor.role !== 'host' || descriptor.deviceId !== listed.deviceId
        || descriptor.membershipId !== listed.membershipId) {
        throw new ClientModeError('PEER_IDENTITY_MISMATCH', 'Server Host details do not match the authorized device list.');
    }
}
function websocketUrl(baseUrl) {
    const url = new URL(baseUrl);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    url.pathname = `${url.pathname.replace(/\/$/, '')}/ws/v1/connect`;
    return url.toString();
}
function webrtcDiagnosticsLogFields(diagnostics) {
    if (diagnostics === undefined)
        return {};
    return {
        rtcConnectionState: diagnostics.connectionState,
        rtcIceConnectionState: diagnostics.iceConnectionState,
        rtcIceGatheringState: diagnostics.iceGatheringState,
        rtcLocalCandidates: diagnostics.localCandidates,
        rtcRemoteCandidates: diagnostics.remoteCandidates,
        rtcCandidatePairs: diagnostics.candidatePairs,
        rtcFilteredLocalCandidates: diagnostics.filteredLocalCandidates,
        rtcFilteredCandidatePairs: diagnostics.filteredCandidatePairs,
        ...(diagnostics.selectedPath === undefined ? {} : { rtcSelectedPath: diagnostics.selectedPath }),
    };
}
function record(value) {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        throw new ClientModeError('INVALID_MESSAGE', 'The control request payload is invalid.');
    }
    return value;
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function ok(value) { return { ok: true, value }; }
async function invokeRemoteCommand(client, request) {
    const rpcId = uuidV7();
    const response = await client.rpc('harness.api.call', {
        method: `${request.namespace}.${request.method}`,
        rpcId,
        payload: request.args,
    }, request.signal);
    if (response.rpcId !== rpcId) {
        throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned an invalid command response.');
    }
    return unwrapNativeResult(response);
}
async function readRemoteWorkspaceBaseline(gateway, signal) {
    const lifetime = new AbortController();
    const activeSignal = signal === undefined
        ? lifetime.signal
        : AbortSignal.any([signal, lifetime.signal]);
    const source = await gateway.open('workspace/follow', { args: {} }, activeSignal);
    const iterator = source[Symbol.asyncIterator]();
    try {
        const first = await iterator.next();
        if (first.done || !isRecord(first.value) || first.value.type !== 'baseline'
            || !isRecord(first.value.value) || !Array.isArray(first.value.value.items)) {
            throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned an invalid Workspace baseline.');
        }
        return first.value.value.items.map((item) => {
            if (!isRecord(item) || typeof item.workspaceId !== 'string'
                || typeof item.path !== 'string' || typeof item.title !== 'string') {
                throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned an invalid Workspace row.');
            }
            return { workspaceId: item.workspaceId, path: item.path, title: item.title };
        });
    }
    finally {
        lifetime.abort('workspace-baseline-read');
        await iterator.return?.();
    }
}
function unwrapNativeResult(response) {
    const result = response.result;
    if (typeof result !== 'object' || result === null || !('ok' in result)) {
        throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned an invalid response.');
    }
    if (result.ok !== true || !('value' in result)) {
        const message = 'error' in result && typeof result.error === 'object' && result.error !== null
            && 'message' in result.error && typeof result.error.message === 'string'
            ? result.error.message
            : 'The remote Host rejected the request.';
        throw new ClientModeError('REMOTE_API_ERROR', message);
    }
    return result.value;
}
function workspaceRecordId(value) {
    if (typeof value !== 'object' || value === null || !('workspaceId' in value)
        || typeof value.workspaceId !== 'string' || value.workspaceId.length === 0) {
        throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned an invalid Workspace.');
    }
    return value.workspaceId;
}
function remoteWorkspaceTitle(path) {
    const normalized = path.replace(/[\\/]+$/u, '');
    return normalized.split(/[\\/]+/u).filter(Boolean).at(-1) ?? path;
}
function fail(error) {
    const source = error instanceof Error ? error : undefined;
    const remoteCode = source !== undefined && 'code' in source && typeof source.code === 'string'
        ? source.code
        : source instanceof ClientModeError ? source.code : undefined;
    const retryable = source !== undefined && 'retryable' in source && typeof source.retryable === 'boolean'
        ? source.retryable
        : source instanceof ClientModeError ? source.retryable : false;
    return {
        ok: false,
        error: {
            code: 'internal',
            message: source?.message ?? 'The remote-mode operation failed.',
            details: remoteCode === undefined ? {} : { remoteCode, retryable },
        },
    };
}
function shortId(value) { return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`; }
/** Conservative feature profile for Hosts that predate fine-grained capability discovery. */
export function remoteHostFeatures(clientVersion) {
    return {
        commandList: isVersionAtLeast(clientVersion, REMOTE_COMMAND_LIST_MIN_VERSION),
        fileViewer: isVersionAtLeast(clientVersion, REMOTE_FILE_VIEWER_MIN_VERSION),
        terminal: false,
        apiProxy: true,
        remoteGateway: false,
        codex: false,
    };
}
export async function probeRemoteHostFeatures(client, clientVersion) {
    const fallback = remoteHostFeatures(clientVersion);
    let value;
    try {
        value = await client.rpc('harness.transport.describe', {});
    }
    catch (error) {
        if (error instanceof Error && 'code' in error && error.code === 'METHOD_NOT_FOUND')
            return fallback;
        throw error;
    }
    if (!isRecord(value) || !Array.isArray(value.capabilities)
        || value.capabilities.some(capability => typeof capability !== 'string')) {
        throw new ClientModeError('INVALID_MESSAGE', 'The remote Host returned invalid transport capabilities.');
    }
    const capabilities = new Set(value.capabilities);
    const apiProxy = capabilities.has('harness.api.v1');
    const remoteV1 = capabilities.has('harness.remote.v1');
    const remoteV3 = capabilities.has('harness.remote.v3');
    const terminal = capabilities.has('harness.terminal.v1');
    const codex = capabilities.has('codex.appserver.v1');
    if (remoteV1 && remoteV3) {
        throw new ClientModeError('INVALID_MESSAGE', 'The remote Host advertised conflicting Harness Session formats.');
    }
    const sessionFormat = remoteV3 ? 3 : undefined;
    const remoteGateway = remoteV3 || remoteV1;
    if (!apiProxy && !remoteGateway && !codex) {
        throw new ClientModeError('FEATURE_NOT_SUPPORTED', 'The remote Host exposes no supported Harness transport.');
    }
    return {
        commandList: remoteGateway || (apiProxy && fallback.commandList),
        fileViewer: capabilities.has('fileviewer.read.v1'),
        terminal,
        apiProxy,
        remoteGateway,
        ...(sessionFormat === undefined ? {} : { sessionFormat }),
        codex,
    };
}
async function waitForCodexFrames(stream, signal) {
    if (signal?.aborted)
        throw new ClientModeError('RPC_ABORTED', 'The Codex event poll was cancelled.');
    await new Promise((resolve, reject) => {
        const previousWake = stream.wake;
        const timer = setTimeout(done, 25_000);
        const onAbort = () => {
            cleanup();
            reject(new ClientModeError('RPC_ABORTED', 'The Codex event poll was cancelled.'));
        };
        function cleanup() {
            clearTimeout(timer);
            stream.wake = previousWake;
            signal?.removeEventListener('abort', onAbort);
        }
        function done() {
            cleanup();
            resolve();
        }
        stream.wake = () => {
            previousWake();
            done();
        };
        signal?.addEventListener('abort', onAbort, { once: true });
    });
}
function isVersionAtLeast(value, minimum) {
    const match = value?.match(/^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
    if (match === undefined || match === null)
        return false;
    const version = match.slice(1, 4).map(part => Number(part));
    for (let index = 0; index < minimum.length; index += 1) {
        const part = version[index] ?? 0;
        const expected = minimum[index] ?? 0;
        if (part > expected)
            return true;
        if (part < expected)
            return false;
    }
    return true;
}
function diagnosticReason(error) {
    const code = 'code' in error && typeof error.code === 'string' ? error.code : undefined;
    const message = error.message.replace(/[\r\n\t]+/g, ' ').slice(0, 240);
    return code === undefined ? message : `${code}: ${message}`;
}
function preferredTransportsForAttempt(attempt) {
    if (attempt === 'direct')
        return ['lan', 'p2p', 'relay'];
    if (attempt === 'turn')
        return ['turn', 'relay'];
    return ['relay'];
}
function transportPreferenceForMode(mode) {
    if (mode === 'LAN')
        return 'lan';
    if (mode === 'P2P')
        return 'p2p';
    if (mode === 'TURN')
        return 'turn';
    if (mode === 'Relay')
        return 'relay';
    return undefined;
}
function iceServersForAttempt(attempt, iceServers) {
    return attempt === 'direct' ? stunOnlyIceServers(iceServers) : iceServers;
}
//# sourceMappingURL=client-runtime.js.map