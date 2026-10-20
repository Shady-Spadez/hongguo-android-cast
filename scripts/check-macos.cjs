// Run: node scripts/check-macos.cjs. No network, GUI, or user data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');
const sourcePath = path.resolve(__dirname, '../main.js');
const localRequire = createRequire(sourcePath);
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-macos-'));

function executable(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  return file;
}

function load(platform = 'darwin') {
  const root = fs.mkdtempSync(path.join(temp, platform + '-'));
  const app = new EventEmitter();
  let ready, about;
  const deniedExecutables = new Set();
  const fixtureFs = Object.create(fs);
  fixtureFs.accessSync = (file, mode) => {
    if (mode === fs.constants.X_OK && deniedExecutables.has(file)) {
      throw Object.assign(new Error('Execution denied by fixture'), { code: 'EACCES' });
    }
    return fs.accessSync(file, mode);
  };
  Object.assign(app, {
    getVersion: () => require('../package.json').version, getPath: () => root,
    setAboutPanelOptions: options => { about = options; },
    commandLine: { appendSwitch() {} },
    requestSingleInstanceLock: () => true, whenReady: () => ({ then(fn) { ready = fn; } }),
    quit() {},
  });
  const windows = [];
  class Window extends EventEmitter {
    constructor(options) {
      super(); this.options = options; this.destroyed = false;
      this.webContents = new EventEmitter();
      Object.assign(this.webContents, { setWindowOpenHandler() {}, send() {} });
      windows.push(this);
    }
    static getAllWindows() { return windows.filter(w => !w.destroyed); }
    isDestroyed() { return this.destroyed; }
    isMinimized() { return false; }
    setMenuBarVisibility() {}
    loadFile() { return Promise.resolve(); }
    loadURL() { return Promise.resolve(); }
    show() { this.visible = true; }
    focus() { this.focused = true; }
    close() { this.destroyed = true; this.emit('closed'); }
  }
  let menu;
  const electron = {
    app, BrowserWindow: Window, ipcMain: { handle() {} },
    Menu: { buildFromTemplate: x => x, setApplicationMenu: x => { menu = x; } },
    session: { defaultSession: { setProxy: async () => {} } },
    protocol: { registerSchemesAsPrivileged() {}, handle() {} },
  };
  const context = vm.createContext({
    require: name => name === 'electron' ? electron : name === 'fs' ? fixtureFs : localRequire(name),
    __dirname: root, console, Buffer, URL, Response, setTimeout, clearTimeout,
    setInterval: () => ({ unref() {} }), clearInterval() {},
    process: { platform, arch: 'arm64', resourcesPath: path.join(root, 'Resources'), env: { PATH: '' } },
  });
  vm.runInContext(fs.readFileSync(sourcePath, 'utf8'), context, { filename: sourcePath });
  return { root, context, app, windows, ready: () => ready(), menu: () => menu, about: () => about, denyExecute: file => deniedExecutables.add(file) };
}

(async () => {
  const failures = [];
  async function check(name, fn) {
    try { await fn(); console.log('PASS ' + name); }
    catch (error) { failures.push(name); console.error('FAIL ' + name + ': ' + error.message); }
  }
  await check('Mac finds bundled executables without .exe', () => {
    const h = load();
    const ff = executable(path.join(h.root, 'Resources/bin/ffmpeg'));
    assert.equal(h.context.resolveFfmpeg('ffmpeg'), ff);
  });
  await check('Mac development resolves arm64 resource directory', () => {
    const h = load();
    const ff = executable(path.join(h.root, 'build/ffmpeg/darwin-arm64/ffprobe'));
    assert.equal(h.context.resolveFfmpeg('ffprobe'), ff);
  });
  await check('Missing and non-executable tools are reported unavailable', () => {
    const h = load();
    assert.equal(h.context.resolveFfmpeg('ffmpeg'), null);
    const ff = executable(path.join(h.root, 'Resources/bin/ffmpeg'));
    // Windows does not implement POSIX executable bits; simulate the OS denial.
    h.denyExecute(ff);
    assert.equal(h.context.resolveFfmpeg('ffmpeg'), null);
  });
  await check('PATH fallback resolves an actual file', () => {
    const h = load();
    const dir = path.join(h.root, 'tools with spaces');
    const ff = executable(path.join(dir, 'ffmpeg'));
    h.context.process.env.PATH = dir;
    assert.equal(h.context.resolveFfmpeg('ffmpeg'), ff);
  });
  await check('Windows bundled .exe remains supported', () => {
    const h = load('win32');
    const ff = executable(path.join(h.root, 'Resources/bin/ffmpeg.exe'));
    assert.equal(h.context.resolveFfmpeg('ffmpeg'), ff);
  });
  await check('Dock reopens main window when hidden search window exists', async () => {
    const h = load();
    await h.ready();
    const main = h.windows[0];
    h.context.getSearchWindow();
    main.close();
    h.app.emit('activate');
    assert.equal(h.windows.length, 3, 'main window must be recreated');
    h.app.emit('activate');
    assert.equal(h.windows.length, 3, 'existing main window must be reused');
    assert.ok(h.windows[2].focused);
  });
  await check('Mac native menu supplies editing and quit shortcuts', async () => {
    const h = load();
    await h.ready();
    const roles = (h.menu() || []).flatMap(item => item.submenu || []).map(item => item.role);
    for (const role of ['copy', 'paste', 'selectAll', 'quit']) assert.ok(roles.includes(role), role);
  });
  await check('Mac About panel displays only the public two-part version', async () => {
    const h = load();
    await h.ready();
    assert.equal(h.about()?.applicationVersion, require('../package.json').version.replace(/^(\d+\.\d+)\.0$/, '$1'));
    assert.equal(h.about()?.version, '');
  });
  assert.equal(failures.length, 0, failures.join('; '));
})().catch(error => { console.error(error.message); process.exitCode = 1; })
  .finally(() => fs.rmSync(temp, { recursive: true, force: true }));
