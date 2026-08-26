<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'

type Theme = 'light' | 'dark'

const theme = ref<Theme>('light')
const themeLabel = computed(() => (theme.value === 'light' ? 'Use dark theme' : 'Use light theme'))

const navItems = [
  { to: '/labs', label: 'Labs', icon: 'flask' },
  { to: '/templates', label: 'Templates', icon: 'template' },
  { to: '/test-results', label: 'Test Results', icon: 'check' },
  { to: '/concepts', label: 'Docker Concepts', icon: 'book' },
  { to: '/settings', label: 'Settings', icon: 'settings' },
]

function toggleTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
  document.documentElement.dataset.theme = theme.value
}
</script>

<template>
  <div class="application-frame">
    <a class="skip-link" href="#main-content">Skip to main content</a>

    <aside class="sidebar" aria-label="Primary navigation">
      <RouterLink class="brand" to="/labs" aria-label="LabDeck home">
        <span class="brand-mark" aria-hidden="true">L</span>
        <span>LabDeck</span>
      </RouterLink>

      <nav class="primary-nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="nav-link">
          <span class="nav-icon" :data-icon="item.icon" aria-hidden="true"></span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-engine">
        <span class="status-dot status-dot--healthy" aria-hidden="true"></span>
        <span>
          <strong>Local engine</strong>
          <small>Connected</small>
        </span>
      </div>
    </aside>

    <header class="topbar">
      <div class="engine-status" role="status">
        <span class="status-dot status-dot--healthy" aria-hidden="true"></span>
        <span>Docker Engine connected</span>
      </div>
      <button class="icon-button" type="button" :aria-label="themeLabel" @click="toggleTheme">
        <span aria-hidden="true">{{ theme === 'light' ? '☾' : '☀' }}</span>
      </button>
    </header>

    <main id="main-content" class="main-content" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>
