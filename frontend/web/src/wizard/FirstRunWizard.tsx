import * as Dialog from '@radix-ui/react-dialog'
import {
  Crown,
  FolderOpen,
  Network,
  PlayCircle,
  SendHorizontal,
  type LucideIcon,
} from 'lucide-react'
import { type ReactNode, useState } from 'react'

import { useI18n } from '../i18n.js'

interface FirstRunWizardProps {
  open: boolean
  onClose: (shouldMarkSeen?: boolean) => void
  onAddWorkspace: () => void
  onTryDemo: () => void
}

const LAST_STEP = 2

export const FirstRunWizard = ({
  open,
  onClose,
  onAddWorkspace,
  onTryDemo,
}: FirstRunWizardProps) => {
  const { t } = useI18n()
  const [step, setStep] = useState(0)

  const close = (markSeen = true) => {
    setStep(0)
    onClose(markSeen)
  }

  const title =
    step === 0
      ? t('firstRun.title')
      : step === 1
        ? t('firstRun.howItWorks')
        : t('firstRun.getStarted')
  const description =
    step === 0
      ? t('firstRun.desc')
      : step === 1
        ? t('firstRun.subtitle')
        : t('firstRun.optionDesc')
  const flowItems: Array<{ Icon: LucideIcon; title: string; description: string }> = [
    { Icon: FolderOpen, title: t('firstRun.slide1Title'), description: t('firstRun.slide1Desc') },
    { Icon: Crown, title: t('firstRun.slide2Title'), description: t('firstRun.slide2Desc') },
    {
      Icon: SendHorizontal,
      title: t('firstRun.slide3Title'),
      description: t('firstRun.slide3Desc'),
    },
  ]

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) close()
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="app-overlay fixed inset-0 z-40" />
        <div className="pointer-events-none fixed inset-0 z-50 grid place-items-center p-4">
          <Dialog.Content
            className="dialog-scale-pop elev-2 pointer-events-auto w-[500px] max-w-[calc(100vw-32px)] overflow-hidden rounded-xl border"
            style={{ background: 'var(--bg-elevated)', borderColor: 'var(--border-bright)' }}
          >
            <header className="border-b px-6 pt-6 pb-5" style={{ borderColor: 'var(--border)' }}>
              <div className="mb-5 flex items-center justify-between">
                <span className="inline-flex items-center gap-2 text-xs font-medium text-ter">
                  <Network size={15} aria-hidden />
                  Termestra
                </span>
                <ol className="flex gap-1.5" aria-label={t('firstRun.step', { current: step + 1, total: 3 })}>
                  {[0, 1, 2].map((index) => (
                    <li
                      key={index}
                      aria-current={index === step ? 'step' : undefined}
                      className="h-1.5 w-8 rounded-full"
                      style={{ background: index <= step ? 'var(--accent)' : 'var(--bg-3)' }}
                    />
                  ))}
                </ol>
              </div>
              <Dialog.Title className="text-xl font-semibold text-pri">{title}</Dialog.Title>
              <Dialog.Description className="mt-1.5 text-sm text-sec">
                {description}
              </Dialog.Description>
            </header>

            <main className="min-h-[210px] px-6 py-5">
              {step === 0 ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  <GuideCard
                    icon={<FolderOpen size={20} />}
                    title={t('firstRun.slide1Title')}
                    description={t('firstRun.slide1Desc')}
                  />
                  <GuideCard
                    icon={<Crown size={20} />}
                    title={t('firstRun.slide2Title')}
                    description={t('firstRun.slide2Desc')}
                  />
                </div>
              ) : null}

              {step === 1 ? (
                <ol className="space-y-3">
                  {flowItems.map(({ Icon, title: itemTitle, description: itemDescription }, index) => (
                    <li
                      key={String(itemTitle)}
                      className="flex items-start gap-3 rounded-lg border p-3"
                      style={{ borderColor: 'var(--border)', background: 'var(--bg-1)' }}
                    >
                      <span className="mt-0.5 text-accent" aria-hidden>
                        <Icon size={18} />
                      </span>
                      <div className="min-w-0">
                        <div className="text-sm font-medium text-pri">
                          {index + 1}. {itemTitle}
                        </div>
                        <div className="mt-0.5 text-xs text-sec">{itemDescription}</div>
                      </div>
                    </li>
                  ))}
                </ol>
              ) : null}

              {step === 2 ? (
                <div className="grid gap-3">
                  <button
                    type="button"
                    onClick={() => {
                      onAddWorkspace()
                      close(false)
                    }}
                    className="icon-btn icon-btn--primary h-11 w-full justify-center"
                  >
                    <FolderOpen size={16} aria-hidden />
                    {t('firstRun.addWorkspace')}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onTryDemo()
                      close()
                    }}
                    className="icon-btn h-11 w-full justify-center"
                  >
                    <PlayCircle size={16} aria-hidden />
                    {t('firstRun.tryDemo')}
                  </button>
                </div>
              ) : null}
            </main>

            <footer
              className="flex items-center justify-between border-t px-6 py-4"
              style={{ borderColor: 'var(--border)', background: 'var(--bg-1)' }}
            >
              <button type="button" onClick={() => close()} className="text-xs text-ter hover:text-sec">
                {t('firstRun.skip')}
              </button>
              <div className="flex items-center gap-2">
                {step > 0 ? (
                  <button type="button" onClick={() => setStep((value) => value - 1)} className="icon-btn">
                    {t('firstRun.back')}
                  </button>
                ) : null}
                {step < LAST_STEP ? (
                  <button
                    type="button"
                    onClick={() => setStep((value) => value + 1)}
                    className="icon-btn icon-btn--primary"
                  >
                    {t('firstRun.next')}
                  </button>
                ) : null}
              </div>
            </footer>
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

const GuideCard = ({
  icon,
  title,
  description,
}: {
  icon: ReactNode
  title: string
  description: string
}) => (
  <div className="rounded-lg border p-4" style={{ borderColor: 'var(--border)', background: 'var(--bg-1)' }}>
    <span className="mb-3 inline-flex rounded-lg border p-2 text-accent" style={{ borderColor: 'var(--border-bright)' }} aria-hidden>
      {icon}
    </span>
    <div className="text-sm font-medium text-pri">{title}</div>
    <div className="mt-1 text-xs leading-relaxed text-sec">{description}</div>
  </div>
)
