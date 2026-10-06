import { createRpcError, createRpcResponse, } from '@dsh-remote/protocol';
import { z } from 'zod';
import { RpcError, safeErrorCode } from './safe-error.js';
export { RpcError } from './safe-error.js';
const wireRequestSchema = z.object({ method: z.string().min(1), params: z.unknown() }).strict();
const emptyParamsSchema = z.object({}).strict();
const apiMethods = new Set([
    'harness.transport.describe',
    'harness.api.call',
    'harness.api.transfer.open',
    'harness.api.transfer.chunk',
    'harness.api.transfer.commit',
    'harness.api.transfer.read',
    'harness.api.transfer.close',
    'harness.api.respond',
    'harness.api.stream.open',
    'harness.api.stream.close',
    'harness.remote.call',
    'harness.remote.transfer.open',
    'harness.remote.transfer.chunk',
    'harness.remote.transfer.commit',
    'harness.remote.transfer.read',
    'harness.remote.transfer.close',
    'harness.remote.stream.open',
    'harness.remote.stream.close',
    'loopback.call',
    'fileviewer.call',
    'codex.app.call',
    'codex.app.respond',
    'codex.app.stream.open',
    'codex.app.stream.close',
    'codex.app.transfer.open',
    'codex.app.transfer.chunk',
    'codex.app.transfer.commit',
    'codex.app.transfer.read',
    'codex.app.transfer.close',
    'acp.initialize', 'acp.session.new', 'acp.session.load', 'acp.session.prompt',
    'acp.session.respond_permission', 'acp.session.cancel', 'acp.session.set_mode',
]);
export const HOST_CAPABILITIES = [
    'harness.api.v1',
    'harness.api.transfer.v1',
    'harness.remote.v1',
    'harness.remote.transfer.v1',
    'fileviewer.read.v1',
    'codex.appserver.v1',
    'codex.appserver.transfer.v1',
    'agent.acp.v1',
];
export class RpcRouter {
    harnessApi;
    maxPending;
    logger;
    fileViewer;
    harnessRemote;
    capabilities;
    codex;
    acp;
    loopback;
    active = 0;
    constructor(harnessApi, maxPending = 128, logger, fileViewer, harnessRemote, capabilities = () => HOST_CAPABILITIES, codex, acp, loopback) {
        this.harnessApi = harnessApi;
        this.maxPending = maxPending;
        this.logger = logger;
        this.fileViewer = fileViewer;
        this.harnessRemote = harnessRemote;
        this.capabilities = capabilities;
        this.codex = codex;
        this.acp = acp;
        this.loopback = loopback;
    }
    async closePeerStreams() {
        this.loopback?.closeAll();
        await Promise.all([
            this.harnessApi?.closeAll(),
            this.harnessRemote?.closeAll(),
            this.codex?.closeAll(),
        ]);
    }
    async handle(message) {
        if (message.type !== 'rpc.request') {
            return createRpcError(message.id, 'INVALID_MESSAGE', 'Only RPC requests are accepted on the Host business channel.');
        }
        const parsedPayload = wireRequestSchema.safeParse(message.payload);
        if (!parsedPayload.success)
            return createRpcError(message.id, 'INVALID_MESSAGE', 'The RPC request payload is invalid.');
        if (!apiMethods.has(parsedPayload.data.method)) {
            return createRpcError(message.id, 'METHOD_NOT_FOUND', 'The requested method does not exist.');
        }
        const request = message;
        if (this.active >= this.maxPending) {
            return createRpcError(request.id, 'RATE_LIMITED', 'Too many Host requests are already pending.', undefined, true);
        }
        this.active += 1;
        const startedAt = performance.now();
        try {
            const result = await this.invoke(request.payload.method, request.payload.params);
            this.logger?.debug('host rpc ok', {
                method: request.payload.method,
                durationMs: Math.round(performance.now() - startedAt),
            });
            return createRpcResponse(request.id, result);
        }
        catch (error) {
            const response = errorResponse(request.id, error);
            this.logger?.warn('host rpc failed', {
                method: request.payload.method,
                durationMs: Math.round(performance.now() - startedAt),
                code: response.payload.code,
                retryable: response.payload.retryable,
            });
            return response;
        }
        finally {
            this.active -= 1;
        }
    }
    invoke(method, params) {
        switch (method) {
            case 'harness.transport.describe': {
                emptyParamsSchema.parse(params);
                return { capabilities: [...this.capabilities()] };
            }
            case 'harness.api.call': return this.requireApiProxy().call(params);
            case 'harness.api.transfer.open': return this.requireApiProxy().openTransfer(params);
            case 'harness.api.transfer.chunk': return this.requireApiProxy().appendTransfer(params);
            case 'harness.api.transfer.commit': return this.requireApiProxy().commitTransfer(params);
            case 'harness.api.transfer.read': return this.requireApiProxy().readTransfer(params);
            case 'harness.api.transfer.close': return this.requireApiProxy().closeTransfer(params);
            case 'harness.api.respond': return this.requireApiProxy().respond(params);
            case 'harness.api.stream.open': return this.requireApiProxy().openStream(params);
            case 'harness.api.stream.close': return this.requireApiProxy().closeStream(params);
            case 'harness.remote.call': return this.requireRemoteGateway().call(params);
            case 'harness.remote.transfer.open': return this.requireRemoteGateway().openTransfer(params);
            case 'harness.remote.transfer.chunk': return this.requireRemoteGateway().appendTransfer(params);
            case 'harness.remote.transfer.commit': return this.requireRemoteGateway().commitTransfer(params);
            case 'harness.remote.transfer.read': return this.requireRemoteGateway().readTransfer(params);
            case 'harness.remote.transfer.close': return this.requireRemoteGateway().closeTransfer(params);
            case 'harness.remote.stream.open': return this.requireRemoteGateway().openStream(params);
            case 'harness.remote.stream.close': return this.requireRemoteGateway().closeStream(params);
            case 'loopback.call': {
                if (this.loopback === undefined)
                    throw new RpcError('FEATURE_NOT_SUPPORTED', 'Loopback preview is unavailable on this Host.');
                return this.loopback.call(params);
            }
            case 'fileviewer.call': {
                if (this.fileViewer === undefined) {
                    throw new RpcError('FILE_VIEWER_UNAVAILABLE', 'The Remote Host does not have DSH File Viewer available.');
                }
                return this.fileViewer.call(params);
            }
            case 'codex.app.call': return this.requireCodex().call(params);
            case 'codex.app.respond': return this.requireCodex().respond(params);
            case 'codex.app.stream.open': return this.requireCodex().openStream(params);
            case 'codex.app.stream.close': return this.requireCodex().closeStream(params);
            case 'codex.app.transfer.open': return this.requireCodex().openTransfer(params);
            case 'codex.app.transfer.chunk': return this.requireCodex().appendTransfer(params);
            case 'codex.app.transfer.commit': return this.requireCodex().commitTransfer(params);
            case 'codex.app.transfer.read': return this.requireCodex().readTransfer(params);
            case 'codex.app.transfer.close': return this.requireCodex().closeTransfer(params);
            case 'acp.initialize': return this.requireAcp().initialize(params);
            case 'acp.session.new': return this.requireAcp().sessionNew(params);
            case 'acp.session.load': return this.requireAcp().sessionLoad(params);
            case 'acp.session.prompt': return this.requireAcp().prompt(params, async () => undefined);
            case 'acp.session.respond_permission': return this.requireAcp().respondPermission(params);
            case 'acp.session.cancel': return this.requireAcp().cancel(params);
            case 'acp.session.set_mode': return this.requireAcp().setMode(params);
            default: throw new RpcError('METHOD_NOT_FOUND', 'The requested method does not exist.');
        }
    }
    requireApiProxy() {
        if (this.harnessApi === undefined) {
            throw new RpcError('FEATURE_NOT_SUPPORTED', 'This Harness version does not provide the legacy ApiProxy transport.');
        }
        return this.harnessApi;
    }
    requireRemoteGateway() {
        if (this.harnessRemote === undefined) {
            throw new RpcError('FEATURE_NOT_SUPPORTED', 'This Harness version does not provide the Remote Gateway transport.');
        }
        return this.harnessRemote;
    }
    requireAcp() {
        if (!this.acp)
            throw new RpcError('CAPABILITY_NOT_SUPPORTED', 'ACP is not configured on this Host.');
        return this.acp;
    }
    requireCodex() {
        if (this.codex === undefined) {
            throw new RpcError('FEATURE_NOT_SUPPORTED', 'Codex Remote is disabled or unavailable on this Host.');
        }
        return this.codex;
    }
}
function errorResponse(requestId, error) {
    const code = safeErrorCode(error);
    if (error instanceof RpcError)
        return createRpcError(requestId, code, error.message, error.details, error.retryable);
    return createRpcError(requestId, code, code === 'INVALID_MESSAGE' ? 'The RPC parameters are invalid.' : 'The Host could not complete the request.');
}
//# sourceMappingURL=rpc-router.js.map