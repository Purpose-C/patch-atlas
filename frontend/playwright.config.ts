import { defineConfig, devices } from '@playwright/test'

/**
 * 浏览器套件不启停栈。先 `./scripts/up.sh`，再 `npm run e2e`。
 * 只跑 Chromium；retries 固定为 0，失败就是失败。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:8080',
    trace: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
