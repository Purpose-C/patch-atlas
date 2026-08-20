import { expect, test, type Page, type Route } from '@playwright/test'
import { listItem, sampleDetail } from './fixtures'

const ALPHA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const BETA = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

async function fulfillJson(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

async function stubListAndDetail(
  page: Page,
  list: (url: URL) => { status: number; body: unknown },
  details: Record<string, ReturnType<typeof sampleDetail>>,
) {
  await page.route('**/api/runs**', async (route) => {
    const url = new URL(route.request().url())
    const match = url.pathname.match(/^\/api\/runs\/([^/]+)$/)
    if (match) {
      const detail = details[match[1]]
      if (!detail) {
        await fulfillJson(route, 404, { detail: 'not found' })
        return
      }
      await fulfillJson(route, 200, detail)
      return
    }
    if (url.pathname === '/api/runs') {
      const response = list(url)
      await fulfillJson(route, response.status, response.body)
      return
    }
    await route.fallback()
  })
}

test.describe('L2 list behaviour with intercepted API', () => {
  test('clicking a state link opens the run detail', async ({ page }) => {
    const item = listItem(ALPHA, { issueTitle: 'Make persist explicit', state: 'FAILED' })
    await stubListAndDetail(
      page,
      () => ({ status: 200, body: { items: [item], nextCursor: null } }),
      { [ALPHA]: sampleDetail({ runId: ALPHA, input: { ...sampleDetail().input, issueTitle: 'Make persist explicit' } }) },
    )

    await page.goto('/runs')
    await expect(page.getByRole('heading', { name: '运行列表' })).toBeVisible()
    await page.getByRole('link', { name: 'FAILED' }).click()
    await expect(page).toHaveURL(new RegExp(`/runs/${ALPHA}$`))
    await expect(page.getByRole('heading', { name: 'Make persist explicit' })).toBeVisible()
    await expect(page.getByRole('link', { name: '← 返回列表' })).toBeVisible()
  })

  test('load more appends rows and keeps already loaded rows', async ({ page }) => {
    const alpha = listItem(ALPHA, { issueTitle: 'alpha-issue', state: 'QUEUED' })
    const beta = listItem(BETA, { issueTitle: 'beta-issue', state: 'FAILED' })
    await stubListAndDetail(page, (url) => {
      if (url.searchParams.get('cursor') === 'cursor-1') {
        return { status: 200, body: { items: [beta], nextCursor: null } }
      }
      return { status: 200, body: { items: [alpha], nextCursor: 'cursor-1' } }
    }, {})

    await page.goto('/runs')
    await expect(page.getByText('alpha-issue')).toBeVisible()
    await page.getByRole('button', { name: '加载更多' }).click()
    await expect(page.getByText('beta-issue')).toBeVisible()
    await expect(page.getByText('alpha-issue')).toBeVisible()
  })

  test('quiet first-page refresh keeps already loaded later pages', async ({ page }) => {
    const alpha = listItem(ALPHA, { issueTitle: 'alpha-issue', state: 'QUEUED' })
    const beta = listItem(BETA, { issueTitle: 'beta-issue', state: 'FAILED' })
    let firstPageCalls = 0
    await stubListAndDetail(page, (url) => {
      if (url.searchParams.get('cursor') === 'cursor-1') {
        return { status: 200, body: { items: [beta], nextCursor: null } }
      }
      firstPageCalls += 1
      if (firstPageCalls === 1) {
        return { status: 200, body: { items: [alpha], nextCursor: 'cursor-1' } }
      }
      return {
        status: 200,
        body: {
          items: [{ ...alpha, state: 'COMPLETED', issueTitle: 'alpha-issue' }],
          nextCursor: 'cursor-1',
        },
      }
    }, {})

    await page.clock.install()
    await page.goto('/runs')
    await expect(page.getByText('alpha-issue')).toBeVisible()
    await page.getByRole('button', { name: '加载更多' }).click()
    await expect(page.getByText('beta-issue')).toBeVisible()
    await page.clock.fastForward(15_000)
    await expect(page.getByText('beta-issue')).toBeVisible()
    await expect(page.getByRole('link', { name: 'COMPLETED' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'FAILED' })).toBeVisible()
  })

  test('list load failure shows retry and recovers', async ({ page }) => {
    const alpha = listItem(ALPHA, { issueTitle: 'alpha-issue' })
    let calls = 0
    await stubListAndDetail(page, () => {
      calls += 1
      if (calls === 1) {
        return { status: 500, body: { detail: 'backend down' } }
      }
      return { status: 200, body: { items: [alpha], nextCursor: null } }
    }, {})

    await page.goto('/runs')
    await expect(page.getByRole('alert')).toContainText('加载失败')
    await page.getByRole('button', { name: '重试' }).click()
    await expect(page.getByText('alpha-issue')).toBeVisible()
  })

  test('refresh failure keeps the table and warns that state may be stale', async ({ page }) => {
    const alpha = listItem(ALPHA, { issueTitle: 'alpha-issue' })
    let calls = 0
    await stubListAndDetail(page, () => {
      calls += 1
      if (calls === 1) {
        return { status: 200, body: { items: [alpha], nextCursor: null } }
      }
      return { status: 500, body: { detail: 'refresh failed' } }
    }, {})

    await page.goto('/runs')
    await expect(page.getByText('alpha-issue')).toBeVisible()
    await page.getByRole('button', { name: '刷新' }).click()
    await expect(page.getByRole('status')).toContainText('状态可能已过期')
    await expect(page.getByText('alpha-issue')).toBeVisible()
    await expect(page.getByRole('table')).toBeVisible()
  })
})
