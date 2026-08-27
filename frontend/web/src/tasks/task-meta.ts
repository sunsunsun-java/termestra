type PillTone = 'green' | 'orange' | 'red' | 'neutral'

export type TaskMetaItem =
  | { kind: 'owner'; value: string }
  | { kind: 'status'; value: string; tone: PillTone }
  | { kind: 'path'; label: string; value: string }
  | { kind: 'note'; value: string }

const OWNER_COLORS = [
  '#a78bfa',
  '#60a5fa',
  '#5eead4',
  '#86efac',
  '#fde047',
  '#fb923c',
  '#f9a8d4',
  '#fca5a5',
] as const

const OWNER_KEYS = new Set(['owner', 'assignee', '负责', '负责人', '执行人'])
const STATUS_KEYS = new Set(['status', '状态'])
const PATH_KEYS = new Set(['报告', 'report', '文件', 'file', 'path', '日志', 'log', '产物'])

const STATUS_TONE_BY_VALUE: Readonly<Record<string, PillTone>> = {
  blocked: 'red',
  completed: 'green',
  complete: 'green',
  dispatched: 'orange',
  dispatching: 'orange',
  done: 'green',
  error: 'red',
  errored: 'red',
  failed: 'red',
  finished: 'green',
  finish: 'green',
  idle: 'neutral',
  inprogress: 'orange',
  ok: 'green',
  open: 'neutral',
  pending: 'neutral',
  queued: 'neutral',
  running: 'orange',
  success: 'green',
  todo: 'neutral',
  waiting: 'neutral',
  working: 'orange',
  出错: 'red',
  处理中: 'orange',
  完成: 'green',
  已完成: 'green',
  待办: 'neutral',
  执行中: 'orange',
  搞定: 'green',
  未开始: 'neutral',
  派单: 'orange',
  派单中: 'orange',
  等待: 'neutral',
  等待中: 'neutral',
  进行中: 'orange',
  队列中: 'neutral',
  阻塞: 'red',
  失败: 'red',
}

const isEscaped = (text: string, index: number): boolean => {
  let backslashes = 0
  for (let cursor = index - 1; cursor >= 0 && text[cursor] === '\\'; cursor -= 1) {
    backslashes += 1
  }
  return backslashes % 2 === 1
}

const trailingMetadataGroup = (text: string): { body: string; title: string } | null => {
  const trimmed = text.trimEnd()
  if (!trimmed.endsWith(')')) return null

  let depth = 0
  let quote: '"' | null = null
  for (let cursor = trimmed.length - 1; cursor >= 0; cursor -= 1) {
    const character = trimmed[cursor]
    if (character === '"' && !isEscaped(trimmed, cursor)) {
      quote = quote === '"' ? null : '"'
      continue
    }
    if (quote !== null) continue
    if (character === ')') depth += 1
    if (character !== '(') continue
    depth -= 1
    if (depth === 0) {
      return {
        body: trimmed.slice(cursor + 1, -1),
        title: trimmed.slice(0, cursor).trim(),
      }
    }
  }
  return null
}

const splitMetadata = (body: string): string[] => {
  const parts: string[] = []
  let current = ''
  let quote: '"' | null = null
  let nestedDepth = 0

  const finishPart = () => {
    const value = current.trim()
    if (value) parts.push(value)
    current = ''
  }

  for (let index = 0; index < body.length; index += 1) {
    const character = body[index] ?? ''
    if (character === '"' && !isEscaped(body, index)) {
      quote = quote === '"' ? null : '"'
      continue
    }
    if (quote === null) {
      if (character === '(') nestedDepth += 1
      if (character === ')' && nestedDepth > 0) nestedDepth -= 1
      if (nestedDepth === 0 && /[·,，;；]/.test(character)) {
        finishPart()
        continue
      }
    }
    current += character
  }
  finishPart()
  return parts
}

const splitKeyValue = (part: string): { key: string; value: string } | null => {
  const separator = part.search(/[:：]/)
  if (separator < 0) return null
  const key = part.slice(0, separator).trim()
  const value = part.slice(separator + 1).trim()
  return key && value ? { key, value } : null
}

const statusTone = (value: string): PillTone => {
  const normalized = value.toLowerCase().replace(/[-_\s]/g, '')
  return STATUS_TONE_BY_VALUE[normalized] ?? 'neutral'
}

const looksLikeFileLocation = (value: string): boolean =>
  /[/\\]/.test(value) || /^[A-Za-z0-9._-]+\.[A-Za-z0-9]+$/.test(value)

const classifyMetadata = (part: string): TaskMetaItem => {
  const pair = splitKeyValue(part)
  if (!pair) return { kind: 'note', value: part }

  const normalizedKey = pair.key.toLowerCase()
  if (OWNER_KEYS.has(normalizedKey)) return { kind: 'owner', value: pair.value }
  if (STATUS_KEYS.has(normalizedKey)) {
    return { kind: 'status', tone: statusTone(pair.value), value: pair.value }
  }
  if (PATH_KEYS.has(normalizedKey) || looksLikeFileLocation(pair.value)) {
    return { kind: 'path', label: pair.key, value: pair.value }
  }
  return { kind: 'note', value: `${pair.key}: ${pair.value}` }
}

export const ownerToneFromName = (name: string): string => {
  let hash = 0
  for (let index = 0; index < name.length; index += 1) {
    hash = (hash * 31 + name.charCodeAt(index)) >>> 0
  }
  return OWNER_COLORS[hash % OWNER_COLORS.length] ?? OWNER_COLORS[0]
}

export const parseTaskMetadata = (text: string): { title: string; meta: TaskMetaItem[] } => {
  const group = trailingMetadataGroup(text)
  if (!group || !group.body.trim()) return { title: text, meta: [] }
  return { title: group.title, meta: splitMetadata(group.body).map(classifyMetadata) }
}
