<script setup lang="ts">
import { computed } from 'vue'

import type { LabDetail, TestRun } from '../api/lab-api'

const props = defineProps<{
  lab: LabDetail
  history: TestRun[]
  activeRun: TestRun | null
  busy: boolean
}>()

defineEmits<{ run: []; cancel: [] }>()

const testPlan = computed(() => props.lab.plan?.tests ?? null)
const canRun = computed(
  () => props.lab.state === 'RUNNING' && testPlan.value !== null && !props.activeRun && !props.busy,
)
const activeRunIsExecuting = computed(
  () =>
    props.activeRun?.status === 'RUNNING' ||
    props.activeRun?.status === 'CANCELLING' ||
    props.activeRun?.status === 'PERSISTING',
)

function displayReason(reason?: string | null) {
  return reason ? reason.toLowerCase().replaceAll('_', ' ') : 'in progress'
}

function displayRecordedAt(recordedAt?: string) {
  return recordedAt ? new Date(recordedAt).toLocaleString() : 'Time not available'
}
</script>

<template>
  <section class="test-panel" aria-labelledby="assignment-tests-title">
    <div class="section-heading">
      <div>
        <p class="eyebrow">Manifest-defined</p>
        <h2 id="assignment-tests-title">Assignment test</h2>
      </div>
      <button
        class="button button--primary"
        type="button"
        :disabled="!canRun"
        @click="$emit('run')"
      >
        {{ busy ? 'Starting…' : 'Run test' }}
      </button>
    </div>

    <p class="section-copy">
      This is a developer test from <code>labdeck.yml</code>. It is not an official grade.
    </p>

    <div v-if="testPlan" class="test-plan" aria-label="Assignment test plan">
      <dl>
        <div>
          <dt>Service</dt>
          <dd>{{ testPlan.service }}</dd>
        </div>
        <div>
          <dt>Time limit</dt>
          <dd>{{ testPlan.timeoutSeconds }} seconds</dd>
        </div>
      </dl>
      <div>
        <span class="field-label">Exact command</span>
        <code class="command-line">{{ JSON.stringify(testPlan.command) }}</code>
      </div>
    </div>
    <p v-else class="empty-state" role="status">
      This manifest does not define an assignment test.
    </p>

    <div
      v-if="activeRun && activeRunIsExecuting"
      class="active-test"
      role="status"
      aria-live="polite"
    >
      <div>
        <strong>{{
          activeRun.status === 'CANCELLING'
            ? 'Stopping test and lab…'
            : activeRun.status === 'PERSISTING'
              ? 'Saving test result…'
              : 'Test is running…'
        }}</strong>
        <span>{{ Math.max(0, Math.round(activeRun.durationMillis / 1000)) }} seconds elapsed</span>
      </div>
      <button
        v-if="activeRun.status !== 'PERSISTING'"
        class="button button--secondary"
        type="button"
        :disabled="!activeRun.canCancel || busy"
        @click="$emit('cancel')"
      >
        Cancel test
      </button>
      <p v-if="activeRun.status !== 'PERSISTING'" class="cancel-warning">
        Cancel stops this lab because Docker cannot stop one exec process safely. Restart the lab
        after cancellation.
      </p>
      <p v-else class="cancel-warning">LabDeck is writing this result to local history.</p>
    </div>
    <div v-else-if="activeRun" class="active-test" role="alert">
      <div>
        <strong>Test result could not be saved.</strong>
        <span>{{ displayReason(activeRun.outcomeReason) }}</span>
      </div>
      <p class="cancel-warning">
        LabDeck kept this test slot reserved to avoid losing the result. Restart LabDeck before you
        run this assignment test again.
      </p>
    </div>

    <div class="test-history">
      <h3>Recent results</h3>
      <p v-if="history.length === 0" class="empty-state">No assignment test results yet.</p>
      <ol v-else class="result-list">
        <li v-for="run in history" :key="run.id" class="result-item">
          <div class="result-summary">
            <strong class="status-label" :data-status="run.status.toLowerCase()">{{
              run.status
            }}</strong>
            <span>{{ displayReason(run.outcomeReason) }}</span>
            <span>{{ run.durationMillis }} ms</span>
            <span>Exit {{ run.exitCode ?? 'not available' }}</span>
          </div>
          <p class="result-provenance">
            Revision {{ run.labRevision }} · Service {{ run.service }} ·
            <time v-if="run.recordedAt" :datetime="run.recordedAt">{{
              displayRecordedAt(run.recordedAt)
            }}</time>
            <span v-else>Time not available</span>
          </p>
          <p class="result-digest">
            Test plan <code>{{ run.testPlanSha256 }}</code>
          </p>
          <details v-if="run.stdout || run.stderr || run.stdoutTruncated || run.stderrTruncated">
            <summary>View bounded output</summary>
            <div v-if="run.stdout || run.stdoutTruncated" class="output-block">
              <span>Standard output{{ run.stdoutTruncated ? ' (truncated)' : '' }}</span>
              <pre>{{ run.stdout }}</pre>
            </div>
            <div v-if="run.stderr || run.stderrTruncated" class="output-block">
              <span>Standard error{{ run.stderrTruncated ? ' (truncated)' : '' }}</span>
              <pre>{{ run.stderr }}</pre>
            </div>
          </details>
        </li>
      </ol>
    </div>
  </section>
</template>
