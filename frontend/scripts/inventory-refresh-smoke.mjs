/** Current desktop auto-refresh/recovery story; no real backend, key, DB or ArenaNet call.
 * Replaces the historical390px repetition and assertions for removed manual inventory buttons.
 * Saved42 survives automatic refresh failure; System Status recovery installs supplied fresh77.
 * Run after npm run build: node scripts/inventory-refresh-smoke.mjs. The aggregate CI command already runs this same named story once.
 */
import { runUserJourneys } from './user-journeys.mjs'
await runUserJourneys({ recoveryOnly: true })
