import { randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { replaceFile, sweepStaleTemporaries } from './atomic-file.js';
const FILE_NAME = 'client-target.json';
/**
 * Persisted "last remote target" for the Client half.
 *
 * Android may reclaim a backgrounded app outright, so a resumed app can find neither a socket nor
 * a close event: nothing would start the reconnect loop, and the user would be left in a local
 * shell while the window still pointed at a remote workspace. Recording the target is what makes
 * that case recoverable on the next start. The file sits beside the plugin state rather than in the
 * per-server client directory, because it has to be readable before any connection exists.
 */
export class ClientTargetStore {
    directory;
    constructor(directory) {
        this.directory = directory;
    }
    get file() {
        return join(this.directory, FILE_NAME);
    }
    /** Read the record. A missing, unreadable or malformed file reads as "no record". */
    async load() {
        let raw;
        try {
            raw = await readFile(this.file, 'utf8');
        }
        catch {
            return undefined;
        }
        try {
            const value = JSON.parse(raw);
            if (typeof value !== 'object' || value === null)
                return undefined;
            const record = value;
            if (record.schemaVersion !== 1)
                return undefined;
            if (record.mode !== 'local' && record.mode !== 'remote')
                return undefined;
            // A remote record without a Host cannot be restored, so it is treated as no record at all.
            if (record.mode === 'remote' && (typeof record.hostDeviceId !== 'string' || record.hostDeviceId.length === 0)) {
                return undefined;
            }
            return {
                schemaVersion: 1,
                mode: record.mode,
                ...(typeof record.serverUrl === 'string' && record.serverUrl.length > 0 ? { serverUrl: record.serverUrl } : {}),
                ...(typeof record.hostDeviceId === 'string' && record.hostDeviceId.length > 0
                    ? { hostDeviceId: record.hostDeviceId }
                    : {}),
                ...(typeof record.hostName === 'string' && record.hostName.length > 0 ? { hostName: record.hostName } : {}),
                savedAt: typeof record.savedAt === 'number' ? record.savedAt : 0,
            };
        }
        catch {
            return undefined;
        }
    }
    /** Record the current target. Failures reach the caller so a boot can log them. */
    async save(target) {
        const value = {
            schemaVersion: 1,
            mode: target.mode,
            ...(target.serverUrl === undefined ? {} : { serverUrl: target.serverUrl }),
            ...(target.hostDeviceId === undefined ? {} : { hostDeviceId: target.hostDeviceId }),
            ...(target.hostName === undefined ? {} : { hostName: target.hostName }),
            savedAt: Date.now(),
        };
        await mkdir(this.directory, { recursive: true, mode: 0o700 });
        await sweepStaleTemporaries(this.directory);
        const temporary = this.file + '.' + String(process.pid) + '.' + randomUUID() + '.tmp';
        await writeFile(temporary, JSON.stringify(value, null, 2) + '\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });
        await chmod(temporary, 0o600);
        await replaceFile(temporary, this.file);
        await chmod(this.file, 0o600);
    }
}
//# sourceMappingURL=client-target-store.js.map