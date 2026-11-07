import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.E2E_PORT || 8092)
const baseURL = process.env.E2E_BASE_URL || `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'java -jar ../target/helpdesk-0.1.0.jar',
        url: `${baseURL}/actuator/health`,
        reuseExistingServer: true,
        timeout: 120_000,
        env: {
          SERVER_PORT: String(port),
          DB_URL: process.env.E2E_DB_URL || 'jdbc:postgresql://127.0.0.1:5432/helpdesk_e2e',
          FLYWAY_URL: process.env.E2E_FLYWAY_URL || 'jdbc:postgresql://127.0.0.1:5432/helpdesk_e2e',
          DB_USER: 'helpdesk_app',
          DB_PASSWORD: 'helpdesk_app',
          FLYWAY_USER: 'postgres',
          FLYWAY_PASSWORD: 'root',
          REDIS_HOST: process.env.REDIS_HOST || '127.0.0.1',
          REDIS_PORT: process.env.REDIS_PORT || '6379',
          REDIS_DATABASE: '2',
          HELPDESK_EMBEDDING_PROVIDER: 'mock',
          HELPDESK_RERANK_ENABLED: 'false',
          HELPDESK_LLM_PROVIDER: 'mock',
          HELPDESK_ATTACHMENT_DIR: '../target/e2e-attachments',
          LOG_FILE: '../target/e2e-helpdesk.log',
        },
      },
})
