/**
 * Freshness guard for the built frontend the real-browser checks serve (STORY-WEB-018).
 *
 * `scripts/stubOrigin.mjs` serves `frontend/dist/`, so a check that only asserts the bundle exists
 * can report browser evidence about source the browser never loaded. This module answers the
 * stronger question instead: was `dist/` emitted after every file `npm run build` reads? A build
 * input modified later than the emitted `dist/index.html` means the browser would be shown stale
 * output — a defect in the evidence rather than in the page — so the check refuses to start and
 * names the offending file.
 *
 * It refuses rather than building: a build is a multi-minute type-check plus bundle, and a check
 * that produces the very output it then validates can no longer report on what the developer has.
 *
 * Modification times are the only usable signal: the build emits content-hashed asset names but
 * records nothing about its inputs. Both halves of a comparison therefore have to be legible in the
 * failure message, which is why every verdict carries the two timestamps it compared.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * `frontend/`, resolved from this module so every check shares one definition of the tree. Resolved
 * on demand, not at import: a caller that states its own root — the checks below — must not depend
 * on how the module itself was loaded.
 */
export function defaultFrontendDir() {
  return fileURLToPath(new URL('../', import.meta.url))
}

/**
 * Everything `npm run build` reads, relative to `frontend/`. Directories are walked recursively.
 * `src` deliberately includes the `__tests__` specs: `npm run build` type-checks them first, so a
 * spec edit can be the reason the current source does not build at all.
 */
export const BUILD_INPUTS = [
  'index.html',
  'package-lock.json',
  'package.json',
  'public',
  'src',
  'tsconfig.json',
  'vite.config.ts'
]

function asIso(mtimeMs) {
  return new Date(mtimeMs).toISOString()
}

/**
 * The most recently modified build input, as `{ path, mtimeMs }`, or `null` when none of them
 * exists. `path` keeps `/` separators so a failure message reads the same on every platform.
 */
export function newestBuildInput(frontendDir, inputs = BUILD_INPUTS) {
  let newest = null

  const visit = (relative) => {
    let stats
    try {
      stats = statSync(join(frontendDir, relative))
    } catch {
      return // an input that is not present contributes nothing
    }
    if (stats.isDirectory()) {
      for (const entry of readdirSync(join(frontendDir, relative))) visit(`${relative}/${entry}`)
      return
    }
    if (newest === null || stats.mtimeMs > newest.mtimeMs) {
      newest = { path: relative, mtimeMs: stats.mtimeMs }
    }
  }

  for (const input of inputs) visit(input)
  return newest
}

/** Every same-origin file the built document asks the stub origin for, relative to `dist/`. */
function referencedFiles(documentFile) {
  const html = readFileSync(documentFile, 'utf8')
  const matches = [...html.matchAll(/(?:src|href)="\/([^"/][^"]*)"/g)].map((match) => match[1])
  return [...new Set(matches)]
}

/**
 * Whether `dist/` can be served as evidence about the current source.
 *
 * @returns {{ fresh: boolean, reason: string | null, detail: string }} `reason` is the actionable
 *   message for a developer when `fresh` is false; `detail` always names what was compared.
 */
export function describeBundleFreshness({ frontendDir, inputs = BUILD_INPUTS } = {}) {
  const root = frontendDir ?? defaultFrontendDir()
  const distDir = join(root, 'dist')
  const documentFile = join(distDir, 'index.html')

  if (!existsSync(documentFile)) {
    return {
      fresh: false,
      reason: 'frontend/dist/index.html is missing — run `npm run build` in frontend/ first.',
      detail: 'no build output'
    }
  }
  const builtAtMs = statSync(documentFile).mtimeMs

  const missing = referencedFiles(documentFile).filter((file) => !existsSync(join(distDir, file)))
  if (missing.length > 0) {
    return {
      fresh: false,
      reason:
        `frontend/dist is incomplete: its index.html references ${missing.join(', ')}, which the ` +
        'build output does not contain — run `npm run build` in frontend/ first.',
      detail: `dist/index.html built ${asIso(builtAtMs)}, ${missing.length} referenced file(s) absent`
    }
  }

  const newest = newestBuildInput(root, inputs)
  if (newest === null) {
    return {
      fresh: false,
      reason:
        `No build input (${inputs.join(', ')}) exists under ${root}, so frontend/dist ` +
        'cannot be compared against the current source. Nothing was checked.',
      detail: `dist/index.html built ${asIso(builtAtMs)}, no build input found`
    }
  }

  const compared = `dist/index.html built ${asIso(builtAtMs)}; newest build input ${newest.path} ${asIso(newest.mtimeMs)}`
  if (newest.mtimeMs > builtAtMs) {
    return {
      fresh: false,
      reason:
        `frontend/dist is stale: ${newest.path} was modified ${asIso(newest.mtimeMs)}, after ` +
        `dist/index.html was built ${asIso(builtAtMs)} — run \`npm run build\` in frontend/ first.`,
      detail: compared
    }
  }

  return { fresh: true, reason: null, detail: compared }
}

/**
 * Throws unless `dist/` reflects the current source; returns the comparison the caller should print,
 * so a passing run states which bundle its browser evidence was taken from.
 */
export function requireFreshBundle(options = {}) {
  const verdict = describeBundleFreshness(options)
  if (!verdict.fresh) throw new Error(verdict.reason)
  return verdict.detail
}
