import type { WorkerRole } from '../../../src/shared/types.js'
import type { UiLanguage } from '../uiLanguage.js'

/**
 * Short, CLI-safe call signs give a Termestra team a recognisable visual
 * language without borrowing real-person name lists. English suggestions are
 * lowercase single tokens so `team send <name>` never needs shell quoting.
 */
const ENGLISH_CALL_SIGNS = {
  coder: [
    'lattice',
    'forge',
    'vector',
    'radix',
    'mosaic',
    'relay',
    'kernel',
    'prism',
    'syntax',
    'lumen',
    'ember',
    'weaver',
  ],
  reviewer: [
    'compass',
    'mirror',
    'verity',
    'sentinel',
    'balance',
    'ledger',
    'signal',
    'clarity',
    'margin',
    'sieve',
    'anchor',
    'vantage',
  ],
  tester: [
    'probe',
    'beacon',
    'scout',
    'pulse',
    'tracer',
    'horizon',
    'radar',
    'echo',
    'quartz',
    'delta',
    'spark',
    'rover',
  ],
} as const satisfies Record<Exclude<WorkerRole, 'custom'>, readonly string[]>

const CHINESE_CALL_SIGNS = {
  coder: ['星织', '铸光', '经纬', '榫卯', '云砚', '流萤', '墨线', '构木', '灵枢', '弦图', '青简', '微澜'],
  reviewer: ['明镜', '衡尺', '守望', '观澜', '清议', '砥柱', '远鉴', '持衡', '正则', '辨微', '照影', '定盘'],
  tester: ['探针', '寻迹', '远望', '脉冲', '北斗', '回声', '巡星', '流火', '试锋', '逐浪', '飞梭', '刻度'],
} as const satisfies Record<Exclude<WorkerRole, 'custom'>, readonly string[]>

const CUSTOM_ENGLISH = [
  ...ENGLISH_CALL_SIGNS.coder,
  ...ENGLISH_CALL_SIGNS.reviewer,
  ...ENGLISH_CALL_SIGNS.tester,
  'aurora',
  'canvas',
  'harbor',
  'orbit',
] as const

const CUSTOM_CHINESE = [
  ...CHINESE_CALL_SIGNS.coder,
  ...CHINESE_CALL_SIGNS.reviewer,
  ...CHINESE_CALL_SIGNS.tester,
  '天工',
  '长风',
  '云帆',
  '星河',
] as const

const CALL_SIGNS: Record<UiLanguage, Record<WorkerRole, readonly string[]>> = {
  en: { ...ENGLISH_CALL_SIGNS, custom: CUSTOM_ENGLISH },
  zh: { ...CHINESE_CALL_SIGNS, custom: CUSTOM_CHINESE },
}

const randomUint32 = (): number => {
  const buffer = new Uint32Array(1)
  globalThis.crypto.getRandomValues(buffer)
  return buffer[0] ?? 0
}

interface GenerateWorkerNameOptions {
  language?: UiLanguage
  role?: WorkerRole
  usedNames?: ReadonlySet<string>
  nextUint32?: () => number
}

const selectableNames = (pool: readonly string[], usedNames?: ReadonlySet<string>) => {
  if (!usedNames?.size) return pool
  const unused = pool.filter((name) => !usedNames.has(name))
  return unused.length > 0 ? unused : pool
}

export const generateWorkerName = ({
  language = 'en',
  role = 'coder',
  usedNames,
  nextUint32 = randomUint32,
}: GenerateWorkerNameOptions = {}): string => {
  const candidates = selectableNames(CALL_SIGNS[language][role], usedNames)
  return candidates[nextUint32() % candidates.length] as string
}
