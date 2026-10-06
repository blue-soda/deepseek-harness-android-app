import type { ApiProxy, RpcResponse } from '@deepseek-ai/dsh-host-apiproxy/api';
import type { HarnessApiFrameData, HarnessApiStreamClosedData, HarnessApiTransferCommitResult, HarnessApiTransferReadResult } from '@dsh-remote/protocol';
import type { SafeLogger } from './logging.js';
import type { TypertGatewayLike } from './typert-gateway-contract.js';
type PublishFrame = (event: 'harness.api.frame' | 'harness.api.stream.closed', data: HarnessApiFrameData | HarnessApiStreamClosedData) => Promise<void>;
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
export declare const HARNESS_API_ALLOWLIST: readonly ["session.list", "session.search", "session.create", "session.history", "session.models", "session.selectModel", "session.rename", "session.fork", "session.prompt", "session.attachment", "session.updateQueue", "session.cancel", "subagent.list", "subagent.history", "subagent.prompt", "subagent.interrupt", "host.describe", "host.listDirectory", "workspace.list", "workspace.create", "workspace.rename", "workspace.delete", "workspace.insertBefore", "workspace.insertSessionBefore", "workspace.archiveSession", "skill.list", "agentPreset.list", "agentPreset.select", "agentPreset.read", "goal.create", "goal.edit", "goal.pause", "goal.resume", "goal.complete", "goal.clear", "commands.execute", "commands.list", "llm.providers", "llm.models", "llm.discoverModels", "settings.describe", "settings.update", "settings.replace", "settings.mutate", "credentials.describe", "credentials.set", "credentials.unset"];
export type AllowedHarnessApiMethod = typeof HARNESS_API_ALLOWLIST[number];
/**
 * The official Typert gateway surface (`typertGateway` service from
 * `dsh-api-gateway`) used to dispatch Harness command endpoints. The ApiProxy
 * has no `commands` domain — commands live behind the Typert registry, which
 * is exactly the path the official Web UI exercises via `/api/commands/*`.
 */
export type { TypertGatewayLike } from './typert-gateway-contract.js';
export declare class HarnessApiBridge {
    private readonly api;
    private readonly publish;
    private readonly maxStreams;
    private readonly logger?;
    private readonly harnessVersion?;
    private readonly methods;
    private readonly streams;
    private readonly respondable;
    private readonly incomingTransfers;
    private readonly outgoingTransfers;
    private readonly mux;
    private readonly host;
    private readonly answer;
    constructor(api: ApiProxy, publish: PublishFrame, maxStreams?: number, logger?: SafeLogger | undefined, typertGateway?: TypertGatewayLike, harnessVersion?: string | undefined);
    call(input: unknown): Promise<RpcResponse<unknown>>;
    private callDirectoryFallback;
    private describeFallback;
    openTransfer(input: unknown): {
        opened: true;
        transferId: string;
    };
    appendTransfer(input: unknown): {
        accepted: true;
        transferId: string;
        index: number;
    };
    commitTransfer(input: unknown): Promise<HarnessApiTransferCommitResult>;
    readTransfer(input: unknown): HarnessApiTransferReadResult;
    closeTransfer(input: unknown): {
        closed: boolean;
        transferId: string;
    };
    respond(input: unknown): Promise<unknown>;
    openStream(input: unknown): {
        opened: true;
        streamId: string;
    };
    closeStream(input: unknown): {
        closed: boolean;
        streamId: string;
    };
    closeAll(reason?: HarnessApiStreamClosedData['reason']): Promise<void>;
    private pruneTransfers;
    private pump;
    private trackRespondable;
    private deleteRespondable;
}
//# sourceMappingURL=harness-api-bridge.d.ts.map