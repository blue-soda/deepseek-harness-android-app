interface DirectoryEntry {
    name: string;
    path: string;
    hidden: boolean;
}
export interface DirectoryListing {
    path: string;
    home: string;
    crumbs: DirectoryEntry[];
    entries: DirectoryEntry[];
    truncated: boolean;
}
/** Read-only fallback used only over an authenticated Host channel when Harness has a native-only picker. */
export declare function listRemoteDirectory(path?: string, signal?: AbortSignal): Promise<DirectoryListing>;
export {};
//# sourceMappingURL=remote-directory-browser.d.ts.map