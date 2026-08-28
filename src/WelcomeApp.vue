<!--
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 -->

<script setup>
import { createWelcomeApp } from './app/core/createWelcomeApp.js';
import WelcomeWindow from './components/chrome/WelcomeWindow.vue';

const app = createWelcomeApp();
</script>

<template>
  <WelcomeWindow :app="app" />
</template>
