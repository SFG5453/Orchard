<!--
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
-->

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
