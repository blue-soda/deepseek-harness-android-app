import { RemoteClientError } from '@dsh-remote/client-core';
import { decodeByteValue, hydrateRpcAttachments } from './rpc-binary-attachments.js';
import { HARNESS_API_TRANSFER_CHUNK_BYTES, MAX_HARNESS_API_TRANSFER_BYTES, } from '@dsh-remote/protocol';
import { uuidV7 } from './ids.js';
const DIRECT_API_CALL_BYTES = 2 * 1024 * 1024;
const AGENT_PRESET_SETTINGS_NS = 'agent-presets';
const LEGACY_AGENT_PRESET_ALIASES = {
    code: 'ptc',
};
const WELCOME_NOTICE_NAMESPACE = 'ui-settings-general';
const WELCOME_NOTICE_FIELD = 'welcomeNoticeVersion';
const WELCOME_NOTICE_VERSION = '2026-08-13.1';
/** ApiProxy-compatible face that preserves the native Harness envelopes over Remote RPC. */
export class RemoteHarnessApiProxy {
    client;
    harnessVersion;
    api;
    legacyWelcomeAcknowledged = false;
    settingsDescribeValue;
    constructor(client, harnessVersion) {
        this.client = client;
        this.harnessVersion = harnessVersion;
        const call = (method) => (request, signal) => this.call(method, request, signal);
        this.api = {
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
            subagents: {
                list: call('subagent.list'),
                history: call('subagent.history'),
                prompt: call('subagent.prompt'),
                interrupt: call('subagent.interrupt'),
            },
            host: {
                describe: call('host.describe'),
                pickDirectory: call('host.pickDirectory'),
                listDirectory: call('host.listDirectory'),
                createDirectory: call('host.createDirectory'),
                openPath: call('host.openPath'),
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
            skills: { list: call('skill.list') },
            agentPresets: {
                list: call('agentPreset.list'),
                select: call('agentPreset.select'),
                read: call('agentPreset.read'),
                copy: call('agentPreset.copy'),
                openDocument: call('agentPreset.openDocument'),
                remove: call('agentPreset.remove'),
            },
            goals: {
                create: call('goal.create'),
                edit: call('goal.edit'),
                pause: call('goal.pause'),
                resume: call('goal.resume'),
                complete: call('goal.complete'),
                clear: call('goal.clear'),
            },
            settings: {
                describe: call('settings.describe'),
                openDocument: call('settings.openDocument'),
                update: call('settings.update'),
                replace: call('settings.replace'),
                mutate: call('settings.mutate'),
            },
            credentials: {
                describe: call('credentials.describe'),
                set: call('credentials.set'),
                unset: call('credentials.unset'),
            },
            llm: {
                providers: call('llm.providers'),
                models: call('llm.models'),
                discoverModels: call('llm.discoverModels'),
            },
            events: {
                mux: (request, signal) => this.stream('mux', request, signal),
                host: (request, signal) => this.stream('host', request, signal),
            },
            downloads: {},
            respond: message => this.respond(message),
        };
    }
    async call(method, request, signal) {
        const params = {
            method,
            rpcId: String(request.rpcId),
            payload: normalizeLegacyRequest(method, request.payload),
        };
        const encoded = new TextEncoder().encode(JSON.stringify(params));
        const response = method === 'session.attachment' || encoded.byteLength > DIRECT_API_CALL_BYTES
            ? await this.callTransferred(encoded, signal)
            : await this.client.rpc('harness.api.call', params, signal);
        if (String(response.rpcId) !== String(request.rpcId) || typeof response.result !== 'object' || response.result === null) {
            throw new Error('The remote Host returned an invalid Harness API response.');
        }
        // The Host sends bytes beside the result (see collectRpcAttachments); DSH's own connection layer
        // would copy them back before validation, so restore them here.
        const hydrated = { ...response, result: hydrateRpcAttachments(response.result) };
        const normalized = normalizeLegacyResponse(method, normalizeByteResult(method, hydrated));
        return this.normalizeLegacyWelcomeSettings(method, params.payload, normalized);
    }
    normalizeLegacyWelcomeSettings(method, payload, response) {
        if (!isLegacyRemoteHost(this.harnessVersion))
            return response;
        if (method === 'settings.describe' && response.result.ok && isRecord(response.result.value)) {
            this.settingsDescribeValue = response.result.value;
            return this.legacyWelcomeAcknowledged ? patchWelcomeDescribe(response) : response;
        }
        if (method !== 'settings.mutate' || !isWelcomeNoticeRequest(payload))
            return response;
        this.legacyWelcomeAcknowledged = true;
        // Older Hosts reject the 0.1.7 onboarding field before returning a usable
        // settings snapshot. Treat the acknowledgement as client-local in that
        // case; a later describe is patched when one becomes available.
        return patchWelcomeMutate(this.settingsDescribeValue ?? { namespaces: [] }, response.rpcId);
    }
    async callTransferred(encoded, signal) {
        if (encoded.byteLength > MAX_HARNESS_API_TRANSFER_BYTES) {
            throw new Error('The Harness API request exceeds the remote image transfer limit.');
        }
        const transferId = uuidV7();
        const totalChunks = Math.ceil(encoded.byteLength / HARNESS_API_TRANSFER_CHUNK_BYTES);
        let opened = false;
        try {
            await this.client.rpc('harness.api.transfer.open', {
                transferId,
                totalBytes: encoded.byteLength,
                totalChunks,
            }, signal);
            opened = true;
            for (let index = 0; index < totalChunks; index += 1) {
                const start = index * HARNESS_API_TRANSFER_CHUNK_BYTES;
                const chunk = encoded.subarray(start, Math.min(start + HARNESS_API_TRANSFER_CHUNK_BYTES, encoded.byteLength));
                await this.client.rpc('harness.api.transfer.chunk', {
                    transferId,
                    index,
                    data: bytesToBase64(chunk),
                }, signal);
            }
            const committed = await this.client.rpc('harness.api.transfer.commit', { transferId }, signal);
            if (committed.kind === 'inline')
                return committed.response;
            if (committed.transferId !== transferId
                || committed.totalBytes <= 0
                || committed.totalBytes > MAX_HARNESS_API_TRANSFER_BYTES
                || committed.totalChunks !== Math.ceil(committed.totalBytes / HARNESS_API_TRANSFER_CHUNK_BYTES)) {
                throw new Error('The remote Host returned an invalid Harness API transfer descriptor.');
            }
            const responseBytes = new Uint8Array(committed.totalBytes);
            let offset = 0;
            for (let index = 0; index < committed.totalChunks; index += 1) {
                const result = await this.client.rpc('harness.api.transfer.read', { transferId, index }, signal);
                if (result.transferId !== transferId || result.index !== index) {
                    throw new Error('The remote Host returned an out-of-order Harness API transfer chunk.');
                }
                const chunk = base64ToBytes(result.data);
                const expectedBytes = Math.min(HARNESS_API_TRANSFER_CHUNK_BYTES, committed.totalBytes - offset);
                if (chunk.byteLength !== expectedBytes) {
                    throw new Error('The remote Host returned an invalid Harness API transfer chunk.');
                }
                responseBytes.set(chunk, offset);
                offset += chunk.byteLength;
            }
            return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(responseBytes));
        }
        finally {
            if (opened) {
                await this.client.rpc('harness.api.transfer.close', { transferId }).catch(() => undefined);
            }
        }
    }
    async respond(message) {
        return this.client.rpc('harness.api.respond', { message });
    }
    async *stream(stream, request, signal) {
        const streamId = uuidV7();
        const queue = new AsyncFrameQueue();
        const unsubscribe = this.client.onEvent(event => routeStreamEvent(event, streamId, queue));
        const unsubscribeClose = this.client.onClose(() => queue.close());
        const onAbort = () => queue.close();
        signal.addEventListener('abort', onAbort, { once: true });
        try {
            try {
                await this.client.rpc('harness.api.stream.open', {
                    streamId,
                    stream,
                    rpcId: String(request.rpcId),
                    payload: request.payload,
                }, signal);
                for await (const frame of queue)
                    yield frame;
            }
            catch (error) {
                // Native Harness event consumers treat a thrown stream iterator as a
                // fatal load failure. A remote disconnect is normal lifecycle here:
                // ClientModeRuntime has already switched ApiProxy back to local mode,
                // so finish this old iterator cleanly instead of terminating Harness.
                if (!isRemoteDisconnect(error))
                    throw error;
            }
        }
        finally {
            signal.removeEventListener('abort', onAbort);
            unsubscribe();
            unsubscribeClose();
            queue.close();
            await this.client.rpc('harness.api.stream.close', { streamId }).catch(() => undefined);
        }
    }
}
/** RC7 host.describe did not include the home field made mandatory by RC8. */
function normalizeLegacyResponse(method, response) {
    if (method !== 'host.describe' || !response.result.ok)
        return response;
    const value = response.result.value;
    if (typeof value !== 'object' || value === null || Array.isArray(value))
        return response;
    const description = value;
    if (typeof description.home === 'string' || typeof description.cwd !== 'string')
        return response;
    return {
        ...response,
        result: {
            ...response.result,
            value: { ...description, home: description.cwd },
        },
    };
}
/** Older Remote Web clients persisted the pre-rc.1 coding preset id as `code`. */
function normalizeLegacyRequest(method, payload) {
    if (!isRecord(payload))
        return payload;
    if (method === 'agentPreset.read')
        return replaceAgentPreset(payload, 'agentPreset');
    if (method === 'agentPreset.select')
        return replaceAgentPreset(payload, 'agentPreset');
    if (method === 'agentPreset.copy')
        return replaceAgentPreset(payload, 'from');
    if (method === 'settings.update' || method === 'settings.replace' || method === 'settings.mutate') {
        return replaceAgentPresetSettings(payload);
    }
    return payload;
}
function replaceAgentPreset(payload, key) {
    const value = payload[key];
    const replacement = typeof value === 'string' ? LEGACY_AGENT_PRESET_ALIASES[value] : undefined;
    return replacement === undefined ? payload : { ...payload, [key]: replacement };
}
function replaceAgentPresetSettings(payload) {
    if (payload.ns !== AGENT_PRESET_SETTINGS_NS || !isRecord(payload.patch))
        return payload;
    const patch = replaceAgentPreset(payload.patch, 'default');
    return patch === payload.patch ? payload : { ...payload, patch };
}
function isLegacyRemoteHost(version) {
    if (version === undefined)
        return false;
    const match = /^(?:dsh-)?v?(\d+)\.(\d+)\.(\d+)(?:-|$)/u.exec(version.trim());
    return match !== null && Number(match[1]) === 0 && Number(match[2]) === 1 && Number(match[3]) < 7;
}
function isWelcomeNoticeRequest(payload) {
    if (!isRecord(payload) || payload.ns !== WELCOME_NOTICE_NAMESPACE || !Array.isArray(payload.ops))
        return false;
    return payload.ops.some(operation => isRecord(operation)
        && operation.op === 'set'
        && Array.isArray(operation.path)
        && operation.path.length === 1
        && operation.path[0] === WELCOME_NOTICE_FIELD
        && operation.value === WELCOME_NOTICE_VERSION);
}
function patchWelcomeDescribe(response) {
    if (!response.result.ok || !isRecord(response.result.value) || !Array.isArray(response.result.value.namespaces))
        return response;
    return {
        ...response,
        result: {
            ...response.result,
            value: {
                ...response.result.value,
                namespaces: response.result.value.namespaces.map(namespace => {
                    if (!isRecord(namespace) || namespace.ns !== WELCOME_NOTICE_NAMESPACE)
                        return namespace;
                    const value = isRecord(namespace.value) ? namespace.value : {};
                    return { ...namespace, value: { ...value, [WELCOME_NOTICE_FIELD]: WELCOME_NOTICE_VERSION } };
                }),
            },
        },
    };
}
function patchWelcomeMutate(value, rpcId) {
    const response = {
        rpcId,
        result: { ok: true, value: { ...value, namespaces: [] } },
    };
    const namespaces = Array.isArray(value.namespaces) ? value.namespaces : [];
    response.result.value.namespaces = namespaces.map(namespace => {
        if (!isRecord(namespace) || namespace.ns !== WELCOME_NOTICE_NAMESPACE)
            return namespace;
        const current = isRecord(namespace.value) ? namespace.value : {};
        return { ...namespace, value: { ...current, [WELCOME_NOTICE_FIELD]: WELCOME_NOTICE_VERSION } };
    });
    return response;
}
function isRemoteDisconnect(error) {
    return error instanceof RemoteClientError
        && (error.code === 'TRANSPORT_CLOSED' || error.code === 'CLIENT_CLOSED');
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
class AsyncFrameQueue {
    values = [];
    waiters = [];
    closed = false;
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
        for (const waiter of this.waiters.splice(0))
            waiter({ done: true, value: undefined });
    }
    async *[Symbol.asyncIterator]() {
        while (true) {
            const value = this.values.shift();
            if (value !== undefined) {
                yield value;
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
function routeStreamEvent(event, streamId, queue) {
    if (event.event === 'harness.api.frame') {
        const data = event.data;
        if (data.streamId !== streamId || typeof data.frame !== 'object' || data.frame === null
            || typeof data.frame.rpcId !== 'string' || !('payload' in data.frame))
            return;
        queue.push(data.frame);
    }
    if (event.event === 'harness.api.stream.closed') {
        const data = event.data;
        if (data.streamId === streamId)
            queue.close();
    }
}
function bytesToBase64(bytes) {
    let binary = '';
    for (let offset = 0; offset < bytes.byteLength; offset += 0x8000) {
        binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.byteLength)));
    }
    return btoa(binary);
}
function base64ToBytes(value) {
    let binary;
    try {
        binary = atob(value);
    }
    catch {
        throw new Error('The remote Host returned a malformed Harness API transfer chunk.');
    }
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1)
        bytes[index] = binary.charCodeAt(index);
    if (bytesToBase64(bytes) !== value) {
        throw new Error('The remote Host returned a non-canonical Harness API transfer chunk.');
    }
    return bytes;
}
/**
 * \`workspaceFiles.readBytes\` must reach the native UI as a \`Uint8Array\`.
 *
 * The CodeX workspace projection answers it with base64 for its own consumers, and the generated schema
 * on the native side rejects that with \`expected "Uint8Array", path: ["data"]\`. This carrier uses the
 * dotted method name, so the check is separate from the Typert one. A shape warning (type and key count
 * only) makes a value that still cannot be decoded identifiable instead of invisible.
 *
 * @param method - ApiProxy method name.
 * @param response - the peer's response.
 * @returns the response with byte-valued fields restored.
 */
/** Shapes only: what a byte field looks like where it crosses a seam (never the content). */
function describeBytes(data) {
    return {
        dataIsBytes: data instanceof Uint8Array,
        dataType: typeof data,
        dataKeys: typeof data === 'object' && data !== null ? Object.keys(data).length : 0,
        preview: typeof data === 'string' ? data.slice(0, 12) : undefined,
    };
}
function normalizeByteResult(method, response) {
    if (method !== 'workspaceFiles.readBytes')
        return response;
    const result = response.result;
    if (result === undefined || result.ok !== true)
        return response;
    const value = result.value;
    if (typeof value !== 'object' || value === null || Array.isArray(value))
        return response;
    const data = value.data;
    console.warn('[dsh-remote] workspaceFiles/readBytes at the ApiProxy exit', describeBytes(data));
    if (data instanceof Uint8Array)
        return response;
    const bytes = decodeByteValue(data);
    // Shapes only; never the content itself. This is the seam that faces the local shell.
    console.warn('[dsh-remote] workspace probe', {
        where: 'apiproxy.exit',
        endpoint: method,
        dataType: typeof data,
        dataKeys: typeof data === 'object' && data !== null ? Object.keys(data).length : 0,
        decoded: bytes !== undefined,
    });
    if (bytes === undefined)
        return response;
    return { ...response, result: { ...result, value: { ...value, data: bytes } } };
}
//# sourceMappingURL=remote-api-proxy.js.map