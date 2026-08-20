import { expect, test, type ConsoleMessage, type Page } from '@playwright/test'

function attachConsoleErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') {
      errors.push(msg.text())
    }
  })
  page.on('pageerror', (error) => {
    errors.push(error.message)
  })
  return errors
}

test.describe('L1 packaged console on an empty database', () => {
  test('opens /runs with the list title and no console errors', async ({ page }) => {
    const errors = attachConsoleErrors(page)
    await page.goto('/runs')
    await expect(page.getByRole('heading', { name: '运行列表' })).toBeVisible()
    expect(errors).toEqual([])
  })

  test('empty database shows how to create a run via POST', async ({ page }) => {
    await page.goto('/runs')
    await expect(page.getByText('暂无运行。通过')).toBeVisible()
    await expect(page.locator('code', { hasText: 'POST /api/runs' })).toBeVisible()
  })

  test('unknown route renders the 404 view', async ({ page }) => {
    await page.goto('/this-route-does-not-exist')
    await expect(page.getByRole('heading', { name: '未找到页面' })).toBeVisible()
    await expect(page.getByRole('link', { name: '返回运行列表' })).toBeVisible()
    await expect(page.locator('#app')).not.toBeEmpty()
  })

  test('health endpoint reports UP', async ({ request }) => {
    const response = await request.get('/api/v1/health')
    expect(response.ok()).toBeTruthy()
    const body = (await response.json()) as { status: string }
    expect(body.status).toBe('UP')
  })
})
