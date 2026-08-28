/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { createApp } from 'vue';
import { Quasar } from 'quasar';
import '@quasar/extras/material-icons/material-icons.css';
import 'quasar/dist/quasar.css';
import './styles.css';
import WelcomeApp from './WelcomeApp.vue';

createApp(WelcomeApp)
  .use(Quasar, {
    config: {
      brand: {
        primary: '#67d98b',
        secondary: '#8d948f',
        accent: '#83eca2',
        dark: '#050605'
      }
    }
  })
  .mount('#app');
