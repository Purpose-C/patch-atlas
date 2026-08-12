<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { fetchRuns, type RunListItem } from '../api/runs'
import { mergeFirstPage } from './runListMerge'

const REFRESH_MS = 15_000

const items = ref<RunListItem[]>([])
const nextCursor = ref<string | null>(null)
/** 是否已加载过超出第一页的数据；刷新首页时需保留这些历史项 */
const hasLoadedMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref<string>()
/** 刷新失败且已有表格数据时只提示，不拆掉表格 */
const refreshWarning = ref<string>()

/** 首页刷新代次：只应用最新一次响应，防止乱序覆盖 */
let firstGen = 0
/** 是否有首页请求在飞（quiet 轮询跳过，避免重叠） */
let firstInFlight = false
/** 链式 setTimeout，不用 setInterval */
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let alive = true

function clearRefreshTimer() {
  if (refreshTimer !== undefined) {
    clearTimeout(refreshTimer)
    refreshTimer = undefined
  }
}

/** 仅在当前首页请求结束后安排下一次自动刷新 */
function scheduleRefresh() {
  clearRefreshTimer()
  if (!alive) return
  if (document.visibilityState !== 'visible') return
  refreshTimer = setTimeout(() => {
    void loadFirst({ quiet: true })
  }, REFRESH_MS)
}

async function loadFirst(options: { quiet?: boolean } = {}) {
  const quiet = options.quiet === true && items.value.length > 0
  // 自动刷新：首页在飞或「加载更多」在飞时跳过，避免与分页竞态
  if (quiet && firstInFlight) {
    // 在飞的 loadFirst 会在 finally 里 scheduleRefresh
    return
  }
  if (quiet && loadingMore.value) {
    // 定时器已被消费且本轮不发请求：必须重新挂链，否则自动刷新永久停摆
    scheduleRefresh()
    return
  }
  // 手动刷新也不与 loadMore 并行写列表（等更多页落地后再刷）
  if (!quiet && loadingMore.value) {
    return
  }

  const gen = ++firstGen
  firstInFlight = true
  if (!quiet) {
    loading.value = true
    error.value = undefined
  }
  refreshWarning.value = undefined

  try {
    const page = await fetchRuns(20)
    if (gen !== firstGen) {
      return
    }
    // 应用时再读 hasLoadedMore：loadMore 一开始就会置 true，避免并发整表替换丢后续页
    if (hasLoadedMore.value && items.value.length > 0) {
      items.value = mergeFirstPage(items.value, page.items)
      // 不重置 nextCursor：后续页仍可通过「加载更多」继续（若 cursor 失效会在 loadMore 报错）
    } else {
      items.value = page.items
      nextCursor.value = page.nextCursor
    }
    error.value = undefined
    refreshWarning.value = undefined
  } catch (e) {
    if (gen !== firstGen) {
      return
    }
    const msg = e instanceof Error ? e.message : '加载失败'
    if (items.value.length > 0) {
      refreshWarning.value = msg
    } else {
      error.value = msg
    }
  } finally {
    if (gen === firstGen) {
      firstInFlight = false
      loading.value = false
      scheduleRefresh()
    }
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  // 在 await 之前标记：并发首页刷新必须走 merge，不能整表替换丢掉 b
  hasLoadedMore.value = true
  refreshWarning.value = undefined
  const cursor = nextCursor.value
  try {
    const page = await fetchRuns(20, cursor)
    const seen = new Set(items.value.map((i) => i.runId))
    for (const item of page.items) {
      if (!seen.has(item.runId)) {
        items.value.push(item)
        seen.add(item.runId)
      }
    }
    nextCursor.value = page.nextCursor
  } catch (e) {
    refreshWarning.value = e instanceof Error ? e.message : '加载更多失败'
  } finally {
    loadingMore.value = false
    // 分页结束后若无首页在飞，确保自动刷新链路仍在
    if (!firstInFlight) {
      scheduleRefresh()
    }
  }
}

function formatTime(iso: string | null) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN')
}

function onVisibility() {
  if (document.visibilityState === 'hidden') {
    clearRefreshTimer()
    return
  }
  // 恢复可见：若无在飞首页请求则拉一次，否则等在飞结束再 schedule
  if (!firstInFlight) {
    void loadFirst({ quiet: items.value.length > 0 })
  }
}

onMounted(() => {
  alive = true
  document.addEventListener('visibilitychange', onVisibility)
  void loadFirst()
})

onUnmounted(() => {
  alive = false
  clearRefreshTimer()
  // 抬高代次，丢弃卸载后返回的响应
  firstGen++
  firstInFlight = false
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <main class="page-shell">
    <header class="masthead">
      <div>
        <p class="eyebrow">VERIFICATION RUNS</p>
        <h1>运行列表</h1>
        <p class="lede">持久化历史状态与裁决。创建请使用 REST API。</p>
      </div>
      <!-- 不在有数据时 disable：允许手动再刷抬高代次，避免与慢请求乱序 -->
      <button
        class="refresh-button"
        type="button"
        :disabled="loading && items.length === 0"
        @click="loadFirst()"
      >
        {{ loading && items.length === 0 ? '加载中…' : loading ? '刷新中…' : '刷新' }}
      </button>
    </header>

    <section class="status-panel" aria-live="polite">
      <p v-if="loading && items.length === 0" class="status-copy">正在加载运行…</p>

      <div v-else-if="error && items.length === 0" class="status-error" role="alert">
        <p>加载失败</p>
        <small>{{ error }}</small>
        <button class="refresh-button" type="button" style="margin-top: 1rem" @click="loadFirst()">
          重试
        </button>
      </div>

      <template v-else>
        <div v-if="refreshWarning" class="status-error" role="status" style="margin-bottom: 1rem">
          <p>状态可能已过期</p>
          <small>{{ refreshWarning }}</small>
        </div>

        <p v-if="items.length === 0" class="status-copy">
          暂无运行。通过 <code>POST /api/runs</code> 创建。
        </p>

        <div v-else class="table-wrap">
          <table class="runs-table">
            <thead>
              <tr>
                <th>状态</th>
                <th>模式</th>
                <th>Issue</th>
                <th>仓库</th>
                <th>结果</th>
                <th>创建</th>
                <th>更新</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in items" :key="row.runId">
                <td>
                  <RouterLink :to="`/runs/${row.runId}`" class="state-link">{{ row.state }}</RouterLink>
                </td>
                <td>{{ row.mode }}</td>
                <td class="clip">{{ row.issueTitle }}</td>
                <td class="clip">{{ row.repositoryUrl }}</td>
                <td>
                  <span v-if="row.verdict">{{ row.verdict }}</span>
                  <span v-else-if="row.failureCategory">{{ row.failureCategory }}</span>
                  <span v-else>—</span>
                </td>
                <td>{{ formatTime(row.createdAt) }}</td>
                <td>{{ formatTime(row.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
          <button
            v-if="nextCursor"
            class="refresh-button"
            type="button"
            :disabled="loadingMore"
            style="margin-top: 1rem"
            @click="loadMore"
          >
            {{ loadingMore ? '加载中…' : '加载更多' }}
          </button>
        </div>
      </template>
    </section>
  </main>
</template>
