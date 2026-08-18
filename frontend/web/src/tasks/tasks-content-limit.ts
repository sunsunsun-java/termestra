export const MAX_TASKS_TRANSPORT_CONTENT_BYTES = 900 * 1024

/**
 * Counts the UTF-8 bytes occupied by the content inside a JSON string. This
 * mirrors the backend contract: quotes, backslashes and control characters
 * expand during JSON escaping, while a surrogate pair remains four UTF-8
 * bytes. The surrounding pair of JSON quote bytes is envelope overhead and
 * is deliberately excluded.
 */
export const tasksTransportContentBytes = (content: string): number =>
  countTasksTransportContentBytes(content)

const countTasksTransportContentBytes = (content: string, stopAfter?: number): number => {
  let bytes = 0
  for (let index = 0; index < content.length; index += 1) {
    const code = content.charCodeAt(index)
    if (
      code === 0x22 ||
      code === 0x5c ||
      code === 0x08 ||
      code === 0x0c ||
      code === 0x0a ||
      code === 0x0d ||
      code === 0x09
    ) {
      bytes += 2
    } else if (code <= 0x1f) {
      bytes += 6
    } else if (
      code >= 0xd800 &&
      code <= 0xdbff &&
      index + 1 < content.length &&
      content.charCodeAt(index + 1) >= 0xdc00 &&
      content.charCodeAt(index + 1) <= 0xdfff
    ) {
      bytes += 4
      index += 1
    } else if (code >= 0xd800 && code <= 0xdfff) {
      bytes += 6
    } else if (code <= 0x7f) {
      bytes += 1
    } else if (code <= 0x7ff) {
      bytes += 2
    } else {
      bytes += 3
    }
    if (stopAfter !== undefined && bytes > stopAfter) return bytes
  }
  return bytes
}

export const tasksContentFitsTransport = (content: string): boolean =>
  countTasksTransportContentBytes(content, MAX_TASKS_TRANSPORT_CONTENT_BYTES) <=
  MAX_TASKS_TRANSPORT_CONTENT_BYTES
