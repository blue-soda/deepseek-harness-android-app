import { CodexRemoteClient, deriveCodexCwdWorkspaces, projectCodexThread, } from '@dsh-remote/client-core';
import { codexPermissionPresetFromResponse } from './permissions.js';
import { CODEX_HISTORY_MAX_MESSAGES } from './method-policy.js';
import { collectImageBlocks, contentBlocks, isCanonicalBase64, toolOutputImageBlocks, } from './codex-image-codec.js';
const CODEX_SESSION_PREFIX = 'codex:';
const CODEX_WORKSPACE_PREFIX = 'codex-workspace:';
const CODEX_PROVIDER = 'codex';
const CODEX_MODEL = 'codex';
const CODEX_PAGE_LIMIT = 100;
const MAX_CODEX_PAGES = 32;
const MAX_LIVE_TOOL_OUTPUT = 128 * 1024;
const DEFAULT_HISTORY_MESSAGES = 50;
const SESSION_SEARCH_RESULT_LIMIT = 20;
const SESSION_SEARCH_SNIPPET_CODE_POINTS = 240;
const CODEX_DEFAULT_PERMISSION = 'workspace-write';
const MAX_CODEX_PROMPT_PARTS = 16;
const MAX_CODEX_PROMPT_TEXT = 256 * 1024;
const MAX_CODEX_IMAGE_BASE64 = 288 * 1024 * 1024;
const CODEX_IMAGE_MEDIA_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
const CODEX_IMAGE_ATTACHMENT_PREFIX = 'codex-image:';
const HOST_WORKSPACE_ENDPOINTS = new Set([
    'workspaceFiles/list', 'workspaceFiles/stat', 'workspaceFiles/read', 'workspaceFiles/readBytes',
    'workspaceFiles/readAll', 'workspaceFiles/readRelated', 'terminal/environment', 'terminal/shells',
    'terminal/list', 'terminal/create', 'terminal/write', 'terminal/resize', 'terminal/rename', 'terminal/close',
]);
const HOST_WORKSPACE_STREAM_ENDPOINTS = new Set(['workspaceFiles/changes', 'terminal/follow', 'terminal/retain']);
function isHostWorkspaceEndpoint(endpoint) {
    return HOST_WORKSPACE_ENDPOINTS.has(endpoint);
}
function isHostWorkspaceStreamEndpoint(endpoint) {
    return HOST_WORKSPACE_STREAM_ENDPOINTS.has(endpoint);
}
export function codexProjectWorkspaceId(projectId) {
    return `${CODEX_WORKSPACE_PREFIX}project:${projectId}`;
}
/** Discover the CodeX projects visible through the Host App Server. */
export async function discoverCodexVirtualWorkspaces(client, signal) {
    return (await loadCatalog(client, signal)).workspaces;
}
/**
 * A plugin-owned virtual Harness target. It projects CodeX Thread/Turn data at
 * the existing ApiProxy/Typert carrier boundary, so every UI layer above the
 * official Workspace and Session controllers remains native DSH.
 */
export class CodexVirtualHarness {
    client;
    host;
    sessionGeneration;
    hostCarrier;
    api;
    catalog;
    workspaceStreams = new Set();
    controlStreams = new Set();
    eventStreams = new Map();
    rcMuxStreams = new Set();
    rcHostStreams = new Set();
    follows = new Set();
    pendingRequestIds = new Map();
    pendingApprovals = new Map();
    pendingThreads = new Map();
    blankThreads = new Set();
    selectedModels = new Map();
    selectedPermissions = new Map();
    imageAttachments = new Map();
    modelDirectory;
    modelDirectoryPromise;
    lastProjectionSeq = 0;
    commandSeq = 0;
    selectedWorkspaceId;
    closed = false;
    /**
     * The local shell's carriers. Once this Harness answers `/api` for a remote Codex
     * workspace, every endpoint outside the CodeX domain belongs to the shell that
     * owns the window — its settings bootstrap, plugin registry and account reads.
     * @param carrier - local carriers, or undefined to fall back to the remote Host.
     */
    setLocalCarrier(carrier) { this.localCarrier = carrier; }
    localCarrier;
    constructor(client, host, sessionGeneration = 'legacy', hostCarrier) {
        this.client = client;
        this.host = host;
        this.sessionGeneration = sessionGeneration;
        this.hostCarrier = hostCarrier;
        this.api = this.createApiProxy();
    }
    static remote(core, host, sessionGeneration = 'legacy', hostCarrier) {
        return new CodexVirtualHarness(new CodexRemoteClient(core), host, sessionGeneration, hostCarrier);
    }
    async workspaces(signal) {
        return (await this.refreshCatalog(signal)).workspaces;
    }
    async selectWorkspace(workspaceId, signal) {
        const catalog = await loadCatalog(this.client, signal);
        const workspace = catalog.workspaces.find(item => item.workspaceId === workspaceId);
        if (workspace === undefined)
            throw new Error('The selected CodeX workspace is no longer available.');
        this.selectedWorkspaceId = workspace.workspaceId;
        this.catalog = catalog;
        return workspace;
    }
    async preferredSessionId(signal) {
        const catalog = this.catalog;
        const selected = catalog?.workspaces.find(workspace => workspace.workspaceId === this.selectedWorkspaceId);
        const selectedSessionIds = new Set(selected?.sessionIds ?? []);
        const sessions = (catalog?.sessions ?? []).filter(session => selectedSessionIds.has(session.id));
        const idleNamed = sessions.filter(session => session.status !== 'running'
            && session.status !== 'waiting'
            && displayTitle(session) !== null);
        const idle = sessions.filter(session => session.status !== 'running' && session.status !== 'waiting');
        // The most recently updated Thread is often the one still owned by the
        // interactive Codex process. Prefer the previous named Thread when one is
        // available, then fall back through the complete catalog.
        const preferredNamed = idleNamed.length > 1 ? [...idleNamed.slice(1), idleNamed[0]] : idleNamed;
        const candidates = [...new Set([...preferredNamed, ...idle, ...sessions].map(session => session.id))];
        for (const sessionId of candidates) {
            const threadId = nativeThreadId(sessionId);
            try {
                const thread = await this.fetchThread(threadId, signal);
                return sessionId;
            }
            catch {
                // A currently active or concurrently changing CodeX Thread may reject
                // a full history read. Keep looking so the native UI does not remain
                // on an ungrouped placeholder Session after entering the Workspace.
            }
        }
        return undefined;
    }
    async invoke(request) {
        const result = await this.dispatch(`${request.namespace}/${request.method}`, { args: request.args }, request.signal ?? new AbortController().signal);
        if (result.ok)
            return result.value;
        throw Object.assign(new Error(result.error.message), {
            isDSHRemoteError: true,
            code: result.error.code,
            details: result.error.details,
        });
    }
    async dispatch(endpoint, payload, signal) {
        try {
            if (isHostWorkspaceEndpoint(endpoint)) {
                if (this.hostCarrier === undefined)
                    return fail('method-not-found', `CodeX virtual Harness does not implement ${endpoint}.`);
                return await this.hostCarrier.dispatch(endpoint, payload, signal);
            }
            const args = carrierArgs(payload);
            switch (endpoint) {
                case '$events/result': return business(await this.answerRemoteEvent(args, signal));
                case 'workspace/list': {
                    const catalog = await this.refreshCatalog(signal);
                    return business(success({
                        items: nativeVisibleWorkspaces(catalog, this.selectedWorkspaceId).map(nativeWorkspace),
                        archivedSessionIds: catalog.sessions.filter(item => item.archived).map(item => item.id),
                        // `WorkspaceBaseline` declares this field, and the client reads its length
                        // directly: omitting it throws instead of rendering an empty pin list.
                        pinnedSessionIds: [],
                    }));
                }
                case 'workspace/create': return business(await this.createWorkspace(requestArg(args), signal));
                case 'workspace/rename': return business(await this.renameWorkspace(requestArg(args)));
                case 'workspace/delete': return business(failure('workspace-read-only', 'CodeX virtual Workspaces cannot be deleted.'));
                case 'workspace/insertBefore': return business({
                    workspaceIds: nativeVisibleWorkspaces(await this.currentCatalog(signal), this.selectedWorkspaceId).map(item => item.workspaceId),
                });
                case 'workspace/insertSessionBefore': return business(await this.workspaceForSession(requestArg(args), signal));
                case 'workspace/archiveSession': return business(await this.archiveSession(requestArg(args), signal));
                case 'session/list': return business(success({ items: await this.sessionSummaries(signal) }));
                case 'session/search': return business(await this.searchSessions(requestArg(args), signal));
                case 'session/create': return business(await this.createSession(requestArg(args), signal));
                case 'session/fork': return business(await this.forkSession(requestArg(args), signal));
                case 'session/history': return business(await this.sessionHistory(requestArg(args), signal));
                case 'session/page': return business(await this.sessionPage(requestArg(args), signal));
                case 'session/prompt': return business(await this.prompt(requestArg(args), signal));
                case 'session/cancel': return business(await this.cancel(requestArg(args), signal));
                case 'session/rename': return business(await this.renameSession(requestArg(args), signal));
                case 'session/updateQueue': return business(failure('queue-item-not-found', 'CodeX does not expose a DSH inbox queue.'));
                case 'session/attachment': return business(await this.attachment(requestArg(args), signal));
                case 'session/modelCatalog': return business(success(modelCatalog(await this.models(signal))));
                case 'session/models': {
                    const sessionId = requiredString(requestArg(args).sessionId, 'sessionId');
                    nativeThreadId(sessionId);
                    const directory = await this.models(signal);
                    return business(success({
                        current: this.modelSelection(sessionId, directory),
                        routable: true,
                        groups: directory.groups,
                        failures: [],
                    }));
                }
                case 'session/selectModel': return business(await this.selectModel(requestArg(args), signal));
                case 'session/canOpenWorkspacePath': return business(this.selectedWorkspaceId !== undefined);
                case 'session/openWorkspacePath': return business(failure('bad-request', 'Opening Host paths is unavailable in CodeX mode.'));
                case 'host/describe': return business(success(await this.describeHost(signal)));
                case 'host/listDirectory': return business(await this.listDirectory(requestArg(args), signal));
                case 'directoryPicker/list': return business(await this.listDirectory(requestArg(args), signal));
                case 'skills/list': return business(success({ items: [] }));
                case 'commands/list': return ok(this.commandList(args));
                case 'commands/execute': return ok(await this.executeCommand(args, signal));
                default: {
                    // Selecting this Harness as the Codex target makes it answer every `/api`
                    // endpoint, but it only owns the CodeX domain. The rest belongs to the local
                    // shell: its settings bootstrap, plugin registry and account reads describe
                    // *this* installation. Answering "method-not-found" broke the native start,
                    // and answering from the remote Host showed the remote Host's welcome flow
                    // and account instead of the local ones.
                    if (this.localCarrier?.dispatch !== undefined)
                        return await this.localCarrier.dispatch(endpoint, payload, signal);
                    if (this.hostCarrier !== undefined)
                        return await this.hostCarrier.dispatch(endpoint, payload, signal);
                    return fail('method-not-found', `CodeX virtual Harness does not implement ${endpoint}.`);
                }
            }
        }
        catch (error) {
            return failFrom(error);
        }
    }
    async open(endpoint, payload, signal) {
        if (isHostWorkspaceStreamEndpoint(endpoint)) {
            if (this.hostCarrier === undefined) {
                throw Object.assign(new Error(`CodeX virtual Harness does not implement stream ${endpoint}.`), {
                    isDSHRemoteError: true,
                    code: 'method-not-found',
                    details: {},
                });
            }
            return this.hostCarrier.open(endpoint, payload, signal);
        }
        const args = carrierArgs(payload);
        if (endpoint === 'workspace/follow')
            return this.workspaceFollow(signal);
        if (endpoint === 'session/control')
            return this.sessionControl(signal);
        if (endpoint === 'session/follow')
            return this.sessionFollow(requestArg(args), signal);
        if (endpoint === '$events')
            return this.remoteEvents(signal);
        // Same delegation as the unary path: a stream the CodeX domain does not own is the
        // local shell's (the plugin registry's own events stream, for instance), and
        // refusing it failed those subscriptions.
        if (this.localCarrier?.open !== undefined)
            return this.localCarrier.open(endpoint, payload, signal);
        if (this.hostCarrier !== undefined)
            return this.hostCarrier.open(endpoint, payload, signal);
        throw Object.assign(new Error(`CodeX virtual Harness does not implement stream ${endpoint}.`), {
            isDSHRemoteError: true,
            code: 'method-not-found',
            details: {},
        });
    }
    async close() {
        if (this.closed)
            return;
        this.closed = true;
        for (const stream of this.workspaceStreams)
            stream.close();
        for (const stream of this.controlStreams)
            stream.close();
        for (const stream of this.eventStreams.values())
            stream.close();
        for (const stream of this.rcMuxStreams)
            stream.close();
        for (const stream of this.rcHostStreams)
            stream.close();
        this.workspaceStreams.clear();
        this.controlStreams.clear();
        this.eventStreams.clear();
        this.rcMuxStreams.clear();
        this.rcHostStreams.clear();
        const follows = [...this.follows];
        this.follows.clear();
        for (const follow of follows) {
            follow.queue.close();
            await follow.close?.().catch(() => undefined);
        }
        this.pendingApprovals.clear();
        this.pendingRequestIds.clear();
        this.pendingThreads.clear();
        this.blankThreads.clear();
        this.selectedModels.clear();
        this.selectedPermissions.clear();
        this.imageAttachments.clear();
    }
    async refreshCatalog(signal) {
        const catalog = await loadCatalog(this.client, signal, this.pendingThreads);
        if (this.selectedWorkspaceId !== undefined
            && !catalog.workspaces.some(workspace => workspace.workspaceId === this.selectedWorkspaceId)) {
            this.selectedWorkspaceId = undefined;
        }
        this.catalog = catalog;
        return catalog;
    }
    async currentCatalog(signal) {
        return this.catalog ?? this.refreshCatalog(signal);
    }
    models(signal) {
        if (this.modelDirectory !== undefined)
            return Promise.resolve(this.modelDirectory);
        this.modelDirectoryPromise ??= loadModelDirectory(this.client, signal)
            .then(directory => {
            this.modelDirectory = directory;
            return directory;
        })
            .finally(() => { this.modelDirectoryPromise = undefined; });
        return this.modelDirectoryPromise;
    }
    modelSelection(sessionId, directory) {
        return this.selectedModels.get(sessionId) ?? directory?.default ?? modelSelection();
    }
    permissionSelection(sessionId) {
        return this.selectedPermissions.get(sessionId);
    }
    setPermissionSelection(sessionId, preset) {
        if (preset === undefined)
            this.selectedPermissions.delete(sessionId);
        else
            this.selectedPermissions.set(sessionId, preset);
        this.publishProjection(sessionId, 'permissions', codexPermissionsProjection(preset), this.nextProjectionSeq());
    }
    commandList(args) {
        nativeThreadId(requiredString(args.agentId, 'agentId'));
        return [{
                name: 'permission',
                description: 'Switch the CodeX Remote sandbox mode and approval policy',
                input: { hint: '<preset>' },
            }];
    }
    async executeCommand(args, signal) {
        const sessionId = requiredString(args.agentId, 'agentId');
        const threadId = nativeThreadId(sessionId);
        const line = requiredString(args.line, 'command line').trim();
        if (!Array.isArray(args.images))
            throw new Error('The CodeX command image list is required.');
        const match = /^\/permission(?:\s+([\s\S]*))?$/u.exec(line);
        if (match === null)
            return undefined;
        const commandId = `codex-permission:${++this.commandSeq}`;
        if (args.images.length > 0)
            return {
                commandId,
                result: { kind: 'error', text: 'CodeX Remote permission commands do not accept attachments.' },
            };
        const requested = (match[1] ?? '').trim();
        if (requested === '') {
            await this.readHistoryPage(threadId, { maxMessages: 1 }, signal);
            return {
                commandId,
                result: { kind: 'success', text: `current preset ${this.permissionSelection(sessionId) ?? 'Host settings (not reported)'}` },
            };
        }
        if (!isCodexPermissionPreset(requested))
            return {
                commandId,
                result: { kind: 'error', text: `unknown preset "${requested}" (available: workspace-write, danger-full-access)` },
            };
        const result = await this.client.request('thread/resume', { threadId, permissionPreset: requested }, signal);
        const effective = codexPermissionPresetFromResponse(result);
        if (effective === undefined)
            throw new Error('The Host did not confirm the CodeX permission preset.');
        this.setPermissionSelection(sessionId, effective);
        if (effective !== requested)
            throw new Error('The Host did not apply the requested CodeX permission preset.');
        return { commandId, result: { kind: 'success', text: `preset ${effective}` } };
    }
    sessionTitle(sessionId) {
        const session = this.catalog?.sessions.find(item => item.id === sessionId);
        return session === undefined ? null : displayTitle(session);
    }
    async selectModel(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        nativeThreadId(sessionId);
        const provider = requiredString(request.provider, 'provider');
        const model = requiredString(request.model, 'model');
        const reasoningEffort = string(request.reasoningEffort);
        const directory = await this.models(signal);
        const available = directory.models.get(model);
        if (provider !== CODEX_PROVIDER || available === undefined) {
            return failure('model-unavailable', 'The selected CodeX model is unavailable on this Host.');
        }
        const supportedEfforts = available.reasoning?.efforts.map(effort => effort.id) ?? [];
        if (reasoningEffort !== undefined && !supportedEfforts.includes(reasoningEffort)) {
            return failure('model-unavailable', 'The selected reasoning effort is unavailable for this CodeX model.');
        }
        const selected = {
            provider,
            model,
            ...(reasoningEffort === undefined ? {} : { reasoningEffort }),
        };
        this.selectedModels.set(sessionId, selected);
        const seq = Date.now();
        this.publishProjection(sessionId, 'modelSelection', modelSelectionProjection(selected), seq);
        return success({ selected });
    }
    publishProjection(sessionId, key, value, seq) {
        for (const queue of this.controlStreams)
            queue.push({ type: 'projection', sessionId, key, value, seq });
        this.broadcastRcMux({ type: 'session/projection', sessionId, key, value, seq });
    }
    nextProjectionSeq() {
        const seq = Math.max(Date.now(), this.lastProjectionSeq + 1);
        this.lastProjectionSeq = seq;
        return seq;
    }
    updateThreadName(threadId, name) {
        const pending = this.pendingThreads.get(threadId);
        if (pending !== undefined)
            this.pendingThreads.set(threadId, { ...pending, name });
        if (this.catalog === undefined)
            return;
        const threads = this.catalog.threads.map(thread => string(thread.id) === threadId ? { ...thread, name } : thread);
        const sessions = this.catalog.sessions.map(session => {
            if (session.nativeId !== threadId)
                return session;
            const { title: _title, ...rest } = session;
            return name.trim() === '' ? rest : { ...rest, title: name };
        });
        this.catalog = { ...this.catalog, threads, sessions };
    }
    async sessionSummaries(signal) {
        const catalog = await this.refreshCatalog(signal);
        const directory = await this.models(signal).catch(() => undefined);
        return catalog.sessions.map(session => {
            const blank = this.blankThreads.has(session.nativeId)
                || this.pendingThreads.has(session.nativeId);
            return this.sessionSummary(session, {
                blank,
                modelSelection: this.modelSelection(session.id, directory),
                lastPromptAt: session.updatedAt || null,
            });
        });
    }
    sessionSummary(session, options) {
        return {
            sessionId: session.id,
            updatedAt: session.updatedAt,
            running: session.status === 'running' || session.status === 'waiting',
            blank: options.blank,
            ...(session.cwd === undefined ? {} : { cwd: session.cwd }),
            projections: {
                // `SessionProjectionHints` is a discriminated union; without its kind the
                // client's exhaustive switch throws and takes the whole session list down.
                kind: 'sequenced',
                asOfSeq: options.asOfSeq ?? 0,
                values: {
                    title: displayTitle(session),
                    sessionListMetadata: { blank: options.blank, lastPromptAt: options.lastPromptAt },
                    modelSelection: modelSelectionProjection(options.modelSelection),
                    permissions: codexPermissionsProjection(this.permissionSelection(session.id)),
                    imageLimits: codexImageLimitsProjection(),
                },
            },
        };
    }
    async searchSessions(request, signal) {
        const query = requiredString(request.query, 'query').trim();
        if (query.length === 0 || query.length > 500 || query.includes('\0')) {
            return failure('bad-request', 'The CodeX Session search query is invalid.');
        }
        const catalog = await this.currentCatalog(signal);
        const needle = query.toLocaleLowerCase();
        const matches = catalog.threads.flatMap(thread => {
            const session = projectCodexThread(thread);
            if (session === undefined)
                return [];
            const candidates = [session.title, session.preview, session.cwd, session.nativeId]
                .filter((value) => value !== undefined && value.length > 0);
            const snippet = candidates.find(value => value.toLocaleLowerCase().includes(needle));
            if (snippet === undefined)
                return [];
            return [{ sessionId: session.id, snippet: truncateCodePoints(snippet, SESSION_SEARCH_SNIPPET_CODE_POINTS) }];
        });
        return success({
            items: matches.slice(0, SESSION_SEARCH_RESULT_LIMIT),
            hasMore: matches.length > SESSION_SEARCH_RESULT_LIMIT,
        });
    }
    async workspaceFollow(signal) {
        const queue = new AsyncValueQueue(signal);
        this.workspaceStreams.add(queue);
        const catalog = await this.refreshCatalog(signal);
        queue.push({
            type: 'baseline',
            value: {
                items: nativeVisibleWorkspaces(catalog, this.selectedWorkspaceId).map(nativeWorkspace),
                archivedSessionIds: catalog.sessions.filter(item => item.archived).map(item => item.id),
                // Required by `WorkspaceBaseline`; CodeX virtual Workspaces hold no pins.
                pinnedSessionIds: [],
            },
        });
        return queue.iterate(() => this.workspaceStreams.delete(queue));
    }
    async sessionControl(signal) {
        const queue = new AsyncValueQueue(signal);
        this.controlStreams.add(queue);
        queue.push({ type: 'baseline', value: { queues: {}, jobs: {}, projections: {} } });
        return queue.iterate(() => this.controlStreams.delete(queue));
    }
    async remoteEvents(signal) {
        const queue = new AsyncValueQueue(signal);
        const clientId = `codex-events:${this.host.deviceId}:${Date.now().toString(36)}`;
        this.eventStreams.set(clientId, queue);
        const home = (await this.currentCatalog(signal)).workspaces[0]?.path ?? '/';
        queue.push({ type: 'ready', clientId, host: { home } });
        return queue.iterate(() => this.eventStreams.delete(clientId));
    }
    async sessionFollow(request, signal) {
        const sessionId = sessionIdFromAddress(record(request.address));
        const threadId = nativeThreadId(sessionId);
        const history = await this.readHistoryPage(threadId, {
            maxMessages: optionalPositiveInteger(request.maxMessages),
        }, signal);
        const directory = await this.models(signal).catch(() => undefined);
        const queue = new AsyncValueQueue(signal);
        const follow = {
            sessionId,
            threadId,
            queue,
            nextSeq: history.cursor + 1,
            turn: history.nextTurn,
            stepOpen: history.activeTurnId !== undefined,
            startedItems: new Set(),
            completedItems: new Set(),
            streamedBlocks: new Map(),
            nextBlockIndex: 0,
            streamActive: false,
            assistantStreamRevision: 0,
            liveItems: new Map(),
            liveToolOutput: new Map(),
            liveToolResultSeq: new Map(),
            pendingToolImages: [],
            requestId: this.pendingRequestIds.get(sessionId),
            ...(history.activeTurnId === undefined ? {} : { activeTurnId: history.activeTurnId }),
        };
        this.follows.add(follow);
        queue.push({
            type: 'snapshot',
            header: history.header,
            cursor: history.cursor,
            records: history.records,
            hasMore: history.hasMore,
            projections: {
                // Same union as the summary path: the kind is what the client switches on.
                kind: 'sequenced',
                asOfSeq: history.cursor,
                values: {
                    title: this.sessionTitle(sessionId),
                    sessionListMetadata: { blank: history.cursor < 0, lastPromptAt: null },
                    modelSelection: modelSelectionProjection(this.modelSelection(sessionId, directory)),
                    permissions: codexPermissionsProjection(this.permissionSelection(sessionId)),
                    imageLimits: codexImageLimitsProjection(),
                },
            },
            ...(this.sessionGeneration === 'v3' ? { assistantStream: { revision: 0 } } : {}),
        });
        try {
            const stream = await this.client.subscribe(threadId, frame => this.acceptCodexFrame(follow, frame), signal, () => this.closeFollowAfterRemoteStreamClosed(follow));
            follow.close = () => stream.close();
        }
        catch (error) {
            this.follows.delete(follow);
            queue.close();
            throw error;
        }
        return queue.iterate(() => {
            this.follows.delete(follow);
            void follow.close?.().catch(() => undefined);
        });
    }
    acceptCodexFrame(follow, frame) {
        const params = record(frame.params);
        if (frame.method === 'thread/name/updated') {
            const name = string(params.threadName);
            if (name === undefined)
                return;
            const seq = this.nextProjectionSeq();
            this.updateThreadName(follow.threadId, name);
            const title = name.trim() === '' ? null : name.slice(0, 256);
            this.publishProjection(follow.sessionId, 'title', title, seq);
            return;
        }
        if (frame.method === 'thread/settings/updated') {
            const preset = codexPermissionPresetFromResponse(params);
            this.setPermissionSelection(follow.sessionId, preset);
            return;
        }
        if (frame.method === 'turn/started') {
            const turn = record(params.turn);
            this.endAssistantAttempt(follow, { kind: 'abandoned' });
            this.resetLiveTurn(follow);
            follow.turn += 1;
            follow.activeTurnId = string(turn.id);
            follow.requestId = this.pendingRequestIds.get(follow.sessionId);
            follow.stepOpen = true;
            this.pushEvent(follow, 'turn/start', { turn: follow.turn });
            this.pushEvent(follow, 'step/start', { turn: follow.turn, step: 1 });
            this.emitRemoteEvent('api-session/status', [follow.sessionId, true]);
            for (const item of array(turn.items))
                this.acceptCompletedItem(follow, record(item), false);
            return;
        }
        if (frame.method === 'item/started' || frame.method === 'item/completed') {
            const item = record(params.item);
            const itemId = string(item.id);
            if (frame.method === 'item/started' && itemId !== undefined) {
                follow.startedItems.add(itemId);
                follow.liveItems.set(itemId, item);
                if (isToolItemType(string(item.type)))
                    this.ensureToolStarted(follow, item);
            }
            if (frame.method === 'item/completed')
                this.acceptCompletedItem(follow, item, true);
            return;
        }
        if (frame.method === 'item/agentMessage/delta') {
            const itemId = string(params.itemId) ?? `assistant:${follow.turn}`;
            const delta = string(params.delta);
            if (delta === undefined)
                return;
            this.appendStreamDelta(follow, `agent:${itemId}`, itemId, 'text', delta);
            return;
        }
        if (frame.method === 'item/reasoning/summaryPartAdded') {
            const itemId = string(params.itemId);
            if (itemId !== undefined)
                this.ensureStreamBlock(follow, `reasoning-summary:${itemId}:${integer(params.summaryIndex) ?? 0}`, itemId, 'reasoning');
            return;
        }
        if (frame.method === 'item/reasoning/summaryTextDelta'
            || frame.method === 'item/reasoning/textDelta'
            || frame.method === 'item/plan/delta') {
            const itemId = string(params.itemId);
            const delta = string(params.delta);
            if (itemId === undefined || delta === undefined)
                return;
            const key = frame.method === 'item/plan/delta'
                ? `plan:${itemId}`
                : frame.method === 'item/reasoning/summaryTextDelta'
                    ? `reasoning-summary:${itemId}:${integer(params.summaryIndex) ?? 0}`
                    : `reasoning-content:${itemId}:${integer(params.contentIndex) ?? 0}`;
            this.appendStreamDelta(follow, key, itemId, 'reasoning', delta);
            return;
        }
        if (frame.method === 'turn/plan/updated') {
            const todos = array(params.plan).flatMap(value => {
                const item = record(value);
                const content = string(item.step);
                if (content === undefined)
                    return [];
                const status = item.status === 'inProgress'
                    ? 'in_progress'
                    : item.status === 'completed' ? 'completed' : 'pending';
                return [{ content, status }];
            });
            this.pushEvent(follow, 'todo/write', { todos });
            return;
        }
        if (frame.method === 'item/commandExecution/outputDelta'
            || frame.method === 'item/fileChange/outputDelta'
            || frame.method === 'item/mcpToolCall/progress') {
            const itemId = string(params.itemId);
            const delta = frame.method === 'item/mcpToolCall/progress'
                ? string(params.message)
                : string(params.delta);
            if (itemId === undefined || delta === undefined)
                return;
            const type = frame.method === 'item/commandExecution/outputDelta'
                ? 'commandExecution'
                : frame.method === 'item/fileChange/outputDelta' ? 'fileChange' : 'mcpToolCall';
            const item = follow.liveItems.get(itemId) ?? { id: itemId, type, status: 'inProgress' };
            follow.liveItems.set(itemId, item);
            this.ensureToolStarted(follow, item);
            const separator = frame.method === 'item/mcpToolCall/progress' && follow.liveToolOutput.has(itemId) ? '\n' : '';
            const output = appendBoundedText(follow.liveToolOutput.get(itemId) ?? '', `${separator}${delta}`);
            follow.liveToolOutput.set(itemId, output);
            this.emitToolResult(follow, item, output);
            return;
        }
        if (frame.method === 'item/fileChange/patchUpdated') {
            const itemId = string(params.itemId);
            if (itemId === undefined)
                return;
            const previous = follow.liveItems.get(itemId) ?? { id: itemId, type: 'fileChange', status: 'inProgress' };
            const item = { ...previous, changes: sanitizedFileChanges(params.changes) };
            follow.liveItems.set(itemId, item);
            this.ensureToolStarted(follow, item);
            this.emitToolResult(follow, item, fileChangeSummary(item));
            return;
        }
        if (frame.method === 'thread/status/changed') {
            const status = record(params.status);
            const running = status.type === 'active';
            this.emitRemoteEvent('api-session/status', [follow.sessionId, running]);
            if (status.type === 'systemError') {
                this.broadcastRcHost({
                    type: 'host/agent-error',
                    sessionId: follow.sessionId,
                    message: 'CodeX reported a thread system error.',
                });
            }
            return;
        }
        if (frame.method === 'model/rerouted') {
            const model = string(params.toModel);
            if (model === undefined)
                return;
            const previous = this.selectedModels.get(follow.sessionId);
            const selected = {
                provider: CODEX_PROVIDER,
                model,
                ...(previous?.reasoningEffort === undefined ? {} : { reasoningEffort: previous.reasoningEffort }),
            };
            this.selectedModels.set(follow.sessionId, selected);
            const seq = this.pushEvent(follow, 'request/context', { provider: CODEX_PROVIDER, model });
            this.publishProjection(follow.sessionId, 'modelSelection', modelSelectionProjection(selected), seq);
            return;
        }
        if (frame.method === 'turn/completed') {
            const turn = record(params.turn);
            for (const item of array(turn.items))
                this.acceptCompletedItem(follow, record(item), true);
            if (follow.stepOpen) {
                this.closeAllStreamBlocks(follow);
                if (follow.streamActive)
                    this.finishStream(follow);
                this.endAssistantAttempt(follow, { kind: 'abandoned' });
                this.pushEvent(follow, 'step/end', { turn: follow.turn, step: 1 });
                follow.stepOpen = false;
            }
            this.pushEvent(follow, 'turn/end', {
                turn: follow.turn,
                reason: turn.status === 'failed' || turn.error !== undefined && turn.error !== null
                    ? { kind: 'error', error: { message: 'CodeX turn failed.', code: 'codex-turn-failed' } }
                    : { kind: 'completed' },
            });
            this.emitRemoteEvent('api-session/status', [follow.sessionId, false]);
            this.emitRemoteEvent('api-session/activity', [follow.sessionId, Date.now()]);
            follow.activeTurnId = undefined;
            this.pendingRequestIds.delete(follow.sessionId);
            void this.refreshAndPublishWorkspaces();
            return;
        }
        if (frame.method === 'item/commandExecution/requestApproval'
            || frame.method === 'item/fileChange/requestApproval') {
            const requestHandle = string(params.requestHandle);
            if (requestHandle === undefined)
                return;
            const command = commandText(params.command);
            this.pendingApprovals.set(requestHandle, { requestHandle, sessionId: follow.sessionId });
            this.emitApproval({
                eventId: requestHandle,
                agentId: follow.sessionId,
                request: {
                    toolName: frame.method.includes('fileChange') ? 'CodeX file change' : 'CodeX command',
                    ...(command === undefined ? {} : { reason: command }),
                },
            });
        }
    }
    acceptCompletedItem(follow, item, settleAssistant) {
        const itemId = string(item.id) ?? `item:${follow.nextSeq}`;
        if (follow.completedItems.has(itemId))
            return;
        const type = string(item.type);
        if (type === 'agentMessage' && !settleAssistant)
            return;
        follow.completedItems.add(itemId);
        this.closeItemStreamBlocks(follow, itemId, itemText(item));
        if (type === 'agentMessage' && follow.streamActive && follow.streamedBlocks.size === 0)
            this.finishStream(follow);
        const supplementalImages = type === 'agentMessage' ? follow.pendingToolImages : [];
        let assistantMessageSeq;
        for (const event of itemEvents(item, follow.turn, 1, follow.requestId, this.modelSelection(follow.sessionId), follow.sessionId, supplementalImages)) {
            if (event.type === 'tool/call' && follow.startedItems.has(itemId))
                continue;
            if (event.type === 'tool/result')
                this.pushToolResult(follow, itemId, event);
            else {
                const seq = this.pushEvent(follow, event.type, event.data, event.view);
                if (event.type === 'assistant/message')
                    assistantMessageSeq = seq;
            }
        }
        if (type === 'agentMessage' && assistantMessageSeq !== undefined) {
            this.endAssistantAttempt(follow, {
                kind: 'committed',
                eventType: 'assistant/message',
                seq: assistantMessageSeq,
            });
        }
        if (type === 'agentMessage' && supplementalImages.length > 0)
            follow.pendingToolImages = [];
        else if (type !== undefined && isToolItemType(type)) {
            follow.pendingToolImages.push(...toolOutputImageBlocks(item));
        }
        follow.liveItems.delete(itemId);
        follow.liveToolOutput.delete(itemId);
        follow.liveToolResultSeq.delete(itemId);
        follow.liveToolResultSeq.delete(`${itemId}:call`);
    }
    pushEvent(follow, type, data, view, placement) {
        const seq = follow.nextSeq++;
        const event = {
            type,
            seq,
            time: Date.now(),
            data: adaptEventData(type, type === 'assistant/message' && this.sessionGeneration === 'v3' && follow.assistantAttempt !== undefined
                ? { ...record(data), stream: follow.assistantAttempt.stream }
                : data, this.sessionGeneration),
            ...(isSurfaceEvent(type)
                ? placement === undefined
                    ? { surfaceOp: 'append' }
                    : {
                        surfaceOp: this.sessionGeneration === 'v3'
                            ? { op: 'replace', startSeq: placement.replaceSeq, endSeq: placement.replaceSeq }
                            : { op: 'replace', start: placement.replaceSeq, end: placement.replaceSeq },
                        sourceEventSeqs: [placement.replaceSeq],
                    }
                : {}),
        };
        this.cacheImageBlocks(follow.sessionId, event.data);
        if (!follow.rcOnly) {
            follow.queue.push({
                type: 'event',
                event,
                ...(view === undefined ? {} : { view }),
            });
        }
        this.broadcastRcMux({
            type: 'session/event',
            sessionId: follow.sessionId,
            event,
            ...(view === undefined ? {} : { view }),
        });
        return seq;
    }
    cacheImageBlocks(sessionId, value) {
        for (const block of collectImageBlocks(value)) {
            const attachment = record(block.attachment);
            const attachmentId = string(attachment.attachmentId);
            const data = string(block.data);
            if (attachmentId === undefined || data === undefined || !attachmentId.startsWith(CODEX_IMAGE_ATTACHMENT_PREFIX))
                continue;
            this.imageAttachments.set(attachmentId, { sessionId, attachment, data });
        }
    }
    ensureStreamBlock(follow, key, itemId, kind) {
        const current = follow.streamedBlocks.get(key);
        if (current !== undefined)
            return current;
        const block = { itemId, index: follow.nextBlockIndex++, kind, text: '' };
        follow.streamedBlocks.set(key, block);
        follow.streamActive = true;
        this.pushAssistantChunk(follow, { type: 'block-start', index: block.index, blockType: kind });
        return block;
    }
    appendStreamDelta(follow, key, itemId, kind, delta) {
        const block = this.ensureStreamBlock(follow, key, itemId, kind);
        block.text = appendBoundedText(block.text, delta);
        this.pushAssistantChunk(follow, {
            type: kind === 'reasoning' ? 'reasoning-delta' : 'text-delta', index: block.index, text: delta,
        });
    }
    closeItemStreamBlocks(follow, itemId, fallback) {
        const matches = [...follow.streamedBlocks].filter(([, block]) => block.itemId === itemId);
        for (const [key, block] of matches) {
            const text = block.text || fallback || '';
            this.pushAssistantChunk(follow, {
                type: 'block-end', index: block.index, block: { type: block.kind, text },
            });
            follow.streamedBlocks.delete(key);
        }
    }
    closeAllStreamBlocks(follow) {
        for (const block of follow.streamedBlocks.values()) {
            this.pushAssistantChunk(follow, {
                type: 'block-end', index: block.index, block: { type: block.kind, text: block.text },
            });
        }
        follow.streamedBlocks.clear();
    }
    finishStream(follow) {
        this.pushAssistantChunk(follow, { type: 'finish', reason: { kind: 'stop' } });
        follow.streamActive = false;
    }
    pushAssistantChunk(follow, chunk) {
        if (this.sessionGeneration !== 'v3' || follow.rcOnly === true) {
            this.pushEvent(follow, 'assistant/chunk', { turn: follow.turn, step: 1, chunk });
            return;
        }
        let attempt = follow.assistantAttempt;
        if (attempt === undefined) {
            attempt = {
                attemptId: `codex-attempt:${follow.activeTurnId ?? follow.turn}:${follow.nextSeq}`,
                startedAfterSeq: follow.nextSeq - 1,
                nextIndex: 0,
                stream: [],
            };
            follow.assistantAttempt = attempt;
            follow.assistantStreamRevision += 1;
            follow.queue.push({
                type: 'assistant-stream',
                frame: {
                    type: 'start',
                    attemptId: attempt.attemptId,
                    revision: follow.assistantStreamRevision,
                    startedAfterSeq: attempt.startedAfterSeq,
                    turn: follow.turn,
                    step: 1,
                },
            });
        }
        const time = Date.now();
        const index = attempt.nextIndex++;
        attempt.stream.push({ time, chunk });
        follow.assistantStreamRevision += 1;
        follow.queue.push({
            type: 'assistant-stream',
            frame: {
                type: 'chunk',
                attemptId: attempt.attemptId,
                revision: follow.assistantStreamRevision,
                index,
                time,
                chunk,
            },
        });
    }
    endAssistantAttempt(follow, outcome) {
        const attempt = follow.assistantAttempt;
        if (attempt === undefined)
            return;
        follow.assistantAttempt = undefined;
        follow.assistantStreamRevision += 1;
        if (this.sessionGeneration === 'v3' && follow.rcOnly !== true) {
            follow.queue.push({
                type: 'assistant-stream',
                frame: {
                    type: 'end',
                    attemptId: attempt.attemptId,
                    revision: follow.assistantStreamRevision,
                    index: attempt.nextIndex,
                    outcome,
                },
            });
        }
    }
    ensureToolStarted(follow, item) {
        const itemId = string(item.id);
        if (itemId === undefined)
            return;
        if (!follow.startedItems.has(itemId))
            follow.startedItems.add(itemId);
        if (follow.liveItems.get(itemId) !== item)
            follow.liveItems.set(itemId, item);
        const call = itemEvents(item, follow.turn, 1, follow.requestId, this.modelSelection(follow.sessionId), follow.sessionId).find(event => event.type === 'tool/call');
        if (call !== undefined && !follow.liveToolResultSeq.has(`${itemId}:call`)) {
            this.pushEvent(follow, call.type, call.data, call.view);
            follow.liveToolResultSeq.set(`${itemId}:call`, -1);
        }
    }
    emitToolResult(follow, item, resultText) {
        const itemId = string(item.id);
        if (itemId === undefined)
            return;
        const result = toolResultEvent(item, follow.turn, 1, itemId, resultText);
        this.pushToolResult(follow, itemId, result);
    }
    pushToolResult(follow, itemId, event) {
        const previous = follow.liveToolResultSeq.get(itemId);
        const projected = previous === undefined ? event : toolResultContentReplacement(event);
        const seq = this.pushEvent(follow, projected.type, projected.data, projected.view, previous === undefined ? undefined : { replaceSeq: previous });
        follow.liveToolResultSeq.set(itemId, seq);
    }
    resetLiveTurn(follow) {
        follow.startedItems.clear();
        follow.completedItems.clear();
        follow.streamedBlocks.clear();
        follow.nextBlockIndex = 0;
        follow.streamActive = false;
        follow.assistantAttempt = undefined;
        follow.liveItems.clear();
        follow.liveToolOutput.clear();
        follow.liveToolResultSeq.clear();
        follow.pendingToolImages = [];
    }
    emitRemoteEvent(event, args) {
        for (const queue of this.eventStreams.values())
            queue.push({ type: 'emit', event, args });
        const sessionId = typeof args[0] === 'string' ? args[0] : undefined;
        if (event === 'api-session/status' && sessionId !== undefined && typeof args[1] === 'boolean') {
            this.broadcastRcHost({ type: 'host/session-status', sessionId, running: args[1] });
        }
        else if (event === 'api-session/removed' && sessionId !== undefined) {
            this.broadcastRcHost({ type: 'host/session-removed', sessionId });
        }
        else if (event === 'api-session/added' && isRecord(args[0])) {
            const summary = args[0];
            this.broadcastRcHost({
                type: 'host/session-added',
                sessionId: summary.sessionId,
                blank: summary.blank === true,
                ...(typeof summary.cwd === 'string' ? { cwd: summary.cwd } : {}),
            });
        }
    }
    emitApproval(input) {
        for (const queue of this.eventStreams.values())
            queue.push({
                type: 'waterfall',
                event: 'approval/request',
                eventId: input.eventId,
                agentId: input.agentId,
                request: input.request,
            });
        this.broadcastRcMux({
            type: 'approval/requested',
            sessionId: input.agentId,
            approvalId: input.eventId,
            toolName: string(input.request.toolName) ?? 'CodeX',
            ...(typeof input.request.reason === 'string' ? { reason: input.request.reason } : {}),
        }, input.eventId);
    }
    async answerRemoteEvent(args, signal) {
        const eventId = string(args.eventId);
        const outcome = record(args.outcome);
        if (eventId === undefined)
            throw new Error('The CodeX approval result is missing its event id.');
        const pending = this.pendingApprovals.get(eventId);
        if (pending === undefined)
            return undefined;
        this.pendingApprovals.delete(eventId);
        const decision = outcome.kind === 'result' && outcome.value === 'allowed-once'
            ? 'accept'
            : outcome.kind === 'result' && outcome.value === 'cancelled'
                ? 'cancel'
                : 'decline';
        await this.client.respond(pending.requestHandle, decision, signal);
        return undefined;
    }
    async createWorkspace(request, signal) {
        const path = string(request.path);
        if (path === undefined || path.trim() === '')
            return failure('bad-request', 'A CodeX working directory is required.');
        const catalog = await this.currentCatalog(signal);
        const existing = catalog.workspaces.find(item => item.path === path);
        if (existing !== undefined)
            return success({ workspace: nativeWorkspace(existing), created: false });
        return failure('workspace-not-found', 'The selected directory is not visible to CodeX Remote.');
    }
    async renameWorkspace(request) {
        return failure('workspace-read-only', `CodeX virtual Workspace ${string(request.workspaceId) ?? ''} cannot be renamed.`);
    }
    async workspaceForSession(request, signal) {
        const sessionId = string(request.sessionId);
        const catalog = await this.currentCatalog(signal);
        const workspace = catalog.workspaces.find(item => sessionId !== undefined && item.sessionIds.includes(sessionId));
        return workspace === undefined
            ? failure('workspace-not-found', 'The CodeX virtual Workspace was not found.')
            : success({ workspace: nativeWorkspace(workspace) });
    }
    selectedWorkspace(catalog) {
        return this.selectedWorkspaceId === undefined
            ? undefined
            : catalog.workspaces.find(item => item.workspaceId === this.selectedWorkspaceId);
    }
    async describeHost(signal) {
        const catalog = await this.currentCatalog(signal);
        const workspace = this.selectedWorkspace(catalog);
        const models = await this.models(signal).catch(() => undefined);
        return {
            version: 'CodeX Remote',
            cwd: workspace?.path ?? '',
            home: workspace?.path ?? '',
            provider: 'CodeX',
            ...(models === undefined ? {} : { model: models.default.model }),
            attachedSessions: catalog.sessions.length,
            canOpenPath: workspace !== undefined,
        };
    }
    async listDirectory(request, signal) {
        const catalog = await this.currentCatalog(signal);
        const workspace = this.selectedWorkspace(catalog);
        if (workspace === undefined)
            return failure('workspace-not-found', 'The CodeX virtual Workspace was not found.');
        const path = string(request.path) ?? workspace.path;
        if (!containsPath(workspace.path, path)) {
            return failure('workspace-not-found', 'The selected directory is outside the current CodeX virtual Workspace.');
        }
        const listing = record(await this.client.request('dsh/directoryList', { path }, signal));
        if (!isCodexDirectoryListing(listing)) {
            return failure('internal', 'CodeX Remote returned an invalid directory listing.');
        }
        const listedPaths = [
            listing.path,
            listing.home,
            ...listing.crumbs.map(item => item.path),
            ...listing.entries.map(item => item.path),
        ];
        if (listedPaths.some(path => !containsPath(workspace.path, path))) {
            return failure('internal', 'CodeX Remote returned a directory outside the current Workspace.');
        }
        return success(listing);
    }
    async archiveSession(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        await this.client.request('thread/archive', { threadId: nativeThreadId(sessionId) }, signal);
        const catalog = await this.refreshCatalog(signal);
        this.publishWorkspaceBaseline(catalog);
        return success({ archivedSessionIds: [sessionId] });
    }
    async createSession(request, signal) {
        const catalog = await this.currentCatalog(signal);
        const workspaceId = string(request.workspaceId) ?? this.selectedWorkspaceId;
        const workspace = workspaceId === undefined ? undefined : catalog.workspaces.find(item => item.workspaceId === workspaceId);
        const cwd = string(request.cwd)
            ?? workspace?.path;
        if (cwd === undefined)
            return failure('workspace-not-found', 'The CodeX virtual Workspace was not found.');
        if (workspace !== undefined && !containsPath(workspace.path, cwd)) {
            return failure('workspace-not-found', 'The selected directory is outside the current CodeX virtual Workspace.');
        }
        const directory = await this.models(signal);
        const selection = directory.default;
        const requestedPreset = CODEX_DEFAULT_PERMISSION;
        const result = record(await this.client.request('thread/start', {
            cwd,
            model: selection.model,
            permissionPreset: requestedPreset,
        }, signal));
        const permissionPreset = codexPermissionPresetFromResponse(result) ?? requestedPreset;
        const thread = { ...record(result.thread), cwd, turns: array(record(result.thread).turns) };
        const projected = projectCodexThread(thread);
        if (projected === undefined)
            return failure('internal', 'CodeX returned an invalid Thread.');
        this.pendingThreads.set(projected.nativeId, thread);
        this.blankThreads.add(projected.nativeId);
        this.selectedModels.set(projected.id, selection);
        this.selectedPermissions.set(projected.id, permissionPreset);
        await this.refreshAndPublishWorkspaces();
        const seq = this.nextProjectionSeq();
        this.emitRemoteEvent('api-session/added', [this.sessionSummary(projected, {
                blank: true,
                modelSelection: selection,
                lastPromptAt: null,
                asOfSeq: seq,
            })]);
        this.publishProjection(projected.id, 'title', displayTitle(projected), seq);
        return success({ sessionId: projected.id });
    }
    async forkSession(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const result = record(await this.client.request('thread/fork', {
            threadId: nativeThreadId(sessionId),
        }, signal));
        const permissionPreset = codexPermissionPresetFromResponse(result);
        const projected = projectCodexThread(record(result.thread));
        if (projected === undefined)
            return failure('internal', 'CodeX returned an invalid forked Thread.');
        this.selectedModels.set(projected.id, this.modelSelection(sessionId, await this.models(signal)));
        this.setPermissionSelection(projected.id, permissionPreset);
        await this.refreshAndPublishWorkspaces();
        const seq = this.nextProjectionSeq();
        this.publishProjection(projected.id, 'title', displayTitle(projected), seq);
        return success({ sessionId: projected.id });
    }
    async prompt(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const content = array(request.content);
        const input = codexPromptInput(content);
        if (input === undefined)
            return failure('attachment-error', 'CodeX virtual Sessions accept text and pasted PNG, JPEG, WebP, or GIF images only.');
        const threadId = nativeThreadId(sessionId);
        const selection = this.modelSelection(sessionId, await this.models(signal));
        const isFreshBlankThread = this.blankThreads.has(threadId) || this.pendingThreads.has(threadId);
        await this.ensureRcFollow(sessionId);
        if (!isFreshBlankThread) {
            const resumeResult = await this.client.request('thread/resume', {
                threadId,
                model: selection.model,
            }, signal);
            const serverPreset = codexPermissionPresetFromResponse(resumeResult);
            if (serverPreset !== undefined) {
                this.setPermissionSelection(sessionId, serverPreset);
            }
        }
        this.pendingRequestIds.set(sessionId, string(request.requestId) ?? '');
        const mode = request.mode === 'steer' ? 'turn/steer' : 'turn/start';
        if (mode === 'turn/steer') {
            const active = this.activeTurnId(threadId);
            if (active === undefined)
                return failure('steer-unavailable', 'The CodeX Thread has no active turn to steer.');
            await this.client.request(mode, { threadId, expectedTurnId: active, input }, signal);
        }
        else {
            await this.client.request(mode, {
                threadId,
                input,
                ...codexModelParams(selection),
            }, signal);
        }
        this.blankThreads.delete(threadId);
        return success({ accepted: true });
    }
    async attachment(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const attachmentId = requiredString(request.attachmentId, 'attachmentId');
        if (!attachmentId.startsWith(CODEX_IMAGE_ATTACHMENT_PREFIX)) {
            return failure('attachment-error', 'The CodeX image is not available in this virtual Session.', { reason: 'not-referenced' });
        }
        nativeThreadId(sessionId);
        const cached = this.imageAttachments.get(attachmentId);
        if (cached?.sessionId === sessionId)
            return success({ attachment: cached.attachment, data: cached.data });
        await this.hydrateImageAttachments(sessionId, signal);
        const hydrated = this.imageAttachments.get(attachmentId);
        return hydrated?.sessionId === sessionId
            ? success({ attachment: hydrated.attachment, data: hydrated.data })
            : failure('attachment-error', 'The CodeX image is not available in this virtual Session.', { reason: 'not-referenced' });
    }
    async hydrateImageAttachments(sessionId, signal) {
        const threadId = nativeThreadId(sessionId);
        const result = record(await this.client.request('thread/read', { threadId, includeTurns: true }, signal));
        const thread = record(result.thread);
        if (string(thread.id) !== threadId)
            throw new Error('CodeX returned an invalid Thread history.');
        this.cacheHistoryImages(projectCodexNativeHistory(thread, sessionId));
    }
    async cancel(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const threadId = nativeThreadId(sessionId);
        const turnId = this.activeTurnId(threadId);
        if (turnId !== undefined)
            await this.client.request('turn/interrupt', { threadId, turnId }, signal);
        return success({ accepted: true });
    }
    async renameSession(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const title = requiredString(request.title, 'title');
        // Codex 0.160.0 refuses an empty name upstream ("Codex App Server rejected the
        // request"), so clearing a title stays a local projection change.
        if (title.trim() !== '') {
            await this.client.request('thread/name/set', { threadId: nativeThreadId(sessionId), name: title }, signal);
        }
        const seq = this.nextProjectionSeq();
        this.updateThreadName(nativeThreadId(sessionId), title);
        this.publishProjection(sessionId, 'title', title, seq);
        await this.refreshAndPublishWorkspaces();
        return success({ title, seq });
    }
    async sessionPage(request, signal) {
        const sessionId = sessionIdFromAddress(record(request.address));
        const page = await this.readHistoryPage(nativeThreadId(sessionId), {
            beforeSeq: optionalNonNegativeInteger(request.beforeSeq),
            throughSeq: optionalInteger(request.throughSeq),
            maxMessages: optionalPositiveInteger(request.maxMessages),
        }, signal);
        return success({ records: page.records, hasMore: page.hasMore });
    }
    activeTurnId(threadId) {
        for (const follow of this.follows)
            if (follow.threadId === threadId && follow.stepOpen)
                return follow.activeTurnId;
        return undefined;
    }
    async fetchThread(threadId, signal) {
        const result = record(await this.client.request('thread/read', { threadId, includeTurns: false }, signal));
        const thread = record(result.thread);
        if (string(thread.id) !== threadId)
            throw new Error('CodeX returned an invalid Thread history.');
        return thread;
    }
    async readHistoryPage(threadId, page, signal) {
        // The Host's call policy caps a history page at CODEX_HISTORY_MAX_MESSAGES, and the
        // native UI asks for a far larger page. Forwarding that request was refused outright
        // ("dsh/sessionHistory -> maxMessages: too_big") and the session history never loaded,
        // so every read clamps to the same constant the policy uses.
        const maxMessages = page.maxMessages === undefined
            ? undefined
            : Math.min(CODEX_HISTORY_MAX_MESSAGES, Math.max(1, page.maxMessages));
        let value;
        try {
            value = record(await this.client.request('dsh/sessionHistory', {
                threadId,
                ...(page.beforeSeq === undefined ? {} : { beforeSeq: page.beforeSeq }),
                ...(page.throughSeq === undefined ? {} : { throughSeq: page.throughSeq }),
                ...(maxMessages === undefined ? {} : { maxMessages }),
            }, signal));
        }
        catch (error) {
            if (!isLegacySessionHistoryUnsupported(error))
                throw error;
            const result = record(await this.client.request('thread/read', { threadId, includeTurns: true }, signal));
            const thread = record(result.thread);
            if (string(thread.id) !== threadId)
                throw new Error('CodeX returned an invalid Thread history.');
            value = paginateCodexNativeHistory(projectCodexNativeHistory(thread, `codex:${threadId}`, this.sessionGeneration), {
                beforeSeq: page.beforeSeq,
                throughSeq: page.throughSeq,
                maxMessages,
            });
        }
        value = adaptCodexHistoryPage(value, this.sessionGeneration);
        if (!isCodexNativeHistoryPage(value, `codex:${threadId}`)) {
            throw new Error('CodeX Remote returned an invalid paginated History.');
        }
        if (Object.hasOwn(value, 'permissionPreset')) {
            const preset = value.permissionPreset;
            this.setPermissionSelection(`codex:${threadId}`, preset === 'workspace-write' || preset === 'danger-full-access' ? preset : undefined);
        }
        this.cacheHistoryImages(value);
        return value;
    }
    cacheHistoryImages(history) {
        const sessionId = history.header.id;
        const entries = 'entries' in history ? history.entries : history.records;
        for (const entry of entries)
            this.cacheImageBlocks(sessionId, entry.event.data);
    }
    async sessionHistory(request, signal) {
        const sessionId = requiredString(request.sessionId, 'sessionId');
        const beforeSeq = optionalNonNegativeInteger(request.beforeSeq);
        const history = await this.readHistoryPage(nativeThreadId(sessionId), {
            beforeSeq,
            maxMessages: optionalPositiveInteger(request.maxMessages),
        }, signal);
        const directory = await this.models(signal).catch(() => undefined);
        // rc.2 has one global mux rather than the alpha session/follow stream. A
        // history read is the reliable signal that the native client has opened a
        // Session, so attach the CodeX live stream here before returning its tail.
        if (beforeSeq === undefined)
            await this.ensureRcFollow(sessionId, history);
        return success({
            events: history.records.map(entry => ({
                event: entry.event,
                ...(entry.view === undefined ? {} : { view: entry.view }),
            })),
            hasMore: history.hasMore,
            ...(beforeSeq !== undefined ? {} : {
                projections: {
                    // Same discriminated union as the other projection sites.
                    kind: 'sequenced',
                    asOfSeq: history.cursor,
                    values: {
                        title: this.sessionTitle(sessionId),
                        sessionListMetadata: { blank: history.cursor < 0, lastPromptAt: null },
                        modelSelection: modelSelectionProjection(this.modelSelection(sessionId, directory)),
                        permissions: codexPermissionsProjection(this.permissionSelection(sessionId)),
                        imageLimits: codexImageLimitsProjection(),
                    },
                },
            }),
        });
    }
    async ensureRcFollow(sessionId, seedHistory) {
        if (this.followsHas(sessionId))
            return;
        const threadId = nativeThreadId(sessionId);
        // The Host bounds CodeX streams per connection. rc.2 only renders one
        // active conversation, so release older rc-only follows when navigation
        // moves to another Session. Alpha follows own their lifecycle separately.
        const stale = [...this.follows].filter(follow => follow.rcOnly && follow.sessionId !== sessionId);
        for (const follow of stale) {
            this.follows.delete(follow);
            follow.queue.close();
            await follow.close?.().catch(() => undefined);
        }
        const history = seedHistory ?? await this.readHistoryPage(threadId, {}, undefined);
        const controller = new AbortController();
        const follow = {
            sessionId,
            threadId,
            queue: new AsyncValueQueue(controller.signal),
            nextSeq: history.cursor + 1,
            turn: history.nextTurn,
            stepOpen: history.activeTurnId !== undefined,
            startedItems: new Set(),
            completedItems: new Set(),
            streamedBlocks: new Map(),
            nextBlockIndex: 0,
            streamActive: false,
            assistantStreamRevision: 0,
            liveItems: new Map(),
            liveToolOutput: new Map(),
            liveToolResultSeq: new Map(),
            pendingToolImages: [],
            requestId: this.pendingRequestIds.get(sessionId),
            rcOnly: true,
            ...(history.activeTurnId === undefined ? {} : { activeTurnId: history.activeTurnId }),
        };
        this.follows.add(follow);
        try {
            const stream = await this.client.subscribe(threadId, frame => this.acceptCodexFrame(follow, frame), controller.signal, () => this.closeFollowAfterRemoteStreamClosed(follow));
            follow.close = async () => {
                controller.abort();
                await stream.close();
            };
        }
        catch (error) {
            this.follows.delete(follow);
            controller.abort();
            follow.queue.close();
            throw error;
        }
    }
    followsHas(sessionId) {
        for (const follow of this.follows)
            if (follow.sessionId === sessionId)
                return true;
        return false;
    }
    closeFollowAfterRemoteStreamClosed(follow) {
        this.follows.delete(follow);
        follow.queue.close();
        this.emitRemoteEvent('api-session/status', [follow.sessionId, false]);
    }
    async refreshAndPublishWorkspaces() {
        const catalog = await this.refreshCatalog().catch(() => undefined);
        if (catalog !== undefined)
            this.publishWorkspaceBaseline(catalog);
    }
    publishWorkspaceBaseline(catalog) {
        for (const queue of this.workspaceStreams) {
            for (const workspace of nativeVisibleWorkspaces(catalog, this.selectedWorkspaceId)) {
                queue.push({ type: 'upsert', workspace: nativeWorkspace(workspace) });
            }
            queue.push({ type: 'archived', archivedSessionIds: catalog.sessions.filter(item => item.archived).map(item => item.id) });
        }
    }
    createApiProxy() {
        const call = (endpoint) => async (request, signal) => {
            const payload = endpoint === 'session.prompt' && isRecord(request.payload)
                ? { ...request.payload, requestId: String(request.rpcId) }
                : request.payload;
            const result = await this.dispatch(rcEndpoint(endpoint), { args: { request: payload } }, signal ?? new AbortController().signal);
            return {
                rpcId: request.rpcId,
                result: (result.ok
                    ? success(result.value)
                    : failure(result.error.code, result.error.message, result.error.details)),
            };
        };
        return {
            sessions: {
                list: call('session.list'),
                search: call('session.search'),
                create: call('session.create'),
                history: call('session.history'),
                models: call('session.models'),
                selectModel: call('session.selectModel'),
                rename: call('session.rename'),
                fork: call('session.fork'),
                prompt: call('session.prompt'),
                attachment: call('session.attachment'),
                updateQueue: call('session.updateQueue'),
                cancel: call('session.cancel'),
            },
            workspace: {
                list: call('workspace.list'),
                create: call('workspace.create'),
                rename: call('workspace.rename'),
                delete: call('workspace.delete'),
                insertBefore: call('workspace.insertBefore'),
                insertSessionBefore: call('workspace.insertSessionBefore'),
                archiveSession: call('workspace.archiveSession'),
            },
            subagents: {},
            host: {
                describe: call('host.describe'),
                listDirectory: call('host.listDirectory'),
            },
            skills: { list: call('skills.list') },
            agentPresets: {},
            goals: {},
            settings: {},
            credentials: {},
            llm: {},
            events: {
                mux: ((request, signal) => this.rcMux(request, signal)),
                host: ((request, signal) => this.rcHost(request, signal)),
            },
            downloads: {},
            respond: async (message) => {
                const pending = this.pendingApprovals.get(String(message.rpcId));
                if (pending === undefined)
                    return { accepted: false, reason: 'not-pending' };
                const result = message.result;
                const outcome = result.ok ? result.value : undefined;
                const decision = outcome === 'allowed-once' ? 'accept' : outcome === 'cancelled' ? 'cancel' : 'decline';
                await this.client.respond(pending.requestHandle, decision);
                this.pendingApprovals.delete(pending.requestHandle);
                return { accepted: true };
            },
        };
    }
    async *rcMux(request, signal) {
        const queue = new AsyncValueQueue(signal);
        this.rcMuxStreams.add(queue);
        const catalog = await this.currentCatalog(signal);
        for (const session of catalog.sessions)
            queue.push({
                rpcId: `${String(request.rpcId)}:${session.id}:subscribed`,
                payload: { type: 'session/subscribed', sessionId: session.id, lastSeq: -1 },
            });
        try {
            yield* queue;
        }
        finally {
            this.rcMuxStreams.delete(queue);
            queue.close();
        }
    }
    async *rcHost(_request, signal) {
        const queue = new AsyncValueQueue(signal);
        this.rcHostStreams.add(queue);
        try {
            yield* queue;
        }
        finally {
            this.rcHostStreams.delete(queue);
            queue.close();
        }
    }
    broadcastRcMux(payload, rpcId = `codex-mux:${Date.now()}:${Math.random()}`) {
        for (const queue of this.rcMuxStreams)
            queue.push({ rpcId, payload });
    }
    broadcastRcHost(payload) {
        const frame = { rpcId: `codex-host:${Date.now()}:${Math.random()}`, payload };
        for (const queue of this.rcHostStreams)
            queue.push(frame);
    }
}
async function loadCatalog(client, signal, pendingThreads) {
    let projects = await loadCodexProjects(client, signal);
    const threads = [];
    let cursor;
    for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
        const result = record(await client.request('thread/list', {
            limit: CODEX_PAGE_LIMIT,
            sortKey: 'updated_at',
            sortDirection: 'desc',
            archived: false,
            ...(cursor === undefined ? {} : { cursor }),
        }, signal));
        for (const value of array(result.data)) {
            const thread = record(value);
            if (string(thread.id) !== undefined)
                threads.push(thread);
        }
        cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
        if (cursor === undefined)
            break;
    }
    if (pendingThreads !== undefined) {
        const listedIds = new Set(threads.map(thread => string(thread.id)).filter((id) => id !== undefined));
        for (const [threadId, thread] of pendingThreads) {
            if (listedIds.has(threadId))
                pendingThreads.delete(threadId);
            else
                threads.unshift(thread);
        }
    }
    if (projects.length === 0) {
        projects = deriveCodexCwdWorkspaces(threads).map(workspace => ({
            id: workspace.id,
            name: workspace.name,
            roots: [{ path: workspace.path }],
            position: workspace.position,
            createdAt: workspace.createdAt,
            updatedAt: workspace.updatedAt,
        }));
    }
    const projected = threads.map(thread => {
        const session = projectCodexThread(thread);
        return session === undefined ? undefined : { thread, session };
    }).filter((value) => value !== undefined);
    const projectWorkspaces = projects.map(projectWorkspace);
    const workspaceByProjectId = new Map(projects.map((project, index) => [project.id, projectWorkspaces[index]]));
    const visibleSessionIds = new Set();
    const visibleThreads = [];
    for (const { thread, session } of projected) {
        const projectWorkspaceForSession = findProjectWorkspace(thread, session, projects, workspaceByProjectId);
        if (projectWorkspaceForSession !== undefined) {
            addSessionToWorkspace(projectWorkspaceForSession, session);
            visibleSessionIds.add(session.id);
            visibleThreads.push(thread);
        }
    }
    const sessions = projected
        .map(value => value.session)
        .filter(session => visibleSessionIds.has(session.id));
    return {
        threads: visibleThreads,
        sessions,
        projects,
        workspaces: projectWorkspaces,
    };
}
async function loadCodexProjects(client, signal) {
    const projects = [];
    let cursor;
    try {
        for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
            const result = record(await client.request('project/list', {
                limit: CODEX_PAGE_LIMIT,
                ...(cursor === undefined ? {} : { cursor }),
            }, signal));
            for (const value of array(result.data)) {
                const project = projectCodexProject(value, projects.length);
                if (project !== undefined && !projects.some(item => item.id === project.id))
                    projects.push(project);
            }
            cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
            if (cursor === undefined)
                break;
        }
    }
    catch (error) {
        if (isProjectListUnsupported(error))
            return [];
        throw error;
    }
    return projects.sort((left, right) => left.position - right.position || left.name.localeCompare(right.name) || left.id.localeCompare(right.id));
}
async function loadModelDirectory(client, signal) {
    const models = new Map();
    let defaultModelId;
    let cursor;
    for (let page = 0; page < MAX_CODEX_PAGES; page += 1) {
        const result = record(await client.request('model/list', {
            limit: CODEX_PAGE_LIMIT,
            includeHidden: false,
            ...(cursor === undefined ? {} : { cursor }),
        }, signal));
        for (const value of array(result.data)) {
            const source = record(value);
            if (source.hidden === true)
                continue;
            const id = string(source.model) ?? string(source.id);
            if (id === undefined || models.has(id))
                continue;
            const efforts = array(source.supportedReasoningEfforts).map(value => {
                const effort = record(value);
                const effortId = string(effort.reasoningEffort);
                if (effortId === undefined)
                    return undefined;
                const description = string(effort.description);
                return {
                    id: effortId,
                    name: reasoningEffortName(effortId),
                    ...(description === undefined ? {} : { description }),
                };
            }).filter((value) => value !== undefined);
            const defaultEffort = string(source.defaultReasoningEffort);
            const description = string(source.description);
            const model = {
                id,
                name: string(source.displayName) ?? id,
                ...(description === undefined ? {} : { description }),
                ...(efforts.length === 0 ? {} : {
                    reasoning: {
                        efforts,
                        ...(defaultEffort === undefined || !efforts.some(effort => effort.id === defaultEffort)
                            ? {}
                            : { defaultEffort }),
                    },
                }),
            };
            models.set(id, model);
            if (source.isDefault === true)
                defaultModelId = id;
        }
        cursor = typeof result.nextCursor === 'string' && result.nextCursor.length > 0 ? result.nextCursor : undefined;
        if (cursor === undefined)
            break;
    }
    const defaultModel = models.get(defaultModelId ?? '') ?? models.values().next().value;
    if (defaultModel === undefined)
        throw new Error('CodeX did not advertise any available models.');
    const defaultEffort = defaultModel.reasoning?.defaultEffort;
    return {
        default: {
            provider: CODEX_PROVIDER,
            model: defaultModel.id,
            ...(defaultEffort === undefined ? {} : { reasoningEffort: defaultEffort }),
        },
        groups: [{ id: CODEX_PROVIDER, name: 'CodeX', models: [...models.values()] }],
        models,
    };
}
export function projectCodexNativeHistory(thread, sessionId, sessionGeneration = 'legacy') {
    const entries = [];
    let seq = 0;
    let turnNumber = 0;
    const active = activeTurnId(thread.turns);
    const append = (type, data, time = Date.now(), view, sourceEventSeqs) => {
        const eventSeq = seq++;
        entries.push({
            type: 'event',
            event: {
                type,
                seq: eventSeq,
                time,
                data: adaptEventData(type, data, sessionGeneration),
                ...(sourceEventSeqs === undefined ? {} : { sourceEventSeqs }),
                ...(isSurfaceEvent(type) ? { surfaceOp: 'append' } : {}),
            },
            ...(view === undefined ? {} : { view }),
        });
        return eventSeq;
    };
    for (const rawTurn of array(thread.turns)) {
        const turn = record(rawTurn);
        const turnItems = array(turn.items).map(record);
        const toolImages = turnItems.flatMap(toolOutputImageBlocks);
        let imageOwner = -1;
        for (let index = 0; index < turnItems.length; index += 1) {
            if (turnItems[index].type === 'agentMessage')
                imageOwner = index;
        }
        turnNumber += 1;
        const turnActive = isActiveCodexTurn(turn);
        const time = normalizeTime(turn.createdAt) || normalizeTime(turn.startedAt) || normalizeTime(thread.createdAt) || Date.now();
        append('turn/start', { turn: turnNumber }, time);
        append('step/start', { turn: turnNumber, step: 1 }, time);
        for (let itemIndex = 0; itemIndex < turnItems.length; itemIndex += 1) {
            const item = turnItems[itemIndex];
            let toolCallSeq;
            for (const event of itemEvents(item, turnNumber, 1, undefined, modelSelection(), sessionId, itemIndex === imageOwner ? toolImages : [])) {
                const eventSeq = append(event.type, event.data, normalizeTime(item.createdAt) || time, event.view, event.type === 'tool/result' && toolCallSeq !== undefined ? [toolCallSeq] : undefined);
                if (event.type === 'tool/call')
                    toolCallSeq = eventSeq;
            }
        }
        if (turnActive)
            continue;
        append('step/end', { turn: turnNumber, step: 1 }, normalizeTime(turn.updatedAt) || normalizeTime(turn.completedAt) || time);
        append('turn/end', {
            turn: turnNumber,
            reason: turn.status === 'failed' || turn.error !== undefined && turn.error !== null
                ? { kind: 'error', error: { message: 'CodeX turn failed.', code: 'codex-turn-failed' } }
                : { kind: 'completed' },
        }, normalizeTime(turn.updatedAt) || normalizeTime(turn.completedAt) || time);
    }
    const projected = projectCodexThread(thread);
    return {
        header: {
            version: sessionGeneration === 'v3' ? 3 : 1,
            id: sessionId,
            createdAt: projected?.createdAt ?? Date.now(),
            ...(projected?.cwd === undefined ? {} : { cwd: projected.cwd }),
            ...(sessionGeneration === 'v3' ? { isSeeded: false } : {}),
        },
        entries,
        lastSeq: seq - 1,
        nextTurn: turnNumber,
        ...(active === undefined ? {} : { activeTurnId: active }),
    };
}
export function paginateCodexNativeHistory(history, request) {
    const throughSeq = Math.min(request.throughSeq ?? history.lastSeq, history.lastSeq);
    const endSeq = Math.min(throughSeq, request.beforeSeq === undefined ? throughSeq : request.beforeSeq - 1);
    const window = endSeq < 0 ? [] : history.entries.filter(entry => entry.event.seq <= endSeq);
    const maxMessages = request.maxMessages ?? DEFAULT_HISTORY_MESSAGES;
    let messages = 0;
    let cut = 0;
    for (let index = window.length - 1; index >= 0; index -= 1) {
        const event = window[index].event;
        if (!isSurfaceEvent(event.type) || event.surfaceOp !== 'append')
            continue;
        messages += 1;
        if (messages >= maxMessages) {
            cut = Math.min(event.seq, ...(event.sourceEventSeqs ?? []));
            break;
        }
    }
    return {
        header: history.header,
        cursor: history.lastSeq,
        nextTurn: history.nextTurn,
        records: window.filter(entry => entry.event.seq >= cut),
        hasMore: window.some(entry => entry.event.seq < cut),
        ...(history.activeTurnId === undefined ? {} : { activeTurnId: history.activeTurnId }),
    };
}
function adaptEventData(type, data, sessionGeneration) {
    if (sessionGeneration !== 'v3' || type !== 'assistant/message')
        return data;
    const value = record(data);
    return Array.isArray(value.stream) ? value : { ...value, stream: [] };
}
function adaptCodexHistoryPage(value, sessionGeneration) {
    if (sessionGeneration !== 'v3')
        return value;
    const page = record(value);
    const sourceHeader = record(page.header);
    const { seedLength: _seedLength, ...header } = sourceHeader;
    const records = Array.isArray(page.records)
        ? page.records.map(raw => {
            const entry = record(raw);
            const sourceEvent = record(entry.event);
            const sourceSurfaceOp = record(sourceEvent.surfaceOp);
            const surfaceOp = sourceEvent.surfaceOp === 'append'
                ? 'append'
                : sourceSurfaceOp.op === 'replace'
                    && integer(sourceSurfaceOp.startSeq) !== undefined
                    && integer(sourceSurfaceOp.endSeq) !== undefined
                    ? sourceSurfaceOp
                    : sourceSurfaceOp.op === 'replace'
                        && integer(sourceSurfaceOp.start) !== undefined
                        && integer(sourceSurfaceOp.end) !== undefined
                        ? {
                            op: 'replace',
                            startSeq: sourceSurfaceOp.start,
                            endSeq: sourceSurfaceOp.end,
                        }
                        : sourceEvent.surfaceOp;
            return {
                ...entry,
                event: {
                    ...sourceEvent,
                    data: adaptEventData(string(sourceEvent.type) ?? '', sourceEvent.data, sessionGeneration),
                    ...(surfaceOp === undefined ? {} : { surfaceOp }),
                },
            };
        })
        : page.records;
    return {
        ...page,
        header: { ...header, version: 3, isSeeded: sourceHeader.isSeeded === true },
        records,
    };
}
function itemEvents(item, turn, step, requestId, selection = modelSelection(), sessionId = CODEX_SESSION_PREFIX, supplementalImages = []) {
    const type = string(item.type);
    const id = string(item.id) ?? `${turn}:${step}:${hashString(JSON.stringify(item))}`;
    const text = itemText(item);
    if (type === 'userMessage')
        return [{
                type: 'user/message',
                data: {
                    id,
                    role: 'user',
                    content: messageContent(item, `${sessionId}:${id}`, 'text', text),
                    source: requestId === undefined || requestId === '' ? { kind: 'user' } : { kind: 'user', rpcId: requestId },
                },
            }];
    if (type === 'agentMessage' || type === 'reasoning' || type === 'plan')
        return [{
                type: 'assistant/message',
                data: {
                    turn,
                    step,
                    message: {
                        id,
                        role: 'assistant',
                        content: messageContent(item, `${sessionId}:${id}`, type === 'reasoning' ? 'reasoning' : 'text', text, type === 'agentMessage' ? supplementalImages : []),
                        source: { kind: 'model', provider: selection.provider, model: selection.model },
                    },
                },
            }];
    if (type !== undefined && isToolItemType(type)) {
        const name = toolEventName(type);
        const args = toolArguments(item);
        return [
            {
                type: 'tool/call',
                data: { turn, step, callId: id, name, arguments: JSON.stringify(args) },
                view: { for: 'call', view: toolCallView(item, type, args) },
            },
            toolResultEvent(item, turn, step, id, toolResultText(item, type, text)),
        ];
    }
    if (type === 'error')
        return [{
                type: 'assistant/message',
                data: {
                    turn,
                    step,
                    message: {
                        id,
                        role: 'assistant',
                        content: [{ type: 'text', text: text ?? 'CodeX reported an error.' }],
                        source: { kind: 'model', provider: selection.provider, model: selection.model },
                    },
                },
            }];
    return [];
}
function messageContent(item, attachmentSeed, fallbackType, fallbackText, supplementalImages = []) {
    const source = item.content ?? item.input;
    const blocks = contentBlocks(source, attachmentSeed);
    const images = contentBlocks(supplementalImages, `${attachmentSeed}:tool-output`);
    if (blocks.length > 0 || images.length > 0) {
        return [...(blocks.length > 0 ? blocks : [{ type: fallbackType, text: fallbackText ?? '' }]), ...images];
    }
    return [{ type: fallbackType, text: fallbackText ?? '' }];
}
function toolResultEvent(item, turn, step, id, resultText) {
    const type = string(item.type) ?? 'unknown';
    return {
        type: 'tool/result',
        data: {
            turn,
            step,
            message: {
                id: `${id}:result`,
                role: 'user',
                content: [{
                        type: 'tool-result',
                        toolCallId: id,
                        content: [{ type: 'text', text: resultText }],
                        ...(item.status === 'failed' || item.success === false ? { isError: true } : {}),
                    }],
                source: { kind: 'tool', callId: id },
            },
        },
        view: { for: 'result', view: toolResultView(item, type, resultText) },
    };
}
function toolResultContentReplacement(event) {
    const data = record(event.data);
    const message = record(data.message);
    const content = array(message.content);
    const result = record(content[0]);
    if (result.isError === undefined)
        return event;
    const { isError: _isError, ...stableResult } = result;
    return {
        ...event,
        data: {
            ...data,
            message: { ...message, content: [stableResult] },
        },
    };
}
function isToolItemType(type) {
    return type === 'commandExecution'
        || type === 'mcpToolCall'
        || type === 'dynamicToolCall'
        || type === 'fileChange'
        || type === 'functionCallOutput'
        || type === 'hookPrompt'
        || type === 'collabAgentToolCall'
        || type === 'subAgentActivity'
        || type === 'webSearch'
        || type === 'imageView'
        || type === 'imageGeneration'
        || type === 'sleep'
        || type === 'enteredReviewMode'
        || type === 'exitedReviewMode'
        || type === 'contextCompaction';
}
function toolEventName(type) {
    if (type === 'fileChange')
        return 'codex.fileChange';
    if (type === 'commandExecution')
        return 'codex.command';
    if (type === 'webSearch')
        return 'codex.webSearch';
    if (type === 'collabAgentToolCall' || type === 'subAgentActivity')
        return 'codex.subagent';
    if (type === 'imageView' || type === 'imageGeneration')
        return 'codex.image';
    if (type === 'enteredReviewMode' || type === 'exitedReviewMode')
        return 'codex.reviewMode';
    if (type === 'contextCompaction')
        return 'codex.compaction';
    return `codex.${type}`;
}
function toolResultText(item, type, text) {
    if (type === 'fileChange')
        return fileChangeSummary(item);
    if (type === 'webSearch') {
        const count = array(item.results).length;
        return count === 0 ? 'Web search completed.' : `Web search returned ${count} result${count === 1 ? '' : 's'}.`;
    }
    if (type === 'imageView')
        return `Viewed image: ${string(item.path) ?? 'image'}`;
    if (type === 'imageGeneration') {
        const savedPath = string(item.savedPath);
        return savedPath === undefined ? `Image generation ${string(item.status) ?? 'completed'}.` : `Generated image: ${savedPath}`;
    }
    if (type === 'contextCompaction')
        return 'CodeX compacted the conversation context.';
    if (type === 'enteredReviewMode')
        return `Entered review mode${string(item.review) ? `: ${string(item.review)}` : '.'}`;
    if (type === 'exitedReviewMode')
        return `Exited review mode${string(item.review) ? `: ${string(item.review)}` : '.'}`;
    if (type === 'sleep')
        return `Waited ${integer(item.durationMs) ?? 0} ms.`;
    if (type === 'subAgentActivity') {
        return [string(record(item.kind).type) ?? string(item.kind) ?? 'Subagent activity', string(item.agentPath)]
            .filter((value) => value !== undefined).join(': ');
    }
    return text ?? itemText(record(item.result)) ?? itemText(record(item.output)) ?? string(item.status) ?? type;
}
function isSurfaceEvent(type) {
    return type === 'user/message' || type === 'assistant/message' || type === 'tool/result';
}
function toolCallView(item, type, args) {
    if (type === 'commandExecution') {
        return {
            card: 'terminal',
            title: commandText(item.command) ?? 'CodeX command',
            ...(typeof item.cwd === 'string' ? { cwd: item.cwd } : {}),
        };
    }
    const locations = fileLocations(item);
    if (type === 'fileChange') {
        return {
            card: 'generic',
            title: locations.length === 0 ? 'CodeX file changes' : `Modify ${locations.length} file${locations.length === 1 ? '' : 's'}`,
            kind: 'edit',
            rawInput: args,
            ...(locations.length === 0 ? {} : { locations }),
        };
    }
    if (type === 'webSearch') {
        return {
            card: 'generic',
            title: string(item.query) ?? 'CodeX web search',
            kind: 'search',
            rawInput: args,
        };
    }
    if (type === 'imageView' || type === 'imageGeneration') {
        const path = string(item.path) ?? string(item.savedPath);
        return {
            card: 'generic',
            title: type === 'imageView' ? 'View image' : 'Generate image',
            kind: type === 'imageView' ? 'read' : 'other',
            ...(path === undefined ? {} : { locations: [{ path }] }),
        };
    }
    if (type === 'collabAgentToolCall' || type === 'subAgentActivity') {
        return {
            card: 'generic',
            title: type === 'collabAgentToolCall'
                ? `Subagent: ${string(item.tool) ?? 'collaboration'}`
                : `Subagent activity: ${string(item.agentPath) ?? string(item.agentThreadId) ?? 'agent'}`,
            kind: 'other',
            rawInput: args,
        };
    }
    if (type === 'contextCompaction')
        return { card: 'generic', title: 'Compact conversation context', kind: 'other' };
    if (type === 'enteredReviewMode')
        return { card: 'generic', title: 'Enter review mode', kind: 'other' };
    if (type === 'exitedReviewMode')
        return { card: 'generic', title: 'Exit review mode', kind: 'other' };
    return {
        card: 'generic',
        title: toolDisplayName(item, type),
        kind: 'other',
    };
}
function toolResultView(item, type, resultText) {
    if (type === 'commandExecution') {
        return {
            card: 'terminal',
            output: resultText,
            ...(typeof item.exitCode === 'number' && Number.isSafeInteger(item.exitCode) ? { exitCode: item.exitCode } : {}),
        };
    }
    return {
        card: 'generic',
        ...(type === 'fileChange' ? { title: item.status === 'inProgress' ? 'CodeX file changes' : 'CodeX file changes completed' } : {}),
        ...(type === 'mcpToolCall' && item.status === 'inProgress'
            ? { title: 'CodeX MCP tool in progress', content: [{ type: 'text', text: resultText }] }
            : {}),
    };
}
function toolDisplayName(item, type) {
    return string(item.name)
        ?? string(item.tool)
        ?? string(record(item.tool).name)
        ?? (type === 'mcpToolCall' ? 'CodeX MCP tool' : humanizeItemType(type));
}
function fileLocations(item) {
    return array(item.changes)
        .map(value => string(record(value).path))
        .filter((path) => path !== undefined && path.length > 0)
        .map(path => ({ path }));
}
function nativeWorkspace(view) {
    const { sessionCount: _sessionCount, ...workspace } = view;
    return workspace;
}
function nativeVisibleWorkspaces(catalog, selectedWorkspaceId) {
    return catalog.workspaces.filter(workspace => workspace.sessionIds.length > 0 || workspace.workspaceId === selectedWorkspaceId);
}
function projectWorkspace(project) {
    const root = project.roots[0];
    return {
        workspaceId: codexProjectWorkspaceId(project.id),
        path: root.path,
        title: project.name.trim() || basename(root.path),
        sessionIds: [],
        sessionCount: 0,
        createdAt: isoTime(project.createdAt),
        updatedAt: isoTime(project.updatedAt || project.createdAt),
    };
}
function projectCodexProject(value, fallbackPosition) {
    const source = record(value);
    const id = string(source.id);
    if (id === undefined)
        return undefined;
    const roots = array(source.roots)
        .map(root => string(record(root).path))
        .filter((path) => path !== undefined && path.length > 0)
        .map(path => ({ path }));
    if (roots.length === 0)
        return undefined;
    return {
        id,
        name: string(source.name) ?? basename(roots[0].path),
        roots,
        position: finiteNumber(source.position) ?? fallbackPosition,
        createdAt: normalizeTime(source.createdAt),
        updatedAt: normalizeTime(source.updatedAt),
    };
}
function findProjectWorkspace(thread, session, projects, workspaceByProjectId) {
    const explicitProjectId = string(thread.projectId);
    const explicitWorkspace = explicitProjectId === undefined ? undefined : workspaceByProjectId.get(explicitProjectId);
    if (explicitWorkspace !== undefined)
        return explicitWorkspace;
    if (session.cwd === undefined)
        return undefined;
    const match = projects
        .flatMap(project => project.roots.map(root => ({ project, root })))
        .filter(value => containsPath(value.root.path, session.cwd))
        .sort((left, right) => {
        const length = right.root.path.length - left.root.path.length;
        if (length !== 0)
            return length;
        const position = left.project.position - right.project.position;
        if (position !== 0)
            return position;
        return left.project.id.localeCompare(right.project.id);
    })[0];
    return match === undefined ? undefined : workspaceByProjectId.get(match.project.id);
}
function addSessionToWorkspace(workspace, session) {
    if (!workspace.sessionIds.includes(session.id)) {
        workspace.sessionIds.push(session.id);
        workspace.sessionCount = workspace.sessionIds.length;
    }
    const createdAt = isoTime(session.createdAt);
    const updatedAt = isoTime(session.updatedAt || session.createdAt);
    if (createdAt < workspace.createdAt)
        workspace.createdAt = createdAt;
    if (updatedAt > workspace.updatedAt)
        workspace.updatedAt = updatedAt;
}
function containsPath(root, candidate) {
    const normalizedRoot = normalizePathForCompare(root);
    const normalizedCandidate = normalizePathForCompare(candidate);
    return normalizedCandidate === normalizedRoot
        || normalizedCandidate.startsWith(`${normalizedRoot}/`)
        || normalizedCandidate.startsWith(`${normalizedRoot}\\`);
}
function normalizePathForCompare(path) {
    return path.replace(/[\\/]+$/u, '') || path;
}
function modelSelection() {
    return { provider: CODEX_PROVIDER, model: CODEX_MODEL };
}
function codexPromptInput(content) {
    if (content.length === 0 || content.length > MAX_CODEX_PROMPT_PARTS)
        return undefined;
    const input = [];
    for (const value of content) {
        if (!isRecord(value))
            return undefined;
        if (value.type === 'text') {
            if (typeof value.text !== 'string' || value.text.length === 0 || value.text.length > MAX_CODEX_PROMPT_TEXT)
                return undefined;
            input.push({ type: 'text', text: value.text });
            continue;
        }
        if (value.type === 'image') {
            if (typeof value.mediaType !== 'string' || !CODEX_IMAGE_MEDIA_TYPES.has(value.mediaType)
                || typeof value.data !== 'string' || value.data.length > MAX_CODEX_IMAGE_BASE64
                || !isCanonicalBase64(value.data))
                return undefined;
            input.push({ type: 'image', mediaType: value.mediaType, data: value.data });
            continue;
        }
        return undefined;
    }
    return input;
}
function modelSelectionProjection(selection) {
    return { lastUsed: selection, next: selection };
}
function codexPermissionsProjection(preset) {
    return {
        options: [
            {
                value: 'workspace-write',
                name: 'workspace-write',
                description: 'Write inside the workspace; wider command and file access requires one-time approval.',
            },
            {
                value: 'danger-full-access',
                name: 'danger-full-access',
                description: 'Full Host file access without approval prompts for subsequent turns in this virtual Session.',
            },
        ],
        currentValue: preset ?? 'Host settings (not reported)',
    };
}
function codexImageLimitsProjection() {
    return {
        maxImageBytes: 20 * 1024 * 1024,
        maxImagesPerMessage: MAX_CODEX_PROMPT_PARTS,
        maxMessageImageBytes: 100 * 1024 * 1024,
        maxImagePixels: 40_000_000,
        maxImageDimension: 8_192,
        mediaTypes: [...CODEX_IMAGE_MEDIA_TYPES],
    };
}
function isCodexPermissionPreset(value) {
    return value === 'workspace-write' || value === 'danger-full-access';
}
function modelCatalog(directory) {
    return {
        default: directory.default,
        routableProviders: [CODEX_PROVIDER],
        groups: directory.groups,
        failures: [],
    };
}
function codexModelParams(selection) {
    return {
        model: selection.model,
        ...(selection.reasoningEffort === undefined ? {} : { effort: selection.reasoningEffort }),
    };
}
function reasoningEffortName(effort) {
    const names = {
        none: 'None',
        minimal: 'Minimal',
        low: 'Low',
        medium: 'Medium',
        high: 'High',
        xhigh: 'Extra High',
        max: 'Max',
        ultra: 'Ultra',
    };
    return names[effort] ?? effort;
}
function displayTitle(session) {
    const value = session.title ?? session.preview;
    return value === undefined || value.trim() === '' ? null : value.slice(0, 256);
}
function itemText(value) {
    if (typeof value.text === 'string')
        return value.text;
    if (typeof value.content === 'string')
        return value.content;
    const content = Array.isArray(value.content)
        ? value.content
        : Array.isArray(value.input) ? value.input : undefined;
    if (content !== undefined) {
        const text = content.map(part => {
            if (typeof part === 'string')
                return part;
            const block = record(part);
            return string(block.text) ?? string(block.content) ?? '';
        }).filter(Boolean).join('\n');
        if (text !== '')
            return text;
    }
    if (Array.isArray(value.summary))
        return value.summary.filter(item => typeof item === 'string').join('\n');
    if (typeof value.output === 'string')
        return value.output;
    if (typeof value.aggregatedOutput === 'string')
        return value.aggregatedOutput;
    return undefined;
}
function toolArguments(item) {
    if (item.type === 'commandExecution')
        return { command: commandText(item.command) ?? item.command ?? '' };
    if (item.type === 'fileChange')
        return {
            changes: array(item.changes).map(value => {
                const change = record(value);
                const kind = typeof change.kind === 'string' ? change.kind : string(record(change.kind).type);
                return {
                    ...(typeof change.path === 'string' ? { path: change.path } : {}),
                    ...(kind === undefined ? {} : { kind }),
                };
            }),
        };
    if (item.type === 'webSearch')
        return { query: string(item.query) ?? '' };
    if (item.type === 'imageView')
        return { path: string(item.path) ?? '' };
    if (item.type === 'imageGeneration')
        return {
            ...(string(item.revisedPrompt) === undefined ? {} : { prompt: string(item.revisedPrompt) }),
            transparentBackground: item.transparentBackground === true,
        };
    if (item.type === 'collabAgentToolCall')
        return {
            tool: string(item.tool) ?? 'collaboration',
            receiverThreadIds: array(item.receiverThreadIds).filter(value => typeof value === 'string'),
            ...(string(item.model) === undefined ? {} : { model: string(item.model) }),
        };
    if (item.type === 'subAgentActivity')
        return {
            agentThreadId: string(item.agentThreadId) ?? '',
            agentPath: string(item.agentPath) ?? '',
            kind: string(record(item.kind).type) ?? string(item.kind) ?? 'activity',
        };
    if (item.type === 'sleep')
        return { durationMs: integer(item.durationMs) ?? 0 };
    if (item.type === 'enteredReviewMode' || item.type === 'exitedReviewMode')
        return {
            review: string(item.review) ?? '',
        };
    if (item.type === 'contextCompaction')
        return {};
    return { name: item.name ?? item.tool ?? item.type ?? 'tool', arguments: item.arguments ?? item.input ?? {} };
}
function fileChangeSummary(item) {
    const changes = array(item.changes).map(value => {
        const change = record(value);
        const path = string(change.path);
        const kind = typeof change.kind === 'string' ? change.kind : string(record(change.kind).type);
        return [kind, path].filter((part) => part !== undefined && part.length > 0).join(' ');
    }).filter(Boolean);
    return changes.length === 0 ? 'File changes completed.' : changes.join('\n');
}
function sanitizedFileChanges(value) {
    return array(value).flatMap(raw => {
        const change = record(raw);
        const path = string(change.path);
        if (path === undefined)
            return [];
        const kind = typeof change.kind === 'string' ? change.kind : string(record(change.kind).type);
        return [{ path, ...(kind === undefined ? {} : { kind }) }];
    });
}
function appendBoundedText(current, delta) {
    const value = `${current}${delta}`;
    if (value.length <= MAX_LIVE_TOOL_OUTPUT)
        return value;
    return `… earlier output omitted …\n${value.slice(value.length - MAX_LIVE_TOOL_OUTPUT)}`;
}
function humanizeItemType(type) {
    const names = {
        functionCallOutput: 'CodeX function output',
        hookPrompt: 'CodeX hook prompt',
        dynamicToolCall: 'CodeX tool',
        collabAgentToolCall: 'CodeX subagent',
        subAgentActivity: 'CodeX subagent activity',
        webSearch: 'CodeX web search',
        imageView: 'CodeX image view',
        imageGeneration: 'CodeX image generation',
        sleep: 'CodeX wait',
        enteredReviewMode: 'CodeX review mode',
        exitedReviewMode: 'CodeX review mode',
        contextCompaction: 'CodeX context compaction',
    };
    return names[type] ?? 'CodeX tool';
}
function commandText(value) {
    if (typeof value === 'string')
        return value;
    if (Array.isArray(value))
        return value.filter(item => typeof item === 'string').join(' ');
    const source = record(value);
    return string(source.command) ?? string(source.text);
}
function sessionIdFromAddress(address) {
    if (address.kind === 'session')
        return requiredString(address.sessionId, 'sessionId');
    return requiredString(address.childSessionId, 'childSessionId');
}
function nativeThreadId(sessionId) {
    if (!sessionId.startsWith(CODEX_SESSION_PREFIX) || sessionId.length === CODEX_SESSION_PREFIX.length) {
        throw new Error('The selected Session does not belong to CodeX.');
    }
    return sessionId.slice(CODEX_SESSION_PREFIX.length);
}
function activeTurnId(value) {
    if (!Array.isArray(value))
        return undefined;
    for (let index = value.length - 1; index >= 0; index -= 1) {
        const turn = record(value[index]);
        if (isActiveCodexTurn(turn) && typeof turn.id === 'string' && turn.id.length > 0)
            return turn.id;
    }
    return undefined;
}
function isActiveCodexTurn(turn) {
    return turn.status === 'inProgress' || turn.status === 'running';
}
function carrierArgs(payload) {
    return record(record(payload).args);
}
function requestArg(args) {
    return record(args.request ?? args._request ?? args);
}
function rcEndpoint(endpoint) {
    if (endpoint === 'workspace.list')
        return 'workspace/list';
    return endpoint.replace('.', '/');
}
function success(value) {
    return { ok: true, value };
}
function failure(code, message, details = {}) {
    return { ok: false, error: { code, message, details } };
}
function ok(value) {
    return value === undefined ? { ok: true } : { ok: true, value };
}
function business(value) {
    const result = record(value);
    if (result.ok === true)
        return ok(result.value);
    if (result.ok === false) {
        const error = record(result.error);
        return fail(string(error.code) ?? 'internal', string(error.message) ?? 'CodeX virtual Harness rejected the request.', record(error.details));
    }
    return ok(value);
}
function isCodexDirectoryListing(value) {
    const listing = record(value);
    return typeof listing.path === 'string'
        && typeof listing.home === 'string'
        && Array.isArray(listing.crumbs)
        && listing.crumbs.every(isCodexDirectoryEntry)
        && Array.isArray(listing.entries)
        && listing.entries.every(isCodexDirectoryEntry)
        && typeof listing.truncated === 'boolean';
}
function isCodexDirectoryEntry(value) {
    const entry = record(value);
    return typeof entry.name === 'string'
        && typeof entry.path === 'string'
        && typeof entry.hidden === 'boolean';
}
function fail(code, message, details = {}) {
    return { ok: false, error: { code, message, details } };
}
function failFrom(error) {
    const source = error instanceof Error ? error : new Error(String(error));
    return fail(errorCode(source) ?? 'internal', source.message, errorDetails(source));
}
function isLegacySessionHistoryUnsupported(error) {
    if (!(error instanceof Error))
        return false;
    return errorCode(error) === 'METHOD_NOT_ALLOWED'
        || errorCode(error) === 'METHOD_NOT_FOUND'
        || error.message.includes('The requested Codex method is not available over Remote.');
}
function errorCode(error) {
    return 'code' in error && typeof error.code === 'string' ? error.code : undefined;
}
function errorDetails(error) {
    return 'details' in error && isRecord(error.details) ? error.details : {};
}
function record(value) {
    return isRecord(value) ? value : {};
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function array(value) {
    return Array.isArray(value) ? value : [];
}
function string(value) {
    return typeof value === 'string' ? value : undefined;
}
function integer(value) {
    return typeof value === 'number' && Number.isSafeInteger(value) ? value : undefined;
}
function finiteNumber(value) {
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
function optionalInteger(value) {
    if (value === undefined)
        return undefined;
    const parsed = integer(value);
    if (parsed === undefined)
        throw new Error('The CodeX History cursor is invalid.');
    return parsed;
}
function optionalNonNegativeInteger(value) {
    const parsed = optionalInteger(value);
    if (parsed !== undefined && parsed < 0)
        throw new Error('The CodeX History cursor is invalid.');
    return parsed;
}
function optionalPositiveInteger(value) {
    const parsed = optionalInteger(value);
    if (parsed !== undefined && parsed <= 0)
        throw new Error('The CodeX History page size is invalid.');
    return parsed;
}
function isCodexNativeHistoryPage(value, sessionId) {
    const page = record(value);
    const header = record(page.header);
    return header.id === sessionId
        && integer(page.cursor) !== undefined
        && integer(page.nextTurn) !== undefined
        && Array.isArray(page.records)
        && typeof page.hasMore === 'boolean'
        && (page.activeTurnId === undefined || typeof page.activeTurnId === 'string');
}
function truncateCodePoints(value, maximum) {
    return [...value].slice(0, maximum).join('');
}
function requiredString(value, field) {
    if (typeof value !== 'string' || value.length === 0)
        throw new Error(`The CodeX ${field} is required.`);
    return value;
}
function normalizeTime(value) {
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0)
        return 0;
    return value < 10_000_000_000 ? Math.floor(value * 1000) : Math.floor(value);
}
function isoTime(value) {
    return new Date(normalizeTime(value) || Date.now()).toISOString();
}
function isProjectListUnsupported(error) {
    if (!(error instanceof Error))
        return false;
    const code = errorCode(error);
    return code === 'METHOD_NOT_ALLOWED'
        || code === 'METHOD_NOT_FOUND'
        || code === 'CODEX_UPSTREAM_ERROR'
        || error.message.includes('The requested Codex method is not available over Remote.');
}
function basename(path) {
    const normalized = path.replace(/[\\/]+$/u, '');
    const parts = normalized.split(/[\\/]/u);
    return parts.at(-1) || path;
}
function hashString(value) {
    let hash = 0x811c9dc5;
    for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0).toString(36);
}
class AsyncValueQueue {
    signal;
    values = [];
    waiters = [];
    closed = false;
    onAbort;
    constructor(signal) {
        this.signal = signal;
        this.onAbort = () => this.close();
        signal.addEventListener('abort', this.onAbort, { once: true });
        if (signal.aborted)
            this.close();
    }
    push(value) {
        if (this.closed)
            return;
        const waiter = this.waiters.shift();
        if (waiter === undefined)
            this.values.push(value);
        else
            waiter({ done: false, value });
    }
    close() {
        if (this.closed)
            return;
        this.closed = true;
        this.signal.removeEventListener('abort', this.onAbort);
        for (const waiter of this.waiters.splice(0))
            waiter({ done: true, value: undefined });
    }
    iterate(dispose) {
        const queue = this;
        return {
            async *[Symbol.asyncIterator]() {
                try {
                    yield* queue;
                }
                finally {
                    dispose();
                    queue.close();
                }
            },
        };
    }
    async *[Symbol.asyncIterator]() {
        while (true) {
            if (this.values.length > 0) {
                yield this.values.shift();
                continue;
            }
            if (this.closed)
                return;
            const next = await new Promise(resolve => this.waiters.push(resolve));
            if (next.done)
                return;
            yield next.value;
        }
    }
}
//# sourceMappingURL=virtual-harness.js.map