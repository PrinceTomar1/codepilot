import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
  },
  test: {
    // Required for @testing-library/react's automatic afterEach(cleanup) registration --
    // without it, DOM from earlier tests in the same file isn't unmounted and elements
    // accumulate across tests (queries start failing with "multiple elements found").
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Restore spies/mocks (vi.spyOn, vi.fn) to their original state between tests -- without
    // this, a mock set up in one test (e.g. mockResolvedValue) leaks into the next test in the
    // same file and its call history keeps accumulating.
    restoreMocks: true,
  },
})
