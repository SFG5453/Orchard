<script setup>
import { createOrchardApp } from './app/core/createOrchardApp.js';
import AppFrame from './components/chrome/AppFrame.vue';
import WelcomeWindow from './components/chrome/WelcomeWindow.vue';

const app = createOrchardApp();
const isWelcomeWindow = new URLSearchParams(window.location.search).get('welcome') === '1';
</script>

<template>
  <WelcomeWindow v-if="isWelcomeWindow" :app="app" />
  <AppFrame v-else :app="app" />
</template>
