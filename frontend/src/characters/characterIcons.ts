/** Bundled local icons (see icons/ATTRIBUTION.md); unknown names return null so text stays readable. */
const modules = import.meta.glob('./icons/*.png', { eager: true, query: '?url', import: 'default' }) as Record<string, string>
export function characterIcon(name: string): string | null {
  return modules[`./icons/${name.trim().toLowerCase()}.png`] ?? null
}
