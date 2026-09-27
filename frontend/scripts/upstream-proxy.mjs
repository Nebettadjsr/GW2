/**
 * A counting HTTPS forward proxy, used only as *evidence* in the live icon checks
 * (`STORY-WEB-010`, `TARGET_ARCHITECTURE.md` 12.1).
 *
 * The backend is started with `-Dhttps.proxyHost/-Dhttps.proxyPort` pointing here, so every upstream
 * image fetch it makes has to open a CONNECT tunnel through this process. That turns "the backend
 * made no upstream request" from something inferred (a rendered image, an unchanged directory) into
 * something counted. It sits between the backend and ArenaNet; the browser never talks to it.
 *
 * Two modes:
 *   forward (default)  the tunnel is established, so a real cold-cache miss can still be served;
 *   refuse             the tunnel is rejected, which is what "upstream unavailable" means here.
 *
 * Each CONNECT prints one line to stdout, so the count is the log. It holds no credential, reads no
 * body, and cannot see inside the tunnel it forwards.
 *
 * Usage:  node scripts/upstream-proxy.mjs [--port 8099] [--refuse]
 */
import { connect } from 'node:net'
import { createServer } from 'node:http'

const args = process.argv.slice(2)
const port = Number(args[args.indexOf('--port') + 1] ?? 8099)
const refuse = args.includes('--refuse')

let tunnels = 0

const server = createServer((request, response) => {
  // Plain HTTP through the proxy: not expected — the only upstream this backend has is HTTPS.
  console.log(`PLAIN ${request.method} ${request.url}`)
  response.writeHead(502)
  response.end('this proxy only tunnels CONNECT')
})

server.on('connect', (request, clientSocket, head) => {
  tunnels += 1
  console.log(`CONNECT ${request.url} #${tunnels}${refuse ? ' refused' : ''}`)
  if (refuse) {
    clientSocket.end('HTTP/1.1 502 Bad Gateway\r\n\r\n')
    return
  }
  const [host, rawPort] = request.url.split(':')
  const upstream = connect(Number(rawPort ?? 443), host, () => {
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
    upstream.write(head)
    upstream.pipe(clientSocket)
    clientSocket.pipe(upstream)
  })
  const drop = () => {
    upstream.destroy()
    clientSocket.destroy()
  }
  upstream.on('error', drop)
  clientSocket.on('error', drop)
})

server.on('error', (error) => {
  console.error(`upstream proxy could not take 127.0.0.1:${port} (${error.code ?? error.message})`)
  process.exit(1)
})

server.listen(port, '127.0.0.1', () =>
  console.log(`upstream proxy listening on 127.0.0.1:${port} (${refuse ? 'refuse' : 'forward'})`)
)
