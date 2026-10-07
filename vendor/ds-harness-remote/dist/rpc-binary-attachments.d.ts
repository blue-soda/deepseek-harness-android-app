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
//# sourceMappingURL=rpc-binary-attachments.d.ts.map