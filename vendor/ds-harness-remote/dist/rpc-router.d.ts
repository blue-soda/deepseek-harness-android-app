import { type RemoteMessage } from '@dsh-remote/protocol';
import type { LoopbackHost } from './loopback-host.js';
import type { RemoteFileViewerBridge } from './file-viewer-bridge.js';
import type { HarnessApiBridge } from './harness-api-bridge.js';
import type { HarnessRemoteBridge } from './harness-remote-bridge.js';
import type { SafeLogger } from './logging.js';
import type { CodexPeerBridge } from './codex/peer-bridge.js';
import type { AcpGateway } from './acp.js';
export { RpcError } from './safe-error.js';
export declare const HOST_CAPABILITIES: readonly ["harness.api.v1", "harness.api.transfer.v1", "harness.remote.v1", "harness.remote.transfer.v1", "fileviewer.read.v1", "codex.appserver.v1", "codex.appserver.transfer.v1", "agent.acp.v1"];
export declare class RpcRouter {
    private readonly harnessApi;
    private readonly maxPending;
    private readonly logger?;
    private readonly fileViewer?;
    private readonly harnessRemote?;
    private readonly capabilities;
    private readonly codex?;
    private readonly acp?;
    private readonly loopback?;
    private active;
    constructor(harnessApi: HarnessApiBridge | undefined, maxPending?: number, logger?: SafeLogger | undefined, fileViewer?: RemoteFileViewerBridge | undefined, harnessRemote?: HarnessRemoteBridge | undefined, capabilities?: () => readonly string[], codex?: CodexPeerBridge | undefined, acp?: AcpGateway | undefined, loopback?: LoopbackHost | undefined);
    closePeerStreams(): Promise<void>;
    handle(message: RemoteMessage): Promise<RemoteMessage>;
    private invoke;
    private requireApiProxy;
    private requireRemoteGateway;
    private requireAcp;
    private requireCodex;
}
//# sourceMappingURL=rpc-router.d.ts.map