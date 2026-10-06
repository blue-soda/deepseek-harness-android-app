import type { LocalTypertGateway, RemoteTypertGatewayTarget, TypertGatewayLike, TypertGatewayRequest, TypertRpcResult } from './typert-gateway-contract.js';
type RemoteInvoke = (request: TypertGatewayRequest) => Promise<unknown>;
type CarrierDispatch = (endpoint: string, payload: unknown, signal: AbortSignal) => Promise<TypertRpcResult>;
type CarrierOpen = (endpoint: string, payload: unknown, signal: AbortSignal) => Promise<AsyncIterable<unknown>>;
export interface RemoteCommandSupport {
    execute: boolean;
    list: boolean;
}
/** Keeps the official Gateway object stable while its selected Host changes. */
export declare class TypertGatewaySwitch {
    private readonly runtime;
    private readonly originalInvoke;
    private readonly localInvoke;
    private readonly originalStream?;
    private readonly localStream?;
    private readonly originalDispatch?;
    private readonly localDispatch?;
    private readonly originalOpen?;
    private readonly localOpen?;
    private remoteInvoke?;
    private remoteTarget?;
    private remoteSupport;
    private target?;
    private installed;
    /** Defaults to reachable so a caller that never wires this keeps the old behaviour. */
    private remoteAvailability;
    constructor(gateway: TypertGatewayLike);
    /** Original local dispatcher, used by the Host bridge without switch recursion. */
    local(): LocalTypertGateway;
    supportsCarrier(): boolean;
    status(): {
        mode: 'local' | 'remote';
        target?: {
            deviceId: string;
            name: string;
        };
    };
    install(): void;
    selectRemote(remote: RemoteInvoke | RemoteTypertGatewayTarget, support?: RemoteCommandSupport, target?: {
        deviceId: string;
        name: string;
    }): void;
    selectLocal(): void;
    /**
     * The local shell's carriers, for a remote target that owns only part of the
     * endpoint space. The Codex virtual Harness owns the CodeX domain; the shell's own
     * settings bootstrap, plugin registry and account reads must stay here, or the
     * window describes the remote Host instead of this installation.
     * @returns the captured local carriers, absent when the running release has none.
     */
    localCarrier(): {
        dispatch?: CarrierDispatch;
        open?: CarrierOpen;
    };
    restore(): void;
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
    setRemoteAvailability(check: () => boolean): void;
    private routesToRemote;
    private selectInvoke;
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
    private withLocalFallback;
    private failure;
}
export {};
//# sourceMappingURL=typert-gateway-switch.d.ts.map