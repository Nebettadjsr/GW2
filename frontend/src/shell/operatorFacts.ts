/**
 * Operator and deployment facts for the public information pages, supplied by build configuration
 * (`VITE_OPERATOR_*`). Each is an independent fact: operator country, hosting region, public domain
 * and contact email are never inferred from one another. Nothing is invented — an unset value stays
 * `null`, and the pages then say plainly that the preview is incomplete and not deployable.
 * Completed values do not by themselves establish approval of the legal/privacy content.
 */
export interface OperatorFacts {
  readonly operatorName: string | null
  readonly operatorAddress: string | null
  readonly operatorCountry: string | null
  readonly hostingRegion: string | null
  readonly publicDomain: string | null
  readonly contactEmail: string | null
}

export const OPERATOR_FIELDS: ReadonlyArray<{ key: keyof OperatorFacts; label: string }> = [
  { key: 'operatorName', label: 'Operator name' },
  { key: 'operatorAddress', label: 'Postal address' },
  { key: 'operatorCountry', label: 'Operator country' },
  { key: 'hostingRegion', label: 'Hosting region' },
  { key: 'publicDomain', label: 'Website' },
  { key: 'contactEmail', label: 'Email' }
]

function configured(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

export function readOperatorFacts(env: Record<string, unknown> = import.meta.env): OperatorFacts {
  return {
    operatorName: configured(env.VITE_OPERATOR_NAME),
    operatorAddress: configured(env.VITE_OPERATOR_ADDRESS),
    operatorCountry: configured(env.VITE_OPERATOR_COUNTRY),
    hostingRegion: configured(env.VITE_HOSTING_REGION),
    publicDomain: configured(env.VITE_PUBLIC_DOMAIN),
    contactEmail: configured(env.VITE_CONTACT_EMAIL)
  }
}

/** The labels of every fact that is still unset. Any entry makes the pages an incomplete preview. */
export function missingOperatorFacts(facts: OperatorFacts): string[] {
  return OPERATOR_FIELDS.filter(({ key }) => facts[key] === null).map(({ label }) => label)
}
