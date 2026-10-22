const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { Readable } = require('node:stream');
const root = path.join(__dirname, '..');
const file = path.join(root, 'src/native/catalog.js');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const html = (category = 'real-drama', page = 1, list) => `<script>_ROUTER_DATA = ${JSON.stringify({ loaderData: { 'category_$': {
  isSuccess: true, categoryRoute: { contentType: category }, seo: { title: '目录 } ; 测试' },
  recommendList: list || [{ series_id: '1234567890123456789', series_name: '示例剧', series_cover: 'https://example.com/cover.jpg', episode_cnt: 77, tags: ['爱情'] }],
  pagination: { pageNum: page, total: 800, totalPages: 34 }, selectorList: [{ items: [{ selector_item_id: 'romance', show_name: '爱情' }] }],
} } })};function runWindowFn(){throw Error('must not execute remote scripts')}</script>`;

async function checkBrowserTransport() {
  const calls = [];
  let cancelled = false;
  let respond = async () => new Response(html());
  const context = { module: { exports: {} }, require: name => {
    if (name === 'electron') return { net: { request: options => {
      const request = new EventEmitter();
      let body;
      request.abort = () => { request.aborted = true; body?.destroy(); };
      request.end = async () => {
        try {
          const response = await respond(options.url, options);
          body = response.body ? Readable.fromWeb(response.body) : Readable.from([]);
          body.statusCode = response.status;
          body.headers = Object.fromEntries(response.headers.entries());
          request.emit('response', body);
        } catch (error) { request.emit('error', error); }
      };
      calls.push({ url: options.url, options, request }); return request;
    } } };
    if (name === 'axios') return { get: async () => { throw Object.assign(new Error('read ECONNRESET'), { code: 'ECONNRESET' }); } };
    return require(name);
  }, Buffer, AbortController, URL, setTimeout: (fn, ms) => setTimeout(fn, ms === 10000 ? 40 : ms), clearTimeout };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  const { browseList } = context.module.exports;
  const result = await browseList();
  assert.equal(result.success, true, `目录应使用已配置系统代理的 Chromium 网络层，实际：${result.error}`);
  assert.equal(result.results[0].series_title, '示例剧');
  assert.equal(calls[0].url, 'https://hongguoduanju.com/category/real-drama');
  assert.equal(calls[0].options.credentials, undefined, '允许代理认证，不通过 credentials 打开站点 Cookie');
  assert.equal(calls[0].options.useSessionCookies, false, '公开目录不携带登录 Cookie');
  assert.equal(calls[0].options.headers['Cache-Control'], 'no-cache', '目录刷新必须重新验证浏览器缓存');
  let login;
  await browseList({ forceRefresh: true }, (auth, callback) => { login = auth; callback('fixture-user', 'fixture-pass'); });
  calls.at(-1).request.emit('login', { isProxy: true, host: '127.0.0.1', port: 1234 }, (user, password) => {
    assert.equal(user, 'fixture-user'); assert.equal(password, 'fixture-pass');
  });
  assert.equal(login?.isProxy, true, 'Chromium 代理认证必须交给主进程匹配');

  respond = async () => new Response('<article><h2 id="rank-title-1234567">热播测试</h2><a href="/detail?series_id=1234567">详情</a><span>1</span></article>');
  const ranking = await browseList({ category: 'hot-drama' });
  assert.equal(ranking.success, true);
  assert.equal(ranking.results[0].series_title, '热播测试');

  respond = async () => new Response('unavailable', { status: 503 });
  assert.match((await browseList({ forceRefresh: true })).error, /503/);
  respond = async () => new Response(html(), { headers: { 'content-length': String(8 * 1024 * 1024 + 1) } });
  assert.equal((await browseList({ forceRefresh: true })).success, false, '声明过大的页面不能被接受');
  respond = async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(8 * 1024 * 1024 + 1)); },
    cancel() { cancelled = true; },
  }));
  assert.equal((await browseList({ forceRefresh: true })).success, false, '无 Content-Length 的过大页面也应拒绝');
  assert.equal(cancelled, true, '超过限额必须取消响应流');
  respond = async () => { throw new Error('net::ERR_CONNECTION_RESET'); };
  const reset = await browseList({ forceRefresh: true });
  assert.equal(reset.success, false);
  assert.match(reset.error, /切换来源/);
  respond = async () => new Response(new ReadableStream({ start() {} }));
  const timeout = await browseList({ forceRefresh: true });
  assert.match(timeout.error, /超时/);
  assert.equal(calls.at(-1).request.aborted, true);
  console.log('PASS catalog Chromium transport: reset isolation, category/ranking, HTTP errors, bounded response and timeout');
}

async function main() {
  await checkBrowserTransport();
  if (!fs.existsSync(file)) {
    // Exercise the old real queue, with comic navigation stalled and real-drama ready.
    const code = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
    const context = { console: { log() {}, warn() {} }, searchWindowReady: null, SEARCH_TIMEOUT_MS: 1,
      SEARCH_SITE: 'https://hongguoduanju.com', SEARCH_UA: '', SNIFFER_JS: 'cards', BROWSE_META_JS: 'meta', setTimeout,
      getSearchWindow: () => ({ loadURL: url => url.endsWith('/comic') ? new Promise(() => {}) : Promise.resolve(),
        webContents: { executeJavaScript: async script => script === 'cards' ? '[{"series_id":"1234567","series_title":"真人"}]' : '{}' } }),
    };
    vm.createContext(context);
    vm.runInContext(code.slice(code.indexOf('async function runSniffOnUrl('), code.indexOf('async function runSearchSniff(')), context);
    context.runSniffOnUrl('https://hongguoduanju.com/category/comic', 'Browse');
    const outcome = await Promise.race([context.runSniffOnUrl('https://hongguoduanju.com/category/real-drama', 'Browse'), delay(80).then(() => 'blocked')]);
    assert.notEqual(outcome, 'blocked', '漫画导航未完成时，真人剧被共享串行队列阻塞');
    return;
  }

  const calls = [];
  const comic = deferred();
  let response = (url) => url.endsWith('/comic') ? comic.promise : Promise.resolve({ data: html('real-drama', url.includes('page=2') ? 2 : 1) });
  const context = { module: { exports: {} }, require: name => name === 'axios' ? { get: (url, options) => {
    calls.push({ url, options }); return response(url);
  } } : require(name), AbortController, setTimeout: (fn, ms) => setTimeout(fn, ms === 10000 ? 40 : ms), clearTimeout, URL, console };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), context, { filename: file });
  const { browseList } = context.module.exports;
  const slowComic = browseList({ category: 'comic' });
  const [real, same] = await Promise.race([Promise.all([browseList({}), browseList({})]), delay(100).then(() => { throw Error('真人剧被漫画请求阻塞'); })]);
  assert.equal(real.success, true);
  assert.equal(real.results[0].series_id, '1234567890123456789');
  assert.equal(real.results[0].episode_count, 77);
  assert.equal(real.genres[0].slug, 'romance');
  assert.equal(real.pageTitle, '目录 } ; 测试');
  assert.equal(real.totalPages, 34);
  assert.equal(calls.length, 2, '相同目录并发请求应合并');
  assert.deepEqual(real, same);
  comic.resolve({ data: html('comic') });
  const comicResult = await slowComic;
  assert.equal(comicResult.results.length, 0, '图片漫画不能转换为视频卡片');
  assert.equal(comicResult.unsupported, true);
  assert.match(comicResult.emptyMessage, /图文|图片/);
  await browseList({});
  assert.equal(calls.length, 2, '再次访问应使用短缓存');
  await browseList({ forceRefresh: true });
  assert.equal(calls.length, 3, '手动刷新必须跳过缓存');
  const filtered = await browseList({ genre: 'romance', page: 2 });
  assert.equal(calls.at(-1).url, 'https://hongguoduanju.com/category/real-drama/romance?page=2');
  assert.equal(filtered.page, 2);
  assert.equal(calls[0].options.timeout, 10000);
  assert(calls[0].options.signal, '请求超时必须同时取消网络请求');
  response = async () => ({ data: '<html>blocked</html>' });
  const malformed = await browseList({ forceRefresh: true });
  assert.equal(malformed.success, false, '目录结构缺失不能假装成功空态');
  response = () => new Promise(() => {});
  const timeout = await browseList({ category: 'ai-drama' });
  assert.equal(timeout.success, false);
  assert.match(timeout.error, /超时/);
  assert.equal(calls.at(-1).options.signal.aborted, true);
  response = async () => ({ data: html('comic-drama') });
  const animation = await browseList({ category: 'comic-drama' });
  assert.equal(animation.results.length, 1, '漫剧是视频，不能误判为图片漫画');
  const invalid = await browseList({ category: '../../outside' });
  assert.equal(invalid.success, false);
  console.log('PASS catalog: category isolation, SSR parsing, comic distinction, dedup/cache/refresh, filters/pagination, bounded timeout and invalid data');
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
