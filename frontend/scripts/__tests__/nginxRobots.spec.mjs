/** Static checks of the public welcome exception and the app's non-indexable boundary (#225). */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const conf = readFileSync('default.conf.template', 'utf8')
const html = readFileSync('index.html', 'utf8')
const welcomeHtml = readFileSync('welcome.html', 'utf8')
const robots = readFileSync('public/robots.txt', 'utf8')
const HEADER = 'add_header X-Robots-Tag "noindex, nofollow" always;'
const WELCOME_HEADER = 'add_header X-Robots-Tag $gw2_welcome_robots always;'

function expectedHeader(name) {
  return name === '= /welcome' ? WELCOME_HEADER : HEADER
}

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
        expect(body, `location ${name}`).toContain(expectedHeader(name))
      }
    }
  })
  it('every location with a Cache-Control header also has the robots header', () => {
    for (const { name, body } of locations()) {
      if (body.includes('Cache-Control')) expect(body, name).toContain(expectedHeader(name))
    }
  })
  it('allows crawlers only the canonical welcome URL and its assets and emits no sitemap', () => {
    expect(robots.trim().split(/\r?\n/)).toEqual([
      'User-agent: *', 'Allow: /welcome$', 'Allow: /assets/', 'Disallow: /'
    ])
    expect(conf).toMatch(/sitemap[^\n]*\n[\s\S]*return 404/)
  })
  it('gives the separate welcome document its own indexing policy', () => {
    expect(html).toContain('<meta name="robots" content="noindex, nofollow" />')
    expect(welcomeHtml).toContain('<meta name="robots" content="index, follow" />')
  })
  it('permits indexing only on successful welcome responses and keeps errors non-indexable', () => {
    const policy = conf.match(/map \$status \$gw2_welcome_robots\s*\{([^}]+)\}/)?.[1]
    expect(policy).toBeDefined()
    expect([...policy.matchAll(/(default|\d+)\s+"([^"]+)";/g)].map(match => [match[1], match[2]]))
      .toEqual([
        ['default', 'noindex, nofollow'], ['200', 'index, follow'],
        ['206', 'index, follow'], ['304', 'index, follow']
      ])
    const welcome = locations().find(location => location.name === '= /welcome')
    expect(welcome?.body).toContain('try_files /welcome.html =404;')
    expect(welcome?.body).toContain(WELCOME_HEADER)
  })
  it('redirects the noncanonical welcome document without allowing it to index', () => {
    const redirect = locations().find(location => location.name === '= /welcome.html')
    expect(redirect?.body).toContain('return 308 /welcome;')
    expect(redirect?.body).toContain(HEADER)
  })
})
