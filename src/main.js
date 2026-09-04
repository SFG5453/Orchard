/*
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
 */

import { createApp } from 'vue';
import { Quasar } from 'quasar';
import '@quasar/extras/material-icons/material-icons.css';
import 'quasar/dist/quasar.css';
import './styles.css';
import App from './App.vue';
import ExplicitBadge from './components/controls/ExplicitBadge.vue';
import DownloadIndicator from './components/controls/DownloadIndicator.vue';
import { installDesktopPlatform } from './platform/desktop/install.js';

async function start() {
  await installDesktopPlatform();
  createApp(App)
    .component('ExplicitBadge', ExplicitBadge)
    .component('DownloadIndicator', DownloadIndicator)
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
}

void start();
