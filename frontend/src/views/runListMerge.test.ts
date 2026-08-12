import { describe, expect, it } from 'vitest'
import type { RunListItem } from '../api/runs'
import { mergeFirstPage } from './runListMerge'

function item(id: string, state: string, updatedAt: string): RunListItem {
  return {
    runId: id,
    mode: 'LIVE',
    state: state as RunListItem['state'],
    issueTitle: id,
    repositoryUrl: 'https://github.com/ex/repo.git',
    verdict: null,
    failureCategory: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt,
    completedAt: null,
  }
}

describe('mergeFirstPage', () => {
  it('updates first page and keeps later pages', () => {
    const existing = [
      item('a', 'QUEUED', 't1'),
      item('b', 'QUEUED', 't1'),
      item('c', 'COMPLETED', 't0'),
    ]
    const first = [item('n', 'QUEUED', 't2'), item('a', 'GENERATING', 't2')]
    const merged = mergeFirstPage(existing, first)
    expect(merged.map((i) => i.runId)).toEqual(['n', 'a', 'b', 'c'])
    expect(merged.find((i) => i.runId === 'a')?.state).toBe('GENERATING')
    expect(merged.find((i) => i.runId === 'c')?.state).toBe('COMPLETED')
  })
})
