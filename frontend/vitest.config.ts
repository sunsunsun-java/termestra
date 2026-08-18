import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    fileParallelism: false,
    include: ['tests/**/*.test.{ts,tsx}'],
    setupFiles: ['./tests/setup/vitest.setup.ts'],
  },
})
