import { build } from 'esbuild';
import { copyFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(root, '../app/src/main/assets/webchat');
const foundryOutDir = resolve(root, '../app/src/main/assets/foundry');

await mkdir(outDir, { recursive: true });
await mkdir(foundryOutDir, { recursive: true });

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

await build({
  entryPoints: [resolve(root, 'src/foundry.ts')],
  outfile: resolve(foundryOutDir, 'bundle.js'),
  bundle: true,
  platform: 'browser',
  target: 'es2020',
  format: 'iife',
  minify: true,
  sourcemap: false,
  logLevel: 'info'
});

await copyFile(resolve(root, 'src/foundry.html'), resolve(foundryOutDir, 'index.html'));
await copyFile(resolve(root, 'src/styles.css'), resolve(foundryOutDir, 'styles.css'));
console.log(`Foundry bundle written to ${foundryOutDir}`);
