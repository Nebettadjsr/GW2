/**
 * A transparent recording proxy in front of the backend, used by the live icon checks
 * (`STORY-WEB-010`).
 *
 * The dev/preview server is pointed at this process instead of at the backend, so every request the
 * browser really sent through application API routing — and every answer the backend really gave —
 * is written down: path, status, whether it carried a validator, and how long it took. That is what
 * makes "the browser used its own cache" an observation (the backend saw nothing) rather than an
 * inference from a picture being on screen.
 *
 * It rewrites nothing. It adds no header, strips none, and forwards the body untouched.
 */
import { createServer, request as httpRequest } from 'node:http'

/**
 * @param {object} options
 * @param {number} options.port            port to listen on (127.0.0.1 only)
 * @param {string} options.backendOrigin   e.g. http://127.0.0.1:8091
 */
export async function startRecordingProxy({ port, backendOrigin }) {
  const backend = new URL(backendOrigin)
  /** One entry per request that reached the backend. */
  const records = []

  const server = createServer((clientRequest, clientResponse) => {
    const started = Date.now()
    const forwarded = httpRequest(
      {
        host: backend.hostname,
        port: backend.port,
        path: clientRequest.url,
        method: clientRequest.method,
        headers: { ...clientRequest.headers, host: backend.host }
      },
      (backendResponse) => {
        records.push({
          method: clientRequest.method,
          path: clientRequest.url.split('?')[0],
          status: backendResponse.statusCode,
          conditional: clientRequest.headers['if-none-match'] !== undefined,
          cacheControl: backendResponse.headers['cache-control'] ?? null,
          contentType: backendResponse.headers['content-type'] ?? null,
          etag: backendResponse.headers.etag ?? null,
          ms: Date.now() - started
        })
        clientResponse.writeHead(backendResponse.statusCode, backendResponse.headers)
        backendResponse.pipe(clientResponse)
      }
    )
    forwarded.on('error', (error) => {
      records.push({
        method: clientRequest.method,
        path: clientRequest.url.split('?')[0],
        status: 0,
        error: error.code ?? error.message,
        ms: Date.now() - started
      })
      clientResponse.writeHead(502, { 'Content-Type': 'application/json' })
      clientResponse.end(JSON.stringify({ error: 'BACKEND_UNREACHABLE', message: String(error.code) }))
    })
    clientRequest.pipe(forwarded)
  })

  await new Promise((resolve, reject) => {
    server.once('error', (error) =>
      reject(new Error(`The recording proxy could not take 127.0.0.1:${port} (${error.code ?? error.message}).`))
    )
    server.listen(port, '127.0.0.1', () => resolve(undefined))
  })

  return {
    origin: `http://127.0.0.1:${port}`,
    records,
    /** Everything recorded since `mark`, so a phase can be counted on its own. */
    since: (mark) => records.slice(mark),
    mark: () => records.length,
    imagesSince: (mark) => records.slice(mark).filter((entry) => /\/api\/items\/\d+\/icon\//.test(entry.path)),
    close: () => server.close()
  }
}
