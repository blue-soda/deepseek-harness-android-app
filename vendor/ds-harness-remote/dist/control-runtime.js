import { hostname } from 'node:os';
import { existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolveConfig } from './config.js';
import { ClientModeError, } from './client-runtime.js';
import { IdentityStore, serverStorageDirectory } from './identity-store.js';
import { ClientServerApi, HostServerApi } from './server-api.js';
import { ServerCredentialStore } from './server-credentials.js';
import { registerControlRoute } from './control-route.js';
import { ControlStatusStream } from './control-stream.js';
import { codexBinaryCandidates } from './codex/domain.js';
/** Loopback-only control plane shared by Local/Remote switching and plugin setup. */
export class PluginControlRuntime {
    config;
    identityDirectory;
    settings;
    client;
    host;
    deepseekSession;
    constructor(config, identityDirectory, settings, client, host, deepseekSession = undefined) {
        this.config = config;
        this.identityDirectory = identityDirectory;
        this.settings = settings;
        this.client = client;
        this.host = host;
        this.deepseekSession = deepseekSession;
    }
    /**
     * The DeepSeek grant whose Server authorization this runtime already completed.
     *
     * The client polls this control endpoint while the browser page is open, and
     * every call used to re-run the authorization: that repeats a platform request
     * and re-registers the device, which rotates its tokens. The Host connection
     * using those tokens then fails with AUTH_INVALID, intermittently, right after a
     * sign-in. Verify once per grant instead.
     */
    verifiedDeepSeekToken;
    verifiedDeepSeekAuthorization;
    register(connection, webServer) {
        const statusStream = new ControlStatusStream(() => this.streamStatus());
        return registerControlRoute(connection, (endpoint, payload, signal) => this.handle(endpoint, payload, signal), webServer, statusStream);
    }
    /**
     * Read the value the status event stream pushes. It resolves through the same
     * endpoint handler as the unary `status` control call, so a pushed status and
     * a polled one can never diverge.
     */
    async streamStatus() {
        const result = await this.handle('status', {}, new AbortController().signal);
        if (!result.ok)
            throw new ClientModeError('STATUS_UNAVAILABLE', result.error.message);
        return result.value;
    }
    async handle(endpoint, payload, signal) {
        try {
            if (endpoint === 'settings.development.set')
                return ok(await this.setDevelopment(payload));
            if (endpoint === 'settings.get')
                return ok(await this.settingsView());
            if (endpoint === 'settings.configure')
                return ok(await this.configure(payload));
            if (endpoint === 'settings.server.set')
                return ok(await this.setServer(payload));
            if (endpoint === 'settings.role.set')
                return ok(await this.setRole(payload));
            if (endpoint === 'settings.codex.set')
                return ok(await this.setCodex(payload));
            if (endpoint === 'settings.acp.set')
                return ok(await this.setAcp(payload));
            if (endpoint === 'settings.acp.add')
                return ok(await this.addAcp(payload));
            if (endpoint === 'settings.acp.remove')
                return ok(await this.removeAcp(payload));
            if (endpoint === 'settings.logout')
                return ok(await this.logout());
            if (endpoint === 'authorization.local') {
                // Answer from what this installation already stores, without touching the
                // Server. The panel choice must not wait for a network round trip to
                // fail: that is what left the UI in an unanswerable state, and it lasted
                // as long as the Server's timeout.
                const client = this.client === undefined ? false : await this.client.hasStoredAuthorization();
                const host = this.host?.hasStoredAuthorization === undefined
                    ? false
                    : await this.host.hasStoredAuthorization();
                // Device discovery is a Client operation, so Client credentials decide.
                return ok({ stored: client, client, host });
            }
            if (endpoint === 'host.authorization.set' && this.client !== undefined) {
                const value = record(payload);
                if (typeof value.enabled !== 'boolean')
                    throw new ClientModeError('INVALID_MESSAGE', 'Host authorization state is required.');
                const status = await this.client.setHostAuthorization(value.enabled);
                if (this.settings !== undefined) {
                    const current = resolveConfig(this.settings.get());
                    await this.settings.replace(editableConfig({
                        ...current,
                        // Releasing the authorization must not silently drop a pause.
                        hostControl: { enabled: value.enabled, paused: current.hostControl?.paused ?? false },
                    }));
                }
                return ok(status);
            }
            if (endpoint === 'host.connection.set') {
                const value = record(payload);
                if (typeof value.connected !== 'boolean')
                    throw new ClientModeError('INVALID_MESSAGE', 'Host connection state is required.');
                return ok(await this.setHostConnection(value.connected));
            }
            if (endpoint === 'host.reconnect') {
                if (this.host === undefined)
                    throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
                this.host.reconnectHost();
                return ok(this.hostOnlyStatus());
            }
            if (this.client !== undefined)
                return this.client.handleControl(endpoint, payload, signal);
            if (endpoint === 'status')
                return ok(this.hostOnlyStatus());
            if (endpoint === 'devices')
                return ok([]);
            if (endpoint === 'host.account.login') {
                if (this.host === undefined)
                    throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
                const value = record(payload);
                if (typeof value.email !== 'string' || typeof value.password !== 'string') {
                    throw new ClientModeError('INVALID_MESSAGE', 'Email and password are required.');
                }
                return ok(await this.host.authorizeHostWithAccount(value.email, value.password));
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
            if (endpoint === 'mode.set' && record(payload).mode === 'local')
                return ok(this.hostOnlyStatus());
            throw new ClientModeError('METHOD_NOT_ALLOWED', 'Remote Client mode is disabled by the plugin role.');
        }
        catch (error) {
            return fail(error);
        }
    }
    async configure(payload) {
        if (this.settings === undefined) {
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        }
        const value = record(payload);
        if (value.role !== 'host' && value.role !== 'client') {
            throw new ClientModeError('INVALID_MESSAGE', 'Role must be Host or Client.');
        }
        if (typeof value.serverUrl !== 'string') {
            throw new ClientModeError('INVALID_MESSAGE', 'Server URL is required.');
        }
        const current = editableConfig(resolveConfig(this.settings.get()));
        const next = resolveConfig({ ...current, role: value.role, serverUrl: value.serverUrl });
        const identities = new IdentityStore({
            directory: serverStorageDirectory(this.identityDirectory, next.serverUrl, value.role),
        });
        const identity = await identities.loadOrCreate(hostname());
        const api = value.role === 'host'
            ? new HostServerApi(next.serverUrl, new ServerCredentialStore(identities.directory))
            : new ClientServerApi(next.serverUrl, new ServerCredentialStore(identities.directory));
        let authorization;
        if (value.role === 'host' && typeof value.registrationCode === 'string' && value.registrationCode.trim() !== '') {
            authorization = await api.authorizeHostWithCode(identity, value.registrationCode);
        }
        else if (value.provider === 'deepseek') {
            // Sign in with the DeepSeek account DSH is already using. The Server
            // confirms the grant with the platform, so it never trusts this claim.
            if (this.deepseekSession === undefined) {
                throw new ClientModeError('METHOD_NOT_ALLOWED', 'DeepSeek account sign-in is unavailable in this profile.');
            }
            const session = await this.deepseekSession.read();
            if (session === undefined) {
                // Signed out: start the official browser authorization rather than
                // telling the user to go find DSH's own account settings. Nothing is
                // authorized yet, so the settings are left untouched.
                const started = await this.deepseekSession.startSignIn({
                    callbackOrigin: typeof value.callbackOrigin === 'string' ? value.callbackOrigin : '',
                    loginSource: value.loginSource === 'desktop' ? 'desktop' : 'web',
                    locale: typeof value.locale === 'string' ? value.locale : 'en',
                    timezoneOffsetSeconds: typeof value.timezoneOffsetSeconds === 'number' ? value.timezoneOffsetSeconds : 0,
                });
                return {
                    status: 'deepseek-sign-in-required',
                    ...(started.authorizeUrl === undefined ? {} : { authorizeUrl: started.authorizeUrl }),
                };
            }
            // Verify a completed grant once. Re-verifying repeats a platform request and
            // re-registers the device, rotating the tokens the Host connection is already
            // using; polling this endpoint must not invalidate them.
            if (this.verifiedDeepSeekToken === session.token && this.verifiedDeepSeekAuthorization !== undefined) {
                authorization = this.verifiedDeepSeekAuthorization;
            }
            else {
                authorization = await api.authorizeWithDeepSeek(identity, session.token);
                this.verifiedDeepSeekToken = session.token;
                this.verifiedDeepSeekAuthorization = authorization;
            }
        }
        else {
            if (typeof value.email !== 'string' || typeof value.password !== 'string') {
                throw new ClientModeError('INVALID_MESSAGE', 'Email and password are required for account authorization.');
            }
            authorization = await api.authorizeWithAccount(identity, value.email, value.password);
        }
        if (value.role === 'client' && resolveConfig(this.settings.get()).hostControl?.enabled !== false) {
            await this.client?.authorizeHostByDefault();
        }
        await this.settings.replace(editableConfig(next));
        return {
            status: 'authorized',
            role: value.role,
            ...(authorization.account === undefined ? {} : { account: authorization.account }),
            settings: await this.settingsView(),
        };
    }
    async setServer(payload) {
        if (this.settings === undefined) {
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        }
        const value = record(payload);
        if (typeof value.serverUrl !== 'string') {
            throw new ClientModeError('INVALID_MESSAGE', 'Server URL is required.');
        }
        const current = editableConfig(resolveConfig(this.settings.get()));
        const next = resolveConfig({ ...current, serverUrl: value.serverUrl });
        await this.settings.replace(editableConfig(next));
        return this.settingsView();
    }
    async setRole(payload) {
        if (this.settings === undefined) {
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        }
        const role = record(payload).role;
        if (role !== 'host' && role !== 'client') {
            throw new ClientModeError('INVALID_MESSAGE', 'Role must be Host or Client.');
        }
        const current = editableConfig(resolveConfig(this.settings.get()));
        const currentRole = current.role === 'client' ? 'client' : 'host';
        if (role !== currentRole && current.serverUrl !== undefined
            && await this.association(current.serverUrl, role) === undefined) {
            await this.authorizeOwnedRole(current.serverUrl, currentRole, role);
        }
        await this.settings.replace({ ...current, role });
        return this.settingsView();
    }
    async setDevelopment(payload) {
        if (this.settings === undefined)
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        const value = record(payload);
        if (value.terminalEnabled !== undefined && typeof value.terminalEnabled !== 'boolean')
            throw new ClientModeError('INVALID_MESSAGE', 'The terminal switch must be a boolean.');
        if (value.ports !== undefined && (!Array.isArray(value.ports) || value.ports.some((port) => !Number.isInteger(port))))
            throw new ClientModeError('INVALID_MESSAGE', 'Loopback ports must be integers.');
        if (value.terminalEnabled === undefined && value.ports === undefined)
            throw new ClientModeError('INVALID_MESSAGE', 'A terminal switch or loopback ports are required.');
        const current = editableConfig(resolveConfig(this.settings.get()));
        const next = resolveConfig({ ...current,
            terminal: { enabled: value.terminalEnabled === undefined ? (current.terminal?.enabled ?? true) : value.terminalEnabled },
            loopback: { ports: value.ports === undefined ? (current.loopback?.ports ?? []) : value.ports },
        });
        await this.settings.replace(editableConfig(next));
        if (value.terminalEnabled !== undefined)
            this.host?.setTerminalEnabled?.(value.terminalEnabled);
        if (value.ports !== undefined)
            this.host?.setLoopbackPorts?.(value.ports);
        return this.settingsView();
    }
    async setCodex(payload) {
        if (this.settings === undefined) {
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        }
        const enabled = record(payload).enabled;
        if (typeof enabled !== 'boolean') {
            throw new ClientModeError('INVALID_MESSAGE', 'Codex Remote enabled must be a boolean.');
        }
        const binary = record(payload).binary;
        if (binary !== undefined && typeof binary !== 'string') {
            throw new ClientModeError('INVALID_MESSAGE', 'Codex binary must be a string.');
        }
        const current = editableConfig(resolveConfig(this.settings.get()));
        const next = resolveConfig({
            ...current,
            codex: {
                ...current.codex,
                enabled,
                // An emptied field means "find it yourself" again, which is also what
                // survives a desktop-app update that moves the binary.
                ...(binary === undefined ? {} : { binary: binary.trim() === '' ? 'codex' : binary.trim() }),
            },
        });
        await this.settings.replace(editableConfig(next));
        return this.settingsView();
    }
    async setAcp(payload) {
        if (this.settings === undefined)
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        const value = record(payload);
        const backend = value.backend;
        const enabled = value.enabled;
        if (typeof backend !== 'string' || typeof enabled !== 'boolean')
            throw new ClientModeError('INVALID_MESSAGE', 'ACP backend and enabled are required.');
        const current = resolveConfig(this.settings.get());
        const backends = current.acp?.backends.map(item => item.id === backend ? { ...item, enabled } : item) ?? [];
        await this.settings.replace({ ...editableConfig(current), acp: { enabled: current.acp?.enabled ?? true, backends } });
        return this.settingsView();
    }
    async addAcp(payload) {
        if (this.settings === undefined)
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        const value = record(payload);
        if (typeof value.id !== 'string' || typeof value.command !== 'string' || !Array.isArray(value.args) || !value.args.every(item => typeof item === 'string')) {
            throw new ClientModeError('INVALID_MESSAGE', 'ACP name, command, and arguments are required.');
        }
        const current = resolveConfig(this.settings.get());
        if (current.acp?.backends.some(item => item.id === value.id))
            throw new ClientModeError('INVALID_MESSAGE', 'ACP backend already exists.');
        const backends = [...(current.acp?.backends ?? []), { id: value.id, command: value.command, args: value.args, enabled: false }];
        const next = resolveConfig({ ...editableConfig(current), acp: { enabled: current.acp?.enabled ?? true, backends } });
        await this.settings.replace(editableConfig(next));
        return this.settingsView();
    }
    async removeAcp(payload) {
        if (this.settings === undefined)
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        const id = record(payload).id;
        if (typeof id !== 'string' || ['codex', 'cursor', 'kimi'].includes(id))
            throw new ClientModeError('INVALID_MESSAGE', 'Only custom ACP backends can be removed.');
        const current = resolveConfig(this.settings.get());
        const backends = (current.acp?.backends ?? []).filter(item => item.id !== id);
        await this.settings.replace(editableConfig({ ...current, acp: { enabled: current.acp?.enabled ?? true, backends } }));
        return this.settingsView();
    }
    async authorizeOwnedRole(serverUrl, sourceRole, targetRole) {
        const sourceDirectory = serverStorageDirectory(this.identityDirectory, serverUrl, sourceRole);
        const sourceIdentity = await new IdentityStore({ directory: sourceDirectory }).loadOrCreate(hostname());
        const sourceStore = new ServerCredentialStore(sourceDirectory);
        if (await sourceStore.load(serverUrl, sourceIdentity.deviceId) === undefined)
            return;
        const sourceApi = sourceRole === 'host'
            ? new HostServerApi(serverUrl, sourceStore)
            : new ClientServerApi(serverUrl, sourceStore);
        const sourceCredentials = await sourceApi.authenticate(sourceIdentity);
        const targetDirectory = serverStorageDirectory(this.identityDirectory, serverUrl, targetRole);
        const targetIdentity = await new IdentityStore({ directory: targetDirectory }).loadOrCreate(hostname());
        const targetApi = targetRole === 'host'
            ? new HostServerApi(serverUrl, new ServerCredentialStore(targetDirectory))
            : new ClientServerApi(serverUrl, new ServerCredentialStore(targetDirectory));
        await targetApi.authorizeOwnedRole(targetIdentity, sourceCredentials.accessToken, sourceCredentials.account);
    }
    async logout() {
        if (this.settings === undefined) {
            throw new ClientModeError('SETTINGS_UNAVAILABLE', 'DSH user settings are unavailable in this profile.');
        }
        const config = resolveConfig(this.settings.get());
        // Signing out discards the credentials, so the next sign-in has to verify its
        // grant with the Server again rather than reuse a cached authorization.
        this.verifiedDeepSeekToken = undefined;
        this.verifiedDeepSeekAuthorization = undefined;
        if (config.serverUrl !== undefined) {
            await Promise.all([
                this.client?.clearClientAuthorization(),
                this.host?.clearHostAuthorization(),
            ]);
            await Promise.all(['host', 'client'].map(async (role) => {
                const directory = serverStorageDirectory(this.identityDirectory, config.serverUrl, role);
                await new ServerCredentialStore(directory).clear();
            }));
        }
        // Signing in borrows DSH's own DeepSeek authorization, so signing out must
        // release it as well: otherwise the next sign-in silently reuses the same
        // account and this sign-out has no observable effect. A failure is reported
        // through the flag rather than hidden, because the local credentials are
        // already gone at this point and the user should know what remains.
        let deepseekSignedOut = false;
        if (this.deepseekSession !== undefined) {
            try {
                deepseekSignedOut = await this.deepseekSession.signOut();
            }
            catch {
                deepseekSignedOut = false;
            }
        }
        return { ...(await this.settingsView()), deepseekSignedOut };
    }
    /**
     * Stop or resume this machine's remote availability without releasing its
     * authorization, and remember the choice so a restart respects it.
     *
     * This is the non-destructive counterpart of releasing the authorization: it
     * closes the connection, keeps the credentials and the device identity, and
     * therefore costs neither a re-authorization nor a device identity.
     * @param connected - whether this machine should accept remote connections.
     * @returns the refreshed status for the caller's view.
     */
    async setHostConnection(connected) {
        if (this.host === undefined) {
            throw new ClientModeError('METHOD_NOT_ALLOWED', 'This plugin is not running as a Host.');
        }
        if (connected)
            await this.host.resumeHostConnection?.();
        else
            await this.host.pauseHostConnection?.();
        if (this.settings !== undefined) {
            const current = resolveConfig(this.settings.get());
            await this.settings.replace(editableConfig({
                ...current,
                hostControl: { enabled: current.hostControl?.enabled ?? true, paused: !connected },
            }));
        }
        return this.client === undefined ? this.hostOnlyStatus() : await this.client.status();
    }
    async settingsView() {
        const config = this.settings === undefined ? editableConfig(this.config) : editableConfig(resolveConfig(this.settings.get()));
        const associations = await this.associations(config);
        const role = config.role === 'client' ? 'client' : 'host';
        const association = associations[role];
        const discovered = discoveredCodexBinary(config.codex?.binary ?? 'codex');
        return {
            config,
            deviceName: hostname(),
            writable: this.settings !== undefined,
            applies: 'restart',
            associations,
            acpAvailability: Object.fromEntries((config.acp?.backends ?? []).map(item => [item.id, commandAvailable(item.command ?? '')])),
            ...(association === undefined ? {} : { association }),
            ...(discovered === undefined ? {} : { discoveredCodexBinary: discovered }),
        };
    }
    async associations(config) {
        if (config.serverUrl === undefined)
            return {};
        const [host, client] = await Promise.all([
            this.association(config.serverUrl, 'host'),
            this.association(config.serverUrl, 'client'),
        ]);
        return {
            ...(host === undefined ? {} : { host }),
            ...(client === undefined ? {} : { client }),
        };
    }
    async association(serverUrl, role) {
        const identities = new IdentityStore({
            directory: serverStorageDirectory(this.identityDirectory, serverUrl, role),
        });
        const identity = await identities.loadOrCreate(hostname());
        const credentials = await new ServerCredentialStore(identities.directory).load(serverUrl, identity.deviceId);
        if (credentials === undefined)
            return undefined;
        return {
            method: credentials.authorizationMethod,
            ...(credentials.account === undefined ? {} : { account: credentials.account }),
        };
    }
    hostOnlyStatus() {
        return {
            mode: 'local',
            available: false,
            deviceName: hostname(),
            hostAuthorizationAvailable: this.host !== undefined,
            ...(this.host === undefined ? {} : { host: this.host.hostStatus() }),
        };
    }
}
function commandAvailable(command) {
    try {
        execFileSync(process.platform === 'win32' ? 'where' : 'which', [command], { stdio: 'ignore' });
        return true;
    }
    catch {
        return false;
    }
}
/**
 * The Codex binary discovery found for a configured command.
 *
 * Only the bundled candidates count here: the plain command is left to the
 * process `PATH` at spawn time, so reporting it would claim a discovery that was
 * never made. The settings UI shows this path so the user can see what will run
 * before enabling anything.
 * @param configured - the configured binary or command.
 * @returns the discovered absolute path, or undefined when nothing was found.
 */
function discoveredCodexBinary(configured) {
    for (const candidate of codexBinaryCandidates(configured)) {
        if (candidate !== configured && existsSync(candidate))
            return candidate;
    }
    return undefined;
}
function editableConfig(config) {
    return {
        enabled: config.enabled,
        role: config.role,
        ...(config.serverUrl === undefined ? {} : { serverUrl: config.serverUrl }),
        terminal: config.terminal,
        hostControl: config.hostControl ?? { enabled: true, paused: false },
        loopback: config.loopback,
        forceRelay: config.forceRelay,
        logLevel: config.logLevel,
        reconnect: config.reconnect.enabled
            ? {
                initialDelayMs: config.reconnect.initialDelayMs,
                maxDelayMs: config.reconnect.maxDelayMs,
                jitter: config.reconnect.jitter,
            }
            : false,
        codex: {
            enabled: config.codex.enabled,
            binary: config.codex.binary,
        },
        ...(config.acp === undefined ? {} : { acp: { enabled: config.acp.enabled, backends: config.acp.backends } }),
    };
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function record(value) {
    if (!isRecord(value))
        throw new ClientModeError('INVALID_MESSAGE', 'The control request payload is invalid.');
    return value;
}
function ok(value) { return { ok: true, value }; }
function fail(error) {
    const source = error instanceof Error ? error : undefined;
    const remoteCode = source !== undefined && 'code' in source && typeof source.code === 'string'
        ? source.code
        : source instanceof ClientModeError ? source.code : undefined;
    return {
        ok: false,
        error: {
            code: 'internal',
            message: source?.message ?? 'The plugin control operation failed.',
            details: remoteCode === undefined ? {} : { remoteCode },
        },
    };
}
//# sourceMappingURL=control-runtime.js.map