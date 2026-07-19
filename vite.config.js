import { defineConfig } from 'vite';
import { createRequire } from 'node:module';
import vue from '@vitejs/plugin-vue';
import { quasar, transformAssetUrls } from '@quasar/vite-plugin';

const require = createRequire(import.meta.url);
const { version } = require('./package.json');

export default defineConfig({
  base: './',
  define: {
    __APP_VERSION__: JSON.stringify(version)
  },
  plugins: [
    vue({
      template: { transformAssetUrls }
    }),
    quasar({
      sassVariables: false
    })
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: false
  },
  build: {
    target: 'es2022',
    emptyOutDir: true,
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: 'vite-preload-helper',
              test: /vite[\\/]preload-helper\.js/,
              priority: 40
            },
            {
              name: 'vue-quasar',
              test: /node_modules[\\/](?:@vue|vue|quasar)[\\/]/,
              priority: 30
            },
            {
              name: 'animated-background',
              test: /node_modules[\\/](?:@pixi|pixi\.js|@vibrant|node-vibrant|gsap)[\\/]/,
              priority: 25
            },
            {
              name: 'hls',
              test: /node_modules[\\/]hls\.js[\\/]/,
              priority: 24
            },
            {
              name: 'socket-io',
              test: /node_modules[\\/](?:@socket\.io|socket\.io-client|socket\.io-parser|engine\.io-client|engine\.io-parser)[\\/]/,
              priority: 20
            },
            {
              name: 'vendor',
              test: /node_modules[\\/]/,
              priority: 10
            }
          ]
        }
      }
    }
  }
});
