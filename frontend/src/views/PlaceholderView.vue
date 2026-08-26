<script setup lang="ts">
defineProps<{
  title: string
  description: string
}>()
</script>

<template>
  <section class="page" aria-labelledby="page-title">
    <p class="eyebrow">LabDeck</p>
    <h1 id="page-title">{{ title }}</h1>
    <p class="page-intro">{{ description }}</p>
    <div class="empty-state" role="status">
      <span class="empty-state__mark" aria-hidden="true">LD</span>
      <div>
        <h2>This section is ready for its domain service</h2>
        <p>The route and responsive shell are in place. No remote service is required.</p>
      </div>
    </div>
  </section>
</template>
