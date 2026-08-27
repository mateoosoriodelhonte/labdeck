<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import {
  cancelAssignmentTest,
  loadLab,
  loadTestHistory,
  loadTestStatus,
  startAssignmentTest,
  type LabDetail,
  type TestRun,
} from '../api/lab-api'
import AssignmentTestsPanel from '../components/AssignmentTestsPanel.vue'

const route = useRoute()
const labId = String(route.params.labId)
const lab = ref<LabDetail | null>(null)
const history = ref<TestRun[]>([])
const activeRun = ref<TestRun | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
let pollTimer: number | undefined
let pollFailures = 0

function isActive(run: TestRun) {
  return run.status === 'RUNNING' || run.status === 'CANCELLING' || run.status === 'PERSISTING'
}

function schedulePoll(delay: number) {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
  pollTimer = window.setTimeout(() => {
    pollTimer = undefined
    void pollRun()
  }, delay)
}

async function refreshPage() {
  loading.value = true
  error.value = ''
  try {
    const [loadedLab, tests] = await Promise.all([loadLab(labId), loadTestHistory(labId)])
    lab.value = loadedLab
    history.value = tests.runs
    activeRun.value = tests.activeRun
    pollFailures = 0
    if (activeRun.value && isActive(activeRun.value)) schedulePoll(250)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : 'The lab could not be loaded.'
  } finally {
    loading.value = false
  }
}

async function pollRun() {
  if (!activeRun.value) return
  try {
    const run = await loadTestStatus(labId, activeRun.value.id)
    pollFailures = 0
    error.value = ''
    activeRun.value = isActive(run) ? run : null
    if (isActive(run)) {
      schedulePoll(750)
    } else {
      await refreshPage()
    }
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : 'The test status could not be loaded.'
    pollFailures += 1
    schedulePoll(Math.min(10_000, 1_500 * 2 ** Math.min(pollFailures - 1, 3)))
  }
}

async function runTest() {
  if (!lab.value) return
  busy.value = true
  error.value = ''
  try {
    activeRun.value = await startAssignmentTest(lab.value)
    pollFailures = 0
    schedulePoll(250)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : 'The test could not start.'
  } finally {
    busy.value = false
  }
}

async function cancelTest() {
  if (!activeRun.value) return
  busy.value = true
  error.value = ''
  try {
    activeRun.value = await cancelAssignmentTest(labId, activeRun.value.id)
    pollFailures = 0
    schedulePoll(250)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : 'The test could not be cancelled.'
  } finally {
    busy.value = false
  }
}

onMounted(refreshPage)
onBeforeUnmount(() => {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer)
})
</script>

<template>
  <section class="page lab-detail-page" aria-labelledby="lab-title">
    <RouterLink class="back-link" to="/labs">← All labs</RouterLink>

    <div v-if="loading" class="loading-state" aria-busy="true">Loading lab…</div>
    <div v-else-if="!lab" class="error-state" role="alert">
      <h1 id="lab-title">Lab unavailable</h1>
      <p>{{ error }}</p>
      <button class="button button--secondary" type="button" @click="refreshPage">Try again</button>
    </div>
    <template v-else>
      <div class="page-heading">
        <div>
          <p class="eyebrow">Local lab</p>
          <h1 id="lab-title">{{ lab.name }}</h1>
          <p class="page-intro">State: {{ lab.state }} · Revision {{ lab.revision }}</p>
        </div>
      </div>

      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>

      <AssignmentTestsPanel
        :lab="lab"
        :history="history"
        :active-run="activeRun"
        :busy="busy"
        @run="runTest"
        @cancel="cancelTest"
      />
    </template>
  </section>
</template>
