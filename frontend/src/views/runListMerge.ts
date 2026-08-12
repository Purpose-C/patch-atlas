import type { RunListItem } from '../api/runs'

/** 合并第一页：更新已有 run、插入新 run，保留后续分页已加载项 */
export function mergeFirstPage(existing: RunListItem[], firstPage: RunListItem[]): RunListItem[] {
  const firstIds = new Set(firstPage.map((i) => i.runId))
  const mergedFront = firstPage.slice()
  const tail = existing.filter((i) => !firstIds.has(i.runId))
  return [...mergedFront, ...tail]
}
