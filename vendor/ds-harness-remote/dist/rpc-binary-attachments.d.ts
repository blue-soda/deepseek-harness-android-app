/**
 * Rehydrate the out-of-band byte attachments DSH puts beside a Gateway result.
 *
 * The Harness Gateway encodes a result containing a `Uint8Array` as a placeholder plus an
 * attachment list — `{ ok: true, value: { data: null, … }, attachments: [{ path: ['data'],
 * bytes }] }` — and its own connection layer copies `bytes` back to `path` before the value is
 * validated against the generated schema (`z.instanceof(Uint8Array)`, emitted by
 * `typert/generator`). A native Remote call never touches that layer, so our carrier sees the
 * envelope raw, JSON-encoded: the placeholder stays `null` and the bytes arrive as
 * `{ "0": 137, "1": 80, … }`. Handing that to DSH fails with
 * `expected "Uint8Array", path: ["data"]`, which is exactly the image-preview failure.
 *
 * Hydrating here keeps the carrier compatible with any Host version: a Host that never sends
 * attachments passes through untouched.
 */
export declare function hydrateRpcAttachments(result: unknown): unknown;
/**
 * Mirror of the same wire form for our own Host half.
 *
 * A native Remote call leaves the Host through this plugin's tunnel, which JSON-encodes whatever the
 * Gateway returned. DSH rehydrates its own result before the Host plugin sees it, so the bytes arrive
 * as a real \`Uint8Array\` again - and \`JSON.stringify\` turns that into \`{"0":137,"1":80,…}\`, a shape no
 * generated schema accepts. Sending DSH's own form instead (a placeholder plus \`attachments\`) keeps one
 * convention on both sides of the tunnel and lets \`hydrateRpcAttachments\` put the bytes back. A result
 * without bytes is returned untouched, and tagging twice changes nothing.
 *
 * @param result - Gateway result about to be JSON-encoded for the client.
 * @returns the same result, or one carrying byte attachments.
 */
export declare function collectRpcAttachments<T>(result: T): T;
/** Decode one byte value in any shape a JSON hop can produce, or undefined when it is not bytes. */
export declare function decodeByteValue(value: unknown): Uint8Array | undefined;
//# sourceMappingURL=rpc-binary-attachments.d.ts.map