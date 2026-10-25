import { defineConfig } from 'vitest/config';

// The layout checks in a real browser (issue #544), apart from the unit tests because they need Chromium: npm run test:layout.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['layout/**/*.layout.test.ts'],
    testTimeout: 60000,
    hookTimeout: 60000,
  },
});
