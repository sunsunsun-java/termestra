import * as RadixTooltip from '@radix-ui/react-tooltip'
import type { ReactNode } from 'react'

type TooltipProps = {
  children: ReactNode
  label: ReactNode
  side?: 'top' | 'right' | 'bottom' | 'left'
  align?: 'start' | 'center' | 'end'
}

/**
 * Adds hover and keyboard help to one interactive child. Each tooltip carries
 * its own provider so isolated surfaces (and tests) do not depend on app-shell
 * context; an empty label leaves the child untouched.
 */
export const Tooltip = ({
  align = 'center',
  children,
  label,
  side = 'top',
}: TooltipProps) => {
  if (label === null || label === undefined || label === false || label === '') return children

  return (
    <RadixTooltip.Provider delayDuration={250} skipDelayDuration={150}>
      <RadixTooltip.Root>
        <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
        <RadixTooltip.Portal>
          <RadixTooltip.Content
            align={align}
            className="tooltip"
            collisionPadding={8}
            side={side}
            sideOffset={6}
          >
            {label}
          </RadixTooltip.Content>
        </RadixTooltip.Portal>
      </RadixTooltip.Root>
    </RadixTooltip.Provider>
  )
}
