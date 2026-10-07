import { decodeByteValue } from './rpc-binary-attachments.js';
const REMOTE_COMMAND_METHODS = ['execute', 'list'];
const LOCAL_ONLY_NAMESPACES = new Set(['dynamicCordisRunner']);
/**
 * Namespaces the local shell keeps answering even when the peer is gone.
 *
 * A remote-mode window asks for its settings bootstrap, its plugin registry stream and its account
 * read before any remote work happens, and only the local shell can answer those - that is why the
 * fallback exists at all. Everything else is remote data, and answering data locally during an
 * outage is what makes the native UI cache a local answer as if it came from the remote workspace:
 * a session list served that way stays wrong even after the link returns.
 */
const LOCAL_FALLBACK_NAMESPACES = new Set(['$events', 'settings', 'credentials', 'dynamicCordisRunner']);
/** Codes that mean the peer is not there right now, as opposed to answering with a refusal. */
const PEER_GONE_CODES = new Set([
    'TRANSPORT_CLOSED',
    'CLIENT_CLOSED',
    'RPC_TIMEOUT',
    'CONNECTION_FAILED',
    'CONNECTION_REPLACED',
    'NOT_CONNECTED',
    'UNAVAILABLE',
    'internal',
]);
const ALL_REMOTE_COMMANDS = { execute: true, list: true };
/** Keeps the official Gateway object stable while its selected Host changes. */
export class TypertGatewaySwitch {
    runtime;
    originalInvoke;
    localInvoke;
    originalStream;
    localStream;
    originalDispatch;
    localDispatch;
    originalOpen;
    localOpen;
    remoteInvoke;
    remoteTarget;
    remoteSupport = { execute: false, list: false };
    target;
    installed = false;
    /** Defaults to reachable so a caller that never wires this keeps the old behaviour. */
    remoteAvailability = () => true;
    constructor(gateway) {
        this.runtime = gateway;
        this.originalInvoke = gateway.invoke;
        this.localInvoke = this.originalInvoke.bind(gateway);
        this.originalStream = gateway.stream;
        this.localStream = gateway.stream?.bind(gateway);
        this.originalDispatch = this.runtime.dispatchRpc;
        this.localDispatch = this.runtime.dispatchRpc?.bind(gateway);
        this.originalOpen = this.runtime.openWireStream;
        this.localOpen = createLocalOpen(gateway, this.runtime);
    }
    /** Original local dispatcher, used by the Host bridge without switch recursion. */
    local() {
        const dispatch = this.localDispatch ?? (async (endpoint, payload, signal) => {
            try {
                const request = requestFromCarrier(endpoint, payload, signal);
                return { ok: true, value: await this.localInvoke(request) };
            }
            catch (error) {
                return { ok: false, error: this.failure(error) };
            }
        });
        const open = this.localOpen ?? (async (endpoint, payload, signal) => {
            if (this.localStream === undefined)
                throw new Error('The local Harness Gateway does not support Remote streams.');
            return this.localStream(requestFromCarrier(endpoint, payload, signal));
        });
        return {
            invoke: this.localInvoke,
            ...(this.localStream === undefined ? {} : { stream: this.localStream }),
            dispatch,
            open,
            failure: error => this.failure(error),
            supportsCarrier: this.localDispatch !== undefined && this.localOpen !== undefined,
        };
    }
    supportsCarrier() {
        return this.localDispatch !== undefined && this.localOpen !== undefined;
    }
    status() {
        return this.remoteInvoke === undefined
            ? { mode: 'local' }
            : { mode: 'remote', ...(this.target === undefined ? {} : { target: { ...this.target } }) };
    }
    install() {
        if (this.installed)
            return;
        this.runtime.invoke = request => this.selectInvoke(request);
        // The carriers this switch was installed in front of. A target that owns only part of the endpoint
        // space - the command namespace in an rc.2 window, where the ApiProxy switch owns the data plane - must
        // pass every other endpoint down to them. Answering one here sends it to the local shell instead of the
        // peer: that is how workspaceFiles/readBytes came back in a shape the native schema rejects.
        // Keep the receiver: the local dispatcher is an object method that reads its own fields, and calling
        // it unbound failed with a missing invokeRpc on an undefined receiver - which broke every local call
        // whenever this switch forwarded instead of answering itself.
        const previousStream = this.runtime.stream?.bind(this.runtime);
        const previousDispatch = this.runtime.dispatchRpc?.bind(this.runtime);
        const previousOpen = this.runtime.openWireStream;
        // Forwarding is for a window whose data plane belongs to another carrier while the peer is up. With
        // no reachable peer the shell must work locally - forwarding there reached a carrier that cannot
        // serve it and every local call failed with a missing invokeRpc on an undefined carrier.
        const forwards = (endpoint) => (this.remoteTarget === undefined && this.remoteAvailability() && !isLocalOnlyEndpoint(endpoint));
        if (previousStream !== undefined) {
            this.runtime.stream = request => forwards(endpointOf(request))
                ? previousStream(request)
                : !this.routesToRemote(endpointOf(request))
                    ? this.localStream(request)
                    : this.withLocalFallback(endpointOf(request), () => this.remoteTarget.open(endpointOf(request), { args: request.args }, request.signal ?? new AbortController().signal), () => this.localStream(request));
        }
        if (previousDispatch !== undefined) {
            this.runtime.dispatchRpc = (endpoint, payload, signal) => forwards(endpoint)
                ? Promise.resolve(previousDispatch(endpoint, payload, signal)).catch(error => {
                    // Without a remote target the shell has to work locally, and a failing local call is otherwise
                    // invisible: the fallback exists precisely so the user can keep working.
                    console.warn('[dsh-remote] local call failed with no remote target selected', {
                        endpoint,
                        code: typeof error === 'object' && error !== null && 'code' in error ? String(error.code) : undefined,
                        message: error instanceof Error ? error.message : String(error),
                    });
                    throw error;
                })
                : !this.routesToRemote(endpoint)
                    ? this.localDispatch(endpoint, payload, signal)
                    : this.withLocalFallback(endpoint, () => Promise.resolve(this.remoteTarget.dispatch(endpoint, payload, signal)), () => this.localDispatch(endpoint, payload, signal)).then(result => normalizeByteResult(endpoint, result));
        }
        if (previousOpen !== undefined) {
            const open = previousOpen;
            const rc1 = usesRc1Arity(open);
            this.runtime.openWireStream = (...callArgs) => {
                const endpoint = callArgs[0];
                if (forwards(endpoint)) {
                    return rc1
                        ? Reflect.apply(open, this.runtime, callArgs)
                        : open.call(this.runtime, endpoint, callArgs[1], callArgs[2]);
                }
                if (!this.routesToRemote(endpoint)) {
                    return rc1
                        ? Reflect.apply(open, this.runtime, callArgs)
                        : open.call(this.runtime, endpoint, callArgs[1], callArgs[2]);
                }
                const signal = (rc1 ? callArgs[4] : callArgs[2]);
                if (!rc1) {
                    // The legacy carrier returns an iterable, so a rejection cannot be caught and
                    // the local fallback stays unavailable on that arity.
                    return this.remoteTarget.open(endpoint, callArgs[1], signal ?? new AbortController().signal);
                }
                return this.withLocalFallback(endpoint, () => this.remoteTarget.open(endpoint, callArgs[1], signal ?? new AbortController().signal), () => Reflect.apply(open, this.runtime, callArgs));
            };
        }
        this.installed = true;
    }
    selectRemote(remote, support = ALL_REMOTE_COMMANDS, target) {
        if (!this.installed)
            throw new Error('The Typert gateway switch is not installed.');
        this.remoteInvoke = typeof remote === 'function' ? remote : request => remote.invoke(request);
        this.remoteTarget = typeof remote === 'function' ? undefined : remote;
        this.remoteSupport = { ...support };
        this.target = target === undefined ? undefined : { ...target };
    }
    selectLocal() {
        this.remoteInvoke = undefined;
        this.remoteTarget = undefined;
        this.remoteSupport = { execute: false, list: false };
        this.target = undefined;
    }
    /**
     * The local shell's carriers, for a remote target that owns only part of the
     * endpoint space. The Codex virtual Harness owns the CodeX domain; the shell's own
     * settings bootstrap, plugin registry and account reads must stay here, or the
     * window describes the remote Host instead of this installation.
     * @returns the captured local carriers, absent when the running release has none.
     */
    localCarrier() {
        return {
            ...(this.localDispatch === undefined ? {} : { dispatch: this.localDispatch }),
            ...(this.localOpen === undefined ? {} : { open: this.localOpen }),
        };
    }
    restore() {
        if (!this.installed)
            return;
        this.selectLocal();
        this.runtime.invoke = this.originalInvoke;
        if (this.originalStream !== undefined)
            this.runtime.stream = this.originalStream;
        if (this.originalDispatch !== undefined)
            this.runtime.dispatchRpc = this.originalDispatch;
        if (this.originalOpen !== undefined)
            this.runtime.openWireStream = this.originalOpen;
        this.installed = false;
    }
    /**
     * Whether the peer a remote target routes to is reachable right now.
     *
     * A remote-mode boot still needs its own local services — localizations, theme,
     * the plugin registry — before any remote work can happen. Routing those to a
     * peer that is not connected leaves them unanswered, so the whole shell fails to
     * activate: a dropped connection becomes "the application is unavailable" and
     * stays that way until the user restarts into local mode. Serve local while the
     * peer is away; the live session takes over again as soon as it is reachable.
     */
    setRemoteAvailability(check) { this.remoteAvailability = check; }
    routesToRemote(endpoint) {
        return this.remoteTarget !== undefined && !isLocalOnlyEndpoint(endpoint) && this.remoteAvailability();
    }
    selectInvoke(request) {
        if (isLocalOnlyEndpoint(endpointOf(request)))
            return this.localInvoke(request);
        if (this.remoteTarget !== undefined && this.remoteAvailability()) {
            // A remote-mode boot still issues RPCs only the local shell can answer: the
            // Desktop asks the local Web server for its locale bootstrap before any remote
            // work happens. Sweeping those to the peer failed as "desktop welcome: Web RPC
            // failed", the locale plugin failed with it, and 48 entries never activated.
            // Serve locally when the peer does not implement the endpoint, or when it went
            // away mid-call; a business error still propagates.
            return this.remoteTarget.invoke(request)
                .then(result => normalizeByteResult(endpointOf(request), result))
                .catch(error => {
                if (!localFallbackAllowed(endpointOf(request), error))
                    throw error;
                console.warn(`[dsh-remote] serving ${endpointOf(request)} locally: the peer did not answer it`, error);
                return this.localInvoke(request);
            });
        }
        if (request.namespace !== 'commands' || !isRemoteCommandMethod(request.method) || this.remoteInvoke === undefined) {
            return this.localInvoke(request);
        }
        if (!this.remoteAvailability())
            return this.localInvoke(request);
        if (this.remoteSupport[request.method])
            return this.remoteInvoke(request);
        if (request.method === 'list')
            return Promise.resolve([]);
        return this.localInvoke(request);
    }
    /**
     * Run a remote call, and answer it locally when the peer turns out not to serve
     * that endpoint. A remote-mode boot still issues RPCs only the local shell can
     * answer: the Desktop asks the local Web server for its locale bootstrap over the
     * remote mux before any remote work happens. Forwarding those swept them to a host
     * that does not implement them, the locale plugin failed, and every entry
     * depending on it stayed pending. A genuine business error still propagates.
     * @param endpoint - endpoint being routed, for the diagnostic warning.
     * @param remote - the remote carrier call.
     * @param local - the local call used when the peer could not answer.
     * @returns the remote or local result.
     */
    withLocalFallback(endpoint, remote, local) {
        return remote().catch(error => {
            if (!localFallbackAllowed(endpoint, error))
                throw error;
            console.warn(`[dsh-remote] serving ${endpoint} locally: the peer did not answer it`, error);
            return local();
        });
    }
    failure(error) {
        const normalized = this.runtime.wireStream?.failure(error);
        if (normalized !== undefined)
            return normalized;
        const source = error instanceof Error ? error : new Error('The Harness Gateway rejected the request.');
        const code = 'code' in source && typeof source.code === 'string' ? source.code : 'internal';
        const details = 'details' in source && isRecord(source.details) ? source.details : {};
        return { code, message: source.message, details };
    }
}
/**
 * dsh 0.1.7-rc.1 moved the cancellation signal from the third parameter to the
 * fifth; legacy releases keep `(endpoint, payload, signal)`.
 * @param open - carrier opener captured from the running release.
 * @returns whether the opener follows the rc.1 argument order.
 */
function usesRc1Arity(open) {
    return open.length >= 5;
}
/** An uplink nobody sends on, so a read-only stream opens without buffering. */
function endedUplink() {
    return {
        [Symbol.asyncIterator]() {
            return { next: () => Promise.resolve({ done: true, value: undefined }) };
        },
    };
}
/** Controller aborted with the logical stream so the rc.1 carrier owns one lifetime. */
function linkControl(signal) {
    const control = new AbortController();
    if (signal.aborted)
        control.abort(signal.reason);
    else
        signal.addEventListener('abort', () => control.abort(signal.reason), { once: true });
    return control;
}
/**
 * Adapt the running release's carrier opener to the plugin's 3-argument
 * `(endpoint, payload, signal)` seam without leaking the argument displacement.
 * @param gateway - official Gateway whose public `wireStream` is the fallback.
 * @param runtime - Gateway object owning the private `openWireStream` dispatcher.
 * @returns a local opener that carries the signal to the release's signal slot.
 */
function createLocalOpen(gateway, runtime) {
    const open = runtime.openWireStream;
    if (open !== undefined) {
        return usesRc1Arity(open)
            ? (endpoint, payload, signal) => open
                .call(runtime, endpoint, payload, endedUplink(), undefined, signal, linkControl(signal))
            : (endpoint, payload, signal) => open.call(runtime, endpoint, payload, signal);
    }
    const wire = gateway.wireStream;
    if (wire === undefined)
        return undefined;
    const wireOpen = wire.open;
    if (usesRc1Arity(wireOpen)) {
        const rc1 = wireOpen;
        return (endpoint, payload, signal) => rc1.call(wire, endpoint, payload, endedUplink(), undefined, signal);
    }
    const legacy = wireOpen;
    return (endpoint, payload, signal) => legacy.call(wire, endpoint, payload, signal);
}
function requestFromCarrier(endpoint, payload, signal) {
    const segments = endpoint.split('/');
    if (segments.length !== 2 || segments.some(segment => segment.length === 0)) {
        throw new Error('The Harness Gateway endpoint is invalid.');
    }
    if (!isRecord(payload) || !isRecord(payload.args)) {
        throw new Error('The Harness Gateway payload is invalid.');
    }
    return { namespace: segments[0], method: segments[1], args: payload.args, signal };
}
function endpointOf(request) {
    return `${request.namespace}/${request.method}`;
}
function namespaceOf(endpoint) {
    const separator = endpoint.indexOf('/');
    return separator > 0 ? endpoint.slice(0, separator) : undefined;
}
function isLocalOnlyEndpoint(endpoint) {
    const namespace = namespaceOf(endpoint);
    return namespace !== undefined && LOCAL_ONLY_NAMESPACES.has(namespace);
}
/**
 * Whether the local shell may answer a call the peer could not.
 *
 * A refusal means the peer does not implement the endpoint, and the local shell is the right answer
 * for the bootstrap calls a window needs. A peer that is *gone* is different: serving its data
 * locally hands the UI a plausible wrong answer, so only the bootstrap namespaces may answer then.
 * @param endpoint - endpoint being routed.
 * @param error - rejection from the remote carrier.
 * @returns whether the local shell should answer instead.
 */
function localFallbackAllowed(endpoint, error) {
    if (!isUnansweredByPeer(error))
        return false;
    if (!isRecord(error))
        return true;
    const code = typeof error.code === 'string' ? error.code : '';
    if (!PEER_GONE_CODES.has(code))
        return true;
    const namespace = namespaceOf(endpoint);
    return namespace !== undefined && LOCAL_FALLBACK_NAMESPACES.has(namespace);
}
function isRemoteCommandMethod(method) {
    return REMOTE_COMMAND_METHODS.includes(method);
}
/**
 * Whether the peer cannot answer this call at all, as opposed to answering with a
 * refusal. An unimplemented endpoint (a local-only concern the switch forwarded
 * anyway) and a connection that dropped mid-call are both permissionless to retry
 * locally; a business error such as a denied permission is not.
 * @param error - rejection from the remote carrier.
 * @returns whether the local shell should answer the call instead.
 */ function isUnansweredByPeer(error) {
    // A wrapped carrier failure such as "Web RPC failed" often arrives with no code at
    // all, and refusing to fall back there would leave the local shell unanswered. The
    // warning emitted at the call site keeps the decision visible in DevTools.
    if (!isRecord(error))
        return error instanceof Error;
    const code = typeof error.code === 'string' ? error.code : '';
    return code === '' || UNANSWERED_BY_PEER_CODES.has(code);
}
const UNANSWERED_BY_PEER_CODES = new Set([
    'METHOD_NOT_ALLOWED',
    'METHOD_NOT_FOUND',
    'method-not-found',
    'not-implemented',
    'CONNECTION_FAILED',
    'CONNECTION_REPLACED',
    'NOT_CONNECTED',
    'UNAVAILABLE',
    // The client's own transport codes: the peer cannot answer a call it never received, so these are
    // "unanswered" rather than a refusal - and PEER_GONE_CODES above is what decides whether the local
    // shell may answer for it.
    'TRANSPORT_CLOSED',
    'CLIENT_CLOSED',
    'RPC_TIMEOUT',
    'internal',
]);
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
/**
 * Endpoints whose canonical result carries bytes, which the CodeX projection answers with base64.
 *
 * The native UI validates \`workspaceFiles/readBytes\` against a generated schema that requires a real
 * \`Uint8Array\`, while the CodeX workspace projection returns base64 for its own consumers - reaching that
 * schema as \`expected "Uint8Array", path: ["data"]\`, the image-preview failure. Normalising at the local
 * carrier's exit keeps the projection's own wire unchanged and only fixes what the local shell receives.
 *
 * @param endpoint - endpoint the result belongs to.
 * @param result - the peer's result.
 * @returns the result with byte-valued fields restored to \`Uint8Array\`.
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
function normalizeByteResult(endpoint, result) {
    if (endpoint !== 'workspaceFiles/readBytes')
        return result;
    if (typeof result !== 'object' || result === null || Array.isArray(result))
        return result;
    const value = result.value;
    if (typeof value !== 'object' || value === null || Array.isArray(value))
        return result;
    const data = value.data;
    console.warn('[dsh-remote] workspaceFiles/readBytes at the switch exit', describeBytes(data));
    if (data instanceof Uint8Array)
        return result;
    const bytes = decodeByteValue(data);
    // Shapes only; never the content itself. This is the seam that faces the local shell.
    console.warn('[dsh-remote] workspace probe', {
        where: 'switch.exit',
        endpoint,
        dataType: typeof data,
        dataKeys: typeof data === 'object' && data !== null ? Object.keys(data).length : 0,
        decoded: bytes !== undefined,
    });
    if (bytes === undefined)
        return result;
    return { ...result, value: { ...value, data: bytes } };
}
//# sourceMappingURL=typert-gateway-switch.js.map