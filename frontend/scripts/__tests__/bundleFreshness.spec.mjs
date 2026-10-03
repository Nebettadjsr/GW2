/**
 * Targeted checks for the browser-smoke bundle guard (STORY-WEB-018).
 *
 * Each case builds a throwaway `frontend/`-shaped tree in the OS temp directory and sets explicit
 * modification times, so the verdict depends on the comparison under test and never on the state of
 * the real repository's `dist/`.
 */
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { describeBundleFreshness, requireFreshBundle } from '../bundleFreshness.mjs'

const OLDEST_INPUT = new Date('2026-10-03T08:00:00.000Z')
const BUILT_AT = new Date('2026-10-03T10:00:00.000Z')
const BEFORE_BUILD = new Date('2026-10-03T09:00:00.000Z')
const AFTER_BUILD = new Date('2026-10-03T11:00:00.000Z')

/** Only the inputs each case writes, so an absent `public/` or lock file is not what fails. */
const INPUTS = ['index.html', 'src']

let roots = []

afterEach(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true })
  roots = []
})

function writeAt(file, contents, when) {
  writeFileSync(file, contents)
  utimesSync(file, when, when)
}

/**
 * A tree with one nested source file and, unless `withDist` is false, a build output referencing
 * one hashed asset — the shape `vite build` emits and `stubOrigin.mjs` serves.
 */
function makeTree({ sourceModified = BEFORE_BUILD, withDist = true, withAsset = true } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'gw2-bundle-freshness-'))
  roots.push(root)

  mkdirSync(join(root, 'src', 'crafting'), { recursive: true })
  writeAt(join(root, 'index.html'), '<!doctype html><div id="app"></div>', OLDEST_INPUT)
  writeAt(join(root, 'src', 'crafting', 'Screen.vue'), '<template><p /></template>', sourceModified)

  if (withDist) {
    mkdirSync(join(root, 'dist', 'assets'), { recursive: true })
    if (withAsset) writeAt(join(root, 'dist', 'assets', 'index-abc123.js'), 'console.log(1)', BUILT_AT)
    writeAt(
      join(root, 'dist', 'index.html'),
      '<!doctype html><script type="module" src="/assets/index-abc123.js"></script>',
      BUILT_AT
    )
  }
  return root
}

describe('bundle freshness guard', () => {
  it('rejects a missing bundle and names the build command', () => {
    const verdict = describeBundleFreshness({ frontendDir: makeTree({ withDist: false }), inputs: INPUTS })

    expect(verdict.fresh).toBe(false)
    expect(verdict.reason).toContain('frontend/dist/index.html is missing')
    expect(verdict.reason).toContain('npm run build')
  })

  it('rejects a bundle whose source changed after it was built, naming that file and both times', () => {
    const verdict = describeBundleFreshness({
      frontendDir: makeTree({ sourceModified: AFTER_BUILD }),
      inputs: INPUTS
    })

    expect(verdict.fresh).toBe(false)
    expect(verdict.reason).toContain('frontend/dist is stale')
    expect(verdict.reason).toContain('src/crafting/Screen.vue')
    expect(verdict.reason).toContain(AFTER_BUILD.toISOString())
    expect(verdict.reason).toContain(BUILT_AT.toISOString())
    expect(verdict.reason).toContain('npm run build')
  })

  it('rejects output whose document references an asset the build did not leave behind', () => {
    const verdict = describeBundleFreshness({ frontendDir: makeTree({ withAsset: false }), inputs: INPUTS })

    expect(verdict.fresh).toBe(false)
    expect(verdict.reason).toContain('frontend/dist is incomplete')
    expect(verdict.reason).toContain('assets/index-abc123.js')
  })

  it('accepts a bundle built after every input and reports what it compared', () => {
    const verdict = describeBundleFreshness({ frontendDir: makeTree(), inputs: INPUTS })

    expect(verdict.fresh).toBe(true)
    expect(verdict.reason).toBeNull()
    expect(verdict.detail).toContain(`dist/index.html built ${BUILT_AT.toISOString()}`)
    expect(verdict.detail).toContain(`src/crafting/Screen.vue ${BEFORE_BUILD.toISOString()}`)
  })

  it('accepts a source file written in the same millisecond as the output', () => {
    const verdict = describeBundleFreshness({
      frontendDir: makeTree({ sourceModified: BUILT_AT }),
      inputs: INPUTS
    })

    expect(verdict.fresh).toBe(true)
  })

  it('reports no input as a failure instead of silently passing', () => {
    const verdict = describeBundleFreshness({ frontendDir: makeTree(), inputs: ['nothing-here'] })

    expect(verdict.fresh).toBe(false)
    expect(verdict.reason).toContain('Nothing was checked')
  })

  it('requireFreshBundle throws the reason when stale and returns the comparison when fresh', () => {
    const stale = makeTree({ sourceModified: AFTER_BUILD })
    expect(() => requireFreshBundle({ frontendDir: stale, inputs: INPUTS })).toThrow(
      /frontend\/dist is stale: src\/crafting\/Screen\.vue/
    )

    const current = makeTree()
    expect(requireFreshBundle({ frontendDir: current, inputs: INPUTS })).toContain(
      `dist/index.html built ${BUILT_AT.toISOString()}`
    )
  })
})
