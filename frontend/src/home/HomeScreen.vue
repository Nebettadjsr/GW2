<script setup lang="ts">
import { nextTick, ref } from 'vue'
import PageHeader from '../shell/PageHeader.vue'
import { destinationOf, type DestinationId } from '../shell/destinations'
import BeginnerTips from './BeginnerTips.vue'
import goldCrafting from './images/gold-crafting.webp?no-inline'
import goldCraftingSmall from './images/gold-crafting-640.webp?no-inline'
import goldCraftingMedium from './images/gold-crafting-960.webp?no-inline'

const emit = defineEmits<{ navigate: [id: DestinationId] }>()
const TABS = [
  { id: 'welcome', label: 'About this tool' },
  { id: 'features', label: 'What you can do' },
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
    <header class="home__hero">
      <img class="home__hero-art" :src="goldCrafting"
        :srcset="`${goldCraftingSmall} 640w, ${goldCraftingMedium} 960w, ${goldCrafting} 1774w`"
        sizes="(min-width: 1440px) 1408px, calc(100vw - 32px)"
        width="1774" height="887" alt="" fetchpriority="high" />
      <div class="home__hero-heading">
        <PageHeader heading="Nebet’s GW2 Crafting Tool" />
        <ul class="home__hero-promises">
          <li>There’s gold hiding in your bank.</li>
          <li>Turn leftover materials into profit.</li>
          <li>Ever wondered if crafting THAT item is worth it?</li>
        </ul>
      </div>
    </header>

    <div class="home__tabs" role="tablist" aria-label="About GW2 Crafting Tool" @keydown="onTabKey">
      <button v-for="tab in TABS" :id="`home-tab-${tab.id}`" :key="tab.id"
        :ref="element => setTabButton(tab.id, element)" type="button" role="tab"
        :aria-selected="selected === tab.id" :aria-controls="`home-panel-${tab.id}`"
        :tabindex="selected === tab.id ? 0 : -1" @click="selected = tab.id">{{ tab.label }}</button>
    </div>

    <div v-show="selected === 'welcome'" id="home-panel-welcome" class="home__panel" role="tabpanel" aria-labelledby="home-tab-welcome" tabindex="0">
      <article class="home__letter" aria-labelledby="home-letter-title">
        <div class="home__letter-text">
          <header class="home__letter-heading">
            <p class="home__letter-eyebrow">A note from Nebet</p>
            <h2 id="home-letter-title">How it all started</h2>
          </header>
          <p>Upcoming Gem Store sale. You want to exchange your gold for gems, but there’s never enough gold.</p>
          <p>Not enough for that full ascended armor, that next legendary, that next item. Almost all GW2 players always need gold.</p>
          <p>And once your account has been pumped with enough gems, you can make gold fast. But that’s not what this page is about.</p>
          <p>I remember how hard it was before you had all the mounts. Before you could just go and buy that entire ascended armor. When 20g was a lot of coin in the game.</p>
          <p>Eventually, my bank overflowed with one material I had no idea what to do with. Some wood or fabric. And then I wondered:</p>
          <blockquote>Should I sell the material, or can I craft something that sells for more gold than the materials I have to put in?</blockquote>
          <p class="home__letter-signature">Nebet</p>
        </div>
      </article>
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

    <div v-show="selected === 'tips'" id="home-panel-tips" class="home__panel" role="tabpanel" aria-labelledby="home-tab-tips" tabindex="0">
      <BeginnerTips />
    </div>
  </section>
</template>

<style scoped>
@font-face {
  font-family: 'Cinzel';
  font-style: normal;
  font-weight: 600;
  font-display: swap;
  src: url('./fonts/cinzel-semibold.ttf') format('truetype');
}
@font-face {
  font-family: 'Handlee';
  font-style: normal;
  font-weight: 400;
  font-display: swap;
  src: url('./fonts/handlee-regular.woff2') format('woff2');
}
.home { width: 100%; gap: var(--space-5); }
.home__hero { position: relative; isolation: isolate; display: flex; align-items: center; min-height: 19rem; overflow: hidden; padding: clamp(1.5rem, 4vw, 3.5rem); border: 1px solid color-mix(in srgb, var(--color-highlight) 45%, var(--color-border)); border-radius: var(--radius-panel); background: var(--color-bg); }
.home__hero-art { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: center 55%; }
.home__hero::before { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(90deg, rgb(0 0 0 / 88%), rgb(0 0 0 / 70%) 38%, rgb(0 0 0 / 20%) 75%, rgb(0 0 0 / 8%)); }
.home__hero-heading { display: grid; gap: var(--space-4); width: 100%; text-shadow: 0 2px 10px rgb(0 0 0 / 80%); }
.home__hero-heading :deep(.page-head__text) { max-width: 54rem; gap: var(--space-4); }
.home__hero-heading :deep(h1) { color: var(--color-text); font-family: 'Cinzel', 'Palatino Linotype', 'Book Antiqua', Georgia, serif; font-size: clamp(2rem, 3.4vw, 3.25rem); font-weight: 600; line-height: 1.2; letter-spacing: 0.015em; text-wrap: balance; }
.home__hero-promises { display: grid; gap: var(--space-1); max-width: 54ch; margin: 0; padding-left: 1.25em; color: var(--color-text); font-size: var(--text-lg); line-height: 1.65; }
.home__hero-promises li::marker { color: var(--color-highlight); }
.home__tabs, .home__panel { width: 100%; max-width: 1180px; margin-inline: auto; }
.home__tabs { display: flex; flex-wrap: wrap; gap: 2px; border-bottom: 1px solid var(--color-border); }
.home__tabs button { min-height: 44px; padding: var(--space-3) var(--space-5); border: 1px solid var(--color-border); border-bottom: 0; border-radius: var(--radius-control) var(--radius-control) 0 0; background: transparent; color: var(--color-muted); font-weight: 500; }
.home__tabs button[aria-selected="true"] { background: var(--color-raised); color: var(--color-text); font-weight: 700; box-shadow: inset 0 2px var(--color-highlight); }
.home__panel { min-width: 0; }
.home__letter { max-width: 64rem; margin-inline: auto; padding: clamp(1.5rem, 3vw, 2.5rem); border: 1px solid var(--color-border); border-radius: var(--radius-panel); background: linear-gradient(120deg, color-mix(in srgb, var(--color-highlight) 4%, var(--color-surface)), var(--color-surface) 65%); color: var(--color-text); }
.home__letter-text { display: grid; gap: var(--space-4); max-width: 64ch; margin-inline: auto; font-family: 'Handlee', 'Segoe Print', 'Bradley Hand', cursive; font-size: clamp(1.125rem, 1.6vw, 1.375rem); font-weight: 400; line-height: 1.7; }
.home__letter-heading { display: grid; gap: var(--space-2); margin-bottom: var(--space-2); }
.home__letter-eyebrow { color: var(--color-muted); font-family: var(--font-body); font-size: var(--text-sm); letter-spacing: 0.08em; text-transform: uppercase; }
.home__letter-heading h2 { color: var(--color-highlight); font-family: 'Cinzel', Georgia, serif; font-size: var(--text-xl); font-weight: 600; }
.home__letter blockquote { margin: var(--space-2) 0; padding: var(--space-2) var(--space-5); border-left: 2px solid var(--color-highlight); color: var(--color-highlight); font-size: 1.05em; line-height: 1.6; }
.home__letter-signature { margin-top: var(--space-2); color: var(--color-highlight); font-size: 1.5em; }
.home__section-intro p { color: var(--color-muted); line-height: 1.65; }
.home__section-intro { display: grid; gap: var(--space-2); margin-bottom: var(--space-5); }
.home__tools { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 var(--space-6); }
.home__tool { padding-block: var(--space-5); border-top: 1px solid var(--color-border); }
.home__tool h3 { margin-bottom: var(--space-3); }
.home__tool a { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); text-decoration: none; }
.home__tool a:hover { text-decoration: underline; }
.home__tool p { max-width: 58ch; color: var(--color-muted); line-height: 1.65; }
@media (max-width: 48rem) {
  .home__hero { min-height: 17rem; }
  .home__hero::before { background: linear-gradient(90deg, rgb(0 0 0 / 90%), rgb(0 0 0 / 65%)); }
  .home__hero-promises { font-size: var(--text-base); }
  .home__tools { grid-template-columns: minmax(0, 1fr); }
  .home__tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--space-1); border-bottom: 0; }
  .home__tabs button { padding-inline: var(--space-3); border-bottom: 1px solid var(--color-border); border-radius: var(--radius-control); }
}
</style>
