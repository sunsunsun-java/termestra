import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const bookmarkCss = readFileSync(
  new URL('../web/src/terminal/terminal-bookmarks.css', import.meta.url),
  'utf8'
)

test('the expanded bookmark rail stays pointer-transparent outside activation controls', () => {
  assert.match(bookmarkCss, /\.terminal-bookmark-rail\s*\{[^}]*pointer-events:\s*none/s)
  assert.match(
    bookmarkCss,
    /\.terminal-bookmark-rail__activation\s*\{[^}]*width:\s*6px[^}]*pointer-events:\s*auto/s
  )
  assert.match(bookmarkCss, /\.terminal-bookmark-rail button\s*\{[^}]*pointer-events:\s*auto/s)
})
