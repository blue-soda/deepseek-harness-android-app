export interface ReplaceFileOptions {
    /** Delay before each retry. Fewer entries than failures means the last error is thrown. */
    delaysMs?: readonly number[];
}
export interface SweepOptions {
    maxAgeMs?: number;
}
/** Replace `target` with `temporary`, retrying the transient Windows failures. */
export declare function replaceFile(temporary: string, target: string, options?: ReplaceFileOptions): Promise<void>;
/**
 * Remove abandoned `*.tmp` files a crashed write left beside the state files, and return
 * the paths removed. Only names this module produces are considered, and only once they
 * are old enough that no live writer can own them, so a concurrent write is never deleted.
 */
export declare function sweepStaleTemporaries(directory: string, options?: SweepOptions): Promise<string[]>;
//# sourceMappingURL=atomic-file.d.ts.map