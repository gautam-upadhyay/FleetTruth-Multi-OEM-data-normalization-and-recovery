import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    proxy: { '/api': process.env.VITE_API_TARGET || 'http://127.0.0.1:8080' },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('recharts') || id.includes('d3-')) return 'charts';
            if (id.includes('oidc-client')) return 'identity';
            if (id.includes('react') || id.includes('@tanstack')) return 'react-vendor';
          }
        },
      },
    },
  },
});
