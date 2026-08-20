<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  fetchRun,
  isTerminalState,
  type Locating,
  type LocatingStep,
  type RecordedUsageStatus,
  type RunDetail,
} from '../api/runs'

const LOCATING_DETAIL_KEYS = [
  'pattern',
  'pathGlob',
  'path',
  'query',
  'entity',
  'errorType',
  'message',
  'rule',
  'reason',
  'tool',
  'limit',
  'hits',
  'entries',
  'startLine',
  'lines',
  'bytes',
  'paths',
  'submittedNotRead',
  'readNotSubmitted',
  'used',
  'maxCalls',
  'neighbors',
  'repeatOf',
  'durationMs',
  'truncated',
  'rejected',
  'accepted',
  'cacheHit',
  'edgeKinds',
  'confidences',
] as const

const POLL_MS = 3000

const props = defineProps<{ runId: string }>()

const detail = ref<RunDetail>()
const loading = ref(true)
const error = ref<string>()
const staleWarning = ref(false)

/** 仅用于 setTimeout 链式轮询，不用 setInterval */
let pollTimer: ReturnType<typeof setTimeout> | undefined
/** 进行中的请求；非空表示 in-flight，quiet 轮询将跳过 */
let abort: AbortController | undefined

function clearPollTimer() {
  if (pollTimer !== undefined) {
    clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

/** 停止后续轮询；默认中止进行中的请求（卸载/隐藏/切换 run）。 */
function stopPoll(options: { abortRequest?: boolean } = {}) {
  const abortRequest = options.abortRequest !== false
  clearPollTimer()
  if (abortRequest) {
    abort?.abort()
    abort = undefined
  }
}

function shouldContinuePolling(): boolean {
  if (document.visibilityState !== 'visible') return false
  if (!detail.value) return false
  return !isTerminalState(detail.value.state)
}

/** 仅在当前请求结束后安排下一次，避免慢请求被 interval 永久 abort。 */
function schedulePoll() {
  clearPollTimer()
  if (!shouldContinuePolling()) return
  pollTimer = setTimeout(() => {
    void load({ quiet: true })
  }, POLL_MS)
}

async function load(options: { quiet?: boolean } = {}) {
  const quiet = options.quiet === true

  // 进行中：quiet 跳过本轮；手动/首载取消旧请求
  if (abort) {
    if (quiet) return
    abort.abort()
  }

  const controller = new AbortController()
  abort = controller
  if (!quiet) loading.value = true

  try {
    const next = await fetchRun(props.runId, controller.signal)
    if (abort !== controller) return

    detail.value = next
    error.value = undefined
    staleWarning.value = false

    if (isTerminalState(next.state)) {
      stopPoll({ abortRequest: false })
      abort = undefined
    } else {
      schedulePoll()
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
    if (abort !== controller) return

    if (detail.value) {
      staleWarning.value = true
      error.value = e instanceof Error ? e.message : '刷新失败'
      if (shouldContinuePolling()) schedulePoll()
    } else {
      error.value = e instanceof Error ? e.message : '加载失败'
    }
  } finally {
    if (abort === controller) {
      abort = undefined
      loading.value = false
    } else if (!quiet) {
      // 被更新请求取代时，仅清本请求的 loading 语义由新请求接管
      loading.value = false
    }
  }
}

function onVisibility() {
  if (document.visibilityState === 'hidden') {
    stopPoll()
    return
  }
  // 恢复可见：无数据（首载被中止）或非终态都重新拉取
  if (!detail.value) {
    void load()
  } else if (!isTerminalState(detail.value.state)) {
    void load({ quiet: true })
  }
}

watch(
  () => props.runId,
  () => {
    stopPoll()
    detail.value = undefined
    error.value = undefined
    staleWarning.value = false
    void load()
  },
)

onMounted(() => {
  void load()
  document.addEventListener('visibilitychange', onVisibility)
})

onUnmounted(() => {
  stopPoll()
  document.removeEventListener('visibilitychange', onVisibility)
})

function usageStatusLabel(status: RecordedUsageStatus): string {
  switch (status) {
    case 'TRACKING_UNAVAILABLE':
      return '用量跟踪不可用'
    case 'NONE_RECORDED':
      return '未记录用量'
    case 'PARTIALLY_RECORDED':
      return '部分尝试已记录用量'
    case 'RECORDED_FOR_ALL_ATTEMPTS':
      return '全部尝试已记录用量'
  }
}

function toolCallLabel(locating: Locating): string {
  if (!locating.toolCallsApplicable) return '—'
  return String(locating.toolCallCount)
}

function cacheHitLabel(hit: boolean | null): string {
  if (hit == null) return '未知'
  return hit ? '命中' : '未命中'
}

function kindCountsText(counts: Record<string, number>): string {
  const parts = Object.entries(counts).map(([kind, n]) => `${kind} ${n}`)
  return parts.length === 0 ? '—' : parts.join(' · ')
}

function locatingDetailText(step: LocatingStep): string {
  const parts: string[] = []
  for (const key of LOCATING_DETAIL_KEYS) {
    const value = step.detail[key]
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) {
      parts.push(`${key}=${value.map(String).join(',')}`)
    } else {
      parts.push(`${key}=${String(value)}`)
    }
  }
  return parts.join(' · ')
}
</script>

<template>
  <main class="page-shell">
    <header class="masthead">
      <div>
        <p class="eyebrow">RUN DETAIL</p>
        <h1 class="clip">{{ detail?.input.issueTitle ?? '运行详情' }}</h1>
        <p class="lede">
          <RouterLink to="/runs">← 返回列表</RouterLink>
          · {{ props.runId }}
        </p>
      </div>
      <button class="refresh-button" type="button" :disabled="loading" @click="load()">
        {{ loading ? '加载中…' : '刷新' }}
      </button>
    </header>

    <div v-if="staleWarning" class="status-error" role="status" aria-live="polite">
      <p>状态可能已过期</p>
      <small>{{ error }}</small>
    </div>

    <section v-if="loading && !detail" class="status-panel">
      <p class="status-copy">加载中…</p>
    </section>
    <section v-else-if="error && !detail" class="status-panel">
      <div class="status-error" role="alert">
        <p>无法加载</p>
        <small>{{ error }}</small>
      </div>
    </section>

    <template v-else-if="detail">
      <section class="status-panel" aria-live="polite">
        <h2>阶段</h2>
        <p class="status-copy">
          <strong>{{ detail.state }}</strong>
          <span v-if="!isTerminalState(detail.state)"> · 非终态，约每 3 秒刷新</span>
        </p>
        <dl class="status-grid">
          <div>
            <dt>模式</dt>
            <dd>{{ detail.mode }}</dd>
          </div>
          <div>
            <dt>Java / 网络</dt>
            <dd>{{ detail.executionPolicy.javaVersion }} / {{ detail.executionPolicy.networkMode }}</dd>
          </div>
          <div>
            <dt>创建</dt>
            <dd class="mono">{{ detail.createdAt }}</dd>
          </div>
          <div>
            <dt>更新</dt>
            <dd class="mono">{{ detail.updatedAt }}</dd>
          </div>
          <div v-if="detail.completedAt">
            <dt>完成</dt>
            <dd class="mono">{{ detail.completedAt }}</dd>
          </div>
          <div>
            <dt>生成次数</dt>
            <dd>
              {{ detail.generation.attemptCount }}
              <small class="muted">（预验证非正式 Replay 证据）</small>
            </dd>
          </div>
          <div>
            <dt>模型</dt>
            <dd>
              {{ detail.generation.modelProvider ?? '—' }} /
              {{ detail.generation.modelName ?? '—' }}
            </dd>
          </div>
          <div>
            <dt>Token</dt>
            <dd>
              in={{ detail.generation.inputTokens }} out={{ detail.generation.outputTokens }} total={{
                detail.generation.totalTokens
              }}
            </dd>
          </div>
          <div>
            <dt>用量记录</dt>
            <dd>{{ usageStatusLabel(detail.generation.usageStatus) }}</dd>
          </div>
          <div>
            <dt>Estimated cost (recorded usage)</dt>
            <dd v-if="detail.generation.estimatedCost">
              {{ detail.generation.estimatedCost.amount }}
              {{ detail.generation.estimatedCost.currency }}
              （已记录用量的估算下界）
              · Pricing reference {{ detail.generation.estimatedCost.pricingEffectiveDate }} ·
              {{ detail.generation.estimatedCost.pricingSource }}
            </dd>
            <dd v-else>不可用</dd>
          </div>
        </dl>
      </section>

      <section class="status-panel">
        <h2>输入摘要</h2>
        <dl class="status-grid">
          <div>
            <dt>仓库</dt>
            <dd class="clip">{{ detail.input.repositoryUrl }}</dd>
          </div>
          <div v-if="detail.input.issueUrl">
            <dt>Issue URL</dt>
            <dd class="clip">{{ detail.input.issueUrl }}</dd>
          </div>
          <div>
            <dt>模块</dt>
            <dd class="mono">{{ detail.input.modulePath === '' ? '(root)' : detail.input.modulePath }}</dd>
          </div>
          <div>
            <dt>Buggy</dt>
            <dd class="mono">{{ detail.input.buggyRevision }}</dd>
          </div>
          <div v-if="detail.input.fixedRevision">
            <dt>Fixed</dt>
            <dd class="mono">{{ detail.input.fixedRevision }}</dd>
          </div>
        </dl>
        <h3>Issue 正文</h3>
        <pre class="plain">{{ detail.input.issueBody }}</pre>
      </section>

      <section class="status-panel" aria-live="polite">
        <h2>定位</h2>
        <p v-if="!detail.locating.recorded" class="status-copy">无定位记录</p>
        <template v-else>
          <p v-if="detail.locating.truncated" class="status-error" role="status">
            轨迹已截断，最多显示 {{ detail.locating.stepLimit }} 行
          </p>
          <dl class="status-grid">
            <div>
              <dt>来源</dt>
              <dd>{{ detail.locating.contextOrigin ?? '—' }}</dd>
            </div>
            <div>
              <dt>工具调用</dt>
              <dd>{{ toolCallLabel(detail.locating) }}</dd>
            </div>
            <div>
              <dt>步骤种类</dt>
              <dd>{{ kindCountsText(detail.locating.stepKindCounts) }}</dd>
            </div>
            <div>
              <dt>ERROR</dt>
              <dd>{{ detail.locating.errorCount }}</dd>
            </div>
            <div>
              <dt>用量记录</dt>
              <dd>{{ usageStatusLabel(detail.locating.usageStatus) }}</dd>
            </div>
            <div>
              <dt>Token</dt>
              <dd v-if="detail.locating.totalTokens != null">
                in={{ detail.locating.inputTokens }} out={{ detail.locating.outputTokens }} total={{
                  detail.locating.totalTokens
                }}
              </dd>
              <dd v-else>—</dd>
            </div>
            <div v-if="detail.locating.graphBuild">
              <dt>建图</dt>
              <dd>
                {{
                  detail.locating.graphBuild.durationMs != null
                    ? detail.locating.graphBuild.durationMs + ' ms'
                    : '—'
                }}
                · 缓存 {{ cacheHitLabel(detail.locating.graphBuild.cacheHit) }}
              </dd>
            </div>
          </dl>
          <div v-if="detail.locating.budgetEvents.length > 0" class="status-error" role="status">
            <p>预算事件</p>
            <small v-for="event in detail.locating.budgetEvents" :key="'budget-' + event.seq">
              {{ event.kind }} · {{ event.limit ?? '—' }} · used={{ event.used ?? '—' }} / max={{
                event.maxCalls ?? '—'
              }}
            </small>
          </div>
          <h3>逐步轨迹</h3>
          <p v-if="detail.locating.steps.length === 0" class="status-copy">无步骤。</p>
          <div v-else class="table-wrap">
            <table class="runs-table">
              <thead>
                <tr>
                  <th>seq</th>
                  <th>kind</th>
                  <th>subject</th>
                  <th>reason</th>
                  <th>outcome</th>
                  <th>detail</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(step, idx) in detail.locating.steps"
                  :key="step.seq + '-' + idx"
                  :class="{ 'status-error': step.outcome === 'ERROR' }"
                >
                  <td class="mono">{{ step.seq }}</td>
                  <td>{{ step.kind }}</td>
                  <td class="mono">{{ step.subject }}</td>
                  <td>{{ step.reason }}</td>
                  <td>{{ step.outcome }}</td>
                  <td>{{ locatingDetailText(step) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <template v-if="detail.locating.selectedPaths.length > 0">
            <h3>最终选中</h3>
            <p v-for="(path, idx) in detail.locating.selectedPaths" :key="'sel-' + idx" class="mono">
              {{ path }}
            </p>
          </template>
        </template>
      </section>

      <section v-if="detail.result" class="status-panel">
        <h2>终态结果</h2>
        <p v-if="detail.result.verdict" class="status-copy">Verdict: {{ detail.result.verdict }}</p>
        <p v-if="detail.result.failureCategory" class="status-copy">
          {{ detail.result.failureStage }} / {{ detail.result.failureCategory }}:
          {{ detail.result.failureSummary }}
        </p>
      </section>

      <section v-if="detail.candidate" class="status-panel">
        <h2>Candidate Test Patch</h2>
        <p class="status-copy">
          {{ detail.candidate.targetClass }}#{{ detail.candidate.targetMethod }}
        </p>
        <pre class="plain"><code>{{ detail.candidate.patchText }}</code></pre>
      </section>

      <section class="status-panel">
        <h2>正式 Replay Attempts</h2>
        <p v-if="detail.attempts.length === 0" class="status-copy">尚无正式 Attempt。</p>
        <div v-for="(a, idx) in detail.attempts" :key="idx" class="attempt-card">
          <p class="status-copy">
            <strong
              >R{{ a.replayRound }} {{ a.side }} #{{ a.attemptOrdinal }} · {{ a.phase }}</strong
            >
            <span v-if="a.outcome"> · outcome={{ a.outcome }}</span>
            · evidence={{ a.targetEvidence }}
            · schema=v{{ a.evidenceSchemaVersion }}
          </p>
          <dl class="status-grid">
            <div>
              <dt>Sandbox</dt>
              <dd>{{ a.sandboxStatus ?? '—' }}</dd>
            </div>
            <div>
              <dt>Exit / 耗时 / 超时</dt>
              <dd>
                {{ a.exitCode ?? '—' }} /
                {{ a.elapsedMs != null ? a.elapsedMs + ' ms' : '—' }} /
                {{ a.timedOut == null ? '—' : a.timedOut ? 'yes' : 'no' }}
              </dd>
            </div>
            <div>
              <dt>Image</dt>
              <dd class="clip">{{ a.image ?? '—' }}</dd>
            </div>
            <div>
              <dt>Network</dt>
              <dd>{{ a.networkMode ?? '—' }}</dd>
            </div>
          </dl>
          <p v-if="a.commandJson" class="status-copy">
            <span class="muted">command</span>
          </p>
          <pre v-if="a.commandJson" class="plain">{{ a.commandJson }}</pre>
          <p v-if="a.limitsJson" class="status-copy">
            <span class="muted">limits</span>
          </p>
          <pre v-if="a.limitsJson" class="plain">{{ a.limitsJson }}</pre>
          <template v-if="a.targetTestCase">
            <p class="status-copy">
              Target:
              {{ a.targetTestCase.className }}#{{ a.targetTestCase.methodName }} ·
              {{ a.targetTestCase.status }}
              <template v-if="a.targetTestCase.elapsedMs != null">
                · {{ a.targetTestCase.elapsedMs }} ms
              </template>
            </p>
            <p v-if="a.targetTestCase.exceptionType" class="status-copy">
              exceptionType: {{ a.targetTestCase.exceptionType }}
            </p>
            <pre v-if="a.targetTestCase.message" class="plain">{{ a.targetTestCase.message }}</pre>
          </template>
          <pre v-if="a.diagnostic" class="plain">{{ a.diagnostic }}</pre>
          <pre v-if="a.logSummary" class="plain">{{ a.logSummary }}</pre>
        </div>
      </section>
    </template>
  </main>
</template>
