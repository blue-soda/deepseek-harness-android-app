/**
 * Deployment defaults shared by the Host config and the browser bundle.
 *
 * This lives in its own module because the browser bundle cannot import
 * `config.ts`, which resolves the DSH home through Node globals. Both halves
 * must agree on the default Server, so it is declared exactly once here.
 */
export const DEFAULT_REMOTE_SERVER_URL = 'https://sakakibara.ink:8443'