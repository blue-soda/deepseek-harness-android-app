/**
 * Which Harness target this device was last using.
 *
 * `local` is the normal value - almost every install only ever runs its own shell - and it is
 * recorded too, so a boot can tell "never used remote" apart from "the remote target was cleared".
 */
export interface ClientTargetRecord {
    schemaVersion: 1;
    mode: 'local' | 'remote';
    /** Server the target belongs to, so a target from another deployment is never restored blindly. */
    serverUrl?: string;
    hostDeviceId?: string;
    /** Display name of the Host, so a restore can name it before any connection exists. */
    hostName?: string;
    savedAt: number;
}
/**
 * Persisted "last remote target" for the Client half.
 *
 * Android may reclaim a backgrounded app outright, so a resumed app can find neither a socket nor
 * a close event: nothing would start the reconnect loop, and the user would be left in a local
 * shell while the window still pointed at a remote workspace. Recording the target is what makes
 * that case recoverable on the next start. The file sits beside the plugin state rather than in the
 * per-server client directory, because it has to be readable before any connection exists.
 */
export declare class ClientTargetStore {
    private readonly directory;
    constructor(directory: string);
    get file(): string;
    /** Read the record. A missing, unreadable or malformed file reads as "no record". */
    load(): Promise<ClientTargetRecord | undefined>;
    /** Record the current target. Failures reach the caller so a boot can log them. */
    save(target: {
        mode: 'local' | 'remote';
        serverUrl?: string;
        hostDeviceId?: string;
        hostName?: string;
    }): Promise<void>;
}
//# sourceMappingURL=client-target-store.d.ts.map