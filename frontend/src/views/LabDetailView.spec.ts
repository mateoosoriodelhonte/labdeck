import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  cancelAssignmentTest,
  loadLab,
  loadTestHistory,
  loadTestStatus,
  startAssignmentTest,
  type LabDetail,
  type TestRun,
} from '../api/lab-api'
import LabDetailView from './LabDetailView.vue'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ params: { labId: 'lab-1' } }),
}))

vi.mock('../api/lab-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/lab-api')>()),
  cancelAssignmentTest: vi.fn(),
  loadLab: vi.fn(),
  loadTestHistory: vi.fn(),
  loadTestStatus: vi.fn(),
  startAssignmentTest: vi.fn(),
}))

const lab: LabDetail = {
  id: 'lab-1',
  name: 'Course lab',
  state: 'RUNNING',
  revision: 2,
  plan: {
    manifestSha256: `sha256:${'a'.repeat(64)}`,
    tests: { service: 'app', command: ['true'], timeoutSeconds: 5 },
  },
}

function activeRun(): TestRun {
  return {
    id: 'test-1',
    labRevision: 2,
    service: 'app',
    testPlanSha256: `sha256:${'b'.repeat(64)}`,
    status: 'RUNNING',
    durationMillis: 0,
    exitCode: null,
    stdout: '',
    stderr: '',
    stdoutTruncated: false,
    stderrTruncated: false,
    canCancel: true,
  }
}

describe('LabDetailView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(loadLab).mockResolvedValue(lab)
    vi.mocked(loadTestHistory).mockResolvedValue({ runs: [], activeRun: null })
    vi.mocked(startAssignmentTest).mockResolvedValue(activeRun())
    vi.mocked(cancelAssignmentTest).mockResolvedValue({
      ...activeRun(),
      status: 'CANCELLING',
      canCancel: false,
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('keeps a live run reserved and retries after a status request fails', async () => {
    vi.mocked(loadTestStatus)
      .mockRejectedValueOnce(new Error('Temporary network error.'))
      .mockResolvedValueOnce({
        ...activeRun(),
        status: 'PASSED',
        outcomeReason: 'EXIT_ZERO',
        canCancel: false,
      })
    const wrapper = mount(LabDetailView, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()

    await wrapper.get('button.button--primary').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.text()).toContain('Temporary network error.')
    expect(wrapper.text()).toContain('Test is running')
    expect(wrapper.get('button.button--primary').attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(loadTestStatus).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('Test is running')
  })

  it('restores an active run after reload and keeps retrying past five failures', async () => {
    vi.mocked(loadTestHistory)
      .mockResolvedValueOnce({ runs: [], activeRun: activeRun() })
      .mockResolvedValue({ runs: [], activeRun: null })
    for (let failure = 0; failure < 6; failure += 1) {
      vi.mocked(loadTestStatus).mockRejectedValueOnce(new Error('Temporary network error.'))
    }
    vi.mocked(loadTestStatus).mockResolvedValueOnce({
      ...activeRun(),
      status: 'PASSED',
      outcomeReason: 'EXIT_ZERO',
      canCancel: false,
    })

    const wrapper = mount(LabDetailView, {
      global: { stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Test is running')
    expect(wrapper.get('button.button--primary').attributes('disabled')).toBeDefined()

    for (let attempt = 0; attempt < 7; attempt += 1) {
      await vi.advanceTimersToNextTimerAsync()
      await flushPromises()
    }

    expect(loadTestStatus).toHaveBeenCalledTimes(7)
    expect(wrapper.text()).not.toContain('Temporary network error.')
    expect(wrapper.text()).not.toContain('Test is running')
  })
})
