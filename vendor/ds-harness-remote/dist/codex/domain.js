import { randomUUID } from 'node:crypto';
import { accessSync, constants, existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { readdir, realpath, stat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, isAbsolute, join, posix, relative, resolve } from 'node:path';
import { deriveCodexCwdWorkspaces } from '@dsh-remote/client-core';
import { RpcError } from '../safe-error.js';
import { CodexAppServerClient, CodexAppServerError, } from './app-server.js';
import { isThreadMutation, parseCodexCall, threadIdFromParams, } from './method-policy.js';
import { CodexPeerBridge } from './peer-bridge.js';
import { codexPermissionPresetFromResponse } from './permissions.js';
import { paginateCodexNativeHistory, projectCodexNativeHistory } from './virtual-harness.js';
const APPROVAL_TTL_MS = 5 * 60_000;
const DEFAULT_RESTART_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000];
/**
 * How long the whole App Server launch may take.
 *
 * `launchAppServer` tries several binary candidates and each attempt can spend a full request
 * timeout waiting for a binary that will never answer, so a missing or broken install used to
 * cost that timeout several times over. One budget for all candidates keeps the domain's own
 * status honest ('unavailable' in seconds, not tens of seconds) while the existing restart
 * backoff keeps trying in the background.
 */
const CODEX_START_BUDGET_MS = 5_000;
const CODEX_PAGE_LIMIT = 100;
const MAX_CODEX_PAGES = 32;
const CODEX_HISTORY_PAGE_LIMIT = 25;
const MAX_CODEX_HISTORY_PAGES = 64;
const CODEX_DIRECTORY_ENTRY_LIMIT = 500;
/**
 * Optional Codex business domain inside the existing Remote Plugin. It shares
 * Remote identity/transport with Harness, but owns its App Server process,
 * method policy, subscriptions, leases, and approval handles.
 */
export class CodexRemoteDomain {
    config;
    logger;
    createAppServer;
    restartDelaysMs;
    appServer;
    unsubscribeInbound;
    unsubscribeUnavailable;
    peers = new Map();
    peerDeviceIds = new Map();
    turnOwners = new Map();
    approvals = new Map();
    permissionPresets = new Map();
    approvalExpiryTimer;
    restartTimer;
    restartAttempt = 0;
    available = false;
    closed = false;
    state = 'disabled';
    unavailableCode;
    constructor(config, logger, createAppServer = (binary, targetLogger) => new CodexAppServerClient(binary, targetLogger), restartDelaysMs = DEFAULT_RESTART_DELAYS_MS) {
        this.config = config;
        this.logger = logger;
        this.createAppServer = createAppServer;
        this.restartDelaysMs = restartDelaysMs;
    }
    async start() {
        if (this.closed)
            throw new RpcError('CODEX_CLOSED', 'The Codex Remote domain is closed.');
        if (!this.config.enabled)
            return;
        try {
            this.state = 'starting';
            await this.launchWithinBudget();
        }
        catch (error) {
            this.available = false;
            this.state = 'unavailable';
            this.unavailableCode = errorCode(error);
            await this.disposeAppServer(this.appServer);
            this.logger.warn('Codex Remote domain unavailable', { code: this.unavailableCode });
        }
    }
    isAvailable() { return this.available && this.appServer?.isReady() === true; }
    status() {
        return {
            enabled: this.config.enabled,
            available: this.isAvailable(),
            state: this.state,
            restartAttempt: this.restartAttempt,
            ...(this.unavailableCode === undefined ? {} : { error: this.unavailableCode }),
        };
    }
    /** Resolve a thread cwd for Host-owned workspace/terminal carriers. */
    async resolveThreadWorkspace(connectionId, threadId) {
        if (!this.peers.has(connectionId))
            throw new RpcError('CODEX_THREAD_UNAVAILABLE', 'The CodeX thread is not available on this connection.');
        try {
            const known = await this.readKnownThread(threadId);
            return known.cwd;
        }
        catch (error) {
            if (error instanceof RpcError && error.code === 'CODEX_THREAD_NOT_ALLOWED') {
                throw new RpcError('CODEX_THREAD_UNAVAILABLE', 'The CodeX thread is not available.');
            }
            throw new RpcError('CODEX_THREAD_UNAVAILABLE', 'The CodeX thread is not available.');
        }
    }
    createPeer(context, publish) {
        if (!this.config.enabled)
            return undefined;
        const bridge = new CodexPeerBridge(this, context, publish, this.logger);
        this.peers.set(context.connectionId, bridge);
        this.peerDeviceIds.set(context.connectionId, context.peerDeviceId);
        return bridge;
    }
    async call(connectionId, input) {
        const envelope = parseCallEnvelope(input);
        const call = parseCodexCall(envelope.method, envelope.params);
        this.requireAppServer();
        if (call.method === 'account/read') {
            return sanitizeAccount(await this.callUpstream(call.method, call.params));
        }
        if (call.method === 'project/list') {
            return sanitizeProjectList(await this.callUpstream(call.method, call.params));
        }
        if (call.method === 'project/create') {
            const roots = call.params.roots;
            const path = await this.requireNewCodexProjectPath(roots[0].path);
            return sanitizeProjectCreate(await this.callUpstream(call.method, {
                name: call.params.name,
                roots: [{ path }],
                idempotencyKey: call.params.idempotencyKey,
            }));
        }
        if (call.method === 'thread/list') {
            const result = sanitizeThreadList(await this.callUpstream(call.method, call.params));
            return filterThreadListByWorkspaceAuthority(result, await this.readWorkspaceAuthority(result.data));
        }
        if (call.method === 'thread/start') {
            const cwd = await this.requireCodexWorkspacePath(call.params.cwd);
            const permission = codexPermission(call.params, 'workspace-write');
            const result = await this.callUpstream(call.method, {
                ...permission.params,
                cwd,
                ...codexThreadPermissionParams(permission),
                serviceName: 'deepseek_harness_remote',
            });
            if (extractThread(result)?.id === undefined) {
                throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid thread.');
            }
            this.rememberPermission(extractThread(result).id, result);
            return result;
        }
        const threadId = threadIdFromParams(call.params);
        if (call.method === 'dsh/sessionHistory') {
            const thread = await this.readThreadForHistory(connectionId, threadId);
            const page = paginateCodexNativeHistory(projectCodexNativeHistory(thread, `codex:${threadId}`), {
                beforeSeq: optionalInteger(call.params.beforeSeq),
                throughSeq: optionalInteger(call.params.throughSeq),
                maxMessages: optionalInteger(call.params.maxMessages),
            });
            const activeTurnId = typeof page.activeTurnId === 'string'
                ? page.activeTurnId
                : this.turnOwners.get(threadId)?.turnId;
            return {
                ...page,
                ...(activeTurnId === undefined ? {} : { activeTurnId }),
                permissionPreset: this.permissionPresets.get(threadId) ?? null,
            };
        }
        if (call.method === 'dsh/directoryList') {
            return this.listCodexDirectory(call.params.path);
        }
        const allowedThread = threadId === undefined ? undefined : await this.readKnownThread(threadId);
        if (call.method === 'thread/resume' && call.params.permissionPreset !== undefined) {
            const owner = this.turnOwners.get(threadId);
            if (owner !== undefined && owner.connectionId !== connectionId) {
                throw new RpcError('CODEX_THREAD_BUSY', 'Another Remote client is writing to this CodeX thread.');
            }
            return this.changeThreadPermission(threadId, call.params, allowedThread);
        }
        if (call.method === 'thread/read') {
            // assertThreadAllowed already produced a bounded summary, but make the
            // requested read again so includeTurns has its official semantics.
            return this.callUpstream(call.method, call.params);
        }
        if (call.method === 'thread/unsubscribe') {
            const bridge = this.peers.get(connectionId);
            bridge?.removeThreadSubscriptions(threadId);
            if (this.hasSubscriber(threadId))
                return { status: 'unsubscribed' };
            return this.callUpstream(call.method, call.params);
        }
        let claimed = false;
        let previousOwner;
        if (isThreadMutation(call.method) && threadId !== undefined) {
            const claim = this.claimTurn(threadId, connectionId, call.method, typeof call.params.turnId === 'string' ? call.params.turnId : undefined);
            claimed = claim.claimed;
            previousOwner = claim.previous;
        }
        try {
            const permission = codexPermission(call.params);
            const upstreamParams = call.method === 'thread/resume' && allowedThread !== undefined
                ? {
                    ...permission.params,
                    ...(allowedThread.cwd === undefined ? {} : { cwd: allowedThread.cwd }),
                    ...codexThreadPermissionParams(permission),
                    excludeTurns: true,
                }
                : call.method === 'thread/fork' && allowedThread !== undefined
                    ? {
                        ...permission.params,
                        ...(allowedThread.cwd === undefined ? {} : { cwd: allowedThread.cwd }),
                        ...codexThreadPermissionParams(permission),
                    }
                    : call.method === 'turn/start'
                        ? {
                            ...permission.params,
                            ...(allowedThread?.cwd === undefined ? {} : { cwd: allowedThread.cwd }),
                            ...codexTurnPermissionParams(permission, allowedThread?.cwd),
                        }
                        : permission.params;
            let result;
            try {
                result = await this.callUpstream(call.method, upstreamParams);
            }
            catch (error) {
                // App Server rejects thread/resume when this process already has the
                // thread loaded. Remote callers should be able to resume defensively
                // before every turn, including immediately after thread/start.
                if (call.method === 'thread/resume'
                    && allowedThread !== undefined
                    && error instanceof RpcError
                    && error.code === 'CODEX_UPSTREAM_ERROR'
                    && call.params.permissionPreset === undefined) {
                    return { thread: allowedThread.thread };
                }
                throw error;
            }
            if (call.method === 'thread/resume' || call.method === 'thread/fork' || call.method === 'thread/unarchive') {
                await this.assertResultThreadAllowed(result);
            }
            if (call.method === 'thread/resume' || call.method === 'thread/fork') {
                const resultId = extractThread(result)?.id;
                if (typeof resultId === 'string') {
                    this.rememberPermission(resultId, result);
                    if (call.params.permissionPreset !== undefined)
                        await this.publishPermission(resultId, result);
                }
            }
            if (call.method === 'turn/start' && threadId !== undefined) {
                this.rememberTurnId(threadId, connectionId, extractTurnId(result));
                if (call.params.permissionPreset !== undefined) {
                    await this.publishPermission(threadId, codexTurnPermissionParams(permission, allowedThread?.cwd));
                }
            }
            return result;
        }
        catch (error) {
            if (claimed && threadId !== undefined) {
                if (previousOwner === undefined)
                    this.turnOwners.delete(threadId);
                else
                    this.turnOwners.set(threadId, previousOwner);
            }
            throw mapAppServerError(error);
        }
    }
    async respond(connectionId, input) {
        await this.expireApprovals();
        const params = parseRespond(input);
        const approval = this.approvals.get(params.requestHandle);
        if (approval === undefined || approval.connectionId !== connectionId) {
            throw new RpcError('CODEX_APPROVAL_NOT_FOUND', 'The Codex approval is missing, expired, or belongs to another connection.');
        }
        this.approvals.delete(params.requestHandle);
        this.scheduleApprovalExpiry();
        await this.requireAppServer().respond(approval.upstreamId, { decision: params.decision });
        return { resolved: true };
    }
    async detachPeer(connectionId) {
        const bridge = this.peers.get(connectionId);
        if (bridge !== undefined)
            this.peers.delete(connectionId);
        this.peerDeviceIds.delete(connectionId);
        for (const [threadId, owner] of this.turnOwners) {
            if (owner.connectionId === connectionId)
                this.turnOwners.delete(threadId);
        }
        const appServer = this.appServer;
        const pending = [...this.approvals.entries()].filter(([, approval]) => approval.connectionId === connectionId);
        for (const [handle, approval] of pending) {
            this.approvals.delete(handle);
            await appServer?.respond(approval.upstreamId, { decision: 'decline' }).catch(() => undefined);
        }
        this.scheduleApprovalExpiry();
    }
    async close() {
        if (this.closed)
            return;
        this.closed = true;
        this.available = false;
        this.state = this.config.enabled ? 'unavailable' : 'disabled';
        if (this.restartTimer !== undefined)
            clearTimeout(this.restartTimer);
        this.restartTimer = undefined;
        for (const bridge of [...this.peers.values()])
            await bridge.closeAll();
        this.peers.clear();
        this.peerDeviceIds.clear();
        this.turnOwners.clear();
        this.permissionPresets.clear();
        if (this.approvalExpiryTimer !== undefined)
            clearTimeout(this.approvalExpiryTimer);
        this.approvalExpiryTimer = undefined;
        this.approvals.clear();
        this.unsubscribeInbound?.();
        this.unsubscribeInbound = undefined;
        this.unsubscribeUnavailable?.();
        this.unsubscribeUnavailable = undefined;
        await this.appServer?.close();
        this.appServer = undefined;
    }
    /**
     * Launch the App Server within {@link CODEX_START_BUDGET_MS}.
     *
     * The losing side of the race keeps running until the disposal in start()'s catch stops it, so
     * it needs its own handler: an unhandled rejection here would surface as a process-level error
     * for what is only an optional feature being unavailable.
     * @returns nothing once a candidate is ready.
     */
    async launchWithinBudget() {
        let timer;
        const attempt = this.launchAppServer();
        attempt.catch(() => undefined);
        try {
            await Promise.race([
                attempt,
                new Promise((_resolve, reject) => {
                    timer = setTimeout(() => {
                        reject(new RpcError('CODEX_START_TIMEOUT', `Codex App Server did not start within ${CODEX_START_BUDGET_MS}ms.`));
                    }, CODEX_START_BUDGET_MS);
                    timer.unref?.();
                }),
            ]);
        }
        finally {
            if (timer !== undefined)
                clearTimeout(timer);
        }
    }
    async launchAppServer() {
        let lastError;
        for (const binary of codexBinaryCandidates(this.config.binary)) {
            try {
                await this.launchAppServerCandidate(binary);
                return;
            }
            catch (error) {
                lastError = error;
                if (!canTryNextBinary(error))
                    throw error;
            }
        }
        throw lastError ?? new RpcError('CODEX_START_FAILED', 'Codex App Server could not be started.');
    }
    async launchAppServerCandidate(binary) {
        const appServer = this.createAppServer(binary, this.logger);
        this.appServer = appServer;
        this.unsubscribeInbound = appServer.onInbound(message => {
            void this.handleInbound(message).catch(error => {
                this.logger.warn('Codex inbound handling failed', { code: errorCode(error) });
            });
        });
        this.unsubscribeUnavailable = appServer.onUnavailable(code => {
            if (this.available && this.appServer === appServer) {
                void this.handleAppServerUnavailable(appServer, code);
            }
        });
        try {
            await appServer.start();
            const account = await appServer.call('account/read', { refreshToken: false }, 15_000);
            if (!accountCanRun(account)) {
                throw new RpcError('CODEX_AUTH_REQUIRED', 'Codex is not signed in on this Host.');
            }
            if (this.closed || this.appServer !== appServer) {
                await appServer.close().catch(() => undefined);
                return;
            }
            this.available = true;
            this.state = 'ready';
            this.restartAttempt = 0;
            this.unavailableCode = undefined;
            this.logger.info('Codex Remote domain ready');
        }
        catch (error) {
            await this.disposeAppServer(appServer);
            throw error;
        }
    }
    async handleAppServerUnavailable(appServer, code) {
        if (this.closed || this.appServer !== appServer)
            return;
        this.available = false;
        this.state = 'restarting';
        this.unavailableCode = code;
        this.turnOwners.clear();
        this.permissionPresets.clear();
        this.approvals.clear();
        if (this.approvalExpiryTimer !== undefined)
            clearTimeout(this.approvalExpiryTimer);
        this.approvalExpiryTimer = undefined;
        await Promise.all([...this.peers.values()].map(peer => peer.failStreams('failed')));
        await this.disposeAppServer(appServer);
        this.scheduleRestart();
    }
    scheduleRestart() {
        if (this.closed || this.restartTimer !== undefined)
            return;
        if (this.restartAttempt >= this.restartDelaysMs.length) {
            this.state = 'unavailable';
            this.logger.warn('Codex App Server restart attempts exhausted', { attempts: this.restartAttempt });
            return;
        }
        const delayMs = Math.max(0, this.restartDelaysMs[this.restartAttempt] ?? 0);
        this.restartAttempt += 1;
        this.state = 'restarting';
        this.restartTimer = setTimeout(() => {
            this.restartTimer = undefined;
            void this.restartAfterFailure();
        }, delayMs);
        this.restartTimer.unref?.();
        this.logger.warn('Codex App Server restart scheduled', { attempt: this.restartAttempt, delayMs });
    }
    async restartAfterFailure() {
        if (this.closed)
            return;
        try {
            await this.launchAppServer();
        }
        catch (error) {
            this.available = false;
            this.state = 'restarting';
            this.unavailableCode = errorCode(error);
            this.logger.warn('Codex App Server restart failed', {
                attempt: this.restartAttempt,
                code: this.unavailableCode,
            });
            this.scheduleRestart();
        }
    }
    async disposeAppServer(appServer) {
        if (appServer === undefined || this.appServer !== appServer)
            return;
        this.unsubscribeInbound?.();
        this.unsubscribeInbound = undefined;
        this.unsubscribeUnavailable?.();
        this.unsubscribeUnavailable = undefined;
        this.appServer = undefined;
        await appServer.close().catch(() => undefined);
    }
    async handleInbound(message) {
        if (!this.available)
            return;
        if (message.kind === 'request') {
            await this.handleServerRequest(message);
            return;
        }
        const threadId = extractThreadId(message.params);
        if (message.method === 'thread/settings/updated' && threadId !== undefined) {
            this.rememberPermission(threadId, message.params);
        }
        if (message.method === 'thread/closed' && threadId !== undefined)
            this.permissionPresets.delete(threadId);
        if (message.method === 'turn/completed' && threadId !== undefined)
            this.turnOwners.delete(threadId);
        if (message.method === 'turn/started' && threadId !== undefined) {
            const owner = this.turnOwners.get(threadId);
            const turnId = extractTurnId(message.params);
            if (owner !== undefined) {
                this.turnOwners.set(threadId, { ...owner, ...(turnId === undefined ? {} : { turnId }) });
            }
        }
        if (message.method === 'serverRequest/resolved')
            this.resolveUpstreamApproval(message.params);
        if (threadId === undefined)
            return;
        await Promise.all([...this.peers.values()].map(peer => peer.publishInbound(threadId, {
            method: message.method,
            params: message.params,
        })));
    }
    rememberPermission(threadId, value) {
        const preset = codexPermissionPresetFromResponse(value);
        if (preset === undefined)
            this.permissionPresets.delete(threadId);
        else
            this.permissionPresets.set(threadId, preset);
    }
    async changeThreadPermission(threadId, params, allowed) {
        const permission = codexPermission(params);
        const settings = {
            ...codexTurnPermissionParams(permission, allowed.cwd),
            ...(params.model === undefined ? {} : { model: params.model }),
        };
        let result;
        try {
            // Loaded threads can reject resume or ignore its configuration overrides.
            await this.callUpstream('thread/settings/update', { threadId, ...settings });
            result = { thread: allowed.thread, ...settings, sandbox: settings.sandboxPolicy };
        }
        catch {
            // Unloaded threads and older App Servers still use the resume contract.
            result = await this.callUpstream('thread/resume', {
                ...permission.params,
                ...(allowed.cwd === undefined ? {} : { cwd: allowed.cwd }),
                ...codexThreadPermissionParams(permission),
                excludeTurns: true,
            });
            await this.assertResultThreadAllowed(result);
            if (codexPermissionPresetFromResponse(result) !== params.permissionPreset) {
                await this.callUpstream('thread/settings/update', { threadId, ...settings });
                result = { ...(isRecord(result) ? result : {}), ...settings, sandbox: settings.sandboxPolicy };
            }
        }
        await this.publishPermission(threadId, result);
        return result;
    }
    async publishPermission(threadId, value) {
        const source = isRecord(value) ? value : {};
        const threadSettings = { approvalPolicy: source.approvalPolicy, sandboxPolicy: source.sandboxPolicy ?? source.sandbox };
        await this.handleInbound({ kind: 'notification', method: 'thread/settings/updated', params: { threadId, threadSettings } });
    }
    async handleServerRequest(message) {
        const appServer = this.requireAppServer();
        await this.expireApprovals();
        if (message.method !== 'item/commandExecution/requestApproval'
            && message.method !== 'item/fileChange/requestApproval') {
            await appServer.respondError(message.id, -32601, 'This Remote client does not support the server request.');
            return;
        }
        const threadId = extractThreadId(message.params);
        const owner = threadId === undefined ? undefined : this.turnOwners.get(threadId);
        const peer = owner === undefined ? undefined : this.peers.get(owner.connectionId);
        if (threadId === undefined || owner === undefined || peer === undefined || !peer.hasThreadSubscription(threadId)) {
            await appServer.respond(message.id, { decision: 'decline' });
            return;
        }
        const requestHandle = randomUUID();
        this.approvals.set(requestHandle, {
            upstreamId: message.id,
            connectionId: owner.connectionId,
            threadId,
            method: message.method,
            expiresAt: Date.now() + APPROVAL_TTL_MS,
        });
        this.scheduleApprovalExpiry();
        try {
            await peer.publishInbound(threadId, {
                method: message.method,
                params: sanitizeApprovalParams(message.params, requestHandle),
            });
        }
        catch (error) {
            this.approvals.delete(requestHandle);
            this.scheduleApprovalExpiry();
            await appServer.respond(message.id, { decision: 'decline' }).catch(() => undefined);
            throw error;
        }
    }
    async readKnownThread(threadId) {
        const authority = await this.readWorkspaceAuthority();
        let result;
        try {
            result = await this.callUpstream('thread/read', { threadId, includeTurns: false });
        }
        catch (error) {
            if (!isHistoryReadRecoverable(error))
                throw error;
            const listed = await this.findKnownThreadInList(threadId, authority);
            if (listed === undefined) {
                throw new RpcError('CODEX_THREAD_NOT_ALLOWED', 'The Codex thread is not available through this Remote Host.');
            }
            return { thread: listed, ...(typeof listed.cwd === 'string' && listed.cwd.length > 0 ? { cwd: listed.cwd } : {}) };
        }
        const thread = extractThread(result);
        if (thread === undefined || thread.id !== threadId || !isThreadAllowedByWorkspaceAuthority(thread, authority)) {
            throw new RpcError('CODEX_THREAD_NOT_ALLOWED', 'The Codex thread is not available through this Remote Host.');
        }
        return { thread, ...(typeof thread.cwd === 'string' && thread.cwd.length > 0 ? { cwd: thread.cwd } : {}) };
    }
    async readThreadForHistory(connectionId, threadId) {
        const { thread: metadata } = await this.readKnownThread(threadId);
        try {
            return { ...metadata, turns: await this.readThreadTurns(connectionId, threadId, 'full') };
        }
        catch (fullError) {
            if (!isHistoryReadRecoverable(fullError))
                throw fullError;
            this.logHistoryFallback(connectionId, 'turns-full', fullError);
        }
        try {
            return { ...metadata, turns: await this.readThreadTurns(connectionId, threadId, 'summary') };
        }
        catch (summaryError) {
            if (!isHistoryReadRecoverable(summaryError))
                throw summaryError;
            this.logHistoryFallback(connectionId, 'turns-summary', summaryError);
        }
        try {
            const legacyResult = await this.callUpstream('thread/read', { threadId, includeTurns: true });
            const legacyThread = extractThread(legacyResult);
            if (legacyThread === undefined || legacyThread.id !== threadId) {
                throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid Thread history.');
            }
            return legacyThread;
        }
        catch (legacyError) {
            if (!isHistoryReadRecoverable(legacyError))
                throw legacyError;
            this.logHistoryFallback(connectionId, 'thread-read-full', legacyError);
            return { ...metadata, turns: [] };
        }
    }
    async readThreadTurns(connectionId, threadId, itemsView) {
        const turns = [];
        let cursor;
        for (let page = 0; page < MAX_CODEX_HISTORY_PAGES; page += 1) {
            const result = await this.callUpstream('thread/turns/list', {
                threadId,
                limit: CODEX_HISTORY_PAGE_LIMIT,
                sortDirection: 'asc',
                itemsView,
                ...(cursor === undefined ? {} : { cursor }),
            });
            const pageResult = isRecord(result) ? result : {};
            for (const rawTurn of array(pageResult.data)) {
                if (!isRecord(rawTurn))
                    continue;
                const turnId = typeof rawTurn.id === 'string' ? rawTurn.id : undefined;
                const items = rawTurn.itemsView === 'full' || turnId === undefined
                    ? array(rawTurn.items)
                    : await this.readThreadItems(connectionId, threadId, turnId, array(rawTurn.items));
                turns.push({ ...rawTurn, items });
            }
            cursor = typeof pageResult.nextCursor === 'string' && pageResult.nextCursor.length > 0
                ? pageResult.nextCursor
                : undefined;
            if (cursor === undefined)
                break;
        }
        return turns;
    }
    async readThreadItems(connectionId, threadId, turnId, fallbackItems) {
        const items = [];
        let cursor;
        try {
            for (let page = 0; page < MAX_CODEX_HISTORY_PAGES; page += 1) {
                const result = await this.callUpstream('thread/items/list', {
                    threadId,
                    turnId,
                    limit: CODEX_HISTORY_PAGE_LIMIT,
                    sortDirection: 'asc',
                    ...(cursor === undefined ? {} : { cursor }),
                });
                const pageResult = isRecord(result) ? result : {};
                for (const entry of array(pageResult.data)) {
                    if (isRecord(entry) && entry.item !== undefined)
                        items.push(entry.item);
                }
                cursor = typeof pageResult.nextCursor === 'string' && pageResult.nextCursor.length > 0
                    ? pageResult.nextCursor
                    : undefined;
                if (cursor === undefined)
                    break;
            }
        }
        catch (error) {
            if (!isHistoryReadRecoverable(error))
                throw error;
            this.logHistoryFallback(connectionId, 'items', error);
            return fallbackItems;
        }
        return items;
    }
    logHistoryFallback(connectionId, stage, error) {
        this.logger.warn('Codex history read fallback', {
            connectionId: maskId(connectionId),
            stage,
            code: errorCode(error),
        });
    }
    async assertResultThreadAllowed(result) {
        const thread = extractThread(result);
        if (thread === undefined || typeof thread.id !== 'string') {
            throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid thread.');
        }
        if (!isThreadAllowedByWorkspaceAuthority(thread, await this.readWorkspaceAuthority())) {
            throw new RpcError('CODEX_THREAD_NOT_ALLOWED', 'The Codex thread is not available through this Remote Host.');
        }
    }
    claimTurn(threadId, connectionId, method, turnId) {
        const owner = this.turnOwners.get(threadId);
        const peerDeviceId = this.peerDeviceIds.get(connectionId) ?? connectionId;
        const nextOwner = { connectionId, peerDeviceId, ...(turnId === undefined ? {} : { turnId }) };
        if (method === 'turn/interrupt') {
            if (owner === undefined) {
                // A replacement connection may recover an already-running persisted
                // turn. Claim only for this explicit mutation; passive viewers still
                // remain observers and cannot steal a live connection's lease.
                this.turnOwners.set(threadId, nextOwner);
                return { claimed: true };
            }
            if (owner.connectionId !== connectionId && owner.peerDeviceId !== peerDeviceId) {
                throw new RpcError('CODEX_TURN_OWNED', 'Only the connection that started this Codex turn can interrupt it.');
            }
            if (owner.connectionId !== connectionId) {
                this.turnOwners.set(threadId, { ...owner, connectionId, peerDeviceId, ...(turnId === undefined ? {} : { turnId }) });
                return { claimed: true, previous: owner };
            }
            if (turnId !== undefined && owner.turnId === undefined)
                this.turnOwners.set(threadId, { ...owner, turnId });
            return { claimed: false };
        }
        if (owner !== undefined && owner.connectionId !== connectionId && owner.peerDeviceId !== peerDeviceId) {
            throw new RpcError('CODEX_TURN_OWNED', 'Another Remote connection owns the active Codex turn.');
        }
        if (owner?.connectionId === connectionId)
            return { claimed: false };
        this.turnOwners.set(threadId, owner === undefined ? nextOwner : { ...owner, connectionId, peerDeviceId });
        return { claimed: true, previous: owner };
    }
    rememberTurnId(threadId, connectionId, turnId) {
        if (turnId === undefined)
            return;
        const owner = this.turnOwners.get(threadId);
        if (owner === undefined || owner.connectionId !== connectionId)
            return;
        this.turnOwners.set(threadId, { ...owner, turnId });
    }
    hasSubscriber(threadId) {
        return [...this.peers.values()].some(peer => peer.hasThreadSubscription(threadId));
    }
    resolveUpstreamApproval(params) {
        if (!isRecord(params) || (typeof params.requestId !== 'string' && typeof params.requestId !== 'number'))
            return;
        for (const [handle, approval] of this.approvals) {
            if (approval.upstreamId === params.requestId)
                this.approvals.delete(handle);
        }
    }
    async expireApprovals() {
        const now = Date.now();
        const appServer = this.appServer;
        for (const [handle, approval] of this.approvals) {
            if (approval.expiresAt > now)
                continue;
            this.approvals.delete(handle);
            await appServer?.respond(approval.upstreamId, { decision: 'decline' }).catch(() => undefined);
        }
        this.scheduleApprovalExpiry();
    }
    scheduleApprovalExpiry() {
        if (this.approvalExpiryTimer !== undefined)
            clearTimeout(this.approvalExpiryTimer);
        this.approvalExpiryTimer = undefined;
        const nextExpiry = Math.min(...[...this.approvals.values()].map(approval => approval.expiresAt));
        if (!Number.isFinite(nextExpiry))
            return;
        this.approvalExpiryTimer = setTimeout(() => {
            this.approvalExpiryTimer = undefined;
            void this.expireApprovals().catch(error => {
                this.logger.warn('Codex approval expiry failed', { code: errorCode(error) });
            });
        }, Math.max(0, nextExpiry - Date.now()));
        this.approvalExpiryTimer.unref?.();
    }
    requireAppServer() {
        if (!this.isAvailable() || this.appServer === undefined) {
            throw new RpcError('CODEX_UNAVAILABLE', 'Codex Remote is disabled or unavailable on this Host.');
        }
        return this.appServer;
    }
    async callUpstream(method, params) {
        try {
            return await this.requireAppServer().call(method, params);
        }
        catch (error) {
            throw mapAppServerError(error);
        }
    }
    async readWorkspaceAuthority(listedThreads) {
        const projectIds = new Set();
        const roots = [];
        let cursor;
        try {
            for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
                const result = sanitizeProjectList(await this.callUpstream('project/list', {
                    limit: CODEX_PAGE_LIMIT,
                    ...(cursor === undefined ? {} : { cursor }),
                }));
                for (const project of result.data) {
                    const projectRoots = project.roots.map(root => root.path).filter(path => isAbsolute(path));
                    if (projectRoots.length === 0)
                        continue;
                    projectIds.add(project.id);
                    roots.push(...projectRoots);
                }
                cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
                if (cursor === undefined)
                    break;
            }
        }
        catch (error) {
            if (!isProjectListFallbackError(error))
                throw error;
        }
        if (roots.length > 0)
            return { projectIds, roots };
        const threads = listedThreads === undefined ? [] : [...listedThreads];
        if (listedThreads === undefined) {
            cursor = undefined;
            for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
                const result = sanitizeThreadList(await this.callUpstream('thread/list', {
                    limit: CODEX_PAGE_LIMIT,
                    sortKey: 'updated_at',
                    sortDirection: 'desc',
                    archived: false,
                    ...(cursor === undefined ? {} : { cursor }),
                }));
                threads.push(...result.data);
                cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
                if (cursor === undefined)
                    break;
            }
        }
        for (const workspace of deriveCodexCwdWorkspaces(threads)) {
            if (isAbsolute(workspace.path))
                roots.push(workspace.path);
        }
        return { projectIds, roots };
    }
    async findKnownThreadInList(threadId, authority) {
        let cursor;
        for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
            const result = sanitizeThreadList(await this.callUpstream('thread/list', {
                limit: 100,
                sortKey: 'updated_at',
                sortDirection: 'desc',
                archived: false,
                ...(cursor === undefined ? {} : { cursor }),
            }));
            const thread = result.data.find(item => item.id === threadId);
            if (thread !== undefined) {
                return isThreadAllowedByWorkspaceAuthority(thread, authority) ? thread : undefined;
            }
            cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
            if (cursor === undefined)
                break;
        }
        return undefined;
    }
    async requireCodexWorkspacePath(path) {
        if (!isAbsolute(path)) {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
        }
        const paths = await this.listCodexWorkspacePaths();
        const lexicalCandidate = resolve(path);
        let candidate;
        try {
            candidate = await realpath(path);
            if (!(await stat(candidate)).isDirectory())
                throw new Error('not a directory');
        }
        catch {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
        }
        for (const root of paths.values()) {
            try {
                if (!containsCodexPath(resolve(root), lexicalCandidate))
                    continue;
                const canonicalRoot = await realpath(root);
                if (containsCodexPath(canonicalRoot, candidate))
                    return lexicalCandidate;
            }
            catch {
                // A stale project or Thread cwd is not authority for a new Thread.
            }
        }
        throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
    }
    async requireNewCodexProjectPath(path) {
        if (!isAbsolute(path)) {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX project directory must be an existing absolute directory.');
        }
        try {
            const canonical = await realpath(resolve(path));
            if (!(await stat(canonical)).isDirectory())
                throw new Error('not a directory');
            return canonical;
        }
        catch {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX project directory must be an existing absolute directory.');
        }
    }
    async listCodexDirectory(path) {
        const target = await this.resolveCodexDirectory(path);
        const rows = await readdir(target.path, { withFileTypes: true }).catch(() => undefined);
        if (rows === undefined) {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
        }
        const entries = [];
        for (const row of rows) {
            const child = resolve(target.path, row.name);
            let directory = row.isDirectory();
            if (!directory && row.isSymbolicLink()) {
                directory = await stat(child).then(value => value.isDirectory()).catch(() => false);
            }
            if (!directory)
                continue;
            try {
                const canonicalChild = await realpath(child);
                if (!(await stat(canonicalChild)).isDirectory())
                    continue;
                if (!containsCodexPath(target.canonicalRoot, canonicalChild))
                    continue;
            }
            catch {
                continue;
            }
            entries.push({
                name: row.name,
                path: child,
                hidden: process.platform !== 'win32' && row.name.startsWith('.'),
            });
        }
        entries.sort((left, right) => left.name.localeCompare(right.name, undefined, { sensitivity: 'base' }));
        return {
            path: target.path,
            home: target.root,
            crumbs: codexDirectoryCrumbs(target.root, target.path),
            entries: entries.slice(0, CODEX_DIRECTORY_ENTRY_LIMIT),
            truncated: entries.length > CODEX_DIRECTORY_ENTRY_LIMIT,
        };
    }
    async resolveCodexDirectory(path) {
        if (!isAbsolute(path)) {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
        }
        const authority = await this.readWorkspaceAuthority();
        const lexicalCandidate = resolve(path);
        let canonicalCandidate;
        try {
            canonicalCandidate = await realpath(path);
            if (!(await stat(canonicalCandidate)).isDirectory())
                throw new Error('not a directory');
        }
        catch {
            throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
        }
        for (const root of authority.roots) {
            try {
                const lexicalRoot = resolve(root);
                if (!containsCodexPath(lexicalRoot, lexicalCandidate))
                    continue;
                const canonicalRoot = await realpath(root);
                if (containsCodexPath(canonicalRoot, canonicalCandidate)) {
                    return { path: lexicalCandidate, root: lexicalRoot, canonicalRoot };
                }
            }
            catch {
                // A stale project or Thread cwd is not authority for directory browsing.
            }
        }
        throw new RpcError('CODEX_PATH_NOT_ALLOWED', 'The CodeX working directory is not available as a Workspace.');
    }
    async listCodexWorkspacePaths() {
        const paths = new Map();
        const authority = await this.readWorkspaceAuthority();
        for (const root of authority.roots)
            paths.set(normalizeCodexPathForCompare(root), root);
        return paths;
    }
}
/**
 * Prefer Codex bundled with the desktop app when the user kept the default
 * command, so an app install works without a global CLI. Explicit binary
 * configuration is never rewritten or supplemented.
 */
export function codexBinaryCandidates(configured, hostPlatform = process.platform, userHome = homedir()) {
    if (configured !== 'codex')
        return [configured];
    if (hostPlatform === 'win32')
        return [...bundledWindowsCodex(userHome), configured];
    if (hostPlatform !== 'darwin')
        return [configured];
    // The platform is a parameter, not necessarily this host: joining with the host flavour would
    // return Windows separators for a macOS home and make the darwin branch unreachable from a test
    // or tool running elsewhere.
    const bundledCandidates = [
        '/Applications/ChatGPT.app',
        posix.join(userHome, 'Applications', 'ChatGPT.app'),
    ].flatMap(chatGptApp => {
        const codexCli = posix.join(chatGptApp, 'Contents', 'Resources', 'codex-cli');
        try {
            const manifest = JSON.parse(readFileSync(posix.join(codexCli, 'codex-package.json'), 'utf8'));
            if (!isRecord(manifest) || typeof manifest.entrypoint !== 'string' || manifest.entrypoint.length === 0) {
                return [];
            }
            const candidate = posix.join(codexCli, manifest.entrypoint);
            if (!existsSync(candidate))
                return [];
            accessSync(candidate, constants.X_OK);
            return [candidate];
        }
        catch {
            return [];
        }
    });
    return [...new Set([
            ...bundledCandidates,
            '/Applications/ChatGPT.app/Contents/Resources/codex',
            posix.join(userHome, 'Applications', 'ChatGPT.app', 'Contents', 'Resources', 'codex'),
            configured,
        ])];
}
/**
 * Codex shipped with the desktop app on Windows.
 *
 * The app installs each build into its own hash directory under the user's local
 * app data and adds a new one on every update, so the newest directory is the
 * current build. Naming one of those directories in configuration would stop
 * resolving after an update; picking the newest keeps working.
 * @param userHome - the account's home directory, which anchors the app data path.
 * @returns the newest bundled binary, or nothing when the app is not installed.
 */
function bundledWindowsCodex(userHome) {
    const bin = join(userHome, 'AppData', 'Local', 'OpenAI', 'Codex', 'bin');
    try {
        const newest = readdirSync(bin, { withFileTypes: true })
            .filter(entry => entry.isDirectory())
            .map(entry => join(bin, entry.name, 'codex.exe'))
            .filter(candidate => existsSync(candidate))
            .map(candidate => ({ candidate, modified: statSync(candidate).mtimeMs }))
            .sort((left, right) => right.modified - left.modified);
        const candidate = newest[0]?.candidate;
        return candidate === undefined ? [] : [candidate];
    }
    catch {
        return [];
    }
}
function parseCallEnvelope(input) {
    if (!isRecord(input) || typeof input.method !== 'string' || !('params' in input)
        || Object.keys(input).some(key => key !== 'method' && key !== 'params')) {
        throw new RpcError('INVALID_MESSAGE', 'The Codex call envelope is invalid.');
    }
    return { method: input.method, params: input.params };
}
function parseRespond(input) {
    if (!isRecord(input)
        || typeof input.requestHandle !== 'string'
        || !['accept', 'decline', 'cancel'].includes(String(input.decision))
        || Object.keys(input).some(key => key !== 'requestHandle' && key !== 'decision')) {
        throw new RpcError('INVALID_MESSAGE', 'The Codex approval response is invalid.');
    }
    return input;
}
function accountCanRun(result) {
    if (!isRecord(result) || typeof result.requiresOpenaiAuth !== 'boolean')
        return false;
    return result.requiresOpenaiAuth === false || isRecord(result.account);
}
function sanitizeAccount(result) {
    if (!isRecord(result))
        throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned invalid account state.');
    const account = isRecord(result.account) ? result.account : undefined;
    return {
        authenticated: account !== undefined || result.requiresOpenaiAuth === false,
        requiresOpenaiAuth: result.requiresOpenaiAuth === true,
        ...(account === undefined ? {} : {
            account: {
                ...(typeof account.type === 'string' ? { type: account.type } : {}),
                ...(typeof account.planType === 'string' ? { planType: account.planType } : {}),
            },
        }),
    };
}
function sanitizeThreadList(result) {
    if (!isRecord(result) || !Array.isArray(result.data)) {
        throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid thread list.');
    }
    const data = result.data.flatMap(value => {
        if (!isRecord(value) || typeof value.id !== 'string')
            return [];
        return [{
                id: value.id,
                ...(typeof value.sessionId === 'string' ? { sessionId: value.sessionId } : {}),
                ...(typeof value.projectId === 'string' ? { projectId: value.projectId } : {}),
                ...(typeof value.name === 'string' ? { name: value.name } : {}),
                ...(typeof value.preview === 'string' ? { preview: value.preview } : {}),
                ...(typeof value.cwd === 'string' ? { cwd: value.cwd } : {}),
                ...(typeof value.createdAt === 'number' && Number.isFinite(value.createdAt) ? { createdAt: value.createdAt } : {}),
                ...(typeof value.updatedAt === 'number' && Number.isFinite(value.updatedAt) ? { updatedAt: value.updatedAt } : {}),
                ...(typeof value.archived === 'boolean' ? { archived: value.archived } : {}),
                ...(typeof value.isPinned === 'boolean' ? { isPinned: value.isPinned } : {}),
                ...(isRecord(value.status) ? { status: value.status } : {}),
            }];
    });
    return {
        data,
        ...(typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? { nextCursor: result.nextCursor } : { nextCursor: null }),
        ...(typeof result.backwardsCursor === 'string' && result.backwardsCursor.length > 0
            ? { backwardsCursor: result.backwardsCursor }
            : {}),
    };
}
function filterThreadListByWorkspaceAuthority(result, authority) {
    if (!isRecord(result) || !Array.isArray(result.data)) {
        throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid thread list.');
    }
    return {
        ...result,
        data: result.data
            .map(record => isRecord(record) ? record : undefined)
            .filter((thread) => (thread !== undefined && isThreadAllowedByWorkspaceAuthority(thread, authority))),
    };
}
function sanitizeProject(value) {
    if (!isRecord(value) || typeof value.id !== 'string' || typeof value.name !== 'string')
        return undefined;
    const roots = Array.isArray(value.roots)
        ? value.roots.flatMap(root => {
            const path = isRecord(root) && typeof root.path === 'string' && root.path.length > 0 ? root.path : undefined;
            return path === undefined || !isAbsolute(path) ? [] : [{ path }];
        })
        : [];
    if (roots.length === 0)
        return undefined;
    return {
        id: value.id,
        name: value.name,
        roots,
        ...(typeof value.position === 'number' && Number.isFinite(value.position) ? { position: value.position } : {}),
        ...(typeof value.createdAt === 'number' && Number.isFinite(value.createdAt) ? { createdAt: value.createdAt } : {}),
        ...(typeof value.updatedAt === 'number' && Number.isFinite(value.updatedAt) ? { updatedAt: value.updatedAt } : {}),
    };
}
function sanitizeProjectList(result) {
    if (!isRecord(result) || !Array.isArray(result.data)) {
        throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid project list.');
    }
    const data = result.data.flatMap(value => sanitizeProject(value) ?? []);
    return {
        data,
        ...(typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? { nextCursor: result.nextCursor } : { nextCursor: null }),
    };
}
function sanitizeProjectCreate(result) {
    const project = isRecord(result) ? sanitizeProject(result.project) : undefined;
    if (project === undefined) {
        throw new RpcError('CODEX_INVALID_RESPONSE', 'Codex App Server returned an invalid created project.');
    }
    return { project };
}
function isThreadAllowedByWorkspaceAuthority(thread, authority) {
    const projectId = typeof thread.projectId === 'string' ? thread.projectId : undefined;
    if (projectId !== undefined && authority.projectIds.has(projectId))
        return true;
    const cwd = typeof thread.cwd === 'string' ? thread.cwd : undefined;
    return cwd !== undefined && authority.roots.some(root => containsCodexPath(root, cwd));
}
function containsCodexPath(root, candidate) {
    const normalizedRoot = normalizeCodexPathForCompare(root);
    const normalizedCandidate = normalizeCodexPathForCompare(candidate);
    return normalizedCandidate === normalizedRoot
        || normalizedCandidate.startsWith(`${normalizedRoot}/`)
        || normalizedCandidate.startsWith(`${normalizedRoot}\\`);
}
function normalizeCodexPathForCompare(path) {
    const trimmed = path.replace(/[\\/]+$/u, '') || path;
    // Windows resolves paths case-insensitively, and Codex reports the same
    // directory in whatever spelling it happens to hold: a lowercased drive letter,
    // a project renamed after its thread was created, or a short 8.3 name such as
    // `SAKAKI~1`. Comparing literally therefore declared a thread's own directory
    // "outside" the authority built from that very directory, which rejected every
    // thread on Windows.
    return process.platform === 'win32' ? trimmed.toLowerCase() : trimmed;
}
function codexDirectoryCrumbs(root, path) {
    const crumbs = [{ name: basename(root) || root, path: root, hidden: false }];
    const remainder = relative(root, path);
    if (remainder === '')
        return crumbs;
    let current = root;
    for (const segment of remainder.split(/[\\/]+/u).filter(Boolean)) {
        current = resolve(current, segment);
        crumbs.push({ name: segment, path: current, hidden: false });
    }
    return crumbs;
}
function extractThread(result) {
    return isRecord(result) && isRecord(result.thread) ? result.thread : undefined;
}
function extractTurnId(result) {
    if (!isRecord(result))
        return undefined;
    if (typeof result.turnId === 'string' && result.turnId.length > 0)
        return result.turnId;
    if (isRecord(result.turn) && typeof result.turn.id === 'string' && result.turn.id.length > 0)
        return result.turn.id;
    return undefined;
}
function extractThreadId(params) {
    if (!isRecord(params))
        return undefined;
    if (typeof params.threadId === 'string')
        return params.threadId;
    if (isRecord(params.thread) && typeof params.thread.id === 'string')
        return params.thread.id;
    if (isRecord(params.turn) && typeof params.turn.threadId === 'string')
        return params.turn.threadId;
    return undefined;
}
function optionalInteger(value) {
    return typeof value === 'number' && Number.isSafeInteger(value) ? value : undefined;
}
function codexPermission(params, fallbackPreset) {
    const { permissionPreset, ...rest } = params;
    const preset = permissionPreset === 'workspace-write' || permissionPreset === 'danger-full-access'
        ? permissionPreset
        : fallbackPreset;
    if (preset === undefined)
        return { params: mapCodexImageInputs(rest) };
    const fullAccess = preset === 'danger-full-access';
    return {
        params: mapCodexImageInputs(rest),
        approvalPolicy: fullAccess ? 'never' : 'on-request',
        sandbox: fullAccess ? 'danger-full-access' : 'workspace-write',
    };
}
function codexThreadPermissionParams(permission) {
    if (permission.approvalPolicy === undefined || permission.sandbox === undefined)
        return {};
    return {
        approvalPolicy: permission.approvalPolicy,
        sandbox: permission.sandbox,
    };
}
function codexTurnPermissionParams(permission, cwd) {
    if (permission.approvalPolicy === undefined || permission.sandbox === undefined)
        return {};
    return {
        approvalPolicy: permission.approvalPolicy,
        sandboxPolicy: permission.sandbox === 'danger-full-access'
            ? { type: 'dangerFullAccess' }
            : {
                type: 'workspaceWrite',
                writableRoots: cwd === undefined ? [] : [cwd],
                networkAccess: false,
                excludeTmpdirEnvVar: false,
                excludeSlashTmp: false,
            },
    };
}
function mapCodexImageInputs(params) {
    if (!Array.isArray(params.input))
        return params;
    return {
        ...params,
        input: params.input.map(value => {
            if (!isRecord(value) || value.type !== 'image'
                || typeof value.mediaType !== 'string' || typeof value.data !== 'string')
                return value;
            return { type: 'image', url: `data:${value.mediaType};base64,${value.data}` };
        }),
    };
}
function sanitizeApprovalParams(params, requestHandle) {
    if (!isRecord(params))
        return { requestHandle };
    const safe = { ...params };
    delete safe.proposedExecpolicyAmendment;
    delete safe.additionalPermissions;
    safe.availableDecisions = ['accept', 'decline', 'cancel'];
    safe.requestHandle = requestHandle;
    return safe;
}
function mapAppServerError(error) {
    if (error instanceof RpcError)
        return error;
    if (error instanceof CodexAppServerError) {
        if (error.code === 'CODEX_UPSTREAM_ERROR' && isActiveWriterMessage(error.message)) {
            return new RpcError('CODEX_THREAD_BUSY', 'The selected CodeX thread is already active in another CodeX client.');
        }
        return new RpcError(error.code, error.message, undefined, error.code === 'CODEX_REQUEST_TIMEOUT');
    }
    return new RpcError('CODEX_UPSTREAM_ERROR', 'Codex App Server could not complete the request.');
}
function errorCode(error) {
    if (error instanceof RpcError || error instanceof CodexAppServerError)
        return error.code;
    return 'CODEX_START_FAILED';
}
function isHistoryReadRecoverable(error) {
    return error instanceof RpcError
        && ['METHOD_NOT_ALLOWED', 'METHOD_NOT_FOUND', 'CODEX_UPSTREAM_ERROR', 'CODEX_REQUEST_TIMEOUT', 'CODEX_THREAD_BUSY'].includes(error.code);
}
function isProjectListFallbackError(error) {
    return error instanceof RpcError
        && ['METHOD_NOT_ALLOWED', 'METHOD_NOT_FOUND', 'CODEX_UPSTREAM_ERROR'].includes(error.code);
}
function canTryNextBinary(error) {
    return !(error instanceof RpcError) || !['CODEX_AUTH_REQUIRED', 'CODEX_CLOSED'].includes(error.code);
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function array(value) {
    return Array.isArray(value) ? value : [];
}
function maskId(value) {
    return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`;
}
function isActiveWriterMessage(message) {
    return message.toLowerCase().includes('active writer');
}
//# sourceMappingURL=domain.js.map