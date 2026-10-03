const STACK_SIZE = 250

/** Format a material quantity as full 250-item stacks plus any remainder. */
export function formatStackQuantity(quantity: number): string {
  const stacks = Math.floor(quantity / STACK_SIZE)
  const remainder = quantity % STACK_SIZE

  if (stacks === 0) return String(quantity)
  return remainder === 0
    ? `${stacks} × ${STACK_SIZE}`
    : `${stacks} × ${STACK_SIZE} + ${remainder}`
}
