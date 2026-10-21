// Execute main.js startup with an Electron boundary double; no windows, network or user files.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');
const { test } = require('node:test');
const source = path.resolve(__dirname, '../main.js');
const req = createRequire(source);

function load(ownsLock, preview = false, options = {}) {
  const timers = new Map();
  const fixtureFs = Object.create(fs);
  fixtureFs.existsSync = file => {
    if (options.slowDisk && String(file).startsWith('/isolated-slow-downloads')) {
      events.push(['disk']);
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
      return false;
    }
    return fs.existsSync(file);
  };
  const events = [], ready = [], app = new EventEmitter();
  let userData = '/tmp/hongguo-single-instance-test';
  Object.assign(app, {
    getVersion: () => 'test', getPath: name => name === 'userData' ? userData : '/tmp',
    setName() {}, setPath: (name, value) => { if (name === 'userData') userData = value; },
    commandLine: { appendSwitch() {} },
    requestSingleInstanceLock: () => { events.push(['lock', userData]); return ownsLock; },
    quit: () => events.push(['quit']),
    whenReady: () => { events.push(['ready']); return { then: callback => ready.push(callback) }; },
  });
  const store = { getTasks: () => options.tasks || [], setErrorHandler() {}, init: file => { events.push(['store', file]); if (options.storeFailure) throw Error('fixture private path'); } };
  const electron = { app, dialog: { showErrorBox: (title, message) => events.push(['error', title, message]) }, ipcMain: { handle() {} }, session: { defaultSession: {
    setProxy: async () => {
      events.push(['proxy']);
      if (options.proxyPending) return new Promise(() => {});
      if (options.proxyReject) throw new Error('fixture proxy secret');
    },
  } }, protocol: { registerSchemesAsPrivileged() {} } };
  const context = vm.createContext({
    require: name => name === 'fs' ? fixtureFs : name === 'electron' ? electron : name === './src/store' ? store
      : name === './package.json' ? { name: preview ? 'hongguo-experience-preview' : 'hongguo-downloader' } : req(name),
    __dirname: path.dirname(source), process: { ...process, platform: 'linux', env: {} },
    console: { log() {}, warn() {}, error() {} }, Buffer, URL, Response, Request, Headers, AbortController,
    setTimeout: (fn, ms) => { const timer = { unref() {} }; timers.set(timer, { fn, ms }); return timer; }, setInterval: () => ({ unref() {} }), clearTimeout: timer => timers.delete(timer), clearInterval,
  });
  vm.runInContext(fs.readFileSync(source, 'utf8'), context, { filename: source });
  for (const name of ['loadDownloadTasks', 'loadSeriesRegistry', 'rebuildSeriesRegistryFromTasks', 'rescanDownloadsFromDisk', 'loadMergeTasks', 'registerStreamProtocol', 'registerLocalProtocol']) if ((name !== 'rescanDownloadsFromDisk' || !options.slowDisk) && (name !== 'loadDownloadTasks' || !options.tasks)) context[name] = () => {};
  context.getCurrentSettings = () => options.slowDisk ? { root: '/isolated-slow-downloads' } : {};
  if (options.slowDisk) vm.runInContext("seriesRegistry = [{ series_id: 'slow', series_title: 'fixture', episodes: [] }]", context);
  if (!options.tasks) context.enqueuePendingTasks = () => { events.push(['queue']); return 0; };
  const window = {
    isDestroyed: () => false, isMinimized: () => true,
    restore: () => events.push(['restore']), show: () => events.push(['show']), focus: () => events.push(['focus']),
  };
  context.fixtureWindow = window;
  context.createWindow = () => { events.push(['window']); vm.runInContext('mainWindow = fixtureWindow', context); };
  return { app, events, ready, context, timers };
}

test('second process quits before readiness, data load, queued jobs or a window', async () => {
  const h = load(false);
  for (const callback of h.ready) await callback();
  assert.deepEqual(h.events.map(event => event[0]), ['lock', 'quit']);
});

test('lock owner starts once; repeated launches restore its existing window', async () => {
  for (const preview of [false, true]) {
    const h = load(true, preview);
    for (const callback of h.ready) await callback();
    const expectedDirectory = preview ? path.join('/tmp', 'hongguo-experience-preview') : '/tmp/hongguo-single-instance-test';
    assert.deepEqual(h.events[0], ['lock', expectedDirectory], 'take lock after preview userData isolation');
    assert.equal(h.events.filter(event => event[0] === 'store').length, 1);
    assert.equal(h.events.find(event => event[0] === 'store')[1], path.join(expectedDirectory, 'data.json'));
    h.events.length = 0;
    h.app.emit('second-instance');
    assert.deepEqual(h.events.map(event => event[0]), ['restore', 'show', 'focus']);
    vm.runInContext('mainWindow = null', h.context);
    h.events.length = 0;
    h.app.emit('second-instance');
    assert.deepEqual(h.events.map(event => event[0]), ['window', 'restore', 'show', 'focus']);
  }
});


test('startup initialization failure reports its stage and exits without exposing raw errors', async () => {
  const h = load(true, false, { storeFailure: true });
  await assert.doesNotReject(h.ready[0]());
  const error = h.events.find(event => event[0] === 'error');
  assert.ok(error, 'failed startup must produce a visible native error');
  assert.match(error[2], /读取本地数据/);
  assert.ok(!error[2].includes('fixture private path'));
  assert.equal(h.events.at(-1)[0], 'quit');
  assert.equal(h.events.filter(event => event[0] === 'window').length, 0);
});

test('pending proxy initialization has a deadline and never opens an unconfigured network window', async () => {
  const h = load(true, false, { proxyPending: true });
  const startup = h.ready[0]();
  const deadline = [...h.timers.values()].find(timer => timer.ms === 15000);
  assert.ok(deadline, 'proxy startup must not wait forever before the first window');
  deadline.fn();
  await startup;
  assert.match(h.events.find(event => event[0] === 'error')[2], /初始化网络/);
  assert.equal(h.events.at(-1)[0], 'quit');
  assert.equal(h.events.filter(event => event[0] === 'window').length, 0);
});

test('successful startup clears its deadline and opens exactly one window', async () => {
  const h = load(true);
  await h.ready[0]();
  assert.equal([...h.timers.values()].filter(timer => timer.ms === 15000).length, 0);
  assert.equal(h.events.filter(event => event[0] === 'window').length, 1);
  assert.equal(h.events.filter(event => event[0] === 'error' || event[0] === 'quit').length, 0);
});


test('a slow download volume is not scanned before opening the main window', async () => {
  const h = load(true, false, { slowDisk: true });
  await h.ready[0]();
  assert.equal(h.events.filter(event => event[0] === 'disk').length, 0,
    'startup must not synchronously probe the download volume');
  assert.equal(h.events.filter(event => event[0] === 'window').length, 1);
  h.context.rescanDownloadsFromDisk();
  assert.ok(h.events.some(event => event[0] === 'disk'), 'explicit rescan still reaches the real directory scanner');
});


test('saved paused and completed tasks remain loaded without automatic restart', async () => {
  const h = load(true, false, { tasks: [
    { id: 'paused', status: 'paused', startTime: 1 },
    { id: 'done', status: 'completed', startTime: 2 },
  ] });
  await h.ready[0]();
  assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(downloadTasks.map(({id,status}) => ({id,status})))', h.context)),
    [{ id: 'done', status: 'completed' }, { id: 'paused', status: 'paused' }]);
  assert.equal(vm.runInContext('downloadQueue.length', h.context), 0);
  assert.equal(vm.runInContext('activeDownloads', h.context), 0);
});


test('a rejected Electron proxy configuration aborts startup through the real settings chain', async () => {
  const h = load(true, false, { proxyReject: true });
  await h.ready[0]();
  assert.equal(h.events.filter(event => event[0] === 'proxy').length, 1);
  assert.equal(h.events.filter(event => event[0] === 'window').length, 0,
    'a proxy rejection must not open an unconfigured network window');
  assert.match(h.events.find(event => event[0] === 'error')[2], /初始化网络/);
  assert.equal(h.events.at(-1)[0], 'quit');
  await assert.rejects(h.context.applyProxySettings({ proxy_enabled: false }),
    error => error.message === '设置网络代理失败，请重试。');
});
