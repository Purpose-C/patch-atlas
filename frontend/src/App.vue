<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchPlatformStatus, type PlatformStatus } from './api/status'

const status = ref<PlatformStatus>()
const error = ref<string>()
const loading = ref(true)

async function refreshStatus() {
  loading.value = true
  error.value = undefined

  try {
    status.value = await fetchPlatformStatus()
  } catch (caughtError) {
    error.value = caughtError instanceof Error ? caughtError.message : '无法连接 PatchAtlas 服务'
  } finally {
    loading.value = false
  }
}

onMounted(refreshStatus)
</script>

<template>
  <main class="page-shell">
    <header class="masthead">
      <div>
        <p class="eyebrow">JAVA BUG ARCHAEOLOGIST</p>
        <h1>PatchAtlas</h1>
        <p class="lede">从真实代码证据走向可验证的 Bug Replay。</p>
      </div>
      <button class="refresh-button" type="button" :disabled="loading" @click="refreshStatus">
        {{ loading ? '正在连接…' : '刷新状态' }}
      </button>
    </header>

    <section class="status-panel" aria-labelledby="platform-status-title" aria-live="polite">
      <h2 id="platform-status-title">平台状态</h2>

      <p v-if="loading" class="status-copy">正在检查 Spring Boot 服务。</p>

      <div v-else-if="error" class="status-error" role="alert">
        <p>服务暂时不可用</p>
        <small>{{ error }}</small>
      </div>

      <dl v-else-if="status" class="status-grid">
        <div>
          <dt>服务</dt>
          <dd>{{ status.name }}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd><span class="status-badge">{{ status.status }}</span></dd>
        </div>
        <div>
          <dt>Java</dt>
          <dd>{{ status.runtime.javaVersion }}</dd>
        </div>
        <div>
          <dt>服务端时间</dt>
          <dd>{{ new Date(status.runtime.timestamp).toLocaleString('zh-CN') }}</dd>
        </div>
      </dl>
    </section>

    <section class="next-panel" aria-labelledby="next-title">
      <h2 id="next-title">下一步</h2>
      <p>先完成状态 API 的版本字段练习；随后我们会添加持久化分析任务，而不是直接接入 Agent。</p>
    </section>
  </main>
</template>
