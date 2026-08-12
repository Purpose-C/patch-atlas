import { createRouter, createWebHistory } from 'vue-router'
import RunsListView from './views/RunsListView.vue'
import RunDetailView from './views/RunDetailView.vue'
import NotFoundView from './views/NotFoundView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/runs' },
    { path: '/runs', name: 'runs', component: RunsListView },
    { path: '/runs/:runId', name: 'run-detail', component: RunDetailView, props: true },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})
