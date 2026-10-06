import { type Config, type ConfigInput, type ResolvedConfig } from './config.js';
import { type ClientModeRuntime, type HostConnectionHandle, type HostAuthorizationControl } from './client-runtime.js';
import { type HostWebServerLike } from './control-route.js';
export interface PluginSettingsView {
    config: Config;
    deviceName: string;
    writable: boolean;
    applies: 'live' | 'restart';
    association?: PluginAssociation;
    associations: Partial<Record<'host' | 'client', PluginAssociation>>;
    acpAvailability?: Record<string, boolean>;
    /**
     * The Codex binary the Host found for itself, when the configured command was
     * left at its default. Absent means nothing was found, and the settings UI says
     * so instead of showing an empty field the user cannot interpret.
     */
    discoveredCodexBinary?: string;
}
export interface PluginAssociation {
    method: 'account' | 'host_registration_code' | 'owned_device';
    account?: string;
}
/**
 * Request to begin the DeepSeek browser authorization on the user's behalf.
 *
 * The provider only accepts a loopback callback, so the origin must be the one
 * this page was served from; anything else is refused before the browser opens.
 */
export interface DeepSeekSignInRequest {
    /** Browser-accessible loopback origin that receives the provider callback. */
    callbackOrigin: string;
    /** Initiating UI: a Desktop shell returns from a failed exchange differently. */
    loginSource: 'web' | 'desktop';
    /** Active UI language, reduced to the platform wire locale by the provider. */
    locale: string;
    /** Seconds east of UTC, the form the platform's client identity expects. */
    timezoneOffsetSeconds: number;
}
/**
 * Host-side source of the DSH DeepSeek account grant.
 *
 * The grant is read from the account service on demand and never cached here:
 * it is a bearer credential for the user's DeepSeek account, so the plugin
 * forwards it once and forgets it.
 */
export interface DeepSeekSessionSource {
    /** The signed-in account's platform grant, or undefined when signed out. */
    read(): Promise<{
        token: string;
    } | undefined>;
    /**
     * Begin the official browser authorization and report the page to open.
     * @param request - callback origin and client identity for the attempt.
     * @returns the authorization URL when the provider exposes one.
     */
    startSignIn(request: DeepSeekSignInRequest): Promise<{
        authorizeUrl?: string;
    }>;
    /**
     * Sign the DSH DeepSeek account out.
     *
     * Signing in here borrows that account authorization, so leaving it behind
     * would make this plugin's sign-out look ineffective: the next sign-in would
     * silently reuse the same account.
     * @returns whether the account was signed out.
     */
    signOut(): Promise<boolean>;
}
/**
 * Live read/write face of the plugin's profile-owned entry Config.
 *
 * DSH 0.1.7-rc.1 (DSH-0.1.7-RC1-04) removed the settings-namespace registry
 * and its scope type: the entry's editable fields are a single
 * `.volatile()` Cordis Config, read through `.get()`, and writes go to
 * `ctx.settings.replace(entryId, section)` (persisted in the active profile's
 * `cordis.patch.yml` under the entry id).
 */
export interface PluginSettingsBinding {
    /** The current config, read from the entry's live volatile reference. */
    get(): ConfigInput;
    /** Replace the entry's editable fields in the active profile. */
    replace(section: Config): Promise<void>;
}
/** Loopback-only control plane shared by Local/Remote switching and plugin setup. */
export declare class PluginControlRuntime {
    private readonly config;
    private readonly identityDirectory;
    private readonly settings;
    private readonly client;
    private readonly host;
    private readonly deepseekSession;
    constructor(config: ResolvedConfig, identityDirectory: string, settings: PluginSettingsBinding | undefined, client: ClientModeRuntime | undefined, host: HostAuthorizationControl | undefined, deepseekSession?: DeepSeekSessionSource | undefined);
    /**
     * The DeepSeek grant whose Server authorization this runtime already completed.
     *
     * The client polls this control endpoint while the browser page is open, and
     * every call used to re-run the authorization: that repeats a platform request
     * and re-registers the device, which rotates its tokens. The Host connection
     * using those tokens then fails with AUTH_INVALID, intermittently, right after a
     * sign-in. Verify once per grant instead.
     */
    private verifiedDeepSeekToken?;
    private verifiedDeepSeekAuthorization?;
    register(connection: HostConnectionHandle, webServer?: HostWebServerLike): () => Promise<void>;
    /**
     * Read the value the status event stream pushes. It resolves through the same
     * endpoint handler as the unary `status` control call, so a pushed status and
     * a polled one can never diverge.
     */
    private streamStatus;
    private handle;
    private configure;
    private setServer;
    private setRole;
    private setDevelopment;
    private setCodex;
    private setAcp;
    private addAcp;
    private removeAcp;
    private authorizeOwnedRole;
    private logout;
    /**
     * Stop or resume this machine's remote availability without releasing its
     * authorization, and remember the choice so a restart respects it.
     *
     * This is the non-destructive counterpart of releasing the authorization: it
     * closes the connection, keeps the credentials and the device identity, and
     * therefore costs neither a re-authorization nor a device identity.
     * @param connected - whether this machine should accept remote connections.
     * @returns the refreshed status for the caller's view.
     */
    private setHostConnection;
    private settingsView;
    private associations;
    private association;
    private hostOnlyStatus;
}
//# sourceMappingURL=control-runtime.d.ts.map