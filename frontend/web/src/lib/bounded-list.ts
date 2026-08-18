export const requireBoundedList = <T>(
  payload: unknown,
  label: string,
  maxItems: number
): T[] => {
  if (!Array.isArray(payload)) throw new TypeError(`${label} response must be an array`)
  if (payload.length > maxItems) {
    throw new RangeError(`${label} response exceeded the safe limit of ${maxItems} items`)
  }
  return payload as T[]
}
