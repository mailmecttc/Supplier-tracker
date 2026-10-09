import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { defineConfig, type Plugin } from 'vite';

/**
 * Writes dist/sw.js: precaches every built file so the app works offline after the first load.
 * The cache name is a hash of the build, so a new deploy replaces the old cache.
 */
function serviceWorker(): Plugin {
  return {
    name: 'precache-sw',
    apply: 'build',
    closeBundle() {
      const dist = 'dist';
      const files: string[] = [];
      const walk = (dir: string) => {
        for (const f of readdirSync(dir)) {
          const p = join(dir, f);
          if (statSync(p).isDirectory()) walk(p);
          else if (f !== 'sw.js') files.push(relative(dist, p).split('\\').join('/'));
        }
      };
      walk(dist);
      const hash = createHash('sha256');
      for (const f of files.sort()) hash.update(f).update(readFileSync(join(dist, f)));
      const version = hash.digest('hex').slice(0, 10);
      const list = JSON.stringify(['./', ...files.map((f) => `./${f}`)]);
      const sw = readFileSync('src/sw-template.js', 'utf8')
        .replace('__VERSION__', version)
        .replace('__FILES__', list);
      writeFileSync(join(dist, 'sw.js'), sw);
    },
  };
}

// base './' so the build works from any sub-path (e.g. GitHub Pages /repo-name/).
export default defineConfig({
  base: './',
  build: { target: 'es2020' },
  plugins: [serviceWorker()],
  test: { include: ['tests/**/*.test.ts'] },
} as any);
