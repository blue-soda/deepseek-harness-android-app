import type { ApiProxy } from '@deepseek-ai/dsh-host-apiproxy/api';
export type HarnessMode = 'local' | 'remote';
export interface RemoteTarget {
    deviceId: string;
    name: string;
}
/**
 * Installs stable forwarding objects into the official ApiProxy instance.
 * Existing HTTP/WebSocket carriers retain the same service identity while new
 * requests resolve against the currently selected local or remote target.
 */
export declare class ApiProxySwitch {
    private remote?;
    private target?;
    private mode;
    private installed;
    private readonly local;
    private readonly originals;
    private readonly localRespond;
    constructor(local: ApiProxy);
    install(): void;
    selectRemote(api: ApiProxy, target: RemoteTarget): void;
    selectLocal(): void;
    status(): {
        mode: HarnessMode;
        target?: RemoteTarget;
    };
    restore(): void;
    private selected;
    private originalDomain;
    private requireRemote;
}
//# sourceMappingURL=api-proxy-switch.d.ts.map