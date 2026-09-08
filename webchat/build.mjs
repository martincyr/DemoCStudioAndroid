import { build } from 'esbuild';
import { copyFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(root, '../app/src/main/assets/webchat');

await mkdir(outDir, { recursive: true });

await build({
  entryPoints: [resolve(root, 'src/index.ts')],
  outfile: resolve(outDir, 'bundle.js'),
  bundle: true,
  platform: 'browser',
  target: 'es2020',
  format: 'iife',
  minify: true,
  sourcemap: false,
  logLevel: 'info',
  define: {
    'process.env.NODE_ENV': '"production"',
    global: 'globalThis'
  }
});

await copyFile(resolve(root, 'src/index.html'), resolve(outDir, 'index.html'));
await copyFile(resolve(root, 'src/styles.css'), resolve(outDir, 'styles.css'));

console.log(`WebChat bundle written to ${outDir}`);
