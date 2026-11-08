import { shellProvide } from '@/sync/__tests__/shellProvide'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import BankScreen from '../BankScreen.vue'
import MaterialsScreen from '../MaterialsScreen.vue'
import { FakeAccountApi } from './accountFixtures'

describe.each([
  ['Bank', BankScreen, 'bank-reload'],
  ['Materials', MaterialsScreen, 'materials-reload']
] as const)('%s shared page header contract', (heading, Screen, reloadHook) => {
  it('uses the shared header, keeps navigation focus semantics, and has no manual reload action', async () => {
    const wrapper = mount(Screen, { props: { api: new FakeAccountApi() }, global: { stubs: { InventoryRefresh: true }, provide: shellProvide() } })
    await flushPromises()
    const pageHeader = wrapper.find('.page-head')
    const pageHeading = pageHeader.find('[data-test="page-heading"]')
    expect(pageHeader.exists()).toBe(true)
    expect(pageHeading.text()).toBe(heading)
    expect(pageHeading.attributes('tabindex')).toBe('-1')
    expect(pageHeading.attributes('data-page-heading')).toBeDefined()
    // Refreshing is automatic; the approved header carries no manual reload button.
    expect(wrapper.find(`[data-test="${reloadHook}"]`).exists()).toBe(false)
  })
})
