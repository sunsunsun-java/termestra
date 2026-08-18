import type { CSSProperties, ReactNode } from 'react'

type AvatarProps = {
  size: number
  children: ReactNode
  color: string
  label?: string
  fontRatio?: number
  ringColor?: string | null
  ringSurface?: string
  mono?: boolean
  decoration?: ReactNode
  testId?: string
  data?: Record<string, string | undefined>
  className?: string
  style?: CSSProperties
}

const avatarDataAttributes = (data: AvatarProps['data']) => {
  const attributes: Record<`data-${string}`, string> = {}
  for (const [name, value] of Object.entries(data ?? {})) {
    if (value !== undefined) attributes[`data-${name}`] = value
  }
  return attributes
}

/**
 * A small identity mark shared by workspaces, roles, and CLI providers.
 * Consumers own the glyph and tone; this primitive owns only sizing, contrast,
 * and the optional runtime-status halo. Supplying a label exposes the mark as
 * an image, while unlabeled marks remain decorative inside already-named controls.
 */
export const Avatar = ({
  children,
  className,
  color,
  data,
  decoration,
  fontRatio = 0.4,
  label,
  mono = false,
  ringColor,
  ringSurface = 'var(--bg-1)',
  size,
  style,
  testId,
}: AvatarProps) => {
  const geometry: CSSProperties = {
    width: size,
    height: size,
    fontSize: Math.round(size * fontRatio),
  }
  const palette: CSSProperties = {
    color,
    background: `color-mix(in oklab, ${color} 14%, transparent)`,
    border: `1px solid color-mix(in oklab, ${color} 35%, transparent)`,
    boxShadow: ringColor
      ? `0 0 0 2px ${ringSurface}, 0 0 0 4px ${ringColor}`
      : undefined,
  }

  return (
    <span
      {...avatarDataAttributes(data)}
      aria-hidden={label ? undefined : true}
      aria-label={label}
      className={[
        'relative inline-flex shrink-0 items-center justify-center rounded font-semibold',
        mono ? 'mono' : '',
        className ?? '',
      ]
        .filter(Boolean)
        .join(' ')}
      data-testid={testId}
      role={label ? 'img' : undefined}
      style={{ ...geometry, ...palette, ...style }}
    >
      {children}
      {decoration}
    </span>
  )
}
