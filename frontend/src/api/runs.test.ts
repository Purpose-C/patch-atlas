import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchRun, fetchRuns, isTerminalState } from './runs'

describe('runs api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetchRuns maps ok response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ items: [], nextCursor: null }),
      }),
    )
    const page = await fetchRuns(20)
    expect(page.items).toEqual([])
  })

  it('maps problem detail errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: async () => ({ detail: 'run not found' }),
      }),
    )
    await expect(fetchRun('x')).rejects.toBeInstanceOf(ApiError)
  })

  it('isTerminalState', () => {
    expect(isTerminalState('COMPLETED')).toBe(true)
    expect(isTerminalState('QUEUED')).toBe(false)
  })
})
