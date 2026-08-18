import * as Dialog from '@radix-ui/react-dialog'
import { BookOpenCheck, FileText, Hammer, Sparkles } from 'lucide-react'
import { type ReactNode, useEffect, useRef, useState } from 'react'

import { applyTeamScenario } from '../api.js'
import { type TranslationKey, useI18n } from '../i18n.js'
import { useToast } from '../ui/useToast.js'
import { SCENARIO_PRESETS, type ScenarioId, type ScenarioPreset } from './scenario-presets.js'

interface ScenarioPresentation {
  descriptionKey: TranslationKey
  icon: ReactNode
  titleKey: TranslationKey
}

const PRESENTATION: Record<ScenarioId, ScenarioPresentation> = {
  build_review_test: {
    descriptionKey: 'scenario.build_review_test.desc',
    icon: <Hammer size={16} aria-hidden />,
    titleKey: 'scenario.build_review_test.title',
  },
  research_factcheck: {
    descriptionKey: 'scenario.research_factcheck.desc',
    icon: <BookOpenCheck size={16} aria-hidden />,
    titleKey: 'scenario.research_factcheck.title',
  },
  docs_pipeline: {
    descriptionKey: 'scenario.docs_pipeline.desc',
    icon: <FileText size={16} aria-hidden />,
    titleKey: 'scenario.docs_pipeline.title',
  },
}

export const ScenarioTeamCards = ({ workspaceId }: { workspaceId: string }) => {
  const { language, t } = useI18n()
  const toast = useToast()
  const [selected, setSelected] = useState<ScenarioPreset | null>(null)
  const [goal, setGoal] = useState('')
  const [applying, setApplying] = useState(false)
  const applyingRef = useRef(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const openScenario = (scenario: ScenarioPreset) => {
    setGoal(scenario.goalTemplate[language])
    setSelected(scenario)
  }

  const closeDialog = () => {
    if (!applying) setSelected(null)
  }

  const applyScenario = async () => {
    if (!selected || applyingRef.current || !goal.trim()) return

    applyingRef.current = true
    setApplying(true)
    try {
      await applyTeamScenario(workspaceId, selected.id, goal, language)
      if (!mountedRef.current) return
      toast.show({ kind: 'success', message: t('scenario.applied') })
      setSelected(null)
    } catch (error: unknown) {
      if (!mountedRef.current) return
      toast.show({
        kind: 'error',
        message: error instanceof Error ? error.message : String(error),
      })
    } finally {
      applyingRef.current = false
      if (mountedRef.current) setApplying(false)
    }
  }

  return (
    <div
      className="mx-auto mt-6 w-full max-w-[420px]"
      data-testid="scenario-team-cards"
    >
      <div className="mb-2 flex items-center justify-center gap-1.5 text-xs font-medium uppercase tracking-wide text-ter">
        <Sparkles size={12} aria-hidden />
        {t('scenario.sectionTitle')}
      </div>
      <div className="flex flex-col gap-2">
        {SCENARIO_PRESETS.map((scenario) => {
          const presentation = PRESENTATION[scenario.id]
          return (
            <button
              key={scenario.id}
              type="button"
              onClick={() => openScenario(scenario)}
              className="rounded border bg-1 p-3 text-left transition-colors hover:bg-3"
              style={{ borderColor: 'var(--border)' }}
              data-testid={`scenario-card-${scenario.id}`}
            >
              <div className="flex items-center gap-2 text-pri">
                {presentation.icon}
                <span className="text-sm font-medium">{t(presentation.titleKey)}</span>
              </div>
              <div className="mt-1 text-xs text-ter">{t(presentation.descriptionKey)}</div>
            </button>
          )
        })}
      </div>

      <Dialog.Root open={selected !== null} onOpenChange={(open) => !open && closeDialog()}>
        <Dialog.Portal>
          <Dialog.Overlay className="app-overlay fixed inset-0 z-[60]" />
          <div className="pointer-events-none fixed inset-0 z-[70] grid place-items-center p-4">
            <Dialog.Content
              data-testid="scenario-goal-dialog"
              className="dialog-scale-pop elev-2 pointer-events-auto w-[480px] max-w-[calc(100vw-32px)] rounded-lg border p-5"
              style={{
                background: 'var(--bg-elevated)',
                borderColor: 'var(--border-bright)',
              }}
              onEscapeKeyDown={(event) => {
                if (applying) event.preventDefault()
              }}
              onPointerDownOutside={(event) => {
                if (applying) event.preventDefault()
              }}
            >
              <Dialog.Title className="text-lg font-semibold text-pri">
                {selected ? t(PRESENTATION[selected.id].titleKey) : ''}
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-xs text-ter">
                {t('scenario.goalHint')}
              </Dialog.Description>
              <label className="mt-4 block">
                <span className="mb-1 block text-xs font-medium text-sec">
                  {t('scenario.goalLabel')}
                </span>
                <textarea
                  value={goal}
                  onChange={(event) => setGoal(event.target.value)}
                  rows={5}
                  className="w-full resize-y rounded border bg-1 p-2 text-sm text-pri outline-none focus:border-[var(--accent)]"
                  style={{ borderColor: 'var(--border)' }}
                  disabled={applying}
                  data-testid="scenario-goal-input"
                />
              </label>
              <div className="mt-4 flex justify-end gap-2">
                <button
                  type="button"
                  className="icon-btn"
                  onClick={closeDialog}
                  disabled={applying}
                  data-testid="scenario-goal-cancel"
                >
                  {t('scenario.cancel')}
                </button>
                <button
                  type="button"
                  className="icon-btn icon-btn--primary disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={() => void applyScenario()}
                  disabled={applying || !goal.trim()}
                  data-testid="scenario-goal-apply"
                >
                  {t(applying ? 'scenario.applying' : 'scenario.apply')}
                </button>
              </div>
            </Dialog.Content>
          </div>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
