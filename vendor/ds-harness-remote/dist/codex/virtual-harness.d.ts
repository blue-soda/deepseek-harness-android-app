import type { ApiProxy } from '@deepseek-ai/dsh-host-apiproxy/api';
import { CodexRemoteClient } from '@dsh-remote/client-core';
import type { RemoteTypertGatewayTarget, TypertGatewayRequest, TypertRpcResult } from '../typert-gateway-contract.js';
import type { CodexPermissionSnapshot } from '@dsh-remote/protocol';
import type { HarnessSessionGeneration } from '../harness-version.js';
type JsonRecord = Record<string, unknown>;
/** The local shell's carriers, used for endpoints outside the CodeX domain. */
export interface LocalGatewayCarrier {
    dispatch?: (endpoint: string, payload: unknown, signal: AbortSignal) => Promise<TypertRpcResult>;
    open?: (endpoint: string, payload: unknown, signal: AbortSignal) => Promise<AsyncIterable<unknown>>;
}
export interface CodexVirtualWorkspaceView {
    workspaceId: string;
    path: string;
    title: string;
    sessionIds: string[];
    sessionCount: number;
    createdAt: string;
    updatedAt: string;
}
export declare function codexProjectWorkspaceId(projectId: string): string;
interface CodexClientLike {
    request(method: string, params: unknown, signal?: AbortSignal): Promise<unknown>;
    subscribe(threadId: string, onFrame: (frame: {
        method: string;
        params: unknown;
    }) => void, signal?: AbortSignal, onClose?: (reason: 'cancelled' | 'completed' | 'failed' | 'peer-disconnected') => void): Promise<{
        close(): Promise<void>;
    }>;
    respond(requestHandle: string, decision: 'accept' | 'decline' | 'cancel', signal?: AbortSignal): Promise<void>;
}
interface NativeEvent {
    type: string;
    seq: number;
    time: number;
    data: unknown;
    sourceEventSeqs?: number[];
    surfaceOp?: 'append' | {
        op: 'replace';
        start: number;
        end: number;
    } | {
        op: 'replace';
        startSeq: number;
        endSeq: number;
    };
}
export interface CodexNativeHistory {
    header: {
        version: number;
        id: string;
        createdAt: number;
        cwd?: string;
        isSeeded?: boolean;
    };
    entries: Array<{
        type: 'event';
        event: NativeEvent;
        view?: ToolEventView;
    }>;
    lastSeq: number;
    nextTurn: number;
    activeTurnId?: string;
}
export interface CodexNativeHistoryPage extends CodexPermissionSnapshot {
    header: CodexNativeHistory['header'];
    cursor: number;
    nextTurn: number;
    records: CodexNativeHistory['entries'];
    hasMore: boolean;
    activeTurnId?: string;
}
interface ToolEventView {
    for: 'call' | 'result';
    view: JsonRecord;
}
/** Discover the CodeX projects visible through the Host App Server. */
export declare function discoverCodexVirtualWorkspaces(client: CodexClientLike, signal?: AbortSignal): Promise<CodexVirtualWorkspaceView[]>;
/**
 * A plugin-owned virtual Harness target. It projects CodeX Thread/Turn data at
 * the existing ApiProxy/Typert carrier boundary, so every UI layer above the
 * official Workspace and Session controllers remains native DSH.
 */
export declare class CodexVirtualHarness implements RemoteTypertGatewayTarget {
    private readonly client;
    private readonly host;
    private readonly sessionGeneration;
    private readonly hostCarrier?;
    readonly api: ApiProxy;
    private catalog?;
    private readonly workspaceStreams;
    private readonly controlStreams;
    private readonly eventStreams;
    private readonly rcMuxStreams;
    private readonly rcHostStreams;
    private readonly follows;
    private readonly pendingRequestIds;
    private readonly pendingApprovals;
    private readonly pendingThreads;
    private readonly blankThreads;
    private readonly selectedModels;
    private readonly selectedPermissions;
    private readonly imageAttachments;
    private modelDirectory?;
    private modelDirectoryPromise?;
    private lastProjectionSeq;
    private commandSeq;
    private selectedWorkspaceId?;
    private closed;
    /**
     * The local shell's carriers. Once this Harness answers `/api` for a remote Codex
     * workspace, every endpoint outside the CodeX domain belongs to the shell that
     * owns the window — its settings bootstrap, plugin registry and account reads.
     * @param carrier - local carriers, or undefined to fall back to the remote Host.
     */
    setLocalCarrier(carrier: LocalGatewayCarrier | undefined): void;
    private localCarrier?;
    constructor(client: CodexClientLike, host: {
        deviceId: string;
        name: string;
    }, sessionGeneration?: HarnessSessionGeneration, hostCarrier?: RemoteTypertGatewayTarget | undefined);
    static remote(core: ConstructorParameters<typeof CodexRemoteClient>[0], host: {
        deviceId: string;
        name: string;
    }, sessionGeneration?: HarnessSessionGeneration, hostCarrier?: RemoteTypertGatewayTarget): CodexVirtualHarness;
    workspaces(signal?: AbortSignal): Promise<CodexVirtualWorkspaceView[]>;
    selectWorkspace(workspaceId: string, signal?: AbortSignal): Promise<CodexVirtualWorkspaceView>;
    preferredSessionId(signal?: AbortSignal): Promise<string | undefined>;
    invoke(request: TypertGatewayRequest): Promise<unknown>;
    dispatch(endpoint: string, payload: unknown, signal: AbortSignal): Promise<TypertRpcResult>;
    open(endpoint: string, payload: unknown, signal: AbortSignal): Promise<AsyncIterable<unknown>>;
    close(): Promise<void>;
    private refreshCatalog;
    private currentCatalog;
    private models;
    private modelSelection;
    private permissionSelection;
    private setPermissionSelection;
    private commandList;
    private executeCommand;
    private sessionTitle;
    private selectModel;
    private publishProjection;
    private nextProjectionSeq;
    private updateThreadName;
    private sessionSummaries;
    private sessionSummary;
    private searchSessions;
    private workspaceFollow;
    private sessionControl;
    private remoteEvents;
    private sessionFollow;
    private acceptCodexFrame;
    private acceptCompletedItem;
    private pushEvent;
    private cacheImageBlocks;
    private ensureStreamBlock;
    private appendStreamDelta;
    private closeItemStreamBlocks;
    private closeAllStreamBlocks;
    private finishStream;
    private pushAssistantChunk;
    private endAssistantAttempt;
    private ensureToolStarted;
    private emitToolResult;
    private pushToolResult;
    private resetLiveTurn;
    private emitRemoteEvent;
    private emitApproval;
    private answerRemoteEvent;
    private createWorkspace;
    private renameWorkspace;
    private workspaceForSession;
    private selectedWorkspace;
    private describeHost;
    private listDirectory;
    private archiveSession;
    private createSession;
    private forkSession;
    private prompt;
    private attachment;
    private hydrateImageAttachments;
    private cancel;
    private renameSession;
    private sessionPage;
    private activeTurnId;
    private fetchThread;
    private readHistoryPage;
    private cacheHistoryImages;
    private sessionHistory;
    private ensureRcFollow;
    private followsHas;
    private closeFollowAfterRemoteStreamClosed;
    private refreshAndPublishWorkspaces;
    private publishWorkspaceBaseline;
    private createApiProxy;
    private rcMux;
    private rcHost;
    private broadcastRcMux;
    private broadcastRcHost;
}
export declare function projectCodexNativeHistory(thread: JsonRecord, sessionId: string, sessionGeneration?: HarnessSessionGeneration): CodexNativeHistory;
export declare function paginateCodexNativeHistory(history: CodexNativeHistory, request: {
    beforeSeq?: number;
    throughSeq?: number;
    maxMessages?: number;
}): CodexNativeHistoryPage;
export {};
//# sourceMappingURL=virtual-harness.d.ts.map