import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RunListItem } from '../api/runs'
import { ApiError } from '../api/runs'
import * as runsApi from '../api/runs'
import RunsListView from './RunsListView.vue'

vi.mock('../api/runs', async () => {
  const actual = await vi.importActual<typeof import('../api/runs')>('../api/runs')
  return {
    ...actual,
    fetchRuns: vi.fn(),
  }
})

const fetchRuns = vi.mocked(runsApi.fetchRuns)

function item(
  id: string,
  overrides: Partial<RunListItem> = {},
): RunListItem {
  return {
    runId: id,
    mode: 'LIVE',
    state: 'QUEUED',
    issueTitle: `issue-${id}`,
    repositoryUrl: 'https://github.com/ex/repo.git',
    verdict: null,
    failureCategory: null,
    createdAt: '2026-08-12T10:00:00Z',
    updatedAt: '2026-08-12T10:01:00Z',
    completedAt: null,
    ...overrides,
  }
}

/** 保留默认 slot，便于断言状态链接文本 */
const routerLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a class="state-link"><slot /></a>',
}

async function mountList(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/runs', component: RunsListView },
      { path: '/runs/:runId', component: { template: '<div />' } },
    ],
  })
  await router.push('/runs')
  const wrapper = mount(RunsListView, {
    global: {
      plugins: [router],
      stubs: { RouterLink: routerLinkStub },
    },
  })
  await flushPromises()
  return wrapper
}

describe('RunsListView', () => {
  beforeEach(() => {
    fetchRuns.mockReset()
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

  it('shows loading state before first page arrives', async () => {
    let resolvePage!: (v: { items: RunListItem[]; nextCursor: string | null }) => void
    fetchRuns.mockReturnValueOnce(
      new Promise((r) => {
        resolvePage = r
      }),
    )

    const wrapper = mount(RunsListView, {
      global: { stubs: { RouterLink: routerLinkStub } },
    })
    // 首屏未 resolve 前
    expect(wrapper.text()).toContain('正在加载运行')
    expect(wrapper.find('button.refresh-button').attributes('disabled')).toBeDefined()

    resolvePage({ items: [item('a')], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('issue-a')
    expect(wrapper.text()).not.toContain('正在加载运行')
    wrapper.unmount()
  })

  it('shows empty state when first page has no items', async () => {
    fetchRuns.mockResolvedValue({ items: [], nextCursor: null })
    const wrapper = await mountList()
    expect(wrapper.text()).toContain('暂无运行')
    expect(wrapper.text()).toContain('POST /api/runs')
    expect(wrapper.find('table').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders rows with state, mode, issue, and timestamps', async () => {
    fetchRuns.mockResolvedValue({
      items: [
        item('r1', {
          state: 'COMPLETED',
          mode: 'HISTORICAL',
          issueTitle: 'fix <b>x</b>',
          verdict: 'VALID_REPRODUCTION',
        }),
      ],
      nextCursor: null,
    })
    const wrapper = await mountList()
    const text = wrapper.text()
    expect(text).toContain('COMPLETED')
    expect(text).toContain('HISTORICAL')
    expect(text).toContain('fix <b>x</b>')
    expect(text).toContain('VALID_REPRODUCTION')
    // 纯文本插值，不执行 HTML
    expect(wrapper.find('b').exists()).toBe(false)
    expect(wrapper.html()).not.toMatch(/<script/i)
    wrapper.unmount()
  })

  it('shows error with retry on initial failure', async () => {
    fetchRuns
      .mockRejectedValueOnce(new ApiError(500, 'store down'))
      .mockResolvedValueOnce({ items: [item('recovered')], nextCursor: null })

    const wrapper = await mountList()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).toContain('store down')

    const retry = wrapper
      .findAll('button')
      .find((b) => b.text().includes('重试'))
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('issue-recovered')
    expect(wrapper.text()).not.toContain('加载失败')
    expect(fetchRuns).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('load more appends next page and keeps load-more button when cursor remains', async () => {
    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a'), item('b')],
        nextCursor: 'cursor-1',
      })
      .mockResolvedValueOnce({
        items: [item('c')],
        nextCursor: 'cursor-2',
      })

    const wrapper = await mountList()
    expect(wrapper.text()).toContain('issue-a')
    expect(wrapper.text()).toContain('issue-b')
    expect(wrapper.text()).not.toContain('issue-c')

    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    expect(more).toBeDefined()
    await more!.trigger('click')
    await flushPromises()

    expect(fetchRuns).toHaveBeenNthCalledWith(2, 20, 'cursor-1')
    expect(wrapper.text()).toContain('issue-c')
    // 仍有 cursor-2
    expect(
      wrapper.findAll('button').some((b) => b.text().includes('加载更多')),
    ).toBe(true)
    wrapper.unmount()
  })

  it('hides load more when nextCursor is null', async () => {
    fetchRuns.mockResolvedValue({ items: [item('only')], nextCursor: null })
    const wrapper = await mountList()
    expect(
      wrapper.findAll('button').some((b) => b.text().includes('加载更多')),
    ).toBe(false)
    wrapper.unmount()
  })

  it('first-page in flight then load-more: late first response merges and keeps b', async () => {
    type Page = { items: RunListItem[]; nextCursor: string | null }
    let resolveFirst!: (v: Page) => void
    let resolveMore!: (v: Page) => void

    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a'), item('b')],
        nextCursor: 'cursor-1',
      })
      // 手动首页刷新（慢）
      .mockImplementationOnce(
        () =>
          new Promise<Page>((r) => {
            resolveFirst = r
          }),
      )
      // 加载更多
      .mockImplementationOnce(
        () =>
          new Promise<Page>((r) => {
            resolveMore = r
          }),
      )

    const wrapper = await mountList()
    const headerRefresh = wrapper.find('header button.refresh-button')
    await headerRefresh.trigger('click')
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    // 首页还在飞时开始 load more → hasLoadedMore 立即 true
    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    await more!.trigger('click')
    expect(fetchRuns).toHaveBeenCalledTimes(3)

    // 首页先返回 [n,a]：因 hasLoadedMore，merge → [n,a,b]
    resolveFirst({
      items: [item('n', { issueTitle: 'issue-n' }), item('a')],
      nextCursor: 'ignored',
    })
    await flushPromises()
    expect(wrapper.text()).toContain('issue-n')
    expect(wrapper.text()).toContain('issue-a')
    expect(wrapper.text()).toContain('issue-b')

    // 更多页后到 [c,d]
    resolveMore({
      items: [item('c'), item('d')],
      nextCursor: null,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('issue-c')
    expect(wrapper.text()).toContain('issue-d')
    expect(wrapper.text()).toContain('issue-b')

    wrapper.unmount()
  })

  it('load-more in flight then first-page: first waits or merges without dropping b', async () => {
    type Page = { items: RunListItem[]; nextCursor: string | null }
    let resolveMore!: (v: Page) => void

    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a'), item('b')],
        nextCursor: 'cursor-1',
      })
      .mockImplementationOnce(
        () =>
          new Promise<Page>((r) => {
            resolveMore = r
          }),
      )
      .mockResolvedValueOnce({
        items: [item('n', { issueTitle: 'issue-n' }), item('a')],
        nextCursor: null,
      })

    const wrapper = await mountList()
    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    await more!.trigger('click')

    // more 进行中：手动刷新被跳过
    const headerRefresh = wrapper.find('header button.refresh-button')
    await headerRefresh.trigger('click')
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    // more 返回 [c]；b 仍在
    resolveMore({ items: [item('c')], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('issue-a')
    expect(wrapper.text()).toContain('issue-b')
    expect(wrapper.text()).toContain('issue-c')

    // more 结束后再刷首页 → merge
    await headerRefresh.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('issue-n')
    expect(wrapper.text()).toContain('issue-b')
    expect(wrapper.text()).toContain('issue-c')

    wrapper.unmount()
  })

  it('refresh after load-more keeps later pages via merge', async () => {
    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a', { state: 'QUEUED' }), item('b')],
        nextCursor: 'c1',
      })
      .mockResolvedValueOnce({
        items: [item('c')],
        nextCursor: null,
      })
      // quiet/manual refresh of first page: a updated, new n inserted
      .mockResolvedValueOnce({
        items: [
          item('n', { state: 'QUEUED', issueTitle: 'issue-n' }),
          item('a', { state: 'GENERATING', issueTitle: 'issue-a' }),
        ],
        nextCursor: 'ignored-after-merge',
      })

    const wrapper = await mountList()
    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    await more!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('issue-c')

    // 手动刷新（非 quiet）
    const refresh = wrapper
      .findAll('button')
      .find((b) => b.text() === '刷新' || b.text().includes('刷新'))
    expect(refresh).toBeDefined()
    await refresh!.trigger('click')
    await flushPromises()

    // 第一页合并：n, a(更新), b, c 保留
    const text = wrapper.text()
    expect(text).toContain('issue-n')
    expect(text).toContain('GENERATING')
    expect(text).toContain('issue-b')
    expect(text).toContain('issue-c')
    // 顺序：merge 后 n,a 在前，随后 b,c
    const nPos = text.indexOf('issue-n')
    const aPos = text.indexOf('issue-a')
    const cPos = text.indexOf('issue-c')
    expect(nPos).toBeLessThan(aPos)
    expect(aPos).toBeLessThan(cPos)
    wrapper.unmount()
  })

  it('quiet auto-refresh failure keeps table and shows warning', async () => {
    vi.useFakeTimers()
    fetchRuns
      .mockResolvedValueOnce({ items: [item('keep')], nextCursor: null })
      .mockRejectedValueOnce(new ApiError(503, 'temporary'))

    const wrapper = await mountList()
    expect(wrapper.text()).toContain('issue-keep')

    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()

    expect(wrapper.text()).toContain('状态可能已过期')
    expect(wrapper.text()).toContain('temporary')
    expect(wrapper.text()).toContain('issue-keep')
    expect(wrapper.find('table').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('加载失败')
    wrapper.unmount()
  })

  it('does not let an older first-page response overwrite a newer one', async () => {
    type Page = { items: RunListItem[]; nextCursor: string | null }
    const pending: Array<(v: Page) => void> = []

    fetchRuns
      .mockResolvedValueOnce({ items: [item('a', { state: 'QUEUED' })], nextCursor: null })
      .mockImplementation(
        () =>
          new Promise<Page>((resolve) => {
            pending.push(resolve)
          }),
      )

    const wrapper = await mountList()
    expect(wrapper.text()).toContain('QUEUED')
    expect(pending).toHaveLength(0)

    const headerRefresh = wrapper.find('header button.refresh-button')
    // 连续两次首页刷新：两次 in-flight，代次递增
    await headerRefresh.trigger('click')
    await headerRefresh.trigger('click')
    expect(pending).toHaveLength(2)

    // 较新的请求（第二次）先完成
    pending[1]!({
      items: [item('a', { state: 'GENERATING', issueTitle: 'issue-a' })],
      nextCursor: null,
    })
    await flushPromises()
    expect(wrapper.find('.state-link').text()).toBe('GENERATING')

    // 较旧的请求后完成，不得覆盖
    pending[0]!({
      items: [item('a', { state: 'QUEUED', issueTitle: 'issue-a' })],
      nextCursor: null,
    })
    await flushPromises()
    expect(wrapper.find('.state-link').text()).toBe('GENERATING')

    wrapper.unmount()
  })

  it('reschedules quiet refresh when skipped because load-more is in flight', async () => {
    vi.useFakeTimers()
    type Page = { items: RunListItem[]; nextCursor: string | null }
    let resolveMore!: (v: Page) => void

    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a'), item('b')],
        nextCursor: 'c1',
      })
      .mockImplementationOnce(
        () =>
          new Promise<Page>((r) => {
            resolveMore = r
          }),
      )
      .mockResolvedValue({
        items: [item('a', { state: 'GENERATING' }), item('b')],
        nextCursor: 'c1',
      })

    const wrapper = await mountList()
    // 首载完成后已 schedule 15s quiet
    expect(fetchRuns).toHaveBeenCalledTimes(1)

    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    await more!.trigger('click')
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    // 定时器在 loadMore 期间触发：quiet 跳过但应重新挂链
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    // more 仍在飞；再过 15s 仍应再跳过并继续挂链（不能停摆）
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    resolveMore({ items: [item('c')], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('issue-c')

    // more 结束后 finally 也会 schedule；再过 15s 应恰好再发 1 次 quiet 首页（无重复定时器）
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('GENERATING')

    wrapper.unmount()
  })

  it('skips quiet refresh while a first-page request is in flight', async () => {
    vi.useFakeTimers()
    type Page = { items: RunListItem[]; nextCursor: string | null }
    let resolveSlow!: (v: Page) => void

    fetchRuns
      .mockResolvedValueOnce({ items: [item('a', { state: 'QUEUED' })], nextCursor: null })
      .mockImplementationOnce(
        () =>
          new Promise<Page>((r) => {
            resolveSlow = r
          }),
      )
      .mockResolvedValue({ items: [item('a', { state: 'REPLAYING' })], nextCursor: null })

    const wrapper = await mountList()
    expect(fetchRuns).toHaveBeenCalledTimes(1)

    // 链式 15s 触发 quiet，挂起
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    // 再过 15s：quiet 应跳过（in-flight），不叠加第三请求
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(2)

    resolveSlow({ items: [item('a', { state: 'GENERATING' })], nextCursor: null })
    await flushPromises()
    expect(wrapper.text()).toContain('GENERATING')

    // 结束后才 schedule 下一次
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(fetchRuns).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('REPLAYING')

    wrapper.unmount()
  })

  it('load-more failure keeps existing rows and shows warning', async () => {
    fetchRuns
      .mockResolvedValueOnce({
        items: [item('a')],
        nextCursor: 'c1',
      })
      .mockRejectedValueOnce(new ApiError(500, 'page2 failed'))

    const wrapper = await mountList()
    const more = wrapper
      .findAll('button')
      .find((b) => b.text().includes('加载更多'))
    await more!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('issue-a')
    expect(wrapper.text()).toContain('状态可能已过期')
    expect(wrapper.text()).toContain('page2 failed')
    wrapper.unmount()
  })

  it('clears refresh interval on unmount', async () => {
    vi.useFakeTimers()
    fetchRuns.mockResolvedValue({ items: [item('x')], nextCursor: null })
    const wrapper = await mountList()
    expect(fetchRuns).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(45_000)
    expect(fetchRuns).toHaveBeenCalledTimes(1)
  })
})
