import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunDetail } from '../api/runs'
import { ApiError } from '../api/runs'
import * as runsApi from '../api/runs'
import RunDetailView from './RunDetailView.vue'

vi.mock('../api/runs', async () => {
  const actual = await vi.importActual<typeof import('../api/runs')>('../api/runs')
  return {
    ...actual,
    fetchRun: vi.fn(),
  }
})

const fetchRun = vi.mocked(runsApi.fetchRun)

function sampleDetail(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    runId: '11111111-1111-1111-1111-111111111111',
    mode: 'HISTORICAL',
    state: 'COMPLETED',
    caseId: 'case-1',
    createdAt: '2026-08-12T10:00:00Z',
    updatedAt: '2026-08-12T10:05:00Z',
    completedAt: '2026-08-12T10:05:00Z',
    input: {
      repositoryUrl: 'https://github.com/ex/repo.git',
      issueUrl: 'https://github.com/ex/repo/issues/1',
      issueTitle: 'Title <b>bold</b>',
      issueBody: 'Body <img src=x onerror=alert(1)>',
      buggyRevision: 'a'.repeat(40),
      fixedRevision: 'b'.repeat(40),
      modulePath: 'module-a',
    },
    executionPolicy: { javaVersion: '21', networkMode: 'OFFLINE' },
    generation: {
      attemptCount: 2,
      modelProvider: 'OPENAI',
      modelName: 'gpt-test',
      inputTokens: 10,
      outputTokens: 20,
      totalTokens: 30,
    },
    candidate: {
      patchText: 'diff --git a/x b/x\n+evil <script>alert(1)</script>',
      patchSha256: 'c'.repeat(64),
      targetClass: 'c.T',
      targetMethod: 'm',
    },
    result: {
      verdict: 'VALID_REPRODUCTION',
      failureStage: null,
      failureCategory: null,
      failureSummary: null,
    },
    attempts: [
      {
        replayRound: 1,
        side: 'BUGGY',
        attemptOrdinal: 1,
        phase: 'EXECUTED',
        outcome: 'FAIL',
        targetEvidence: 'TARGET_ASSERTION_FAILURE',
        diagnostic: 'diag <script>x</script>',
        sandboxStatus: 'COMPLETED',
        exitCode: 1,
        elapsedMs: 100,
        timedOut: false,
        commandJson: '["mvn","test"]',
        image: 'maven:3.9-eclipse-temurin-21',
        limitsJson: '{"cpus":2}',
        networkMode: 'OFFLINE',
        logSummary: 'log <b>html</b>',
        targetTestCase: {
          className: 'c.T',
          methodName: 'm',
          status: 'FAILED',
          message: 'expected <true> but was <false>',
          elapsedMs: 42,
          exceptionType: 'org.opentest4j.AssertionFailedError',
        },
        evidenceSchemaVersion: 1,
      },
    ],
    ...overrides,
  }
}

async function mountDetail(
  runId = '11111111-1111-1111-1111-111111111111',
): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/runs', component: { template: '<div />' } },
      { path: '/runs/:runId', component: RunDetailView, props: true },
    ],
  })
  await router.push(`/runs/${runId}`)
  const wrapper = mount(RunDetailView, {
    props: { runId },
    global: {
      plugins: [router],
      stubs: { RouterLink: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('RunDetailView', () => {
  beforeEach(() => {
    fetchRun.mockReset()
    vi.useRealTimers()
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders timestamps, issue url, module, model, and full attempt facts as text', async () => {
    fetchRun.mockResolvedValue(sampleDetail())
    const wrapper = await mountDetail()

    expect(fetchRun).toHaveBeenCalledWith(
      '11111111-1111-1111-1111-111111111111',
      expect.any(AbortSignal),
    )

    const text = wrapper.text()
    expect(text).toContain('2026-08-12T10:00:00Z')
    expect(text).toContain('2026-08-12T10:05:00Z')
    expect(text).toContain('https://github.com/ex/repo/issues/1')
    expect(text).toContain('module-a')
    expect(text).toContain('OPENAI')
    expect(text).toContain('gpt-test')
    expect(text).toContain('VALID_REPRODUCTION')
    expect(text).toContain('R1 BUGGY #1')
    expect(text).toContain('TARGET_ASSERTION_FAILURE')
    expect(text).toContain('42 ms')
    expect(text).toContain('org.opentest4j.AssertionFailedError')

    wrapper.unmount()
  })

  it('shows (root) when modulePath is empty string', async () => {
    fetchRun.mockResolvedValue(
      sampleDetail({
        input: {
          ...sampleDetail().input,
          modulePath: '',
        },
      }),
    )
    const wrapper = await mountDetail()
    expect(wrapper.text()).toContain('(root)')
    wrapper.unmount()
  })

  it('does not execute untrusted strings as HTML', async () => {
    fetchRun.mockResolvedValue(sampleDetail())
    const wrapper = await mountDetail()
    const html = wrapper.html()
    expect(html).not.toMatch(/<script[\s>]/i)
    expect(html).not.toMatch(/<img[^>]*onerror/i)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
    expect(wrapper.text()).toContain('Body <img src=x onerror=alert(1)>')
    wrapper.unmount()
  })

  it('polls non-terminal states and stops on COMPLETED', async () => {
    vi.useFakeTimers()
    fetchRun
      .mockResolvedValueOnce(sampleDetail({ state: 'GENERATING', completedAt: null, result: null }))
      .mockResolvedValueOnce(sampleDetail({ state: 'REPLAYING', completedAt: null, result: null }))
      .mockResolvedValueOnce(sampleDetail({ state: 'COMPLETED' }))

    const wrapper = await mountDetail()
    expect(fetchRun).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('GENERATING')

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('REPLAYING')

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('COMPLETED')

    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(3)

    wrapper.unmount()
  })

  it('stops polling on FAILED', async () => {
    vi.useFakeTimers()
    fetchRun
      .mockResolvedValueOnce(
        sampleDetail({
          state: 'GENERATING',
          completedAt: null,
          result: null,
        }),
      )
      .mockResolvedValueOnce(
        sampleDetail({
          state: 'FAILED',
          result: {
            verdict: null,
            failureStage: 'GENERATION',
            failureCategory: 'MODEL_UNAVAILABLE',
            failureSummary: 'down',
          },
        }),
      )

    const wrapper = await mountDetail()
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.text()).toContain('FAILED')
    const calls = fetchRun.mock.calls.length
    await vi.advanceTimersByTimeAsync(9000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(calls)
    wrapper.unmount()
  })

  it('does not abort slow in-flight polls when the next interval elapses', async () => {
    vi.useFakeTimers()
    let resolveFirst!: (v: RunDetail) => void
    const first = new Promise<RunDetail>((r) => {
      resolveFirst = r
    })
    fetchRun
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(sampleDetail({ state: 'GENERATING', completedAt: null, result: null }))

    const wrapper = mount(RunDetailView, {
      props: { runId: '11111111-1111-1111-1111-111111111111' },
      global: {
        plugins: [
          createRouter({
            history: createMemoryHistory(),
            routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
          }),
        ],
        stubs: { RouterLink: true },
      },
    })
    // 首请求挂起
    expect(fetchRun).toHaveBeenCalledTimes(1)

    // 3s 后 quiet 轮询应跳过（in-flight），不得 abort 导致永久失败
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(1)

    resolveFirst(sampleDetail({ state: 'GENERATING', completedAt: null, result: null }))
    await flushPromises()
    expect(wrapper.text()).toContain('GENERATING')

    // 完成后才 schedule 下一次
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(fetchRun).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('keeps prior detail and shows stale warning on transient poll failure', async () => {
    vi.useFakeTimers()
    fetchRun
      .mockResolvedValueOnce(sampleDetail({ state: 'GENERATING', completedAt: null, result: null }))
      .mockRejectedValueOnce(new ApiError(500, 'backend down'))

    const wrapper = await mountDetail()
    expect(wrapper.text()).toContain('GENERATING')

    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    expect(wrapper.text()).toContain('状态可能已过期')
    expect(wrapper.text()).toContain('backend down')
    expect(wrapper.text()).toContain('GENERATING')

    wrapper.unmount()
  })

  it('shows initial error on 404 without prior detail', async () => {
    fetchRun.mockRejectedValue(new ApiError(404, 'run not found'))
    const wrapper = await mountDetail('missing-id')
    expect(wrapper.text()).toContain('无法加载')
    expect(wrapper.text()).toContain('run not found')
    expect(wrapper.text()).not.toContain('状态可能已过期')
    wrapper.unmount()
  })

  it('stops and aborts when page is hidden; resumes when visible', async () => {
    vi.useFakeTimers()
    let visibility = 'visible'
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    })

    let aborted = false
    fetchRun.mockImplementation((_id, signal) => {
      return new Promise((_resolve, reject) => {
        const onAbort = () => {
          aborted = true
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }))
        }
        if (signal?.aborted) {
          onAbort()
          return
        }
        signal?.addEventListener('abort', onAbort)
      })
    })

    const wrapper = mount(RunDetailView, {
      props: { runId: 'run-vis' },
      global: {
        stubs: { RouterLink: true },
      },
    })
    expect(fetchRun).toHaveBeenCalledTimes(1)

    visibility = 'hidden'
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(aborted).toBe(true)

    const callsAfterHide = fetchRun.mock.calls.length
    await vi.advanceTimersByTimeAsync(9000)
    expect(fetchRun).toHaveBeenCalledTimes(callsAfterHide)

    // 首载被中止后 detail 为空；恢复可见应重新 load
    fetchRun.mockResolvedValue(
      sampleDetail({ state: 'GENERATING', completedAt: null, result: null }),
    )
    visibility = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(fetchRun.mock.calls.length).toBeGreaterThan(callsAfterHide)
    expect(wrapper.text()).toContain('GENERATING')

    wrapper.unmount()
  })

  it('aborts in-flight request on unmount', async () => {
    let aborted = false
    fetchRun.mockImplementation((_id, signal) => {
      return new Promise((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          aborted = true
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }))
        })
      })
    })
    const wrapper = mount(RunDetailView, {
      props: { runId: 'run-unmount' },
      global: { stubs: { RouterLink: true } },
    })
    expect(fetchRun).toHaveBeenCalled()
    wrapper.unmount()
    await flushPromises()
    expect(aborted).toBe(true)
  })
})
