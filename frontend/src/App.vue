<script setup lang="ts">
const product = {
  name: 'LabDeck',
  promise: 'One command. The exact environment your assignment needs.',
}
</script>

<template>
  <main class="app-shell">
    <p class="eyebrow">Local development labs</p>
    <h1>{{ product.name }}</h1>
    <p>{{ product.promise }}</p>
  </main>
</template>
