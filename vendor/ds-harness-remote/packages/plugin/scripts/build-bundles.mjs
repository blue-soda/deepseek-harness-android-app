import { existsSync, renameSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

/**
 * Build one output and swap it into place atomically.
 *
 * DSH loads these files from a live profile, and a development install can point
 * at this checkout directly, so a reader may look at any moment. Writing to a
 * temporary name and renaming means a reader sees either the previous complete
 * file or the new complete file, never a half-written one. The rename is atomic
 * because the temporary file sits on the same filesystem.
 * @param options - esbuild options; `outfile` is replaced by the swap.
 */
async function buildAtomically(options) {
  const target = options.outfile
  const staging = `${target}.tmp`
  await build({ ...options, outfile: staging })
  renameSync(staging, target)
  const stagedMap = `${staging}.map`
  if (existsSync(stagedMap)) renameSync(stagedMap, `${target}.map`)
  else if (existsSync(`${target}.map`)) rmSync(`${target}.map`)
}

await buildAtomically({
  entryPoints: [join(root, 'src/index.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  sourcemap: true,
  outfile: join(root, 'dist/index.js'),
  external: ['@deepseek-ai/*', '@roamhq/wrtc', 'qrcode', 'werift', 'ws'],
})

for (const [moduleId, outfile] of [
  ['ds-harness-remote', 'client.js'],
  ['ds-harness-remote', 'client.github.js'],
]) {
  await buildAtomically({
    entryPoints: [join(root, 'src/client.ts')],
    bundle: true,
    platform: 'browser',
    format: 'iife',
    minifySyntax: true,
    define: {
      DSH_REMOTE_CLIENT_MODULE_ID: JSON.stringify(moduleId),
    },
    outfile: join(root, 'dist', outfile),
  })
}
