<script setup lang="ts">
import { nextTick, ref } from 'vue'
import PageHeader from '../shell/PageHeader.vue'
import { destinationOf, type DestinationId } from '../shell/destinations'
import BeginnerTips from './BeginnerTips.vue'
import GuideScreenshot from './GuideScreenshot.vue'

const emit = defineEmits<{ navigate: [id: DestinationId] }>()
const TABS = [
  { id: 'welcome', label: 'Welcome' },
  { id: 'features', label: 'What you can do' },
  { id: 'guide', label: 'Getting started' },
  { id: 'tips', label: 'GW2 beginner tips' }
] as const
type TabId = typeof TABS[number]['id']
const selected = ref<TabId>('welcome')
const tabButtons = new Map<TabId, HTMLElement>()

function setTabButton(id: TabId, element: unknown): void {
  if (element instanceof HTMLElement) tabButtons.set(id, element)
  else tabButtons.delete(id)
}

async function selectTab(id: TabId): Promise<void> {
  selected.value = id
  await nextTick()
  tabButtons.get(id)?.focus({ preventScroll: true })
}

function onTabKey(event: KeyboardEvent): void {
  const current = TABS.findIndex(tab => tab.id === selected.value)
  let target: number
  if (event.key === 'ArrowRight') target = (current + 1) % TABS.length
  else if (event.key === 'ArrowLeft') target = (current - 1 + TABS.length) % TABS.length
  else if (event.key === 'Home') target = 0
  else if (event.key === 'End') target = TABS.length - 1
  else return
  event.preventDefault()
  void selectTab(TABS[target]!.id)
}

function hrefOf(id: DestinationId): string {
  return `/${destinationOf(id).path}`
}

function open(event: MouseEvent, id: DestinationId): void {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('navigate', id)
}

const tools: { id: DestinationId; description: string }[] = [
  { id: 'crafting', description: 'Compare crafting opportunities using your materials, recipes and characters. Open a result to see the ingredients and steps.' },
  { id: 'discovery', description: 'Find recipes a selected character can discover, with the materials and costs for one discovery attempt.' },
  { id: 'ecto', description: 'Compare the cost of salvaging Globs of Ectoplasm for Crystalline Dust and account luck.' },
  { id: 'materials', description: 'Browse material storage and choose which materials to exclude from Crafting Profit calculations.' },
  { id: 'bank', description: 'Find items in your account bank and save exclusions for materials you want to keep.' },
  { id: 'characters', description: 'Review your characters, crafting disciplines, equipment, inventory and builds in one place.' }
]
</script>

<template>
  <section class="home screen" data-test="home-screen">
    <PageHeader heading="Welcome to GW2 Crafting Tool" intro="Find what’s worth crafting, make more of your loot and pick up a few useful GW2 beginner tips along the way." />

    <div class="home__tabs" role="tablist" aria-label="About GW2 Crafting Tool" @keydown="onTabKey">
      <button v-for="tab in TABS" :id="`home-tab-${tab.id}`" :key="tab.id"
        :ref="element => setTabButton(tab.id, element)" type="button" role="tab"
        :aria-selected="selected === tab.id" :aria-controls="`home-panel-${tab.id}`"
        :tabindex="selected === tab.id ? 0 : -1" @click="selected = tab.id">{{ tab.label }}</button>
    </div>

    <div v-show="selected === 'welcome'" id="home-panel-welcome" class="home__panel" role="tabpanel" aria-labelledby="home-tab-welcome" tabindex="0">
      <div class="home__welcome">
        <div class="home__introduction">
          <p class="home__eyebrow">Your materials. Your characters. Your next craft.</p>
          <h2>Find out what you can make<br class="home__desktop-break" /> with what you already have.</h2>
          <p>Bring your materials, unlocked recipes and crafting disciplines together. Compare your options and turn a promising result into a plan you can follow in game.</p>
          <div class="home__actions">
            <a class="home__primary" :href="hrefOf('synchronization')" @click="open($event, 'synchronization')">Connect your account <span aria-hidden="true">→</span></a>
            <button class="home__secondary" type="button" @click="selectTab('guide')">How it works</button>
          </div>
          <p class="home__reassurance">Free to use · No separate sign-up · Read-only GW2 API</p>
        </div>
        <div class="home__starting-points">
          <h3>A few questions this tool can help with</h3>
          <dl>
            <div><dt>“What could I craft?”</dt><dd>Compare possible crafts and their estimated Trading Post returns.</dd></div>
            <div><dt>“Where did I put that?”</dt><dd>Check your bank, materials and character inventories.</dd></div>
            <div><dt>“What do I need next?”</dt><dd>Open a result to see its ingredients, purchases and crafting steps.</dd></div>
          </dl>
        </div>
      </div>

      <div class="home__audiences">
        <section><h3>Building up your account?</h3><p>Put stored materials to work while you’re saving for your next upgrade. The beginner tips share a few things that helped along the way.</p></section>
        <section><h3>Returning to the game?</h3><p>Get an overview of the characters, recipes and materials already on your account.</p></section>
        <section><h3>Enjoy crafting?</h3><p>Find uses for stored materials, compare their value and choose a crafting plan that suits your account.</p></section>
      </div>
    </div>

    <div v-show="selected === 'features'" id="home-panel-features" class="home__panel" role="tabpanel" aria-labelledby="home-tab-features" tabindex="0">
      <div class="home__section-intro"><h2>Choose the question you want to answer</h2><p>Each tool has its own page. Your account connects once through System Status.</p></div>
      <div class="home__tools">
        <article v-for="tool in tools" :key="tool.id" class="home__tool">
          <h3><a :href="hrefOf(tool.id)" @click="open($event, tool.id)">{{ destinationOf(tool.id).label }} <span aria-hidden="true">↗</span></a></h3>
          <p>{{ tool.description }}</p>
        </article>
      </div>
    </div>

    <div v-show="selected === 'guide'" id="home-panel-guide" class="home__panel" role="tabpanel" aria-labelledby="home-tab-guide" tabindex="0">
      <div class="home__section-intro"><h2>From your account to a crafting plan</h2><p>Your key connects the app to the materials, recipes and characters you already have.</p></div>
      <ol class="home__steps">
        <li><div><h3>Connect a Guild Wars 2 API key</h3><p>Open <a :href="hrefOf('synchronization')" @click="open($event, 'synchronization')">System Status</a>, paste your key and choose <strong>Connect</strong>. “How do I get an API key?” walks you through creating one at ArenaNet.</p><p class="home__hint">The key gives read-only access. The safety explanation covers permissions, storage and deletion.</p><GuideScreenshot name="connect-key" /></div></li>
        <li><div><h3>Let your account data load</h3><p>Open the tool you want to use. It reads the data it needs; the first visit can take longer while your game data is fetched. System Status shows refresh progress.</p><GuideScreenshot name="account-data" /></div></li>
        <li><div><h3>Choose what to include</h3><p>In <strong>Crafting Profit</strong>, choose a character or scope and review the material and buying settings. Save exclusions in <strong>Bank</strong> or <strong>Materials</strong> if you want to keep particular items.</p><GuideScreenshot name="crafting-settings" /></div></li>
        <li><div><h3>Open a result and follow its plan</h3><p>Compare the results, then inspect the ingredients, required purchases and crafting steps. Check the selected quantities and Trading Post price mode before acting in game.</p><GuideScreenshot name="crafting-plan" /></div></li>
      </ol>
      <div class="home__guide-footer"><p>Already have a key?</p><a class="home__primary" :href="hrefOf('synchronization')" @click="open($event, 'synchronization')">Open System Status <span aria-hidden="true">→</span></a></div>
    </div>

    <div v-show="selected === 'tips'" id="home-panel-tips" class="home__panel" role="tabpanel" aria-labelledby="home-tab-tips" tabindex="0">
      <BeginnerTips />
    </div>
  </section>
</template>

<style scoped>
.home { max-width: 1180px; margin-inline: auto; gap: var(--space-5); }
.home__tabs { display: flex; flex-wrap: wrap; gap: 2px; border-bottom: 1px solid var(--color-border); }
.home__tabs button { min-height: 44px; padding: var(--space-3) var(--space-5); border: 1px solid var(--color-border); border-bottom: 0; border-radius: var(--radius-control) var(--radius-control) 0 0; background: transparent; color: var(--color-muted); font-weight: 500; }
.home__tabs button[aria-selected="true"] { background: var(--color-raised); color: var(--color-text); font-weight: 700; box-shadow: inset 0 2px var(--color-highlight); }
.home__panel { min-width: 0; }
.home__welcome { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: linear-gradient(115deg, color-mix(in srgb, var(--color-highlight) 7%, var(--color-surface)), var(--color-surface) 65%); }
.home__introduction, .home__starting-points { padding: var(--space-6); display: flex; flex-direction: column; align-items: flex-start; gap: var(--space-5); }
.home__starting-points { border-left: 1px solid var(--color-border); justify-content: center; }
.home__eyebrow { color: var(--color-highlight); font-size: var(--text-sm); font-weight: 600; }
.home__introduction h2 { font-size: clamp(1.5rem, 2.5vw, 2rem); line-height: 1.25; text-wrap: balance; }
.home__introduction > p:not(.home__eyebrow) { line-height: 1.7; }
.home__starting-points h3 { font-size: var(--text-lg); }
.home__starting-points dl { margin: 0; display: grid; gap: var(--space-5); }
.home__starting-points dt { font-weight: 650; margin-bottom: var(--space-1); }
.home__starting-points dd { margin: 0; color: var(--color-muted); line-height: 1.6; }
.home__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-3); }
.home__primary { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-4); min-height: 44px; padding: var(--space-3) var(--space-4); border: 1px solid var(--color-primary); border-radius: var(--radius-control); background: var(--color-primary); color: var(--color-text); text-decoration: none; font-weight: 600; }
.home__primary:hover { filter: brightness(1.12); }
.home__secondary { min-height: 44px; padding: var(--space-2) 0; border: 0; background: transparent; color: var(--color-secondary); text-decoration: underline; text-underline-offset: 3px; }
.home__reassurance { color: var(--color-muted); font-size: var(--text-sm); }
.home__audiences { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: var(--space-6); }
.home__audiences section { padding-inline: var(--space-5); }
.home__audiences section:first-child { padding-left: 0; }
.home__audiences section:last-child { padding-right: 0; }
.home__audiences section + section { border-left: 1px solid var(--color-border); }
.home__audiences h3 { margin-bottom: var(--space-2); }
.home__audiences p, .home__section-intro p { color: var(--color-muted); line-height: 1.65; }
.home__section-intro { display: grid; gap: var(--space-2); margin-bottom: var(--space-5); }
.home__tools { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 var(--space-6); }
.home__tool { padding-block: var(--space-5); border-top: 1px solid var(--color-border); }
.home__tool h3 { margin-bottom: var(--space-3); }
.home__tool a { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); text-decoration: none; }
.home__tool a:hover { text-decoration: underline; }
.home__tool p { max-width: 58ch; color: var(--color-muted); line-height: 1.65; }
.home__steps { list-style: none; margin: 0; padding: 0; counter-reset: home-step; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-6); }
.home__steps > li { counter-increment: home-step; display: grid; grid-template-columns: 2.75rem minmax(0, 1fr); align-items: start; gap: var(--space-4); }
.home__steps > li::before { content: counter(home-step); display: grid; place-items: center; width: 2.75rem; height: 2.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-highlight); font-weight: 700; }
.home__steps h3 { margin-bottom: var(--space-2); }
.home__steps p { max-width: 78ch; line-height: 1.65; }
.home__hint { margin-top: var(--space-2); color: var(--color-muted); font-size: var(--text-sm); }
.home__guide-footer { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-4); margin-top: var(--space-6); padding-top: var(--space-5); border-top: 1px solid var(--color-border); }
@media (max-width: 48rem) {
  .home__welcome, .home__tools { grid-template-columns: minmax(0, 1fr); }
  .home__steps { grid-template-columns: minmax(0, 1fr); }
  .home__introduction, .home__starting-points { padding: var(--space-5); }
  .home__starting-points { border-left: 0; border-top: 1px solid var(--color-border); }
  .home__audiences { grid-template-columns: minmax(0, 1fr); gap: var(--space-4); }
  .home__audiences section { padding: 0; }
  .home__audiences section + section { border-left: 0; padding-top: var(--space-4); border-top: 1px solid var(--color-border); }
  .home__tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-1); border-bottom: 0; }
  .home__tabs button { padding-inline: var(--space-3); border-bottom: 1px solid var(--color-border); border-radius: var(--radius-control); }
  .home__desktop-break { display: none; }
}
</style>
