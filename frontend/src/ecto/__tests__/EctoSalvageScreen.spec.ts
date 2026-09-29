import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EctoSalvageScreen from '../EctoSalvageScreen.vue'
import {
  accountLuck,
  cappedAccountLuck,
  DUST_ID,
  ECTO_ID,
  itemMetadata,
  itemPrices,
  METADATA_IDS
} from './currentEctoFixtures'

interface RecordedRequest { method: string; url: string }
const requests: RecordedRequest[] = []
let luckAnswer: unknown = accountLuck
let wrapper: VueWrapper | null = null

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' }
  })
}

async function openScreen(): Promise<VueWrapper> {
  wrapper = mount(EctoSalvageScreen)
  await flushPromises()
  return wrapper
}

function buttonIn(open: VueWrapper, selector: string, label: string) {
  const button = open.findAll(selector).find((candidate) => candidate.text().includes(label))
  if (!button) throw new Error(`Missing ${label} button in ${selector}`)
  return button
}

/** One labelled figure of the salvage calculation, addressed by the label it is filed under. */
function calculationFigure(open: VueWrapper, label: string) {
  const row = open
    .findAll('.salvage-calculation .calculation-row, .salvage-calculation .calculation-total')
    .find((candidate) => candidate.find('span').text() === label)
  if (!row) throw new Error(`Missing "${label}" row in the salvage calculation`)
  return row
}

function firstTargetEctos(open: VueWrapper): string {
  return open.findAll('.target-row')[0]?.element.children[2]?.textContent?.trim() ?? ''
}

beforeEach(() => {
  requests.length = 0
  luckAnswer = accountLuck
  vi.stubGlobal('fetch', vi.fn((input: string, init?: RequestInit) => {
    requests.push({ method: init?.method ?? 'GET', url: input })
    const url = new URL(input, 'http://test.local')
    if (url.pathname === '/api/items/metadata') return Promise.resolve(jsonResponse(itemMetadata))
    if (url.pathname === '/api/items/prices') return Promise.resolve(jsonResponse(itemPrices))
    if (url.pathname === '/api/account/luck') return Promise.resolve(jsonResponse(luckAnswer))
    throw new Error(`Obsolete or unexpected request: ${input}`)
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

describe('EctoSalvageScreen', () => {
  it('loads metadata for every displayed item, only two TP prices, and current account Luck', async () => {
    await openScreen()

    expect(requests).toHaveLength(3)
    expect(requests.every((request) => request.method === 'GET')).toBe(true)
    const metadataRequest = requests.find(({ url }) => url.startsWith('/api/items/metadata?'))
    const priceRequest = requests.find(({ url }) => url.startsWith('/api/items/prices?'))
    expect(metadataRequest).toBeDefined()
    expect(priceRequest).toBeDefined()
    expect(requests.map(({ url }) => url)).toContain('/api/account/luck')
    expect(new URL(metadataRequest!.url, 'http://test.local').searchParams.get('ids')?.split(',').map(Number))
      .toEqual(METADATA_IDS)
    expect(new URL(priceRequest!.url, 'http://test.local').searchParams.get('ids')?.split(',').map(Number))
      .toEqual([ECTO_ID, DUST_ID])
    expect(requests.some(({ url }) => url.includes('/api/ecto/salvage'))).toBe(false)
  })

  it('passes all seven backend icon URLs to the shared ItemIcon component', async () => {
    const open = await openScreen()
    const icons = open.findAll('[data-test="item-icon"]')

    expect(icons).toHaveLength(METADATA_IDS.length)
    expect(icons.every((icon) => icon.attributes('data-icon-state') === 'image')).toBe(true)
    expect(icons.map((icon) => icon.find('img').attributes('src'))).toEqual([
      44602, 23041, 89409, 67027, 19986, ECTO_ID, DUST_ID
    ].map((id) => `/api/items/${id}/icon/source-${id}.png`))
    expect(open.html()).not.toContain('render.guildwars2.com')
  })

  it('starts with 100 Ectos, Master/Silver-Fed, instant buy, and instant sell', async () => {
    const open = await openScreen()

    expect((open.find('#ecto-count').element as HTMLInputElement).value).toBe('100')
    expect(open.find('.salvage-row.selected').text()).toContain("Master's / Mystic / Silver-Fed")
    expect(open.find('.tool-choice__button--selected').text()).toBe('Silver-Fed')
    expect(open.findAll('.tp-block')[0]?.find('.tp-option.selected').text()).toContain('Instant buy')
    expect(open.findAll('.tp-block')[1]?.find('.tp-option.selected').text()).toContain('Instant sell')
    expect(open.find('.result-details').text()).toContain(`${(10_457).toLocaleString()} Luck`)
    expect(open.find('.result-details').text()).toContain('185 Dust')
  })

  it('recalculates quantity and method locally from the Wiki yield assumptions', async () => {
    const open = await openScreen()
    await open.find('#ecto-count').setValue('10')

    expect(open.find('.result-details').text()).toContain(`${(1_046).toLocaleString()} Luck`)
    expect(open.find('.result-details').text()).toContain('19 Dust')
    expect(open.find('.result-details').text()).toContain('6s 0c') // 10 Silver-Fed uses
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-13s 45c')

    await buttonIn(open, '.salvage-row', 'Basic / Copper-Fed').trigger('click')
    expect(open.find('.tool-choice__button--selected').text()).toBe('Copper-Fed')
    expect(open.find('.result-details').text()).toContain(`${(1_032).toLocaleString()} Luck`)
    expect(open.find('.result-details').text()).toContain('16 Dust')
    expect(open.find('.result-details').text()).toContain('30c')
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-15s 40c')
    expect(requests).toHaveLength(3)
  })

  it('charges the selected exact tool in coin rather than treating a method as one cost', async () => {
    const open = await openScreen()
    expect(open.find('.result-details').text()).toContain('60s 0c') // Silver-Fed, 100 uses
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 34s 50c')

    await buttonIn(open, '.tool-choice__button', 'Mystic').trigger('click')
    expect(open.find('.result-details').text()).toContain('10s 50c') // rounded 100 x 10.496c
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 84s 0c')
    expect(open.find('.tool-cost-note').text()).toContain('Mystic Forge Stone')
    expect(requests).toHaveLength(3)
  })

  it('keeps Black Lion Gem cost separate from the effective coin cost', async () => {
    const open = await openScreen()
    await buttonIn(open, '.salvage-row', 'Black Lion').trigger('click')

    expect(open.find('.result-details').text()).toContain(`${(1_200).toLocaleString()} Gems`)
    // A gem-priced tool renames the coin total and files the gems in their own row.
    expect(calculationFigure(open, 'Effective coin result').text()).toContain('-2g 26s 80c')
    expect(calculationFigure(open, 'Effective coin result').text()).not.toContain('Gems')
    expect(calculationFigure(open, 'Additional cost').text()).toContain(`${(1_200).toLocaleString()} Gems`)
    expect(open.find('.tool-cost-note').text()).toContain('not converted to gold')
    expect(open.find('.target-row .target-cost small').text()).toContain('Gems')
    expect(requests).toHaveLength(3)
  })

  it('applies the 15% Dust sale fee and recalculates both TP modes locally', async () => {
    const open = await openScreen()
    expect(open.find('.result-story').text()).toContain('3g 14s 50c') // 185 x 200 x 0.85
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 34s 50c')

    await buttonIn(open, '.tp-block:nth-child(2) .tp-option', 'Listing sell').trigger('click')
    expect(open.find('.result-story').text()).toContain('3g 77s 40c') // 185 x 240 x 0.85
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-1g 97s 40c')

    await buttonIn(open, '.tp-block:first-child .tp-option', 'Buy order').trigger('click')
    expect(calculationFigure(open, 'Effective cost').text()).toContain('-2g 17s 40c')
    expect(requests).toHaveLength(3)
  })

  it('prices Luck targets using the selected yield, exact tool, and TP modes', async () => {
    const open = await openScreen()
    expect(firstTargetEctos(open)).toBe('4') // ceil(416 / 104.57)
    expect(open.find('.target-row .target-cost strong').text()).toBe('-5s 38c')

    await buttonIn(open, '.salvage-row', 'Basic / Copper-Fed').trigger('click')
    expect(firstTargetEctos(open)).toBe('5') // ceil(416 / 103.17)
    expect(open.find('.target-row .target-cost strong').text()).toBe('-7s 70c')
    await buttonIn(open, '.tool-choice__button', 'Basic').trigger('click')
    expect(open.find('.target-row .target-cost strong').text()).toBe('-7s 67c')
    await buttonIn(open, '.tp-block:first-child .tp-option', 'Buy order').trigger('click')
    expect(open.find('.target-row .target-cost strong').text()).toBe('-8s 67c')
    expect(requests).toHaveLength(3)
  })

  it('uses the current and next cumulative Luck thresholds for level progress', async () => {
    const open = await openScreen()
    const bar = open.find('.luck-progress__bar')
    const expectedPercent = ((14_134 - 13_790) / (14_550 - 13_790)) * 100
    expect(parseFloat((bar.element as HTMLElement).style.width)).toBeCloseTo(expectedPercent, 8)
  })

  it('renders a full progress bar at the 300% Luck-derived Magic Find cap', async () => {
    luckAnswer = cappedAccountLuck
    const open = await openScreen()

    expect(open.find('.account-summary').text()).toContain('300%')
    expect(open.find('.account-summary').text()).toContain('Maximum')
    expect((open.find('.luck-progress__bar').element as HTMLElement).style.width).toBe('100%')
  })
})
