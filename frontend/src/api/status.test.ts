import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchPlatformStatus } from './status'

describe('fetchPlatformStatus', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns the health payload when the API succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        name: 'PatchAtlas',
        status: 'UP',
        runtime: { javaVersion: '21', timestamp: '2026-07-10T00:00:00Z' },
      }),
    }))

    await expect(fetchPlatformStatus()).resolves.toMatchObject({
      name: 'PatchAtlas',
      status: 'UP',
    })
  })

  it('throws a readable error when the API is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))

    await expect(fetchPlatformStatus()).rejects.toThrow('HTTP 503')
  })
})
