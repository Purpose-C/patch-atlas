import { describe, expect, it } from 'vitest'
import { router } from './router'

describe('router', () => {
  it('redirects root to runs', async () => {
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/runs')
  })

  it('resolves detail route', async () => {
    await router.push('/runs/abc')
    expect(router.currentRoute.value.name).toBe('run-detail')
  })
})
