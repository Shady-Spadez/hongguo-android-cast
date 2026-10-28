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

// Chromium 66 guard: esbuild fails this build if any BigInt *literal* survives
// (it cannot lower them for chrome66), which is the only syntax-level hazard.
// Bare `BigInt(` calls are legal syntax on Chromium 66 and only throw when
// executed: our rewritten sources never execute them, and the buffer polyfill's
// read/writeBig* method bodies that contain them are no longer called.
console.log('ANDROID_MAC_SOURCE_BUILT src/android/generated/mac-app-source.mjs (chrome66, JSBI)');
