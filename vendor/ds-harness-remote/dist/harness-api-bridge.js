import { HARNESS_API_TRANSFER_CHUNK_BYTES, MAX_ACTIVE_TRANSFERS_PER_DIRECTION, MAX_HARNESS_API_TRANSFER_BYTES, TRANSFER_IDLE_MS, } from '@dsh-remote/protocol';
import { z } from 'zod';
import { harnessSessionGeneration, normalizeHarnessVersion, selectHarnessVersion, } from './harness-version.js';
import { RpcError } from './rpc-router.js';
import { safeErrorCode } from './safe-error.js';
import { listRemoteDirectory } from './remote-directory-browser.js';
import { callSessionHistory } from './harness-api-history.js';
const callSchema = z.object({
    method: z.string().min(1).max(80),
    rpcId: z.string().min(1).max(128),
    payload: z.unknown(),
}).strict();
const respondSchema = z.object({
    message: z.object({
        type: z.literal('client-response'),
        rpcId: z.string().min(1).max(128),
        result: z.unknown(),
    }).strict(),
}).strict();
const streamOpenSchema = z.object({
    streamId: z.string().min(1).max(128),
    stream: z.enum(['mux', 'host']),
    rpcId: z.string().min(1).max(128),
    payload: z.object({
        // Optional focus for a mux stream: only frames belonging to this session
        // are forwarded. The Remote Web selects one session at a time, so without
        // this every active session's events (potentially megabytes) would be
        // pushed over the tunnel and stall the WebRTC data channel.
        sessionId: z.string().min(1).max(128).optional(),
    }).strict(),
}).strict();
const streamCloseSchema = z.object({ streamId: z.string().min(1).max(128) }).strict();
const transferIdSchema = z.string().min(1).max(128);
const transferOpenSchema = z.object({
    transferId: transferIdSchema,
    totalBytes: z.number().int().positive().max(MAX_HARNESS_API_TRANSFER_BYTES),
    totalChunks: z.number().int().positive().max(Math.ceil(MAX_HARNESS_API_TRANSFER_BYTES / HARNESS_API_TRANSFER_CHUNK_BYTES)),
}).strict();
const transferChunkSchema = z.object({
    transferId: transferIdSchema,
    index: z.number().int().nonnegative(),
    data: z.string().max(Math.ceil(HARNESS_API_TRANSFER_CHUNK_BYTES / 3) * 4),
}).strict();
const transferCommitSchema = z.object({ transferId: transferIdSchema }).strict();
const transferReadSchema = z.object({ transferId: transferIdSchema, index: z.number().int().nonnegative() }).strict();
const transferCloseSchema = z.object({ transferId: transferIdSchema }).strict();
const commandExecuteSchema = z.object({
    agentId: z.string().min(1).max(128),
    line: z.string().min(1).max(2048),
    // dsh-commands <= 0.1.2 calls this field `images`; dsh-commands 0.1.5
    // renamed it to `submittedAttachments` and expanded its element type to
    // include staged file receipts. Remote command execution keeps attachments
    // out of scope, so either compatibility field is restricted to an empty
    // list. The dispatcher below chooses the matching Host descriptor.
    images: z.array(z.never()).length(0).optional(),
    submittedAttachments: z.array(z.never()).length(0).optional(),
}).strict().superRefine((value, context) => {
    if (value.images !== undefined && value.submittedAttachments !== undefined) {
        context.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Use either images or submittedAttachments, not both.',
            path: ['submittedAttachments'],
        });
    }
});
const commandListSchema = z.object({
    agentId: z.string().min(1).max(128),
}).strict();
const directoryListSchema = z.object({
    path: z.string().min(1).max(4096).optional(),
}).strict();
/** POSIX-portable environment-variable name, mirroring the seam's credentialRef guard. */
const credentialRefSchema = z.string().regex(/^[A-Za-z_][A-Za-z0-9_]*$/, 'Invalid credential reference name').max(128);
const CONFIG_PLANE_SETTINGS_BYTES = 64 * 1024;
const CONFIG_PLANE_MAX_OPS = 64;
const CONFIG_PLANE_MAX_PATH_SEGMENTS = 8;
const CONFIG_PLANE_MAX_PATH_SEGMENT_BYTES = 64;
const CONFIG_PLANE_MAX_CREDENTIAL_VALUE_BYTES = 8 * 1024;
const CONFIG_PLANE_MAX_BASE_URL_BYTES = 2048;
const CONFIG_PLANE_MAX_NS_BYTES = 128;
const settingsDescribeSchema = z.object({}).strict();
const settingsWriteBase = {
    ns: z.string().min(1).max(CONFIG_PLANE_MAX_NS_BYTES),
    expectedRevision: z.number().int().nonnegative().optional(),
};
const settingsUpdateSchema = z.object({
    ...settingsWriteBase,
    patch: z.record(z.string().min(1).max(CONFIG_PLANE_MAX_PATH_SEGMENT_BYTES), z.unknown()),
}).strict();
const settingsReplaceSchema = z.object({
    ...settingsWriteBase,
    section: z.record(z.string().min(1).max(CONFIG_PLANE_MAX_PATH_SEGMENT_BYTES), z.unknown()),
}).strict();
const settingsOpSchema = z.union([
    z.object({
        op: z.literal('set'),
        path: z.array(z.string().min(1).max(CONFIG_PLANE_MAX_PATH_SEGMENT_BYTES)).max(CONFIG_PLANE_MAX_PATH_SEGMENTS),
        value: z.unknown(),
    }).strict(),
    z.object({
        op: z.literal('unset'),
        path: z.array(z.string().min(1).max(CONFIG_PLANE_MAX_PATH_SEGMENT_BYTES)).max(CONFIG_PLANE_MAX_PATH_SEGMENTS),
    }).strict(),
]);
const settingsMutateSchema = z.object({
    ...settingsWriteBase,
    ops: z.array(settingsOpSchema).min(1).max(CONFIG_PLANE_MAX_OPS),
}).strict();
const credentialsDescribeSchema = z.object({
    refs: z.array(credentialRefSchema).max(CONFIG_PLANE_MAX_OPS),
}).strict();
const credentialsSetSchema = z.object({
    ref: credentialRefSchema,
    value: z.string().min(1).max(CONFIG_PLANE_MAX_CREDENTIAL_VALUE_BYTES),
}).strict();
const credentialsUnsetSchema = z.object({
    ref: credentialRefSchema,
}).strict();
const discoverModelsSchema = z.object({
    settingsNs: z.string().min(1).max(CONFIG_PLANE_MAX_NS_BYTES),
    provider: z.string().min(1).max(CONFIG_PLANE_MAX_NS_BYTES).optional(),
    baseURL: z.string().max(CONFIG_PLANE_MAX_BASE_URL_BYTES).optional(),
    api: z.string().min(1).max(CONFIG_PLANE_MAX_NS_BYTES).optional(),
    apiKey: z.string().min(1).max(CONFIG_PLANE_MAX_CREDENTIAL_VALUE_BYTES).optional(),
}).strict();
/**
 * Harness API methods that are safe to expose to an authenticated remote UI.
 * Native open/picker calls, directory mutation, file contents, downloads,
 * attachment upload, and `settings.openDocument` intentionally remain outside
 * this bridge. `session.attachment` is the native read-only lookup used by
 * Harness rc.2 to render an image already referenced by that same session.
 * Directory listing exposes metadata only for workspace picking.
 * `commands.*` follows the official Host registry so the authenticated Remote
 * UI sees the same effective command catalog and handlers as the local UI.
 *
 * The authenticated Remote UI may configure every namespace currently
 * registered with the official Host settings seam. Writes remain bounded and
 * must target that live directory; credential values remain write-only;
 * `settings.openDocument` stays local-only; and `discoverModels` endpoints must
 * be HTTPS (HTTP only for localhost). Anything outside that scope fails closed.
 */
export const HARNESS_API_ALLOWLIST = [
    'session.list',
    'session.search',
    'session.create',
    'session.history',
    'session.models',
    'session.selectModel',
    'session.rename',
    'session.fork',
    'session.prompt',
    'session.attachment',
    'session.updateQueue',
    'session.cancel',
    'subagent.list',
    'subagent.history',
    'subagent.prompt',
    'subagent.interrupt',
    'host.describe',
    'host.listDirectory',
    'workspace.list',
    'workspace.create',
    'workspace.rename',
    'workspace.delete',
    'workspace.insertBefore',
    'workspace.insertSessionBefore',
    'workspace.archiveSession',
    'skill.list',
    'agentPreset.list',
    'agentPreset.select',
    'agentPreset.read',
    'goal.create',
    'goal.edit',
    'goal.pause',
    'goal.resume',
    'goal.complete',
    'goal.clear',
    'commands.execute',
    'commands.list',
    'llm.providers',
    'llm.models',
    'llm.discoverModels',
    'settings.describe',
    'settings.update',
    'settings.replace',
    'settings.mutate',
    'credentials.describe',
    'credentials.set',
    'credentials.unset',
];
/**
 * Native Harness ApiProxy call timeout. The Remote Web frontend gives each
 * `harness.api.call` RPC a 60s window; this bridge must fail faster so the
 * RPC error (not a silent Web-side timeout) reaches the peer and the pending
 * call is released. 30s gives slow native methods room while still beating the
 * Web-side 60s timer by a wide margin.
 */
const NATIVE_CALL_TIMEOUT_MS = 30_000;
const MAX_ACTIVE_API_TRANSFERS = MAX_ACTIVE_TRANSFERS_PER_DIRECTION;
const API_TRANSFER_IDLE_MS = TRANSFER_IDLE_MS;
const INLINE_TRANSFER_RESPONSE_BYTES = 2 * 1024 * 1024;
export class HarnessApiBridge {
    api;
    publish;
    maxStreams;
    logger;
    harnessVersion;
    methods;
    streams = new Map();
    respondable = new Map();
    incomingTransfers = new Map();
    outgoingTransfers = new Map();
    mux;
    host;
    answer;
    constructor(api, publish, maxStreams = 8, logger, typertGateway, harnessVersion) {
        this.api = api;
        this.publish = publish;
        this.maxStreams = maxStreams;
        this.logger = logger;
        this.harnessVersion = harnessVersion;
        this.methods = createMethodMap(api, typertGateway, harnessVersion);
        this.mux = api.events.mux.bind(api.events);
        this.host = api.events.host.bind(api.events);
        this.answer = api.respond.bind(api);
    }
    async call(input) {
        const params = callSchema.parse(input);
        const signal = AbortSignal.timeout(NATIVE_CALL_TIMEOUT_MS);
        const method = this.methods.get(params.method);
        if (method === undefined) {
            if (params.method === 'host.listDirectory') {
                return this.callDirectoryFallback(params, signal);
            }
            if (params.method === 'host.describe') {
                return this.describeFallback(params.rpcId);
            }
            throw deniedMethod(params.method);
        }
        const startedAt = performance.now();
        const request = { rpcId: params.rpcId, payload: params.payload };
        try {
            // Race the native call against the timeout: some native ApiProxy
            // methods ignore AbortSignal, and without this the Host would never
            // answer and the Web-side 60s timer would fire instead. A guaranteed
            // local response turns that into a fast, explicit RPC error.
            const callWithTimeout = (overridePayload) => withTimeout(method({ rpcId: params.rpcId, payload: overridePayload }, signal), NATIVE_CALL_TIMEOUT_MS, `Harness API call ${params.method} timed out after ${NATIVE_CALL_TIMEOUT_MS}ms`);
            let response;
            if (params.method === 'session.history') {
                response = await callSessionHistory(callWithTimeout, params.payload, params.rpcId);
            }
            else {
                response = await callWithTimeout(request.payload);
            }
            if (params.method === 'host.listDirectory' && needsRemoteDirectoryFallback(response)) {
                response = await this.callDirectoryFallback(params, signal);
            }
            if (params.method === 'host.describe') {
                response = normalizeHostDescribe(response, this.harnessVersion, true);
            }
            this.logger?.debug('harness api call ok', {
                method: params.method,
                durationMs: Math.round(performance.now() - startedAt),
            });
            return response;
        }
        catch (error) {
            const durationMs = Math.round(performance.now() - startedAt);
            this.logger?.warn('harness api call failed', {
                method: params.method,
                durationMs,
                timedOut: signal.aborted,
                code: safeErrorCode(error),
            });
            throw error;
        }
    }
    async callDirectoryFallback(params, signal) {
        const payload = directoryListSchema.parse(params.payload);
        const value = await listRemoteDirectory(payload.path, signal);
        return { rpcId: params.rpcId, result: { ok: true, value } };
    }
    describeFallback(rpcId) {
        return {
            rpcId: rpcId,
            result: {
                ok: true,
                value: {
                    version: this.harnessVersion ?? '0.0.1',
                    cwd: '',
                    home: '',
                    attachedSessions: 0,
                    canOpenPath: true,
                },
            },
        };
    }
    openTransfer(input) {
        this.pruneTransfers();
        const params = transferOpenSchema.parse(input);
        if (params.totalChunks !== Math.ceil(params.totalBytes / HARNESS_API_TRANSFER_CHUNK_BYTES)) {
            throw new RpcError('INVALID_MESSAGE', 'The Harness API transfer chunk count is invalid.');
        }
        if (this.incomingTransfers.has(params.transferId) || this.outgoingTransfers.has(params.transferId)) {
            throw new RpcError('REQUEST_CONFLICT', 'The Harness API transfer id is already active.');
        }
        if (this.incomingTransfers.size >= MAX_ACTIVE_API_TRANSFERS) {
            throw new RpcError('RATE_LIMITED', 'Too many Harness API transfers are active.', undefined, true);
        }
        this.incomingTransfers.set(params.transferId, {
            totalBytes: params.totalBytes,
            totalChunks: params.totalChunks,
            chunks: [],
            receivedBytes: 0,
            touchedAt: Date.now(),
        });
        return { opened: true, transferId: params.transferId };
    }
    appendTransfer(input) {
        this.pruneTransfers();
        const params = transferChunkSchema.parse(input);
        const transfer = this.incomingTransfers.get(params.transferId);
        if (transfer === undefined)
            throw new RpcError('TRANSFER_NOT_FOUND', 'The Harness API transfer is not active.');
        if (params.index !== transfer.chunks.length || params.index >= transfer.totalChunks) {
            this.incomingTransfers.delete(params.transferId);
            throw new RpcError('INVALID_MESSAGE', 'Harness API transfer chunks must arrive exactly once and in order.');
        }
        let chunk;
        try {
            chunk = decodeCanonicalBase64(params.data);
        }
        catch (error) {
            this.incomingTransfers.delete(params.transferId);
            throw error;
        }
        const expectedBytes = Math.min(HARNESS_API_TRANSFER_CHUNK_BYTES, transfer.totalBytes - params.index * HARNESS_API_TRANSFER_CHUNK_BYTES);
        if (chunk.byteLength !== expectedBytes) {
            this.incomingTransfers.delete(params.transferId);
            throw new RpcError('INVALID_MESSAGE', 'The Harness API transfer chunk size is invalid.');
        }
        transfer.chunks.push(chunk);
        transfer.receivedBytes += chunk.byteLength;
        transfer.touchedAt = Date.now();
        return { accepted: true, transferId: params.transferId, index: params.index };
    }
    async commitTransfer(input) {
        this.pruneTransfers();
        const params = transferCommitSchema.parse(input);
        const transfer = this.incomingTransfers.get(params.transferId);
        if (transfer === undefined)
            throw new RpcError('TRANSFER_NOT_FOUND', 'The Harness API transfer is not active.');
        this.incomingTransfers.delete(params.transferId);
        if (transfer.chunks.length !== transfer.totalChunks || transfer.receivedBytes !== transfer.totalBytes) {
            throw new RpcError('INVALID_MESSAGE', 'The Harness API transfer is incomplete.');
        }
        let request;
        try {
            const bytes = concatChunks(transfer.chunks, transfer.totalBytes);
            request = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
        }
        catch {
            throw new RpcError('INVALID_MESSAGE', 'The Harness API transfer does not contain a valid request.');
        }
        // Parse before dispatch so a transfer cannot bypass the exact same native
        // envelope validation and method allowlist used by harness.api.call.
        const nativeRequest = callSchema.parse(request);
        const response = await this.call(nativeRequest);
        const responseBytes = new TextEncoder().encode(JSON.stringify(response));
        if (responseBytes.byteLength <= INLINE_TRANSFER_RESPONSE_BYTES) {
            return { kind: 'inline', response };
        }
        if (responseBytes.byteLength > MAX_HARNESS_API_TRANSFER_BYTES) {
            throw new RpcError('RESPONSE_TOO_LARGE', 'The Harness API response exceeds the bounded transfer limit.');
        }
        this.pruneTransfers();
        if (this.outgoingTransfers.size >= MAX_ACTIVE_API_TRANSFERS) {
            throw new RpcError('RATE_LIMITED', 'Too many Harness API response transfers are active.', undefined, true);
        }
        const totalChunks = Math.ceil(responseBytes.byteLength / HARNESS_API_TRANSFER_CHUNK_BYTES);
        this.outgoingTransfers.set(params.transferId, {
            bytes: responseBytes,
            totalChunks,
            nextIndex: 0,
            touchedAt: Date.now(),
        });
        return { kind: 'chunked', transferId: params.transferId, totalBytes: responseBytes.byteLength, totalChunks };
    }
    readTransfer(input) {
        this.pruneTransfers();
        const params = transferReadSchema.parse(input);
        const transfer = this.outgoingTransfers.get(params.transferId);
        if (transfer === undefined)
            throw new RpcError('TRANSFER_NOT_FOUND', 'The Harness API response transfer is not active.');
        if (params.index !== transfer.nextIndex || params.index >= transfer.totalChunks) {
            this.outgoingTransfers.delete(params.transferId);
            throw new RpcError('INVALID_MESSAGE', 'Harness API response chunks must be read exactly once and in order.');
        }
        const start = params.index * HARNESS_API_TRANSFER_CHUNK_BYTES;
        const end = Math.min(start + HARNESS_API_TRANSFER_CHUNK_BYTES, transfer.bytes.byteLength);
        transfer.nextIndex += 1;
        transfer.touchedAt = Date.now();
        return {
            transferId: params.transferId,
            index: params.index,
            data: Buffer.from(transfer.bytes.subarray(start, end)).toString('base64'),
        };
    }
    closeTransfer(input) {
        const params = transferCloseSchema.parse(input);
        const incoming = this.incomingTransfers.delete(params.transferId);
        const outgoing = this.outgoingTransfers.delete(params.transferId);
        return { closed: incoming || outgoing, transferId: params.transferId };
    }
    async respond(input) {
        const params = respondSchema.parse(input);
        this.logger?.debug('harness api respond', { rpcId: shortId(params.message.rpcId) });
        if (!this.respondable.has(params.message.rpcId)) {
            throw new RpcError('PERMISSION_NOT_PENDING', 'The response id was not emitted on this peer connection.');
        }
        const receipt = await this.answer(params.message);
        if (receipt.accepted || receipt.reason === 'not-pending')
            this.respondable.delete(params.message.rpcId);
        return receipt;
    }
    openStream(input) {
        const params = streamOpenSchema.parse(input);
        if (this.streams.has(params.streamId))
            throw new RpcError('REQUEST_CONFLICT', 'The Harness event stream is already open.');
        if (this.streams.size >= this.maxStreams)
            throw new RpcError('RATE_LIMITED', 'Too many Harness event streams are open.', undefined, true);
        const controller = new AbortController();
        const request = { rpcId: params.rpcId, payload: params.payload };
        const stream = params.stream === 'mux'
            ? this.mux(request, controller.signal)
            : this.host(request, controller.signal);
        const focusSessionId = params.stream === 'mux' ? params.payload.sessionId : undefined;
        const task = this.pump(params.streamId, stream, controller.signal, focusSessionId);
        this.streams.set(params.streamId, { controller, task, ...(focusSessionId === undefined ? {} : { focusSessionId }) });
        this.logger?.debug('harness api stream open', {
            stream: params.stream,
            streamId: shortId(params.streamId),
            ...(focusSessionId === undefined ? {} : { focusSessionId: shortId(focusSessionId) }),
        });
        return { opened: true, streamId: params.streamId };
    }
    closeStream(input) {
        const params = streamCloseSchema.parse(input);
        const active = this.streams.get(params.streamId);
        if (active !== undefined) {
            // Free the slot synchronously. A native ApiProxy stream may not observe
            // the abort until its next frame, so a session-focused mux stream on an
            // idle session could otherwise hold its slot forever and block the
            // client's documented close-then-reopen session switch with
            // RATE_LIMITED. The pump's finally performs a no-op delete later.
            this.streams.delete(params.streamId);
            active.controller.abort();
        }
        this.logger?.debug('harness api stream close', { streamId: shortId(params.streamId), closed: active !== undefined });
        return { closed: active !== undefined, streamId: params.streamId };
    }
    async closeAll(reason = 'peer-disconnected') {
        const streams = [...this.streams.values()];
        this.streams.clear();
        this.respondable.clear();
        this.incomingTransfers.clear();
        this.outgoingTransfers.clear();
        for (const stream of streams)
            stream.controller.abort(reason);
        // A native ApiProxy stream may not observe AbortSignal until its next
        // frame. Waiting for every pump here would block a replacement peer from
        // installing its message handler indefinitely. The detached pumps own
        // their errors and terminal event publication, so abort and release them
        // without holding up the authenticated connection handoff.
    }
    pruneTransfers() {
        const cutoff = Date.now() - API_TRANSFER_IDLE_MS;
        for (const [transferId, transfer] of this.incomingTransfers) {
            if (transfer.touchedAt < cutoff)
                this.incomingTransfers.delete(transferId);
        }
        for (const [transferId, transfer] of this.outgoingTransfers) {
            if (transfer.touchedAt < cutoff)
                this.outgoingTransfers.delete(transferId);
        }
    }
    async pump(streamId, stream, signal, focusSessionId) {
        let reason = 'completed';
        try {
            for await (const frame of stream) {
                if (signal.aborted)
                    break;
                if (focusSessionId !== undefined && frameSessionId(frame) !== undefined && frameSessionId(frame) !== focusSessionId) {
                    // Keep pushing to the native stream (the peer's upstream may still
                    // emit approvals for the focused session through the same pump) but
                    // do not forward other sessions' traffic over the tunnel.
                    continue;
                }
                this.trackRespondable(frame);
                await this.publish('harness.api.frame', { streamId, frame });
            }
            if (signal.aborted)
                reason = 'cancelled';
        }
        catch {
            reason = signal.aborted ? 'cancelled' : 'failed';
        }
        finally {
            this.streams.delete(streamId);
            await this.publish('harness.api.stream.closed', { streamId, reason }).catch(() => undefined);
        }
    }
    trackRespondable(frame) {
        const payload = frame.payload;
        if (payload.type === 'approval/requested') {
            this.respondable.set(String(frame.rpcId), `approval:${String(payload.approvalId)}`);
            return;
        }
        if (payload.type === 'question/requested') {
            this.respondable.set(String(frame.rpcId), `question:${String(frame.rpcId)}`);
            return;
        }
        if (payload.type === 'approval/resolved') {
            this.deleteRespondable(`approval:${String(payload.approvalId)}`);
            return;
        }
        if (payload.type === 'question/resolved') {
            this.respondable.delete(String(payload.questionRpcId));
        }
    }
    deleteRespondable(value) {
        for (const [rpcId, correlation] of this.respondable) {
            if (correlation === value)
                this.respondable.delete(rpcId);
        }
    }
}
function needsRemoteDirectoryFallback(response) {
    const result = response.result;
    return typeof result === 'object' && result !== null && 'ok' in result && result.ok === false
        && 'error' in result && typeof result.error === 'object' && result.error !== null
        && 'code' in result.error && result.error.code === 'directory-picker-unavailable';
}
function normalizeHostDescribe(response, harnessVersion, canListDirectory) {
    if (!response.result.ok)
        return response;
    const value = response.result.value;
    if (typeof value !== 'object' || value === null || Array.isArray(value))
        return response;
    const description = value;
    const selectedVersion = selectHarnessVersion(normalizeHarnessVersion(description.version), harnessVersion);
    const versionChanged = selectedVersion !== undefined && description.version !== selectedVersion;
    const canOpenPathChanged = canListDirectory && description.canOpenPath !== true;
    if (!versionChanged && !canOpenPathChanged)
        return response;
    const normalized = {
        ...description,
        ...(versionChanged ? { version: selectedVersion } : {}),
        ...(canOpenPathChanged ? { canOpenPath: true } : {}),
    };
    return {
        ...response,
        result: {
            ...response.result,
            value: normalized,
        },
    };
}
function createMethodMap(api, typertGateway, harnessVersion) {
    const domains = api;
    const methods = new Map();
    for (const method of HARNESS_API_ALLOWLIST) {
        if (method === 'commands.execute' || method === 'commands.list') {
            // Commands live behind the official Typert registry (the ApiProxy has
            // no `commands` domain). Dispatch them through the gateway when it is
            // available; without it the method stays denied (fail-closed).
            if (typertGateway === undefined)
                continue;
            const [namespace, commandMethod] = method.split('.');
            const implementation = async (request, signal) => {
                if (commandMethod === 'execute') {
                    const payload = commandExecuteSchema.parse(request.payload);
                    const legacyArgs = {
                        agentId: payload.agentId,
                        line: payload.line,
                        images: payload.images ?? [],
                    };
                    const currentArgs = {
                        agentId: payload.agentId,
                        line: payload.line,
                        submittedAttachments: payload.submittedAttachments ?? [],
                    };
                    const args = harnessSessionGeneration(harnessVersion) === 'v3' ? currentArgs : legacyArgs;
                    const value = await typertGateway.invoke({
                        namespace,
                        method: 'execute',
                        args,
                        ...(signal === undefined ? {} : { signal }),
                    });
                    return { rpcId: request.rpcId, result: { ok: true, value } };
                }
                const args = commandListSchema.parse(request.payload);
                const value = await typertGateway.invoke({
                    namespace,
                    method: 'list',
                    args,
                    ...(signal === undefined ? {} : { signal }),
                });
                return { rpcId: request.rpcId, result: { ok: true, value } };
            };
            methods.set(method, implementation);
            continue;
        }
        const [wireDomain, action] = method.split('.');
        const domain = domainProperty(wireDomain);
        const implementation = domains[domain]?.[action];
        if (typeof implementation !== 'function')
            continue;
        const scoped = CONFIG_PLANE_METHODS.includes(method)
            ? scopeConfigPlaneMethod(api, method, implementation)
            : undefined;
        methods.set(method, scoped ?? implementation.bind(domains[domain]));
    }
    return methods;
}
function domainProperty(wireDomain) {
    if (wireDomain === 'session')
        return 'sessions';
    if (wireDomain === 'subagent')
        return 'subagents';
    if (wireDomain === 'skill')
        return 'skills';
    if (wireDomain === 'agentPreset')
        return 'agentPresets';
    if (wireDomain === 'goal')
        return 'goals';
    return wireDomain;
}
/**
 * Config-plane methods that need scoping beyond the plain allowlist. Settings
 * writes target only namespaces currently registered with the Host settings
 * seam, credential payloads are bounded and write-only, and model discovery
 * probes only HTTPS/localhost-HTTP endpoints with credential-free failures.
 */
const CONFIG_PLANE_METHODS = [
    'llm.discoverModels',
    'settings.describe',
    'settings.update',
    'settings.replace',
    'settings.mutate',
    'credentials.describe',
    'credentials.set',
    'credentials.unset',
];
function scopeConfigPlaneMethod(api, method, native) {
    switch (method) {
        case 'llm.discoverModels':
            return async (request, signal) => {
                const payload = parseConfigPlane(discoverModelsSchema, request.payload, 'llm.discoverModels');
                if (payload.baseURL !== undefined)
                    validateDiscoverBaseUrl(payload.baseURL);
                try {
                    const response = await native({ rpcId: request.rpcId, payload }, signal);
                    return sanitizeModelDiscoveryResponse(response, payload.settingsNs);
                }
                catch {
                    return modelDiscoveryFailure(request.rpcId, payload.settingsNs);
                }
            };
        case 'settings.describe':
            return async (request, signal) => {
                parseConfigPlane(settingsDescribeSchema, request.payload, 'settings.describe');
                const response = await native({ rpcId: request.rpcId, payload: {} }, signal);
                return disableRemoteSettingsDocument(response);
            };
        case 'settings.update':
            return async (request, signal) => {
                const payload = parseConfigPlane(settingsUpdateSchema, request.payload, 'settings.update');
                await assertRegisteredSettingsNamespace(api, payload.ns);
                assertSerializedBytes(payload.patch, CONFIG_PLANE_SETTINGS_BYTES, 'settings.update patch');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
        case 'settings.replace':
            return async (request, signal) => {
                const payload = parseConfigPlane(settingsReplaceSchema, request.payload, 'settings.replace');
                await assertRegisteredSettingsNamespace(api, payload.ns);
                assertSerializedBytes(payload.section, CONFIG_PLANE_SETTINGS_BYTES, 'settings.replace section');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
        case 'settings.mutate':
            return async (request, signal) => {
                const payload = parseConfigPlane(settingsMutateSchema, request.payload, 'settings.mutate');
                await assertRegisteredSettingsNamespace(api, payload.ns);
                assertSerializedBytes(payload.ops, CONFIG_PLANE_SETTINGS_BYTES, 'settings.mutate ops');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
        case 'credentials.describe':
            return async (request, signal) => {
                const payload = parseConfigPlane(credentialsDescribeSchema, request.payload, 'credentials.describe');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
        case 'credentials.set':
            return async (request, signal) => {
                const payload = parseConfigPlane(credentialsSetSchema, request.payload, 'credentials.set');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
        case 'credentials.unset':
            return async (request, signal) => {
                const payload = parseConfigPlane(credentialsUnsetSchema, request.payload, 'credentials.unset');
                return native({ rpcId: request.rpcId, payload }, signal);
            };
    }
}
function parseConfigPlane(schema, payload, label) {
    try {
        return schema.parse(payload);
    }
    catch (error) {
        if (error instanceof z.ZodError) {
            throw new RpcError('INVALID_MESSAGE', `The ${label} payload is invalid for the remote channel.`);
        }
        throw error;
    }
}
function validateDiscoverBaseUrl(value) {
    let url;
    try {
        url = new URL(value);
    }
    catch {
        throw new RpcError('INVALID_MESSAGE', 'The model discovery baseURL is invalid.');
    }
    const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '::1';
    if (url.protocol !== 'https:' && !(local && url.protocol === 'http:')) {
        throw new RpcError('INVALID_MESSAGE', 'The model discovery baseURL must use HTTPS (HTTP is allowed only for localhost).');
    }
    if (url.username !== '' || url.password !== '') {
        throw new RpcError('INVALID_MESSAGE', 'The model discovery baseURL must not contain credentials.');
    }
    if (url.hash !== '') {
        throw new RpcError('INVALID_MESSAGE', 'The model discovery baseURL must not contain a fragment.');
    }
}
function assertSerializedBytes(value, maxBytes, label) {
    const bytes = new TextEncoder().encode(JSON.stringify(value)).byteLength;
    if (bytes > maxBytes) {
        throw new RpcError('INVALID_MESSAGE', `The ${label} exceeds the ${maxBytes}-byte remote limit.`);
    }
}
function sanitizeModelDiscoveryResponse(response, settingsNs) {
    const result = response.result;
    if (typeof result === 'object' && result !== null && 'ok' in result && result.ok === true)
        return response;
    return modelDiscoveryFailure(response.rpcId, settingsNs);
}
function modelDiscoveryFailure(rpcId, settingsNs) {
    return {
        rpcId,
        result: {
            ok: false,
            error: {
                code: 'model-discovery-failed',
                message: 'Model discovery failed.',
                details: { settingsNs },
            },
        },
    };
}
function disableRemoteSettingsDocument(response) {
    const result = response.result;
    if (typeof result !== 'object' || result === null || !('ok' in result) || result.ok !== true)
        return response;
    const value = result.value;
    if (typeof value !== 'object' || value === null)
        return response;
    return { ...response, result: { ...result, value: { ...value, hasDocument: false } } };
}
const EMPTY_SETTINGS_NAMESPACES = new Set();
async function assertRegisteredSettingsNamespace(api, ns) {
    if (ns === 'ds-harness-remote' || ns === 'dsh-remote') {
        throw new RpcError('PERMISSION_DENIED', 'Remote access settings can only be changed locally on the Host.');
    }
    const allowed = await registeredSettingsNamespaces(api);
    if (!allowed.has(ns))
        throw deniedSettingsNamespace(ns);
}
/** Namespaces currently exposed by the official live Host settings directory. */
async function registeredSettingsNamespaces(api) {
    const settings = api.settings;
    const describe = settings?.describe;
    if (typeof describe !== 'function')
        return EMPTY_SETTINGS_NAMESPACES;
    const response = await withTimeout(describe.call(settings, { rpcId: 'bridge-settings-scope', payload: {} }, AbortSignal.timeout(NATIVE_CALL_TIMEOUT_MS)), NATIVE_CALL_TIMEOUT_MS, 'The Host settings.describe call timed out.');
    const value = unwrapNativeValue(response, 'settings.describe');
    const list = typeof value === 'object' && value !== null ? value.namespaces : undefined;
    if (!Array.isArray(list))
        return EMPTY_SETTINGS_NAMESPACES;
    const namespaces = new Set();
    for (const item of list) {
        if (typeof item !== 'object' || item === null)
            continue;
        const ns = item.ns;
        if (typeof ns === 'string' && ns.length > 0)
            namespaces.add(ns);
    }
    return namespaces;
}
function unwrapNativeValue(response, method) {
    const result = response.result;
    if (typeof result !== 'object' || result === null || !('ok' in result) || result.ok !== true || !('value' in result)) {
        throw new RpcError('INTERNAL_ERROR', `The Host ${method} call did not succeed.`);
    }
    return result.value;
}
function deniedSettingsNamespace(ns) {
    return new RpcError('METHOD_NOT_ALLOWED', `Harness settings namespace ${JSON.stringify(ns)} is not registered on the Host.`);
}
function deniedMethod(method) {
    return new RpcError('METHOD_NOT_ALLOWED', `Harness API method ${JSON.stringify(method)} is not available in remote mode.`);
}
/** Session id of a mux frame, or undefined for frames without one (e.g. stream/error). */
function frameSessionId(frame) {
    const payload = frame.payload;
    return typeof payload.sessionId === 'string' && payload.sessionId.length > 0 ? payload.sessionId : undefined;
}
function withTimeout(promise, ms, message) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            reject(new RpcError('TIMEOUT', message, undefined, true));
        }, ms);
        timer.unref?.();
        promise.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
    });
}
function shortId(value) { return value.length <= 12 ? value : `${value.slice(0, 8)}…${value.slice(-4)}`; }
function decodeCanonicalBase64(value) {
    const bytes = Buffer.from(value, 'base64');
    if (bytes.toString('base64') !== value) {
        throw new RpcError('INVALID_MESSAGE', 'The Harness API transfer chunk is not canonical base64.');
    }
    return bytes;
}
function concatChunks(chunks, totalBytes) {
    const result = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.byteLength;
    }
    return result;
}
//# sourceMappingURL=harness-api-bridge.js.map