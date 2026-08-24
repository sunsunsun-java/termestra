import { useMemo } from 'react'

import { useI18n } from '../i18n.js'
import {
  layoutTerminalBookmarkPositions,
  type TerminalBookmark,
} from './terminal-bookmarks.js'
import './terminal-bookmarks.css'

type TerminalBookmarkRailProps = {
  activeBookmarkId: number | null
  bookmarks: TerminalBookmark[]
  onSelect: (id: number) => void
}

export const TerminalBookmarkRail = ({
  activeBookmarkId,
  bookmarks,
  onSelect,
}: TerminalBookmarkRailProps) => {
  const { language, t } = useI18n()
  const timeFormatter = useMemo(
    () =>
      new Intl.DateTimeFormat(language === 'zh' ? 'zh-CN' : 'en', {
        hour: '2-digit',
        minute: '2-digit',
      }),
    [language]
  )
  const displayPositions = useMemo(() => layoutTerminalBookmarkPositions(bookmarks), [bookmarks])

  if (bookmarks.length === 0) return null

  return (
    <nav className="terminal-bookmark-rail" aria-label={t('terminalBookmarks.aria')}>
      <span className="terminal-bookmark-rail__activation" aria-hidden />
      <span className="terminal-bookmark-rail__hint">
        <strong>{t('terminalBookmarks.hint')}</strong>
        <small>{t('terminalBookmarks.scope')}</small>
      </span>
      {bookmarks.map((bookmark, index) => {
        const fallback = t('terminalBookmarks.fallback', { index: bookmark.id })
        const preview = bookmark.preview || fallback
        const time = timeFormatter.format(new Date(bookmark.createdAt))
        return (
          <button
            aria-label={t('terminalBookmarks.itemAria', {
              current: index + 1,
              preview,
              total: bookmarks.length,
            })}
            className={bookmark.id === activeBookmarkId ? 'is-active' : undefined}
            key={bookmark.id}
            onClick={() => onSelect(bookmark.id)}
            style={{ top: `${displayPositions[index]}%` }}
            type="button"
          >
            <span className="terminal-bookmark-rail__tick" aria-hidden />
            <span className="terminal-bookmark-rail__card">
              <strong>{preview}</strong>
              <small>
                {t('terminalBookmarks.meta', { index: bookmark.id, time })}
              </small>
            </span>
          </button>
        )
      })}
    </nav>
  )
}
