/**
 * A controlled origin for the real-browser checks: it serves `dist/` and answers `/api/` from the
 * calling script's own process.
 *
 * One implementation on purpose. A check that claims "nothing real was touched" needs a positive
 * identity assertion, so this module binds 127.0.0.1 explicitly (never the name `localhost`, which can
 * resolve to an IPv6 dev server the script did not start while the IPv4 port stayed free), rejects on
 * a `listen` error instead of driving whatever else answers there, and counts what it served so the
 * caller can assert the page came from here — see `tasks/lessons.md`.
 */
import { existsSync, readFileSync } from 'node:fs'
import { createServer } from 'node:http'

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
}

/**
 * @param {object} options
 * @param {number} options.port
 * @param {string} options.distDir  absolute path of the built frontend
 * @param {(context: { request: import('node:http').IncomingMessage, response: import('node:http').ServerResponse, url: URL, body: string, sendJson: (status: number, body: unknown) => void }) => void} options.answerApi
 */
export async function startStubOrigin({ port, distDir, answerApi }) {
  const origin = `http://127.0.0.1:${port}`
  /** Every `/api/` request this origin answered, so a run can assert what the browser really sent. */
  const requests = []
  let served = 0

  function sendJsonWith(response) {
    return (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(body))
    }
  }

  function serveStatic(response, pathname) {
    const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\//, '')
    const file = `${distDir}${relative}`
    if (!file.startsWith(distDir) || !existsSync(file)) {
      response.writeHead(404)
      return response.end('not found')
    }
    const extension = relative.slice(relative.lastIndexOf('.'))
    response.writeHead(200, { 'Content-Type': MIME_TYPES[extension] ?? 'application/octet-stream' })
    response.end(readFileSync(file))
  }

  const server = createServer((request, response) => {
    const url = new URL(request.url, origin)
    served += 1
    if (!url.pathname.startsWith('/api/')) return serveStatic(response, url.pathname)

    let body = ''
    request.on('data', (chunk) => {
      body += chunk
    })
    request.on('end', () => {
      requests.push({ method: request.method, path: url.pathname, body })
      answerApi({ request, response, url, body, sendJson: sendJsonWith(response) })
    })
  })

  await new Promise((resolve, reject) => {
    server.once('error', (error) =>
      reject(
        new Error(
          `The stub origin could not take ${origin} (${error.code ?? error.message}). ` +
            'Free that port, or set the port environment variable of this check.'
        )
      )
    )
    server.listen(port, '127.0.0.1', () => resolve(undefined))
  })

  return {
    origin,
    requests,
    /** Requests this origin answered; zero after a page load means a foreign origin served it. */
    servedCount: () => served,
    requestsTo: (prefix) => requests.filter((recorded) => recorded.path.startsWith(prefix)),
    close: () => server.close()
  }
}
