import { createRouter, createWebHistory } from 'vue-router'

import LabsView from './views/LabsView.vue'
import LabDetailView from './views/LabDetailView.vue'
import PlaceholderView from './views/PlaceholderView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/labs' },
    { path: '/labs', component: LabsView },
    {
      path: '/labs/:labId',
      component: LabDetailView,
    },
    {
      path: '/templates',
      component: PlaceholderView,
      props: { title: 'Templates', description: 'Safe local starting points for common courses.' },
    },
    {
      path: '/test-results',
      component: PlaceholderView,
      props: { title: 'Test Results', description: 'Developer test history for your labs.' },
    },
    {
      path: '/concepts',
      component: PlaceholderView,
      props: { title: 'Docker Concepts', description: 'Short explanations when you want them.' },
    },
    {
      path: '/settings',
      component: PlaceholderView,
      props: { title: 'Settings', description: 'Local engine, storage, and appearance settings.' },
    },
  ],
})
