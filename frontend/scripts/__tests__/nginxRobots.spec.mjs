/** Static checks of the frontend Nginx template's non-indexable policy (STORY-LAUNCH-002). */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const conf = readFileSync('default.conf.template', 'utf8')
const html = readFileSync('index.html', 'utf8')
const HEADER = 'add_header X-Robots-Tag "noindex, nofollow" always;'

/** Top-level block bodies of `location` directives, keyed by their line. */
function locations() {
  const out = []
  const re = /^\s*location ([^{]+)\{([\s\S]*?)^\s{4}\}/gm
  for (const m of conf.matchAll(re)) out.push({ name: m[1].trim(), body: m[2] })
  return out
}

describe('nginx robots policy', () => {
  it('sets the header at server level with always', () => expect(conf).toContain(HEADER))
  it('repeats it in every location that declares its own add_header', () => {
    const found = locations()
    expect(found.length).toBeGreaterThanOrEqual(5)
    for (const { name, body } of found) {
      if (/add_header/.test(body) || /return 404/.test(body)) {
        expect(body, `location ${name}`).toContain(HEADER)
      }
    }
  })
  it('every location with a Cache-Control header also has the robots header', () => {
    for (const { name, body } of locations()) {
      if (body.includes('Cache-Control')) expect(body, name).toContain(HEADER)
    }
  })
  it('never disallows crawling and emits no sitemap', () => {
    expect(conf).not.toMatch(/^[^#\n]*Disallow/im)
    expect(conf).toMatch(/sitemap[^\n]*\n[\s\S]*return 404/)
  })
  it('has the matching HTML meta default', () => {
    expect(html).toContain('<meta name="robots" content="noindex, nofollow" />')
  })
})
