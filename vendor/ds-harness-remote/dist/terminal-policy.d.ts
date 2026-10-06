import type { TypertRpcResult } from './typert-gateway-contract.js';
export declare const TERMINAL_CALLS: Set<string>;
export declare const TERMINAL_STREAMS: Set<string>;
/** Host-lifetime device ownership survives transport reconnects; never supplied by the client. */
export declare class TerminalPolicy {
    private readonly enabled;
    private readonly deviceId;
    private readonly owners;
    private readonly attachments;
    constructor(enabled: boolean | (() => boolean), deviceId: string, owners: Map<string, string>);
    check(endpoint: string, payload: unknown): {
        key?: string;
        created?: boolean;
    };
    result(endpoint: string, payload: unknown, result: TypertRpcResult, reservation: {
        key?: string;
        created?: boolean;
    }): TypertRpcResult;
    private deny;
}
//# sourceMappingURL=terminal-policy.d.ts.map