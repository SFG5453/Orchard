import { createApp } from 'vue';
import { Quasar } from 'quasar';
import '@quasar/extras/material-icons/material-icons.css';
import 'quasar/dist/quasar.css';
import './styles.css';
import App from './App.vue';
import ExplicitBadge from './components/controls/ExplicitBadge.vue';

createApp(App)
  .component('ExplicitBadge', ExplicitBadge)
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
