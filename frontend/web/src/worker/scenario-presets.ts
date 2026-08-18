import type { UiLanguage } from '../uiLanguage.js'

export type ScenarioId = 'build_review_test' | 'research_factcheck' | 'docs_pipeline'

export interface ScenarioPreset {
  id: ScenarioId
  goalTemplate: Record<UiLanguage, string>
}

/**
 * Scenario team presets shown by the empty team pane.
 *
 * The backend owns member materialization; the UI only needs the stable id and
 * localized goal template that the user can edit before assembling the team.
 */
export const SCENARIO_PRESETS: readonly ScenarioPreset[] = [
  {
    id: 'build_review_test',
    goalTemplate: {
      en: 'Implement <feature>: have the Coder implement, Reviewer audit, and Tester verify; describe what to build, key constraints, and validation commands or acceptance criteria.',
      zh: '实现 X 功能：让 Coder 实现、Reviewer 审查、Tester 验证；写清楚要做什么、关键约束，以及验证命令或验收标准。',
    },
  },
  {
    id: 'research_factcheck',
    goalTemplate: {
      en: 'Research <topic>: have the Researcher gather evidence and the Factchecker verify it; list questions, trusted sources, and the decision this research should support.',
      zh: '调研 X 主题：让 Researcher 收集证据、Factchecker 复核；列出要回答的问题、可信来源要求，以及这次调研要支撑的决策。',
    },
  },
  {
    id: 'docs_pipeline',
    goalTemplate: {
      en: 'Write <doc>: have the Drafter write and the Doc Reviewer review; state the audience, scope, required sections, and code or files it should describe.',
      zh: '撰写 X 文档：让 Drafter 撰写、Doc Reviewer 审查；写明读者、范围、必备章节，以及它要描述的代码或文件。',
    },
  },
]
