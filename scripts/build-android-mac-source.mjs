import { build } from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import './build-android-startup.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const adapter = path.join(root, 'src/android/mac-source');
const output = path.join(root, 'src/android/generated/mac-app-source.mjs');

// Chromium 66 WebView target: the shared signing modules (app-source.js, sm3.js)
// were rewritten to call JSBI directly instead of using BigInt operators, so no
// transform step is needed here — esbuild only lowers remaining syntax.

await build({
  entryPoints: [path.join(adapter, 'entry.mjs')], outfile: output,
  bundle: true, format: 'esm', platform: 'browser', target: 'chrome66',
  inject: [path.join(adapter, 'buffer-runtime.mjs')],
  alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
  define: { 'AbortSignal.timeout': 'androidAppTimeout' },
  legalComments: 'inline', sourcemap: false,
  banner: { js: '// SPDX-License-Identifier: GPL-3.0-only\n// Generated Android adapter for shared src/native/app-source.js, sm3.js and download-quality.js.\n// Protocol attribution: src/native/licenses/NOTICE.txt and LICENSE.drpys.txt.' },
});

// Fail the build if any raw BigInt usage survived into the generated bundle.
// JSBI code legitimately contains `JSBI.BigInt(` calls and a `static BigInt(`
// class-method definition, so only flag references outside the JSBI namespace.
// BigInt literals need no check: esbuild itself rejects them at target chrome66.
const fs = await import('node:fs');
const generated = fs.readFileSync(output, 'utf8');
const offenders = generated.match(/typeof BigInt\b|(?<![.\w])(?<!static\s)BigInt\s*\(/g);
if (offenders) {
  console.error('ANDROID_MAC_SOURCE_BIGINT_REMAINS', offenders.slice(0, 5));
  process.exit(1);
}
console.log('ANDROID_MAC_SOURCE_BUILT src/android/generated/mac-app-source.mjs (chrome66, JSBI)');
