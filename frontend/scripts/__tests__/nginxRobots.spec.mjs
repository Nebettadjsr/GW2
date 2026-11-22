/** Static checks of the public Home exception and the app's non-indexable boundary (#225). */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const conf = readFileSync('default.conf.template', 'utf8')
const html = readFileSync('index.html', 'utf8')
const welcomeHtml = readFileSync('welcome.html', 'utf8')
const robots = readFileSync('public/robots.txt', 'utf8')
const HEADER = 'add_header X-Robots-Tag "noindex, nofollow" always;'
const HOME_HEADER = 'add_header X-Robots-Tag $gw2_welcome_robots always;'

function expectedHeader(name) {
  return name === '= /home' ? HOME_HEADER : HEADER
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
  it('allows crawlers only the canonical Home URL and its assets and emits no sitemap', () => {
    expect(robots.trim().split(/\r?\n/)).toEqual([
      'User-agent: *', 'Allow: /home$', 'Allow: /assets/', 'Disallow: /'
    ])
    expect(conf).toMatch(/sitemap[^\n]*\n[\s\S]*return 404/)
  })
  it('gives the separate Home document its own indexing policy', () => {
    expect(html).toContain('<meta name="robots" content="noindex, nofollow" />')
    expect(welcomeHtml).toContain('<meta name="robots" content="index, follow" />')
  })
  it('permits indexing only on successful canonical Home responses and keeps errors non-indexable', () => {
    const policy = conf.match(/map \$status \$gw2_welcome_robots\s*\{([^}]+)\}/)?.[1]
    expect(policy).toBeDefined()
    expect([...policy.matchAll(/(default|\d+)\s+"([^"]+)";/g)].map(match => [match[1], match[2]]))
      .toEqual([
        ['default', 'noindex, nofollow'], ['200', 'index, follow'],
        ['206', 'index, follow'], ['304', 'index, follow']
      ])
    const home = locations().find(location => location.name === '= /home')
    expect(home?.body).toContain('try_files /welcome.html =404;')
    expect(home?.body).toContain(HOME_HEADER)
    expect(locations().filter(location => location.body.includes(HOME_HEADER)).map(location => location.name))
      .toEqual(['= /home'])
  })
  it.each(['/welcome', '/welcome.html'])('redirects legacy %s permanently to Home without allowing it to index', path => {
    const redirect = locations().find(location => location.name === `= ${path}`)
    expect(redirect?.body).toContain('absolute_redirect off;')
    expect(redirect?.body).toContain('return 308 /home;')
    expect(redirect?.body).toContain(HEADER)
  })
})
