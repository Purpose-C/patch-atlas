<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <main class="page-shell">
    <header class="masthead">
      <div>
        <p class="eyebrow">404</p>
        <h1>未找到页面</h1>
        <p class="lede"><RouterLink to="/runs">返回运行列表</RouterLink></p>
      </div>
    </header>
  </main>
</template>
