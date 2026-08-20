import { expect, test, type Page } from '@playwright/test'
import { emptyLocating, sampleDetail } from './fixtures'

const RUN = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

async function stubDetail(page: Page, detail: ReturnType<typeof sampleDetail>) {
  await page.route('**/api/runs/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === `/api/runs/${RUN}`) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(detail),
      })
      return
    }
    await route.fallback()
  })
}

test.describe('L2 locating honesty with intercepted API', () => {
  test('unknown values are not shown as zero', async ({ page }) => {
    await stubDetail(
      page,
      sampleDetail({
        runId: RUN,
        locating: {
          ...emptyLocating(),
          recorded: true,
          contextOrigin: 'HEURISTIC',
          toolCallsApplicable: false,
          toolCallCount: null,
          usageStatus: 'NONE_RECORDED',
          graphBuild: { durationMs: 12, cacheHit: null },
          stepKindCounts: { SELECTION: 2 },
        },
      }),
    )

    await page.goto(`/runs/${RUN}`)
    await expect(page.getByRole('heading', { name: '定位' })).toBeVisible()
    const toolCalls = page.locator('dt').filter({ hasText: /^工具调用$/ }).locator('..').locator('dd')
    await expect(toolCalls).toHaveText('—')
    await expect(toolCalls).not.toHaveText('0')
    await expect(page.getByText('未记录用量').first()).toBeVisible()
    await expect(page.getByText('缓存 未知')).toBeVisible()
  })

  test('truncated locating trace is visible with the step limit', async ({ page }) => {
    await stubDetail(
      page,
      sampleDetail({
        runId: RUN,
        locating: {
          ...emptyLocating(),
          recorded: true,
          contextOrigin: 'HEURISTIC',
          truncated: true,
          stepLimit: 8,
          steps: [
            {
              seq: 0,
              kind: 'SELECTION',
              subject: 'Foo',
              reason: 'pick',
              outcome: 'OK',
              detail: {},
            },
          ],
        },
      }),
    )

    await page.goto(`/runs/${RUN}`)
    await expect(page.getByRole('status')).toContainText('已截断')
    await expect(page.getByRole('status')).toContainText('最多显示 8 行')
  })

  test('missing locating record shows 无定位记录 rather than zero', async ({ page }) => {
    await stubDetail(
      page,
      sampleDetail({
        runId: RUN,
        locating: emptyLocating(),
      }),
    )

    await page.goto(`/runs/${RUN}`)
    await expect(page.getByRole('heading', { name: '定位' })).toBeVisible()
    await expect(page.getByText('无定位记录')).toBeVisible()
    await expect(page.getByText('工具调用')).toHaveCount(0)
    await expect(page.getByText('0', { exact: true })).toHaveCount(0)
  })
})
