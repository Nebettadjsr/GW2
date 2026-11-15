// @vitest-environment node
import { createServer } from 'node:http'
import { afterEach, expect, it } from 'vitest'
import { startRequestObserver } from '../requestObserver.mjs'

const cleanup = []
afterEach(async () => {
  for (const close of cleanup.splice(0).reverse()) await close()
})

async function upstream(handler) {
  const server = createServer(handler)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  cleanup.push(() => new Promise(resolve => server.close(resolve)))
  return `http://127.0.0.1:${server.address().port}`
}

async function observe(backendOrigin) {
  const observer = await startRequestObserver({ port: 0, backendOrigin })
  cleanup.push(() => observer.close())
  return observer
}

it('forwards session headers and body but records only safe request facts', async () => {
  let received
  const backendOrigin = await upstream(async (request, response) => {
    let body = ''
    for await (const chunk of request) body += chunk
    received = { method: request.method, url: request.url, headers: request.headers, body }
    response.writeHead(201, { 'Content-Type': 'application/json', 'Set-Cookie': 'GW2_SESSION=fake-lease; HttpOnly' })
    response.end('{"ok":true}')
  })
  const observer = await observe(backendOrigin)
  const response = await fetch(`${observer.origin}/api/session?test=only`, {
    method: 'POST',
    headers: { 'X-GW2-API-Key': 'fake-key', Cookie: 'GW2_SESSION=fake-cookie', 'X-Account-Context': 'fake-context' },
    body: 'synthetic-body'
  })
  expect(response.status).toBe(201)
  expect(await response.text()).toBe('{"ok":true}')
  expect(response.headers.get('set-cookie')).toBe('GW2_SESSION=fake-lease; HttpOnly')
  expect(received).toMatchObject({ method: 'POST', url: '/api/session?test=only', body: 'synthetic-body' })
  expect(received.headers).toMatchObject({ 'x-gw2-api-key': 'fake-key', cookie: 'GW2_SESSION=fake-cookie', 'x-account-context': 'fake-context' })
  expect(observer.since(0)).toEqual([{
    method: 'POST', path: '/api/session', status: 201, conditional: false,
    cacheControl: null, contentType: 'application/json', etag: null
  }])
  expect(JSON.stringify(observer.records)).not.toMatch(/fake-key|fake-cookie|fake-context|synthetic-body|elapsed|duration/)
})

it('preserves image bytes, validators and conditional responses for cache checks', async () => {
  const bytes = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  const backendOrigin = await upstream((request, response) => {
    if (request.headers['if-none-match'] === '"icon-1"') {
      response.writeHead(304, { ETag: '"icon-1"' })
      response.end()
    } else {
      response.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=60', ETag: '"icon-1"' })
      response.end(bytes)
    }
  })
  const observer = await observe(backendOrigin)
  const url = `${observer.origin}/api/items/1/icon/key.png`
  const first = await fetch(url)
  expect(Buffer.from(await first.arrayBuffer())).toEqual(bytes)
  expect(first.headers.get('cache-control')).toBe('public, max-age=60')
  const mark = observer.mark()
  const second = await fetch(url, { headers: { 'If-None-Match': '"icon-1"' } })
  expect(second.status).toBe(304)
  expect(await second.text()).toBe('')
  expect(observer.imagesSince(mark)).toEqual([{
    method: 'GET', path: '/api/items/1/icon/key.png', status: 304,
    conditional: true, cacheControl: null, contentType: null, etag: '"icon-1"'
  }])
})

it('returns a bounded failure when its upstream is unreachable', async () => {
  const backendOrigin = await upstream((_request, response) => response.end())
  await cleanup.pop()()
  const observer = await observe(backendOrigin)
  const response = await fetch(`${observer.origin}/api/account/bank`)
  expect(response.status).toBe(502)
  expect(await response.json()).toMatchObject({ error: 'BACKEND_UNREACHABLE' })
  expect(observer.since(0)).toMatchObject([{ method: 'GET', path: '/api/account/bank', status: 0, error: 'ECONNREFUSED' }])
})
