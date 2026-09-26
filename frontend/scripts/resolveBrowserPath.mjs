/**
 * Locates the browser the real-browser checks drive.
 *
 * `playwright-core` ships no browser of its own by design, so an installed one is used. Only
 * Chromium-family builds Playwright can drive are listed; `GW2_BROWSER_PATH` overrides the list.
 */
import { existsSync } from 'node:fs'

const BROWSER_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
]

export function resolveBrowserPath() {
  const configured = process.env.GW2_BROWSER_PATH
  if (configured !== undefined && configured !== '') {
    if (!existsSync(configured)) throw new Error(`GW2_BROWSER_PATH does not exist: ${configured}`)
    return configured
  }

  const found = BROWSER_CANDIDATES.find((candidate) => existsSync(candidate))
  if (found === undefined) {
    throw new Error('No Chrome/Edge executable found. Set GW2_BROWSER_PATH to one.')
  }
  return found
}
