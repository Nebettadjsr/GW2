// @vitest-environment node
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, sep } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { startStubOrigin } from '../stubOrigin.mjs'

let fixture
let distDir

afterEach(async () => {
  await fixture?.close()
  fixture = undefined
  if (distDir) rmSync(distDir, { recursive: true, force: true })
  distDir = undefined
})

async function origin({ withHome = true } = {}) {
  distDir = mkdtempSync(join(tmpdir(), 'gw2-stub-home-'))
  writeFileSync(join(distDir, 'index.html'), '<main>Application shell</main>')
  if (withHome) writeFileSync(join(distDir, 'welcome.html'), '<main>Public Home</main>')
  const answerApi = vi.fn()
  fixture = await startStubOrigin({ port: 0, distDir: `${distDir}${sep}`, answerApi })
  return { url: fixture.origin, answerApi }
}

describe('controlled origin Home routes', () => {
  it('serves the separate public Home document on direct loads and reloads without an API call', async () => {
    const { url, answerApi } = await origin()
    for (let load = 0; load < 2; load++) {
      const response = await fetch(`${url}/home`)
      expect(response.status).toBe(200)
      expect(response.headers.get('content-type')).toBe('text/html; charset=utf-8')
      expect(response.headers.get('x-robots-tag')).toBe('index, follow')
      expect(await response.text()).toBe('<main>Public Home</main>')
    }
    expect(answerApi).not.toHaveBeenCalled()
    expect(fixture.requests).toEqual([])
  })

  it.each(['/welcome', '/welcome.html'])('redirects legacy %s with 308 and noindex before serving Home', async path => {
    const { url, answerApi } = await origin()
    const redirect = await fetch(`${url}${path}`, { redirect: 'manual' })
    expect(redirect.status).toBe(308)
    expect(redirect.headers.get('location')).toBe('/home')
    expect(redirect.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    expect(await redirect.text()).toBe('')

    const followed = await fetch(`${url}${path}`)
    expect(followed.url).toBe(`${url}/home`)
    expect(followed.status).toBe(200)
    expect(await followed.text()).toBe('<main>Public Home</main>')
    expect(answerApi).not.toHaveBeenCalled()
  })

  it('returns a non-indexable 404 when the Home artifact is absent instead of serving the app shell', async () => {
    const { url } = await origin({ withHome: false })
    const response = await fetch(`${url}/home`)
    expect(response.status).toBe(404)
    expect(response.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    expect(await response.text()).toBe('not found')
  })

  it('keeps the application shell non-indexable', async () => {
    const { url } = await origin()
    const response = await fetch(`${url}/`)
    expect(response.status).toBe(200)
    expect(response.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    expect(await response.text()).toBe('<main>Application shell</main>')
  })
})
