const SWITCHED_DOMAINS = [
    'sessions',
    'subagents',
    'host',
    'workspace',
    'skills',
    'agentPresets',
    'events',
    'goals',
    'llm',
    'settings',
    'credentials',
];
/**
 * Installs stable forwarding objects into the official ApiProxy instance.
 * Existing HTTP/WebSocket carriers retain the same service identity while new
 * requests resolve against the currently selected local or remote target.
 */
export class ApiProxySwitch {
    remote;
    target;
    mode = 'local';
    installed = false;
    local;
    originals;
    localRespond;
    constructor(local) {
        this.local = local;
        this.originals = new Map(SWITCHED_DOMAINS.map(domain => [domain, local[domain]]));
        this.localRespond = local.respond.bind(local);
    }
    install() {
        if (this.installed)
            return;
        for (const domain of SWITCHED_DOMAINS) {
            const localDomain = this.local[domain];
            const forwarder = new Proxy({}, {
                get: (_target, key) => {
                    const selected = this.selected(domain);
                    const value = selected[key];
                    return typeof value === 'function' ? value.bind(selected) : value;
                },
            });
            Object.defineProperty(this.local, domain, {
                configurable: true,
                enumerable: true,
                writable: true,
                value: forwarder,
            });
        }
        Object.defineProperty(this.local, 'respond', {
            configurable: true,
            enumerable: true,
            writable: true,
            value: (...args) => this.mode === 'remote'
                ? this.requireRemote().respond(...args)
                : this.localRespond(...args),
        });
        this.installed = true;
    }
    selectRemote(api, target) {
        if (!this.installed)
            throw new Error('The Harness API switch is not installed.');
        this.remote = api;
        this.target = { ...target };
        this.mode = 'remote';
    }
    selectLocal() {
        this.mode = 'local';
        this.remote = undefined;
        this.target = undefined;
    }
    status() {
        return { mode: this.mode, ...(this.target === undefined ? {} : { target: { ...this.target } }) };
    }
    restore() {
        if (!this.installed)
            return;
        this.selectLocal();
        for (const [domain, value] of this.originals)
            Object.defineProperty(this.local, domain, {
                configurable: true,
                enumerable: true,
                writable: true,
                value,
            });
        Object.defineProperty(this.local, 'respond', {
            configurable: true,
            enumerable: true,
            writable: true,
            value: this.localRespond,
        });
        this.installed = false;
    }
    selected(domain) {
        if (this.mode === 'local')
            return this.originalDomain(domain);
        return this.requireRemote()[domain];
    }
    originalDomain(domain) {
        return this.originals.get(domain);
    }
    requireRemote() {
        if (this.remote === undefined)
            throw new Error('No remote Harness target is selected.');
        return this.remote;
    }
}
//# sourceMappingURL=api-proxy-switch.js.map