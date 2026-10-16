<script setup lang="ts">
import { computed } from 'vue'
import type { CalculationFreshness } from '@/api/types'

/**
 * What the Crafting Profit page says about the inputs behind the result on screen (UD-015 items 4,
 * 5 and 6).
 *
 * Three situations, never two at once:
 *
 * - **refreshing** — the backend answered from inputs it does not consider current, so the cached
 *   result stays on screen while the refresh it admitted runs. The chip reuses the Trading Post
 *   price warning's visual form (a small bordered label beside the results) with a green animated
 *   border instead of the red outline.
 * - **failed** — automatic recovery stopped: the animation ends, the last usable rows stay, and the
 *   backend's own reason is offered with a retry.
 * - nothing at all once a final calculation for the current context has replaced the result.
 *
 * Account-source freshness and Trading Post coverage are reported separately and are never merged
 * into one state: they are refreshed by two independently coordinated mechanisms. Every number and
 * state here is the backend's own; this component counts entries of a supplied list and formats
 * them, and derives no freshness of its own.
 */
const props = defineProps<{
  /** Automatic recovery is running: this is what the animation follows. */
  isRecovering: boolean
  /** A result is displayed and its inputs are not confirmed current. */
  isProvisional: boolean
  /** No usable cached inputs existed, so there is no result to display yet. */
  isPending: boolean
  /** Why recovery stopped without a final result; null while it may still reach one. */
  failure: string | null
  freshness: CalculationFreshness | null
}>()

const emit = defineEmits<{ retry: [] }>()

const isVisible = computed(
  () => props.failure !== null || ((props.isProvisional || props.isPending) && props.isRecovering)
)

const explanation = computed(() =>
  props.isPending
    ? 'No calculation could be made from the data synchronized so far, so no results are shown. ' +
      'They appear as soon as the refresh below has finished.'
    : 'The results below were calculated from the data that last synchronized successfully. They ' +
      'stay on screen until the refresh has finished, and are then replaced automatically with the ' +
      'recalculated ones.'
)

const failureExplanation = computed(() =>
  props.isPending
    ? 'No results could be calculated, because the account data they need has not synchronized.'
    : 'The results below are still the cached ones; they were not replaced.'
)

/** Which mechanisms the backend reported as still working, in its own terms. */
const refreshing = computed(() => {
  const kinds = (props.freshness?.outstandingWork ?? []).map((work) => KIND_LABELS[work.kind] ?? work.kind)
  return [...new Set(kinds)]
})

const accountSummary = computed(() => {
  const sources = props.freshness?.accountSources ?? []
  if (sources.length === 0) return 'Account data: no required source was reported.'

  const fresh = sources.filter((source) => source.state === 'FRESH')
  const expired = sources.filter((source) => source.state === 'STALE')
  const never = sources.filter((source) => source.state === 'MISSING')
  // A state this client has no wording for stays visible rather than being read as up to date.
  const other = sources.filter((source) => !KNOWN_SOURCE_STATES.includes(source.state))

  const parts = [`${fresh.length} of ${sources.length} up to date`]
  if (expired.length > 0) parts.push(`expired: ${names(expired)}`)
  if (never.length > 0) parts.push(`never synchronized: ${names(never)}`)
  for (const source of other) parts.push(`${source.source}: ${source.state}`)
  return `Account data: ${parts.join(' · ')}`
})

const tradingPostSummary = computed(() => {
  const coverage = props.freshness?.tradingPost ?? null
  if (coverage === null) return 'Trading Post prices: no coverage was reported.'
  if (coverage.covered) {
    return `Trading Post prices: all ${coverage.requiredItems} required items covered`
  }
  return (
    `Trading Post prices: ${coverage.freshItems} of ${coverage.requiredItems} required items ` +
    `up to date · expired: ${coverage.staleItems} · no stored price: ${coverage.missingItems}`
  )
})

const KIND_LABELS: Record<string, string> = {
  ACCOUNT: 'account data',
  TRADING_POST: 'Trading Post prices'
}

const KNOWN_SOURCE_STATES = ['FRESH', 'STALE', 'MISSING']

function names(sources: { source: string }[]): string {
  return sources.map((source) => source.source).join(', ')
}
</script>

<template>
  <div v-if="isVisible" class="refresh-status" data-test="refresh-status">
    <p
      v-if="failure === null"
      class="refresh-status__line"
      role="status"
      data-test="stale-data-indicator"
    >
      <span class="refresh-status__chip" data-test="stale-data-chip">
        <span aria-hidden="true">↻</span> Stale data – refreshing…
      </span>

      <span class="meta detail-line" data-test="stale-data-explanation">{{ explanation }}</span>

      <span
        v-if="refreshing.length > 0"
        class="meta detail-line"
        data-test="stale-data-outstanding"
      >
        Refreshing: {{ refreshing.join(' · ') }}
      </span>

      <span class="meta detail-line" data-test="stale-data-account">{{ accountSummary }}</span>
      <span class="meta detail-line" data-test="stale-data-trading-post">{{ tradingPostSummary }}</span>
    </p>

    <p v-else class="notice notice--warning" role="alert" data-test="refresh-failed">
      The automatic refresh did not finish. {{ failureExplanation }}

      <span class="meta detail-line" data-test="refresh-failure-detail">
        Backend answer: {{ failure }}
      </span>

      <span class="meta detail-line" data-test="refresh-failed-account">{{ accountSummary }}</span>
      <span class="meta detail-line" data-test="refresh-failed-trading-post">
        {{ tradingPostSummary }}
      </span>

      <span class="notice__actions">
        <button type="button" data-test="refresh-retry" @click="emit('retry')">
          Try refreshing again
        </button>
      </span>
    </p>
  </div>
</template>

<style scoped>
.refresh-status__line {
  margin: 0;
}

/*
 * The Trading Post price warning's own form — a small bordered label with its own tone, beside the
 * results — with UD-015's green animated border in place of that one's red outline. The words say
 * what the state is, so the colour and the motion only reinforce them. The shell switches every
 * animation off under `prefers-reduced-motion`, which leaves the label and its border in place.
 */
.refresh-status__chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border: 2px solid var(--color-success);
  border-radius: var(--radius-control);
  color: var(--color-success);
  font-size: var(--text-sm);
  animation: refresh-cycle 1.6s ease-in-out infinite;
}

@keyframes refresh-cycle {
  0% {
    border-color: var(--color-success);
    box-shadow: 0 0 0 0 rgb(136 212 161 / 45%);
  }

  50% {
    border-color: var(--color-border);
    box-shadow: 0 0 0 4px rgb(136 212 161 / 0%);
  }

  100% {
    border-color: var(--color-success);
    box-shadow: 0 0 0 0 rgb(136 212 161 / 45%);
  }
}
</style>
