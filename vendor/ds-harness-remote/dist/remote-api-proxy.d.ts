import type { ApiProxy } from '@deepseek-ai/dsh-host-apiproxy/api';
import { type RemoteClientCore } from '@dsh-remote/client-core';
/** ApiProxy-compatible face that preserves the native Harness envelopes over Remote RPC. */
export declare class RemoteHarnessApiProxy {
    private readonly client;
    private readonly harnessVersion?;
    readonly api: ApiProxy;
    private legacyWelcomeAcknowledged;
    private settingsDescribeValue?;
    constructor(client: RemoteClientCore, harnessVersion?: string | undefined);
    private call;
    private normalizeLegacyWelcomeSettings;
    private callTransferred;
    private respond;
    private stream;
}
//# sourceMappingURL=remote-api-proxy.d.ts.map