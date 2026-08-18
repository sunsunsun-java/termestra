import { type ReactNode, useId } from 'react'

interface EmptyStateProps {
  title: string
  description: string
  icon?: ReactNode
  action?: ReactNode
}

/** A named, keyboard-discoverable landmark for empty and recovery states. */
export const EmptyState = ({ title, description, icon, action }: EmptyStateProps) => {
  const titleId = useId()
  const descriptionId = useId()

  return (
    <section
      role="region"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      className="m-auto flex max-w-[380px] flex-col items-center gap-3 px-6 py-8 text-center"
      data-testid="empty-state"
    >
      {icon ? (
        <span
          data-testid="empty-state-icon"
          aria-hidden
          className="flex h-12 w-12 items-center justify-center rounded-lg border text-sec"
          style={{ background: 'var(--bg-2)', borderColor: 'var(--border-bright)' }}
        >
          {icon}
        </span>
      ) : null}
      <h2 id={titleId} className="text-lg font-semibold text-pri" data-testid="empty-state-title">
        {title}
      </h2>
      <p id={descriptionId} className="text-sm text-ter" data-testid="empty-state-description">
        {description}
      </p>
      {action ? <div className="mt-1">{action}</div> : null}
    </section>
  )
}
