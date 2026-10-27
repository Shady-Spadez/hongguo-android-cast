import { build } from 'esbuild';
import { transformSync } from '@babel/core';
import bigintPlugin from 'babel-plugin-transform-bigint';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import './build-android-startup.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const adapter = path.join(root, 'src/android/mac-source');
const output = path.join(root, 'src/android/generated/mac-app-source.mjs');

// Chrome 66 WebView compatibility: the shared signing modules (app-source.js,
// sm3.js) use BigInt operators that Chromium 66 cannot parse. Transpile those
// sources to JSBI calls in-memory before esbuild bundles them, so esbuild can
// then resolve and bundle the jsbi runtime package normally.
const bigintTransform = {
  name: 'babel-bigint-for-old-webview',
  setup(buildInstance) {
    buildInstance.onLoad({ filter: /src[\\/]native[\\/].*\.js$/ }, async (args) => {
      if (!/app-source|sm3|download-quality/.test(args.path)) return undefined;
      const fs = await import('node:fs');
      const source = fs.readFileSync(args.path, 'utf8');
      const { code } = transformSync(source, {
        filename: args.path,
        sourceType: 'module',
        plugins: [bigintPlugin],
      });
      return { contents: code, loader: 'js' };
    });
  },
};

await build({
  entryPoints: [path.join(adapter, 'entry.mjs')], outfile: output,
  bundle: true, format: 'esm', platform: 'browser', target: 'chrome66',
  plugins: [bigintTransform],
  inject: [path.join(adapter, 'buffer-runtime.mjs')],
  alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
  define: { 'AbortSignal.timeout': 'androidAppTimeout' },
  legalComments: 'inline', sourcemap: false,
  banner: { js: '// SPDX-License-Identifier: GPL-3.0-only\n// Generated Android adapter for shared src/native/app-source.js, sm3.js and download-quality.js.\n// Protocol attribution: src/native/licenses/NOTICE.txt and LICENSE.drpys.txt.' },
});

// Fail the build if any raw BigInt usage survived into the generated bundle.
// JSBI-transpiled code legitimately contains `JSBI.BigInt(`, so only flag a
// BigInt reference that is NOT reached through the JSBI namespace.
const fs = await import('node:fs');
const generated = fs.readFileSync(output, 'utf8');
const offenders = generated.match(/typeof BigInt\b|(?<![.\w])BigInt\s*\(/g);
if (offenders) {
  console.error('ANDROID_MAC_SOURCE_BIGINT_REMAINS', offenders.slice(0, 5));
  process.exit(1);
}
console.log('ANDROID_MAC_SOURCE_BUILT src/android/generated/mac-app-source.mjs (chrome66, BigInt->JSBI)');
