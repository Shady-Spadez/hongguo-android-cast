import test from 'node:test';
import assert from 'node:assert/strict';
import { accountStatus, accountView, loginFields } from '../src/android/account.mjs';

test('account projection excludes tokens and all unrecognized response fields', () => {
  const view = accountView({ authenticated: true, serverUrl: 'https://sync.example.com', user: { id: 'u1', username: 'alice', password: 'never' }, token: 'never', refreshToken: 'never', password: 'never', lastSyncAt: 1000, pendingCount: 2, syncing: true });
  assert.deepEqual(view, { authenticated: true, serverUrl: 'https://sync.example.com', user: { id: 'u1', username: 'alice' }, lastSyncAt: 1000, pendingCount: 2, syncing: true, error: '', loginRequired: false });
  assert.equal(JSON.stringify(view).includes('never'), false);
});

test('account projection validates readiness and counts', () => {
  for (const value of [null, undefined, {}, { authenticated: 'true' }]) assert.throws(() => accountView(value));
  const value = accountView({ authenticated: false, user: 1, pendingCount: -1, lastSyncAt: Infinity, syncing: 'true' });
  assert.equal(value.user, null); assert.equal(value.pendingCount, 0); assert.equal(value.lastSyncAt, 0); assert.equal(value.syncing, false);
});

test('login keeps password exact, trims only username and HTTPS root address', () => {
  const fields = loginFields({ serverUrl: ' https://sync.example.com:9443/ ', username: ' alice ', password: ' space password ', deviceName: 'Android TV' });
  assert.deepEqual(fields, { serverUrl: 'https://sync.example.com:9443', username: 'alice', password: ' space password ', deviceName: 'Android TV' });
});

test('login rejects insecure, credential-bearing, invalid and non-root endpoints', () => {
  for (const serverUrl of ['', 'sync.example.com', 'http://sync.example.com', 'ftp://sync.example.com', 'https://a:b@sync.example.com', 'https://sync.example.com/api', 'https://sync.example.com?token=secret', 'https://sync.example.com#fragment']) {
    assert.throws(() => loginFields({ serverUrl, username: 'alice', password: 'password' }), undefined, serverUrl);
  }
});

test('login validates credentials before bridge dispatch', () => {
  const fields = { serverUrl: 'https://sync.example.com', username: 'alice', password: 'password' };
  for (const patch of [{ username: '' }, { username: '  ' }, { username: 'a'.repeat(65) }, { password: '' }, { password: 'a'.repeat(257) }]) assert.throws(() => loginFields({ ...fields, ...patch }));
});

test('status distinguishes guest, reauthentication, offline, pending, syncing and confirmed sync', () => {
  assert.match(accountStatus(null), /正在读取/);
  assert.match(accountStatus(accountView({ authenticated: false })), /游客模式/);
  assert.match(accountStatus(accountView({ authenticated: false, loginRequired: true })), /登录已过期/);
  assert.match(accountStatus(accountView({ authenticated: true, syncing: true })), /正在同步/);
  assert.match(accountStatus(accountView({ authenticated: true, error: 'offline' })), /本机进度已保留/);
  assert.match(accountStatus(accountView({ authenticated: true, pendingCount: 2 })), /2 条进度待同步/);
  assert.match(accountStatus(accountView({ authenticated: true, lastSyncAt: 1000 })), /最近同步/);
  assert.match(accountStatus(accountView({ authenticated: true })), /等待首次同步/);
});

test('native bridge exposes only requested account methods and change callback', async () => {
  const emitted = [];
  globalThis.window = { dispatchEvent: event => emitted.push(event.type), AndroidBridge: { postMessage: raw => { const request = JSON.parse(raw); queueMicrotask(() => window.__androidResponse({ id: request.id, result: { method: request.method, params: request.params } })); } } };
  const { nativeCall } = await import('../src/android/bridge.mjs?account-test');
  for (const method of ['getAccount', 'accountLogin', 'accountLogout', 'syncHistory', 'importGuestHistory']) {
    assert.equal((await nativeCall(method, {})).method, method);
  }
  await assert.rejects(nativeCall('getToken'), /未支持/);
  window.__androidAccountChanged(); assert.deepEqual(emitted, ['androidaccountchanged']);
  delete globalThis.window;
});
