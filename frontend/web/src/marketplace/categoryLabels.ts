import type { UiLanguage } from '../uiLanguage.js'

type LocalizedLabel = Readonly<Record<UiLanguage, string>>

const CATEGORY_CATALOG = [
  ['academic', 'Academic', '学术'],
  ['design', 'Design', '设计'],
  ['engineering', 'Engineering', '工程'],
  ['finance', 'Finance', '金融'],
  ['game-development', 'Game Development', '游戏开发'],
  ['hr', 'HR', '人力资源'],
  ['integrations', 'System Integrations', '系统集成'],
  ['legal', 'Legal', '法务'],
  ['marketing', 'Marketing', '营销'],
  ['misc', 'Misc', '其他'],
  ['paid-media', 'Paid Ads', '广告投放'],
  ['product', 'Product', '产品'],
  ['project-management', 'Project Management', '项目管理'],
  ['sales', 'Sales', '销售'],
  ['spatial-computing', 'Spatial Computing', '空间计算'],
  ['specialized', 'Industry Verticals', '行业角色'],
  ['supply-chain', 'Supply Chain', '供应链'],
  ['support', 'Support', '客户支持'],
  ['testing', 'Testing', '测试'],
] as const

const labelsByCategory = new Map<string, LocalizedLabel>(
  CATEGORY_CATALOG.map(([category, en, zh]) => [category, { en, zh }])
)

const ZH_DOMAIN_ORDER = [
  'engineering',
  'testing',
  'product',
  'design',
  'project-management',
  'integrations',
  'specialized',
  'marketing',
  'paid-media',
  'sales',
  'finance',
  'legal',
  'hr',
  'supply-chain',
  'support',
  'academic',
  'game-development',
  'spatial-computing',
  'misc',
] as const

const zhRank = new Map<string, number>(
  ZH_DOMAIN_ORDER.map((category, position) => [category, position])
)

const fallbackLabel = (category: string): string =>
  category
    .split('-')
    .filter(Boolean)
    .map((word) => `${word.charAt(0).toUpperCase()}${word.slice(1)}`)
    .join(' ')

export const localizeMarketplaceCategory = (category: string, language: UiLanguage): string =>
  labelsByCategory.get(category)?.[language] ?? fallbackLabel(category)

const rankForChinese = (category: string): number =>
  zhRank.get(category) ?? Number.POSITIVE_INFINITY

/**
 * The English manifest already arrives in a readable order. Chinese uses a
 * stable domain-first order so the software roles stay together at the top.
 */
export const sortCategoriesForDisplay = (
  categories: readonly string[],
  language: UiLanguage
): readonly string[] => {
  if (language === 'en') return categories

  return [...categories].sort((left, right) => {
    const rankDifference = rankForChinese(left) - rankForChinese(right)
    return rankDifference || left.localeCompare(right)
  })
}
