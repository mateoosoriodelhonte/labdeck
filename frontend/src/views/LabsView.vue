<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

type LabStatus = 'RUNNING' | 'STOPPED'

interface DemoLab {
  id: string
  course: string
  environment: string
  status: LabStatus
  endpoints: Array<{ name: string; address: string }>
  updated: string
}

const labs: DemoLab[] = [
  {
    id: 'cs-242-web-development',
    course: 'CS 242 — Web Development',
    environment: 'Node + PostgreSQL',
    status: 'STOPPED',
    endpoints: [],
    updated: 'Today, 10:21 AM',
  },
  {
    id: 'cs-341-databases',
    course: 'CS 341 — Databases',
    environment: 'Python + PostgreSQL + Redis',
    status: 'RUNNING',
    endpoints: [
      { name: 'App', address: 'localhost:8000' },
      { name: 'PostgreSQL', address: 'localhost:5432' },
    ],
    updated: 'Today, 9:47 AM',
  },
  {
    id: 'cs-460-operating-systems',
    course: 'CS 460 — Operating Systems',
    environment: 'C++ + CMake',
    status: 'STOPPED',
    endpoints: [],
    updated: 'Yesterday, 4:15 PM',
  },
]

const importInput = ref<HTMLInputElement>()
const importNotice = ref('')

function chooseManifest() {
  importInput.value?.click()
}

function stageManifest(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) {
    importNotice.value = `${file.name} is ready for a safety review before any Docker resource is created.`
  }
}

function statusLabel(status: LabStatus) {
  return status === 'RUNNING' ? 'Running' : 'Stopped'
}
</script>

<template>
  <section class="page labs-page" aria-labelledby="labs-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">Local workspace</p>
        <h1 id="labs-title">My Labs</h1>
        <p class="page-intro">Reproducible environments for your coursework.</p>
      </div>

      <div class="page-actions">
        <input
          ref="importInput"
          class="visually-hidden"
          type="file"
          accept=".yml,.yaml"
          tabindex="-1"
          @change="stageManifest"
        />
        <button class="button button--primary" type="button" @click="chooseManifest">
          <span aria-hidden="true">↥</span>
          Import lab
        </button>
      </div>
    </div>

    <p v-if="importNotice" class="notice" role="status">{{ importNotice }}</p>

    <div class="system-summary" aria-label="Local Docker summary">
      <span><strong>Engine</strong> Docker 29.5.2</span>
      <span><strong>Disk</strong> Calculating owned use</span>
      <span><strong>Privacy</strong> Local only</span>
    </div>

    <div class="lab-table-wrap">
      <table class="lab-table">
        <caption class="visually-hidden">
          Demo development labs and their current state
        </caption>
        <thead>
          <tr>
            <th scope="col">Lab</th>
            <th scope="col">Services and endpoints</th>
            <th scope="col">Status</th>
            <th scope="col">Updated</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="lab in labs"
            :key="lab.id"
            :class="{ 'lab-row--running': lab.status === 'RUNNING' }"
          >
            <td data-label="Lab">
              <RouterLink class="lab-link" :to="`/labs/${lab.id}`">
                <strong>{{ lab.course }}</strong>
                <span>{{ lab.environment }}</span>
              </RouterLink>
            </td>
            <td data-label="Services and endpoints">
              <span v-if="lab.endpoints.length === 0" class="muted">No running services</span>
              <ul v-else class="endpoint-list">
                <li v-for="endpoint in lab.endpoints" :key="endpoint.name">
                  <span>{{ endpoint.name }}</span>
                  <code>{{ endpoint.address }}</code>
                </li>
              </ul>
            </td>
            <td data-label="Status">
              <span class="status-label" :data-status="lab.status.toLowerCase()">
                <span class="status-symbol" aria-hidden="true"></span>
                {{ statusLabel(lab.status) }}
              </span>
            </td>
            <td data-label="Updated" class="updated-at">{{ lab.updated }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="demo-note"><span aria-hidden="true">ⓘ</span> Demo content</p>
  </section>
</template>
