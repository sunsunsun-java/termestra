import { afterEach } from 'vitest'

if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string): MediaQueryList =>
      ({
        addEventListener: () => {},
        addListener: () => {},
        dispatchEvent: () => false,
        matches: false,
        media: query,
        onchange: null,
        removeEventListener: () => {},
        removeListener: () => {},
      }) as MediaQueryList,
  })
}

afterEach(() => {
  document.body.removeAttribute('data-scroll-locked')
  document.body.style.pointerEvents = ''
  document.querySelectorAll('[data-radix-focus-guard]').forEach((node) => node.remove())
})
