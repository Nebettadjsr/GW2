import assert from 'node:assert/strict'

const DUMMY_KEY = 'synthetic-read-only-smoke-key'
const ACCOUNT_ID = '00000000-0000-4000-8000-000000000194'

/**
 * Test-only backend boundary; the real App restores and proves the retained synthetic key.
 * `keyedRoutes` are POST refreshes that, like the real App, must forward that key as a header;
 * `publicDocumentPaths` are document paths (Playwright reports no fragment) that owe no proof.
 */
export function createControlledAccountSession({ calculationRoutes, searchQueries, searchResults = {}, accountReadRoutes = [], publicReadRoutes = [], keyedRoutes = [], publicDocumentPaths = [] }) {
  const methods = new Map([
    ['/api/session', 'POST'],
    ['/api/crafting/selector-options', 'GET'],
    ['/api/crafting/ingredient-search', 'GET'],
    ...accountReadRoutes.map(path => [path, 'GET']),
    ...publicReadRoutes.map(path => [path, 'GET']),
    ...calculationRoutes.map(path => [path, 'POST']),
    ...keyedRoutes.map(path => [path, 'POST'])
  ])
  const failures = []
  let documents = 0
  let publicDocuments = 0
  let onPublicDocument = false
  let proofs = 0
  let lookups = 0
  let needsProof = true
  let accessContext = null

  return {
    async install(page, origin) {
      await page.addInitScript(({ origin, key }) => {
        if (window.location.origin === origin) window.sessionStorage.setItem('gw2.apiKey', key)
      }, { origin, key: DUMMY_KEY })
      page.on('request', request => {
        if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
          // A public document (Home at /welcome) restores no key, so it owes no proof and any
          // API read it made would still be rejected below as preceding one.
          const target = new URL(request.url())
          onPublicDocument = publicDocumentPaths.includes(target.pathname)
          if (onPublicDocument) publicDocuments += 1
          else documents += 1
          needsProof = true
        }
      })
      // An accidental external request is blocked before any real API or host is contacted.
      await page.route('**/*', route => {
        const url = new URL(route.request().url())
        if (url.origin !== origin) {
          failures.push(`Off-origin request: ${url.origin}${url.pathname}`)
          return route.abort()
        }
        return route.continue()
      })
    },

    /** Returns true when the helper answered, false when a feature fixture should answer. */
    answer({ request, url, body, sendJson }) {
      function reject(status, message) {
        failures.push(message)
        sendJson(status, { error: 'INVALID_SMOKE_REQUEST', message })
        return true
      }
      // A public document reads nothing at all: not even an allow-listed public read is acceptable.
      if (onPublicDocument) return reject(403, `Public document made an API call: ${request.method} ${url.pathname}`)
      const method = methods.get(url.pathname)
      if (method === undefined) return reject(404, `Unexpected route: ${url.pathname}`)
      if (request.method !== method) return reject(405, `Unexpected method on ${url.pathname}`)
      if (url.search.includes(DUMMY_KEY) || String(body ?? '').includes(DUMMY_KEY)) {
        return reject(400, `Raw key sent in body or query: ${url.pathname}`)
      }
      const key = request.headers['x-gw2-api-key']
      // Shared public reads (item metadata/prices) need no account proof, and never a key or context.
      if (publicReadRoutes.includes(url.pathname)) {
        if (key !== undefined) return reject(400, `Raw key sent on public route: ${url.pathname}`)
        if (request.headers['x-account-context'] !== undefined) {
          return reject(400, `Account context sent on public route: ${url.pathname}`)
        }
        return false
      }
      if (url.pathname === '/api/session') {
        if (key !== DUMMY_KEY) return reject(401, 'Proof did not use the synthetic retained key')
        proofs += 1
        needsProof = false
        accessContext = `smoke-context-${proofs}`
        sendJson(200, {
          accountId: ACCOUNT_ID, accessContext, expiresAt: '2099-01-01T00:00:00Z',
          grants: ['account', 'inventories', 'characters', 'unlocks']
        })
        return true
      }
      // Keyed refreshes forward the retained key as a header, and require it; nothing else may carry it.
      if (keyedRoutes.includes(url.pathname) ? key !== DUMMY_KEY : key !== undefined) {
        return reject(400, `Wrong key forwarding outside proof: ${url.pathname}`)
      }
      if (needsProof) return reject(401, `Request preceded this document's proof: ${url.pathname}`)
      if (url.pathname === '/api/crafting/ingredient-search') {
        const query = url.searchParams.get('query')
        if (!searchQueries.includes(query)) {
          return reject(400, 'Unexpected ingredient-search query')
        }
        lookups += 1
        sendJson(200, { recipeIds: searchResults[query] ?? [] })
        return true
      }
      if (request.headers['x-account-context'] !== accessContext) {
        return reject(401, `Missing or stale account context: ${url.pathname}`)
      }
      return false
    },

    verify() {
      assert.deepEqual(failures, [], 'Controlled session boundary violations')
      assert.ok(proofs > 0, 'No synthetic key was proved')
      assert.equal(proofs, documents, 'Each document load must prove its retained key once')
      return `${proofs} proofs before protected reads over ${documents} document loads; ${publicDocuments} public document loads with zero API calls; ${lookups} cached search lookups`
    }
  }
}
