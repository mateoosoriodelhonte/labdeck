import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AssignmentTestsPanel from './AssignmentTestsPanel.vue'
import type { LabDetail, TestRun } from '../api/lab-api'

const lab: LabDetail = {
  id: 'lab-1',
  name: 'Course lab',
  state: 'RUNNING',
  revision: 2,
  plan: {
    manifestSha256: `sha256:${'a'.repeat(64)}`,
    tests: { service: 'app', command: ['node', 'test.js', 'a;whoami'], timeoutSeconds: 30 },
  },
}

function result(overrides: Partial<TestRun> = {}): TestRun {
  return {
    id: 'test-1',
    labRevision: 2,
    service: 'app',
    testPlanSha256: `sha256:${'b'.repeat(64)}`,
    recordedAt: '2026-08-27T17:00:00Z',
    status: 'FAILED',
    outcomeReason: 'NON_ZERO_EXIT',
    durationMillis: 125,
    exitCode: 3,
    stdout: 'student output',
    stderr: 'failure detail',
    stdoutTruncated: true,
    stderrTruncated: false,
    canCancel: false,
    ...overrides,
  }
}

describe('AssignmentTestsPanel', () => {
  it('shows the immutable manifest plan and emits a run action', async () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: { lab, history: [], activeRun: null, busy: false },
    })

    expect(wrapper.text()).toContain('It is not an official grade')
    expect(wrapper.text()).toContain('a;whoami')
    expect(wrapper.text()).toContain('30 seconds')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('run')).toHaveLength(1)
  })

  it('announces cancellation and explains that it stops the lab', () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: {
        lab,
        history: [],
        activeRun: result({ status: 'CANCELLING', canCancel: false }),
        busy: false,
      },
    })

    expect(wrapper.text()).toContain('Stopping test and lab')
    expect(wrapper.text()).toContain('Restart the lab after cancellation')
    expect(wrapper.findAll('button')[1].attributes('disabled')).toBeDefined()
  })

  it('renders bounded terminal output, provenance, and truncation', () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: { lab, history: [result()], activeRun: null, busy: false },
    })

    expect(wrapper.text()).toContain('non zero exit')
    expect(wrapper.text()).toContain('Exit 3')
    expect(wrapper.text()).toContain('Revision 2 · Service app')
    expect(wrapper.text()).toContain(`sha256:${'b'.repeat(64)}`)
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-08-27T17:00:00Z')
    expect(wrapper.text()).toContain('Standard output (truncated)')
    expect(wrapper.get('pre').text()).toBe('student output')
  })

  it('shows a reserved result-unavailable state without offering another run', () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: {
        lab,
        history: [],
        activeRun: result({ status: 'ERROR', outcomeReason: 'RESULT_UNAVAILABLE' }),
        busy: false,
      },
    })

    expect(wrapper.text()).toContain('Test result could not be saved')
    expect(wrapper.text()).toContain('Restart LabDeck')
    expect(wrapper.get('button.button--primary').attributes('disabled')).toBeDefined()
  })

  it('shows a persistence-pending result as saving', () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: {
        lab,
        history: [],
        activeRun: result({ status: 'PERSISTING', outcomeReason: null }),
        busy: false,
      },
    })

    expect(wrapper.text()).toContain('Saving test result')
    expect(wrapper.text()).toContain('writing this result to local history')
    expect(wrapper.text()).not.toContain('Test result could not be saved')
  })

  it('shows truncation when a bounded stream has no stored text', () => {
    const wrapper = mount(AssignmentTestsPanel, {
      props: {
        lab,
        history: [result({ stdout: '', stderr: '', stdoutTruncated: true })],
        activeRun: null,
        busy: false,
      },
    })

    expect(wrapper.text()).toContain('Standard output (truncated)')
    expect(wrapper.find('details').exists()).toBe(true)
  })
})
