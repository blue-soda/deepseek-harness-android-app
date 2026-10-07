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
export function hydrateRpcAttachments(result) {
    if (!isRecord(result))
        return result;
    const envelope = result;
    if (!Array.isArray(envelope.attachments) || envelope.attachments.length === 0)
        return result;
    const { attachments, ...rest } = result;
    const base = Object.hasOwn(rest, 'value') ? rest.value : undefined;
    for (const attachment of attachments) {
        const entry = attachment;
        const path = Array.isArray(entry.path) ? entry.path : undefined;
        if (path === undefined || path.length === 0) {
            throw new Error('The remote Host returned a byte attachment without a path.');
        }
        assignAtPath(base, path, decodeAttachmentBytes(entry.bytes));
    }
    // DSH's own client drops the envelope; downstream code must see the identical shape.
    return rest;
}
function assignAtPath(base, path, bytes) {
    if (base === null || typeof base !== 'object') {
        throw new Error('The remote Host returned a byte attachment that its result cannot hold.');
    }
    let cursor = base;
    for (let index = 0; index < path.length - 1; index += 1) {
        const key = pathKey(path[index]);
        const next = cursor[key];
        if (next === null || typeof next !== 'object') {
            throw new Error('The remote Host returned a byte attachment at an unknown result path.');
        }
        cursor = next;
    }
    cursor[pathKey(path[path.length - 1])] = bytes;
}
function pathKey(segment) {
    if (typeof segment === 'string')
        return segment;
    if (typeof segment === 'number' && Number.isInteger(segment) && segment >= 0)
        return String(segment);
    throw new Error('The remote Host returned a byte attachment with an invalid path segment.');
}
/** Accepts every shape a `Uint8Array` takes after a JSON round trip, plus the original. */
function decodeAttachmentBytes(value) {
    if (value instanceof Uint8Array)
        return value;
    if (typeof value === 'string')
        return decodeBase64(value);
    if (Array.isArray(value))
        return Uint8Array.from(value);
    if (!isRecord(value))
        throw new Error('The remote Host returned invalid byte attachment data.');
    if (Array.isArray(value.data)) {
        // Node's Buffer.toJSON() produces { type: 'Buffer', data: [...] }.
        return Uint8Array.from(value.data);
    }
    const keys = Object.keys(value);
    if (keys.every(key => /^\d+$/u.test(key))) {
        // JSON.stringify(new Uint8Array([9, 0])) produces { "0": 9, "1": 0 }.
        const ordered = keys.map(Number).sort((left, right) => left - right);
        return Uint8Array.from(ordered.map(key => value[String(key)]));
    }
    throw new Error('The remote Host returned invalid byte attachment data.');
}
function decodeBase64(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1)
        bytes[index] = binary.charCodeAt(index);
    return bytes;
}
function isRecord(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=rpc-binary-attachments.js.map