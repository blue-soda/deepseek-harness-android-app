import type { RtcIceServer } from '@dsh-remote/webrtc';
import type { HostIdentity } from './identity-store.js';
import { type ServerCredentialStore, type ServerCredentials } from './server-credentials.js';
export interface OAuthQrSession {
    qrId: string;
    scanUrl: string;
    expiresIn: number;
}
export type OAuthProvider = 'wechat' | 'zhihu' | 'github';
/**
 * QR-login providers this build offers. The self-hosted server implements
 * WeChat; Zhihu and GitHub remain implemented for the hosted server but are not
 * offered here. This is the one place to enable or retire a provider.
 */
export declare const ENABLED_QR_PROVIDERS: readonly OAuthProvider[];
/**
 * Whether a caller-supplied string names an offered provider. Narrows the value
 * so downstream calls receive a validated `OAuthProvider` rather than `string`.
 */
export declare function isEnabledQrProvider(value: string): value is OAuthProvider;
/** Human-readable provider name for diagnostics and prompts. */
export declare function oauthProviderName(provider: OAuthProvider): string;
export type OAuthQrPollResult = {
    status: 'pending' | 'expired';
} | {
    status: 'complete';
    authorization: DeviceAuthorization;
};
export interface DeviceAuthorization {
    method: 'account' | 'host_registration_code' | 'owned_device';
    account?: string;
    expiresAt?: number;
    isAdmin?: boolean;
}
export type FetchImplementation = typeof fetch;
export interface ServerHostDevice {
    deviceId: string;
    name: string;
    platform: string;
    membershipId: string;
    online?: boolean;
    lastSeenAt?: number;
    clientVersion?: string;
    harnessVersion?: string;
}
export interface AuthorizedPeerDevice extends ServerHostDevice {
    role: 'host' | 'client';
    identityKey: string;
}
export declare class HostServerApi {
    private readonly store;
    private readonly fetchImplementation;
    private readonly role;
    readonly baseUrl: string;
    private identity?;
    private credentials?;
    private credentialsPromise?;
    private harnessVersion?;
    constructor(serverUrl: string, store: ServerCredentialStore, fetchImplementation?: FetchImplementation, role?: 'host' | 'client');
    bindIdentity(identity: HostIdentity): void;
    setHarnessVersion(version: string | undefined): void;
    currentAuthorization(): DeviceAuthorization | undefined;
    /** Check the persisted device credential without issuing or refreshing one. */
    hasStoredAuthorization(): Promise<boolean>;
    clearAuthorization(): Promise<void>;
    revokeCurrentDevice(): Promise<void>;
    authorizeWithAccount(identity: HostIdentity, email: string, password: string): Promise<DeviceAuthorization>;
    /**
     * Authorize this device with the DSH DeepSeek account grant.
     *
     * The grant is forwarded once so the Server can ask the account platform who
     * it belongs to; the Server then discards it and issues its own account
     * session, exactly like a password sign-in.
     */
    authorizeWithDeepSeek(identity: HostIdentity, token: string): Promise<DeviceAuthorization>;
    startOAuthQrLogin(provider?: OAuthProvider): Promise<OAuthQrSession>;
    pollOAuthQrLogin(identity: HostIdentity, qrId: string, recoverIdentity?: () => Promise<HostIdentity>): Promise<OAuthQrPollResult>;
    authorizeHostWithCode(identity: HostIdentity, code: string): Promise<DeviceAuthorization>;
    authorizeOwnedRole(identity: HostIdentity, authorizingAccessToken: string, account?: string): Promise<DeviceAuthorization>;
    authenticate(identity?: HostIdentity): Promise<ServerCredentials>;
    refreshCredentials(rejectedAccessToken?: string | undefined): Promise<ServerCredentials>;
    private withRefreshLock;
    private rotateCredentials;
    listDevices(): Promise<ServerHostDevice[]>;
    deviceFor(peerDeviceId: string): Promise<AuthorizedPeerDevice>;
    turnCredentials(connectionId: string): Promise<RtcIceServer[]>;
    presenceFor(deviceId: string): Promise<{
        online: boolean;
        lastSeenAt?: number;
    }>;
    private loadOrIssue;
    private register;
    private deviceDescriptor;
    private saveTokens;
    private request;
    private publicRequest;
    private requireIdentity;
}
export declare class ClientServerApi extends HostServerApi {
    constructor(serverUrl: string, store: ServerCredentialStore, fetchImplementation?: FetchImplementation);
}
export declare class ServerApiError extends Error {
    readonly code: string;
    readonly retryable: boolean;
    readonly status?: number | undefined;
    readonly phase?: "credential_refresh" | undefined;
    constructor(code: string, message: string, retryable: boolean, status?: number | undefined, phase?: "credential_refresh" | undefined);
}
//# sourceMappingURL=server-api.d.ts.map