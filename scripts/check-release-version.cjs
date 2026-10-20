const assert = require('node:assert/strict');
const { availableRelease } = require('../src/native/app-update');

const ordered = ['1.7', '1.8', '1.9', '1.71', '1.74', '1.75', '1.75.1', '1.76', '1.99', '1.100', '2.0'];
let checks = 0;
for (let current = 0; current < ordered.length; current++) {
  for (let next = 0; next < ordered.length; next++) {
    const release = availableRelease({ tag_name: `v${ordered[next]}` }, ordered[current]);
    assert.equal(release?.version || null, next > current ? ordered[next] : null,
      `${ordered[current]} to ${ordered[next]}`);
    checks++;
  }
}
for (const tag of ['v1.75', '1.75', 'v1.75.0', '1.75.0']) {
  assert.equal(availableRelease({ tag_name: tag }, '1.75.0'), null);
}
for (const tag of ['v1.76-beta.1', 'v1.76.0-beta.1', 'garbage', '1.76.0.1', '1.9007199254740992']) {
  assert.equal(availableRelease({ tag_name: tag }, '1.75.0'), null);
}
for (const flags of [{ prerelease: true }, { draft: true }]) {
  assert.equal(availableRelease({ tag_name: 'v2.0', ...flags }, '1.75.0'), null);
}
const pkg = require('../package.json');
assert.match(pkg.version, /^\d+\.\d+\.0$/, 'Release versions use two public components and an internal zero patch');
const publicVersion = pkg.version.replace(/\.0$/, '');
assert.equal(availableRelease({ tag_name: 'v2.0' }, '1.80.0').version, '2.0');
assert.equal(availableRelease({ tag_name: 'v2.0' }, '2.0.0'), null);
for (const artifact of [pkg.build.artifactName, pkg.build.portable.artifactName, pkg.build.nsis.artifactName]) {
  assert(artifact.includes(`-${publicVersion}-`), 'Download filenames must contain the two-part release version');
  assert(!artifact.includes('${version}'), 'Raw internal versions must not leak into download filenames');
}
console.log(`PASS ${checks} version pairs, equivalent versions, invalid versions and unpublished releases`);
