import { readdir, rename, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
/**
 * Atomic replacement of a state file, with the retry Windows needs.
 *
 * On Windows `rename` is `MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)`, which fails with
 * `EPERM` while another handle holds the *destination* without `FILE_SHARE_DELETE`. The
 * holders are short-lived and outside this process: antivirus and indexer scans, Explorer
 * previews, backup or sync agents, and a second process writing the same state directory.
 * Node always opens with `FILE_SHARE_DELETE` itself, so this process cannot cause it, and
 * the failure disappears on the next attempt - which is why `EPERM` here means "retry",
 * not "permission denied", and why exclusions or packaging changes do not fix it.
 *
 * A retry only hides a second *writer*: two instances sharing one `DSH_HOME` still race on
 * a read-modify-write, so keep parallel Hosts on separate `DSH_HOME` values (see the
 * refresh lock in `server-credentials.ts` for the same rule applied to credentials).
 */
const TRANSIENT_REPLACE_CODES = new Set(['EPERM', 'EBUSY', 'EACCES']);
const REPLACE_RETRY_DELAYS_MS = [20, 40, 80, 160, 320];
/** `<state file>.<pid>.<uuid v7>.tmp`, the temporary name every caller here creates. */
const TEMPORARY_NAME = /\.\d+\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp$/iu;
/** A live write lasts milliseconds, so anything this old was abandoned by a crash. */
const STALE_TEMPORARY_AGE_MS = 10 * 60 * 1000;
/** Replace `target` with `temporary`, retrying the transient Windows failures. */
export async function replaceFile(temporary, target, options = {}) {
    const delays = options.delaysMs ?? REPLACE_RETRY_DELAYS_MS;
    for (let attempt = 0;; attempt += 1) {
        try {
            await rename(temporary, target);
            return;
        }
        catch (error) {
            const delay = delays[attempt];
            if (delay === undefined || !TRANSIENT_REPLACE_CODES.has(errorCode(error) ?? ''))
                throw error;
            await sleep(delay);
        }
    }
}
/**
 * Remove abandoned `*.tmp` files a crashed write left beside the state files, and return
 * the paths removed. Only names this module produces are considered, and only once they
 * are old enough that no live writer can own them, so a concurrent write is never deleted.
 */
export async function sweepStaleTemporaries(directory, options = {}) {
    const maxAgeMs = options.maxAgeMs ?? STALE_TEMPORARY_AGE_MS;
    const removed = [];
    let entries;
    try {
        entries = await readdir(directory);
    }
    catch {
        return removed;
    }
    for (const entry of entries) {
        if (!TEMPORARY_NAME.test(entry))
            continue;
        const path = join(directory, entry);
        try {
            const info = await stat(path);
            if (!info.isFile() || Date.now() - info.mtimeMs < maxAgeMs)
                continue;
            await rm(path, { force: true });
            removed.push(path);
        }
        catch {
            // Best effort: a file another process is removing is already gone.
        }
    }
    return removed;
}
function errorCode(error) {
    if (typeof error !== 'object' || error === null || !('code' in error))
        return undefined;
    const code = error.code;
    return typeof code === 'string' ? code : undefined;
}
function sleep(ms) {
    return new Promise(resolve => { setTimeout(resolve, ms); });
}
//# sourceMappingURL=atomic-file.js.map