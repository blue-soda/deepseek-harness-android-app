import { type RemoteMessage } from '@dsh-remote/protocol';
import type { IdentityStore } from './identity-store.js';
import type { SafeLogger } from './logging.js';
import type { RpcRouter } from './rpc-router.js';
import type { AuthenticatedPeerChannel } from './types.js';
export interface PeerConnectionContext {
    connectionId: string;
    peerDeviceId: string;
}
export interface ConnectedPeer {
    deviceId: string;
    mode?: 'LAN' | 'P2P' | 'TURN' | 'Relay';
}
export type RpcRouterFactory = (context: PeerConnectionContext, send: (message: RemoteMessage) => Promise<void>) => RpcRouter;
export declare class ConnectionController {
    private readonly identities;
    private readonly createRouter;
    private readonly logger?;
    private readonly active;
    private acceptQueue;
    constructor(identities: IdentityStore, createRouter: RpcRouterFactory, logger?: SafeLogger | undefined);
    accept(channel: AuthenticatedPeerChannel): Promise<void>;
    private acceptOne;
    isOnline(): boolean;
    connectionCount(): number;
    peerDeviceIds(): string[];
    connectedPeers(): ConnectedPeer[];
    peerDeviceId(): string | undefined;
    connectionMode(): 'LAN' | 'P2P' | 'TURN' | 'Relay' | 'Disconnected';
    send(message: RemoteMessage): Promise<void>;
    revoke(deviceId: string): Promise<void>;
    closeConnection(connectionId: string, code?: string): Promise<boolean>;
    close(): Promise<void>;
    private handle;
    private sendTo;
    private sendConnection;
    private disconnect;
    private isActive;
}
export declare class ConnectionRejectedError extends Error {
    readonly code: string;
    constructor(code: string, message: string);
}
//# sourceMappingURL=connection-controller.d.ts.map