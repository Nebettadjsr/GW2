/**
 * A tiny deterministic stand-in for the real application, used only to regression-test the journey runner in a
 * real browser (opt-in). It reproduces the `data-test` hooks, loading/pending phases, pending-vs-saved
 * selection and the `/api/` calls the journeys depend on; it is NOT the product and proves nothing about it.
 */
import { createServer } from 'node:http'

const PAGE = (bug) => `<!doctype html><html><body>
<nav><button data-test="nav-crafting" onclick="location.hash='#/crafting'">Crafting</button>
<button data-test="nav-bank" onclick="location.hash='#/bank'">Bank</button>
<button data-test="nav-materials" onclick="location.hash='#/materials'">Materials</button>
<button data-test="nav-characters" onclick="location.hash='#/characters'">Characters</button></nav>
<h1 data-test="page-heading"></h1><main id="main"></main>
<script>
const BUG = ${JSON.stringify(bug)}
const KEY = 'gw2.materialProtection.v1.acct'
if (localStorage.getItem(KEY) === null) localStorage.setItem(KEY, JSON.stringify({ committed: [1] }))
const main = document.getElementById('main'), heading = document.querySelector('[data-test=page-heading]')
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
let pending = false, checked = false, selected = null, section = 'equipment', recalcs = 0, sell = 'instant', shownSell = 'instant', detailDelayed = false
setInterval(() => { fetch('/api/sync/status').catch(() => {}) }, 40)
const h = (html) => { main.innerHTML = html }
async function render() {
  const hash = location.hash || '#/crafting'
  if (hash === '#/crafting') {
    heading.textContent = 'Crafting Profit'
    h('<p data-test="loading">Loading</p>'); await wait(120); drawCrafting()
  } else if (hash === '#/bank') {
    heading.textContent = 'Bank'; h('<p data-test="bank-loading">Loading</p>'); await wait(120)
    h('<ol><li data-test="bank-slot">Slot</li></ol>')
    if (BUG === 'retained-update') {
      main.insertAdjacentHTML('beforeend', '<p class="refresh-status">Stale data – refreshing</p>')
      setTimeout(() => main.querySelector('.refresh-status')?.remove(), 900)
    }
  } else if (hash === '#/materials') {
    heading.textContent = 'Materials'; h('<p data-test="materials-loading">Loading</p>'); await wait(120); drawMaterials()
  } else if (hash === '#/characters') {
    heading.textContent = 'Characters'; h('<p data-test="roster-loading">Loading</p>'); await wait(150)
    h('<ul><li data-test="roster-row"><button data-test="roster-item" data-name="Synthetic Hero">Synthetic Hero</button></li></ul>')
    main.querySelector('[data-test=roster-item]').onclick = drawDetail
  }
}
function drawCrafting(busy) {
  const showBusy = busy && BUG !== 'hide-busy'
  h('<button data-test="refresh-data-and-results" ' + (showBusy ? 'disabled' : '') + '>Refresh</button>' +
    '<select data-test="setting-listingSell"><option value="instant"' + (sell === 'instant' ? ' selected' : '') + '>Instant</option><option value="listing"' + (sell === 'listing' ? ' selected' : '') + '>Listing</option></select>' +
    (showBusy ? '<p data-test="pending">Calculating</p>' : '') +
    '<p data-test="summary">Run ' + recalcs + ' sell ' + shownSell + '</p><table><tr data-test="profit-row"><th><button data-test="select-row"><span class="recipe-name">Synthetic Recipe</span><span class="recipe-ids">recipe 7</span></button></th></tr></table><div id="detail"></div>')
  const select = main.querySelector('[data-test=setting-listingSell]')
  select.onchange = async () => {
    sell = select.value; drawCrafting(true)
    if (BUG === 'request-flood') {
      // Exhaust the exported trace before the calculation is offered.
      for (let batch = 0; batch < 11; batch++) await Promise.all(Array.from({length:40}, () => fetch('/api/sync/status')))
    }
    if (BUG === 'hide-busy') fetch('/api/crafting/profit?noise=1', { method: 'POST', body: JSON.stringify({settings:{listingSell:sell !== 'listing'}}) })
    await fetch('/api/crafting/profit', { method: 'POST', body: JSON.stringify({settings:{listingSell:sell === 'listing'}}) }); recalcs += 1; shownSell = sell; drawCrafting(false)
  }
  main.querySelector('[data-test=refresh-data-and-results]').onclick = async () => {
    drawCrafting(true); await fetch('/api/account/refresh', { method: 'POST' }); await wait(100); recalcs += 1; drawCrafting(false)
  }
  main.querySelector('[data-test=select-row]').onclick = async () => {
    document.getElementById('detail').innerHTML = '<h3 data-test="detail-name">Synthetic Recipe</h3><p data-test="resolution-loading">Loading detail</p>'; await wait(100)
    const state = BUG === 'resolution-failed' ? 'resolution-failed' : BUG === 'resolution-unavailable' ? 'resolution-unavailable' : 'resolution-tree'
    document.getElementById('detail').innerHTML = '<h3 data-test="detail-name">Synthetic Recipe</h3><div data-test="' + state + '">Explanation outcome</div>'
  }
}
function drawMaterials() {
  h('<div data-test="material-category"><label><input type="checkbox" data-test="protect-checkbox" ' + (checked ? 'checked' : '') + '> item</label></div>' +
    '<p data-test="selection-state" data-state="' + (pending ? 'pending' : 'clean') + '"></p>' +
    '<button data-test="selection-confirm" ' + (pending ? '' : 'disabled') + '>Save selection</button>' +
    '<button data-test="selection-discard" ' + (pending ? '' : 'disabled') + '>Discard changes</button>')
  const box = main.querySelector('[data-test=protect-checkbox]')
  box.onchange = () => { checked = box.checked; pending = true; drawMaterials() }
  main.querySelector('[data-test=selection-confirm]').onclick = () => localStorage.setItem(KEY, JSON.stringify({ committed: [1, 2] }))
  main.querySelector('[data-test=selection-discard]').onclick = () => {
    pending = false; checked = false
    if (BUG === 'discard-writes') localStorage.setItem(KEY, JSON.stringify({ committed: [1] }))
    drawMaterials()
  }
}
function drawDetail() {
  selected = 'Synthetic Hero'; heading.textContent = selected
  const tabs = ['equipment', 'inventory', 'builds'].map((s) => '<button data-test="section-' + s + '" aria-selected="' + (s === section) + '">' + s + '</button>').join('')
  h('<section data-test="character-detail"><div data-test="character-summary">' + selected + '</div>' + tabs + '<div id="panel"></div></section>')
  if (BUG === 'detail-delayed' && !detailDelayed) {
    detailDelayed = true
    main.querySelector('[data-test=character-summary]').hidden = true
    main.insertAdjacentHTML('beforeend', '<p data-test="detail-loading">Loading character</p>')
    setTimeout(() => {
      main.querySelector('[data-test=detail-loading]')?.remove()
      const summary = main.querySelector('[data-test=character-summary]'); if (summary) summary.hidden = false
    }, 900)
  }
  for (const s of ['equipment', 'inventory', 'builds']) main.querySelector('[data-test=section-' + s + ']').onclick = async () => {
    section = s; drawDetail(); document.getElementById('panel').innerHTML = '<p data-test="' + s + '-loading">Loading</p>'; await wait(100)
    const panel = { equipment: '<div data-test="equipment-panel">ok</div>', inventory: '<div data-test="inventory-state">GRANT_REQUIRED</div>', builds: BUG === 'builds-nohook' ? '<p>unknown</p>' : '<p data-test="builds-none">No saved builds</p>' }
    document.getElementById('panel').innerHTML = panel[s]
  }
}
addEventListener('hashchange', render); render()
</script></body></html>`

/** Starts the synthetic origin on 127.0.0.1; `bug: 'discard-writes'` makes Discard use the saved-selection write path. */
export async function startSyntheticApp({ bug = null, calcDelayMs = 150 } = {}) {
  const apiPaths = []
  const server = createServer((request, response) => {
    if (request.url.startsWith('/api/')) {
      apiPaths.push(`${request.method} ${request.url}`)
      const reply = () => {
        response.writeHead(200, { 'Content-Type': 'application/json', 'X-GW2-Test-Request-Id': `synthetic-${apiPaths.length}` })
        response.end('{}')
      }
      // The calculation answers late while unrelated polls keep answering at once.
      if (request.method === 'POST' && request.url === '/api/crafting/profit') return void setTimeout(reply, calcDelayMs)
      return reply()
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(PAGE(bug))
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return { origin: `http://127.0.0.1:${server.address().port}`, apiPaths, close: () => new Promise((resolve) => server.close(resolve)) }
}
