#!/usr/bin/env node
/** Real React App, bridge and directory/search parsers in isolated local Chrome.
 * All native RPC responses are synthetic. No provider, media or device is used. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = ownRoot, output = path.join(ownRoot, 'verification/android-account-sync/ui');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-account-ui.mjs [--root PROJECT] [--output DIRECTORY]');
}
const { build } = createRequire(import.meta.url)('esbuild');
const SID = ['7000000000000000001', '7000000000000000002', '7000000000000000003', '7000000000000000004', '7000000000000000005', '7000000000000000006', '7000000000000000007', '7000000000000000008'];
function installMock() {
  const ids = ['7000000000000000001', '7000000000000000002', '7000000000000000003', '7000000000000000004', '7000000000000000005', '7000000000000000006', '7000000000000000007', '7000000000000000008'];
  const specs = [
    ['合成：山海：第一季', 65], ['合成：山海：第二季', 43], ['合成：山海：第三季', 3],
    ['合成：山海：第四季', 100], ['合成：山海：第四季', 219],
    ['合成山海：第三季', 12], ['独立故事', 8], ['另一故事：第一季', 9],
  ];
  const entries = Object.fromEntries(specs.map(([title, count], index) => {
    const seriesId = ids[index];
    const episodes = Array.from({ length: count }, (_, i) => ({ vid: String(8000000000000000000n + BigInt(index) * 10000n + BigInt(i)), index: i + 1, webAvailable: i < 3, availability: i < 3 ? 'available' : 'unknown' }));
    return [seriesId, { seriesId, title, episodeCount: count, episodes, description: `这是${title}的合成简介，用于验证切季后简介与分集独立。`.repeat(8) }];
  }));
  const first = entries[ids[0]], second = entries[ids[1]];
  const compact = item => ({ seriesId: item.seriesId, title: item.title, episodeCount: item.episodeCount });
  const f = window.__seasonFixture = {
    ids, entries, calls: [], resolves: [], searches: [], detailReads: [], notes: [],
    optionPlans: [], playPlans: [], downloadPlans: [], resolvePlans: [], searchPlans: [], detailPlans: {}, libraryPlans: [],
    options: { quality: '1080p', speed: 1 }, searchKeywords: [],
    library: { favorites: [], history: [
      { ...compact(first), ...first.episodes[31], positionMs: 3200, updatedAt: 1000 },
      { ...compact(second), ...second.episodes[8], positionMs: 9000, updatedAt: 2000 },
    ] },
  };
  f.guest = JSON.parse(JSON.stringify(f.library.history));
  f.account = { serverUrl: '', authenticated: false, user: null, syncing: false, lastSyncAt: 0, pendingCount: 0, error: '', loginRequired: false };
  f.accountPlans = []; f.loginPlans = []; f.logoutPlans = []; f.syncPlans = []; f.importPlans = [];
  const scenario = new URL(location.href).searchParams.get('scenario');
  if (scenario === 'cap-current') entries['7000000000000000099'] = { seriesId: '7000000000000000099', title: '合成：山海：第99季', episodeCount: 6, episodes: Array.from({ length: 6 }, (_, i) => ({ vid: String(8000000000000990000n + BigInt(i)), index: i + 1 })), description: '合成的第99季，用于验证分页不丢失当前季。' };
  if (scenario === 'identical-editions') { entries[ids[4]].episodeCount = 100; entries[ids[4]].episodes = entries[ids[4]].episodes.slice(0, 100); }
  if (scenario === 'invalid-title') { entries[ids[6]].title = '合成超长标题'.repeat(40) + '第一季'; entries[ids[6]].description = '自动分季查找失败仍允许原有详情和播放。'; }
  if (scenario === 'lookup-error') f.searchPlans.push({ error: 'MOCK_SEASON_SEARCH_FAILED' });
  if (scenario === 'lookup-delay') f.searchPlans.push({ delay: 500 });
  if (scenario === 'lookup-hang') f.searchPlans.push({ delay: 10000 });
  if (scenario === 'oversized') { first.episodeCount=4000;first.episodes=Array.from({length:4000},(_,i)=>({vid:String(8000000000000000000n+BigInt(i)),index:i+1})); }
  if (scenario === 'nine') { f.nineItems = Array.from({length:9},(_,n)=>{const id=ids[n]||'7000000000000000009';const value={seriesId:id,title:`合成：山海：第${n+1}季`,episodeCount:100,episodes:Array.from({length:100},(_,i)=>({vid:String(8000000000000000000n+BigInt(n)*10000n+BigInt(i)),index:i+1})),description:'九季目录测试'}; if(entries[id])Object.assign(entries[id],value);else entries[id]=value;return entries[id];}); }
  const raw = item => ({ series_id: item.seriesId, series_name: item.title, series_intro: item.description,
    episode_cnt: item.episodeCount, accessible_episode_cnt: 3, vid_list: item.episodes.map(e => e.vid) });
  const pageHtml = loaderData => `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData })};</script>`;
  const searchResponse = (keyword, offset) => {
    let items, more;
    if (scenario === 'nine') { items=f.nineItems;more=false; } else if (scenario === 'cap-current') {
      items = offset === 0 ? [first, entries['7000000000000000099']] : Array.from({ length: 39 }, (_, n) => ({ seriesId: String(7200000000000000000n + BigInt(n)), title: `合成：山海：第${n + 2}季`, episodeCount: n + 2 }));
      more = offset === 0;
    } else if (scenario === 'capacity') {
      items = Array.from({ length: offset === 0 ? 25 : 20 }, (_, n) => {
        const number = (offset === 0 ? 1 : 26) + n;
        return { seriesId: number === 1 ? ids[0] : String(7100000000000000000n + BigInt(number)), title: `合成：山海：第${number}季`, episodeCount: 10 + number };
      });
      more = true;
    } else if (keyword === '另一故事') { items = [entries[ids[7]]]; more = false; }
    else if (keyword === '独立故事') { items = [entries[ids[6]]]; more = false; }
    else if (offset === 0) { items = [entries[ids[0]], entries[ids[1]], entries[ids[3]], entries[ids[5]], entries[ids[7]]]; more = true; }
    else { items = [entries[ids[0]], entries[ids[2]], entries[ids[4]]]; more = scenario === 'expires' && offset === 20; }
    return { code: 0, search_tabs: [{ tab_type: 11, has_more: more, next_offset: offset + 20,
      search_id: 'synthetic-search', passback: `synthetic-${offset + 20}`,
      data: items.map(item => ({ video_data: [{ series_id: item.seriesId, title: item.title, episode_cnt: item.episodeCount }] })) }] };
  };
  window.AndroidBridge = { postMessage(rawMessage) {
    const m = JSON.parse(rawMessage); f.calls.push(m);
    let result, error, delay = 1, plan = {};
    if (m.method === 'getInfo') result = { isTv: new URL(location.href).searchParams.get('tv') === '1', version: 'MOCK-seasons-ui' };
    else if (m.method === 'getAccount') { plan = f.accountPlans.shift() || {}; result = JSON.parse(JSON.stringify(f.account)); }
    else if (m.method === 'accountLogin') { plan = f.loginPlans.shift() || {}; result = null; }
    else if (m.method === 'accountLogout') { plan = f.logoutPlans.shift() || {}; result = null; }
    else if (m.method === 'importGuestHistory') { plan = f.importPlans.shift() || {}; result = null; }
    else if (m.method === 'syncHistory') { plan = f.syncPlans.shift() || {}; result = null; }
    else if (m.method === 'getLibrary') { plan = f.libraryPlans.shift() || {}; result = JSON.parse(JSON.stringify(f.library)); }
    else if (m.method === 'httpGet') {
      const u = new URL(m.params.url);
      if (u.pathname.startsWith('/category/')) result = { status: 200, body: pageHtml({ catalog: { isSuccess: true, categoryRoute: { contentType: u.pathname.split('/')[2] }, recommendList: [first, entries[ids[6]], entries[ids[7]]].map(raw), pagination: { pageNum: 1, totalPages: 1, total: 3 } } }) };
      else if (/^\/player\/\d+$/.test(u.pathname)) {
        const sid = u.pathname.split('/')[2], item = entries[sid];
        f.detailReads.push(sid); plan = f.detailPlans[sid]?.shift() || {};
        result = item ? { status: 200, body: pageHtml({ 'player_(series_id)/page': { series_id: sid, seriesDetail: raw(item) } }) } : { status: 404, body: '' };
      } else { error = 'UNEXPECTED_HTTP_' + u.pathname; f.notes.push(error); }
    } else if (m.method === 'searchQuery') { f.searches.push(m.params); plan = f.searchPlans.shift() || {}; result = searchResponse(m.params.keyword, m.params.offset); }
    else if (m.method === 'getPlaybackOptions') { plan = f.optionPlans.shift() || {}; result = plan.options || { ...f.options }; }
    else if (m.method === 'setPlaybackOptions') { f.options = { ...f.options, ...m.params }; result = { ...f.options }; }
    else if (m.method === 'play') { plan = f.playPlans.shift() || {}; result = { started: true }; }
    else if (m.method === 'download') { plan = f.downloadPlans.shift() || {}; result = { id: 1 }; }
    else if (m.method === 'getDownloads') result = [];
    else if (m.method === 'getPlaybackCache') result = { files: 0, bytes: 0 };
    else if (m.method === 'getSearchHistory') result = { keywords: [...f.searchKeywords] };
    else if (m.method === 'addSearchHistory') { f.searchKeywords = [m.params.keyword, ...f.searchKeywords.filter(x => x !== m.params.keyword)].slice(0, 20); result = { keywords: [...f.searchKeywords] }; }
    else if (m.method === 'toggleFavorite') { f.library.favorites = [m.params.series]; result = f.library; }
    else { error = 'UNEXPECTED_RPC_' + m.method; f.notes.push(error); }
    delay = plan.delay || delay; error = plan.error || error;
    setTimeout(() => {
      if (m.method === 'play' && !error) {
        f.library.history = [{ ...m.params.series, ...m.params.episode, positionMs: 1234, updatedAt: Date.now() }, ...f.library.history.filter(x => x.seriesId !== m.params.series.seriesId)];
      }
      if (!error && m.method === 'accountLogin') {
        f.account = { ...f.account, serverUrl: m.params.serverUrl, authenticated: true, user: { id: m.params.username, username: m.params.username }, syncing: false, lastSyncAt: 1000, pendingCount: 0, error: '', loginRequired: false };
        f.library.history = [{ ...compact(first), ...first.episodes[3], title: m.params.username + ' 账号记录', positionMs: 42000, updatedAt: 1000 }];
        result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.();
      }
      if (!error && m.method === 'accountLogout') { f.account = { ...f.account, authenticated: false, user: null, lastSyncAt: 0 }; f.library.history = JSON.parse(JSON.stringify(f.guest)); result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.(); }
      if (!error && m.method === 'importGuestHistory') {
        const imported = f.guest.filter(row => !f.library.history.some(existing => existing.seriesId === row.seriesId));
        f.library.history.push(...JSON.parse(JSON.stringify(imported)));
        result = { ...f.account, importedCount: imported.length, skippedCount: f.guest.length - imported.length };
        window.__androidAccountChanged?.();
      }
      if (!error && m.method === 'syncHistory') { Object.assign(f.account, { ...plan.account, lastSyncAt: Date.now() }); if (plan.positionMs) f.library.history[0].positionMs = plan.positionMs; result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.(); }
      window.__androidResponse({ id: m.id, ...(error ? { error } : { result }) });
      if (m.method === 'play' && !error) window.dispatchEvent(new Event('androidresume'));
    }, delay);
  } };
}
fs.mkdirSync(output,{recursive:true});
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-seasons-ui-'));
const report={kind:'real React App + real bridge, MOCK native responses with real source/search parsers; not device or stream QA',root,tests:[],screenshots:[],externalRequests:[],pageErrors:[]};
let chrome, server, ws, cdp;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let passed=0,failed=0,checks=0;
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(a,b,message)=>{checks++;assert.deepEqual(a,b,message);};
try {
  const bundled=await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root,'src/android/App.jsx'))};createRoot(document.getElementById('root')).render(React.createElement(App));`,resolveDir:ownRoot,sourcefile:'mobile-ui-fixture-entry.jsx',loader:'jsx'},bundle:true,write:false,format:'iife',platform:'browser',target:'chrome100',nodePaths:[path.join(ownRoot,'node_modules')],plugins:[{name:'mock-sources-only',setup(build){build.onResolve({filter:/generated\/mac-app-source\.mjs$/},()=>({path:'protocol',namespace:'fixture-protocol'}));build.onLoad({filter:/.*/,namespace:'fixture-protocol'},()=>({contents:`export const fetchAppPlayUrl=async(vid,options)=>{const f=window.__seasonFixture;f.resolves.push({vid,...options});const plan=f.resolvePlans.shift()||{};if(plan.delay)await new Promise(r=>setTimeout(r,plan.delay));if(plan.error)throw Error(plan.error);return {seriesId:options.seriesId,vid,url:'https://media.cdnfixture.net/never-requested.mp4',source:'app',width:1080,height:1920,requestedQuality:options.quality};};`,loader:'js'}));build.onLoad({filter:/LICENSE\?raw$/},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));build.onResolve({filter:/LICENSE\?raw$/},()=>({path:'LICENSE?raw',namespace:'fixture-license'}));build.onLoad({filter:/.*/,namespace:'fixture-license'},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));}}]});
  const js=bundled.outputFiles[0].text,css=fs.readFileSync(path.join(root,'src/android/style.css'),'utf8');
  // This gate mounts App directly with mocked native/source responses. The separate
  // check-android-tv-webview gate owns the real packaged bootstrap and readiness.
  const fixtureHtml=fs.readFileSync(path.join(root,'src/android/index.html'),'utf8')
    .replace(/<script\b[^>]*\bdata-hongguo-boot\b[^>]*><\/script>/,'')
    .replace(/<link\b[^>]*href="\/startup\.css"[^>]*>/,'')
    .replace(/<script[^>]+src="\/main\.jsx"[^>]*><\/script>/,'<script src="/fixture.js"></script>')
    .replace('</head>','<link rel="stylesheet" href="/fixture.css"></head>');
  server=http.createServer((request,response)=>{if(request.url==='/fixture.js'){response.setHeader('content-type','text/javascript');response.end(js);}else if(request.url==='/fixture.css'){response.setHeader('content-type','text/css');response.end(css);}else {response.setHeader('content-type','text/html');response.end(fixtureHtml);}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}/`;
  const chromeBin=process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  const profile=path.join(temporary,'profile');
  chrome=spawn(chromeBin,['--headless=new','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--no-first-run','--disable-background-networking','--disable-default-apps','--no-default-browser-check','about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(output,'chrome.log'),'w')]});
  let port;
  for(let i=0;i<100;i++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break;}catch{}await sleep(100);}
  if(!port)throw Error('Chrome CDP did not start');
  cdp=`http://127.0.0.1:${port}`;
  const target=await fetch(`${cdp}/json/new?about:blank`,{method:'PUT'}).then(r=>r.json());
  ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let seq=0;const pending=new Map();
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}if(m.method==='Runtime.exceptionThrown')report.pageErrors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);if(m.method==='Network.requestWillBeSent'){const url=m.params.request.url;if(!url.startsWith(base)&&!url.startsWith('data:'))report.externalRequests.push(url);}});
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
  const wait=async(predicate,label=predicate)=>{for(let i=0;i<100;i++){if(await evaluate(`Boolean(${predicate})`))return;await sleep(25);}throw Error('WAIT_FAILED '+label);};
  const group=async(name,fn)=>{try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);}};
  const q = value => JSON.stringify(value);
  const selector = id => `.season-selector button[data-season-id="${id}"]`;
  const plays = () => evaluate('window.__seasonFixture.calls.filter(x=>x.method==="play").map(x=>x.params)');
  const currentId = () => evaluate('document.querySelector(".season-selector button[aria-selected=true]")?.dataset.seasonId');
  const currentTitle = () => evaluate('document.querySelector(".detail-copy h1")?.textContent');
  const selectedEpisode = () => evaluate('Number(document.querySelector(".episode-grid button[aria-pressed=true] span")?.textContent)');
  const idle = () => wait('document.querySelector(".play-actions .primary")&&!document.querySelector(".play-actions .primary").disabled');
  const ready = async id => {
    await wait(`document.querySelector('.detail-copy h1')?.textContent===window.__seasonFixture.entries[${q(id)}].title&&document.querySelector('.episode-grid button')&&document.querySelector('select[aria-label="清晰度"]').value==='1080p'`, `loaded season ${id}`);
    await sleep(25);
  };
  const choose = async id => { await wait(`document.querySelector(${q(selector(id))})`); await evaluate(`document.querySelector(${q(selector(id))}).click()`); };
  const clickEpisode = index => evaluate(`(()=>{const b=[...document.querySelectorAll('.episode-grid button')].find(b=>Number(b.querySelector('span').textContent)===${index});if(!b)throw Error('missing episode ${index}');b.click()})()`);
  const button = (label, scope='document') => evaluate(`(()=>{const b=[...${scope}.querySelectorAll('button')].find(x=>x.textContent.trim()===${q(label)});if(!b)throw Error('missing button '+${q(label)});b.click()})()`);
  const nav = label => evaluate(`[...document.querySelectorAll('.app-nav button')].find(b=>b.textContent.replace(/\\s/g,'')===${q(label)}).click()`);
  const key = async (name, code) => { await call('Input.dispatchKeyEvent',{type:'keyDown',key:name,code:name,windowsVirtualKeyCode:code}); await call('Input.dispatchKeyEvent',{type:'keyUp',key:name,code:name,windowsVirtualKeyCode:code}); };
  const reset = async ({tv=false,width=390,height=844,large=false,scenario='',open=true,index=0}={}) => {
    await call('Emulation.setDeviceMetricsOverride',{width:tv?1280:width,height:tv?720:height,deviceScaleFactor:1,mobile:!tv});
    await call('Emulation.setTouchEmulationEnabled',{enabled:!tv});
    await call('Page.navigate',{url:base+`?tv=${tv?1:0}&scenario=${encodeURIComponent(scenario)}`});
    await wait('document.querySelectorAll(".poster").length===3&&window.__seasonFixture.calls.some(c=>c.method==="getLibrary")');
    await sleep(20);
    if(large)await evaluate('document.documentElement.style.fontSize="24px"');
    if(open){await evaluate(`document.querySelectorAll('.poster')[${index}].click()`);await ready(SID[index===0?0:index===1?6:7]);}
    await evaluate('window.__seasonFixture.calls=[];window.__seasonFixture.resolves=[];');
  };
  const more = async () => { await wait('document.querySelector("[data-season-action=more]")&&!document.querySelector("[data-season-action=more]").disabled');await evaluate('document.querySelector("[data-season-action=more]").click()'); };
  const screenshot = async name => { const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));report.screenshots.push(file); };
  const noOverflow = async () => {
    const sizes=await evaluate(`(()=>{const nodes=[document.documentElement,document.querySelector('.main-content'),document.querySelector('.detail-scroll'),document.querySelector('.season-selector')].filter(Boolean);return nodes.map(e=>({class:e.className||'html',width:e.clientWidth,scroll:e.scrollWidth}));})()`);
    check(sizes.every(s=>s.scroll<=s.width+1),'no horizontal overflow '+JSON.stringify(sizes));
  };
  const seasonTargets = async tv => {
    const sizes=await evaluate(`[...document.querySelectorAll('.season-selector button')].filter(e=>e.getClientRects().length).map(e=>({title:e.textContent,w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height}))`);
    check(sizes.length>0,'season controls rendered');
    check(sizes.every(s=>s.w>=(tv?53.5:43.5)&&s.h>=(tv?53.5:43.5)),`season targets minimum ${tv?54:44}px `+JSON.stringify(sizes));
  };
  const dockVisible = async () => {
    const p=await evaluate(`(()=>{const r=e=>e.getBoundingClientRect().toJSON();return {dock:r(document.querySelector('.play-actions')),nav:r(document.querySelector('.app-nav')),buttons:[...document.querySelectorAll('.play-actions button')].map(r),height:innerHeight,width:innerWidth};})()`);
    check(p.dock.top>=0&&p.dock.bottom<=p.nav.top+1,'action dock above mobile navigation');
    check(p.buttons.every(r=>r.top>=0&&r.bottom<=p.nav.top+1&&r.left>=0&&r.right<=p.width),'play/download actions remain reachable');
  };
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');
  // Block anything outside this temporary server before Chrome can load it.
  await call('Fetch.enable',{patterns:[{urlPattern:'*'}]});
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.method!=='Fetch.requestPaused')return;const url=m.params.request.url;const allowed=url.startsWith(base)||url.startsWith('data:');call(allowed?'Fetch.continueRequest':'Fetch.failRequest',{requestId:m.params.requestId,...(allowed?{}:{errorReason:'BlockedByClient'})}).catch(error=>report.pageErrors.push(error.message));});
  await call('Page.addScriptToEvaluateOnNewDocument',{source:`(${installMock.toString()})();`});


  const accountReady = () => wait('document.querySelector(".account-status")&&!document.querySelector(".account-status").textContent.includes("正在读取")');
  const about = async () => { await nav('关于'); await accountReady(); };
  const field = (id, value) => evaluate(`(()=>{const e=document.getElementById(${q(id)});Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,${q(value)});e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const fillLogin = async (name='alice', server='https://sync.example.com:9443', password='fixture Password!') => { await field('account-server',server); await field('account-username',name); await field('account-password',password); };
  const submit = () => evaluate('document.querySelector(".account-form").dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}))');
  const loggedIn = () => wait('document.querySelector(".account-details")&&!document.querySelector(".account-status").textContent.includes("正在登录")');
  const login = async name => { await fillLogin(name); await submit(); await loggedIn(); };
  const history = async () => { await nav('我的追剧'); await evaluate('[...document.querySelectorAll(".section-tabs button")].find(e=>e.textContent.includes("观看历史")).click()'); await wait('!document.querySelector(".state-panel [role=status]")'); };
  const accountCalls = method => evaluate(`window.__seasonFixture.calls.filter(c=>c.method===${q(method)}).map(c=>c.params)`);
  await group('PHONE account form has persistent labels and no overflow at 320/390 large text', async () => {
    for (const width of [320,390]) {
      await reset({width,large:true,open:false}); await about();
      check(await evaluate('document.querySelector(".account-status").textContent.includes("游客模式")'),'guest mode is explicit');
      eq(await evaluate('[...document.querySelectorAll(".account-form label")].map(e=>e.htmlFor)'),['account-server','account-username','account-password'],'all credential inputs have visible labels');
      const sizes=await evaluate('[...document.querySelectorAll(".account-form input,.account-form button")].map(e=>({w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height}))');
      check(sizes.every(r=>r.h>=44&&r.w>=44),'touch targets are at least 44px'); await noOverflow();
      check(await evaluate('document.querySelector(".account-note").textContent.includes("视频缓存保留在本机")'),'scope states that cache stays local');
      await screenshot(`phone-${width}-account.png`);
    }
  });
  await group('TV D-pad moves through server, username, password, submit with visible focus', async () => {
    await reset({tv:true,open:false}); await about(); await fillLogin();
    await evaluate('document.getElementById("account-server").focus()');
    for(const next of ['account-username','account-password']) { await key('ArrowDown',40); eq(await evaluate('document.activeElement.id'),next,'next form control '+next); }
    await key('ArrowDown',40); eq(await evaluate('document.activeElement.closest(".account-form")?.className'), 'account-form', 'remote remains in login form '+await evaluate('document.activeElement.outerHTML'));
    eq(await evaluate('document.activeElement.type'),'submit','remote reaches login');
    check(await evaluate('getComputedStyle(document.activeElement).outlineStyle!=="none"'),'TV focus outline visible');
    await key('Enter',13);await loggedIn();eq((await accountCalls('accountLogin'))[0].deviceName,'Android TV','TV device name'); await noOverflow(); await screenshot('tv-account-signed-in.png');
  });
  await group('LOGIN custom server and exact password pass only to native; new account history replaces guest', async () => {
    await reset({open:false}); await about(); await fillLogin('alice','https://sync.example.com:9443/',' fixture password '); await submit(); await loggedIn();
    eq((await accountCalls('accountLogin'))[0],{serverUrl:'https://sync.example.com:9443',username:'alice',password:' fixture password ',deviceName:'Android 手机'},'exact native contract');
    check(await evaluate('!document.querySelector("#account-password")'),'password input destroyed after login');
    check(await evaluate('!JSON.stringify(localStorage).includes("password")&&!JSON.stringify(localStorage).includes("alice")'),'no web credential storage');
    await history();await wait('document.querySelector(".poster strong")?.textContent==="alice 账号记录"');eq(await evaluate('document.querySelectorAll(".poster").length'),1,'guest records not imported');
    check(await evaluate('document.querySelector(".poster-caption").textContent.includes("0:42")'),'synced playback progress visible');
  });
  await group('LOGIN validation rejects HTTP, credentials and API paths before native', async () => {
    await reset({open:false});await about();
    for (const url of ['http://sync.example.com','https://a:b@sync.example.com','https://sync.example.com/api']) {
      await fillLogin('alice',url);await submit();await wait('document.querySelector(".account-error")');eq((await accountCalls('accountLogin')).length,0,'invalid URL blocked');eq(await evaluate('document.querySelector("#account-password").value'),'','password cleared after validation failure');
    }
  });
  await group('FAILED LOGIN retains guest data, reveals actionable error and clears password', async () => {
    await reset({open:false});await about();await evaluate('window.__seasonFixture.loginPlans=[{error:"账号或密码不正确"}]');await fillLogin();await submit();
    await wait('document.querySelector(".account-error")?.textContent.includes("账号或密码不正确")');
    eq(await evaluate('document.querySelector("#account-password").value'),'','failed password cleared');
    await history();await wait('document.querySelectorAll(".poster").length===2');
    await about();eq(await evaluate('document.querySelector("#account-password").value'),'','navigation never restores password');
  });
  await group('STALE guest library response cannot overwrite history after account login', async () => {
    await reset({open:false});await about();
    await evaluate('window.__seasonFixture.libraryPlans=[{delay:450}];window.dispatchEvent(new Event("androidresume"));window.__seasonFixture.loginPlans=[{delay:60}]');
    await fillLogin('bob');await submit();await loggedIn();await sleep(500);await history();
    await wait('document.querySelector(".poster strong")?.textContent==="bob 账号记录"');eq(await evaluate('document.querySelectorAll(".poster").length'),1,'late guest read ignored');
  });
  await group('MANUAL sync updates progress and native account event refreshes background library', async () => {
    await reset({open:false});await about();await login();
    await evaluate('window.__seasonFixture.syncPlans=[{positionMs:89000}]');await button('立即同步');await wait('document.querySelector(".account-status").textContent.includes("最近同步")');
    await history();await wait('document.querySelector(".poster-caption")?.textContent.includes("1:29")');
    await evaluate('window.__seasonFixture.library.history[0].positionMs=91000;window.__androidAccountChanged()');await wait('document.querySelector(".poster-caption")?.textContent.includes("1:31")');
    eq((await accountCalls('play')).length,0,'sync never starts playback');
  });
  await group('OFFLINE sync error retains authenticated session and allows retry', async () => {
    await reset({open:false});await about();await login();
    await evaluate('window.__seasonFixture.syncPlans=[{account:{error:"服务器暂时连接不上",pendingCount:3}}]');await button('立即同步');await wait('document.querySelector(".account-error")?.textContent.includes("连接不上")');
    check(await evaluate('document.querySelector(".account-status").textContent.includes("本机进度已保留")'),'offline local retention explained');
    check(await evaluate('!!document.querySelector(".account-details")'),'offline stays authenticated');
    await evaluate('window.__seasonFixture.syncPlans=[{account:{error:"",pendingCount:0}}]');await button('重试同步');await wait('!document.querySelector(".account-error")');
  });
  await group('IMPORT guest history requires confirmation, skips existing records and preserves guest originals on phone/TV', async () => {
    for (const tv of [false, true]) {
      await reset({tv,open:false});await about();await login();
      eq((await accountCalls('importGuestHistory')).length,0,'login does not auto import');
      await button('导入本机游客记录');await wait('document.querySelector("#account-import-confirm")');
      check(await evaluate('document.querySelector("#account-import-confirm").textContent.includes("不覆盖云端进度")'),'merge rule explained');
      eq(await evaluate('document.activeElement.textContent'),'取消','cancel focused before import');
      await button('取消');eq((await accountCalls('importGuestHistory')).length,0,'cancel leaves all records untouched');
      await button('导入本机游客记录');await evaluate('window.__seasonFixture.importPlans=[{delay:100}];const b=[...document.querySelectorAll("#account-import-confirm button")].find(e=>e.textContent==="确认导入");b.click();b.click()');
      await wait('document.querySelector(".account-section").textContent.includes("已补充 1 部剧")');
      eq((await accountCalls('importGuestHistory')).length,1,'double confirm dispatches one import');
      check(await evaluate('document.querySelector(".account-section").textContent.includes("跳过 1 条已有或无效记录")'),'existing or invalid legacy records explicitly described');
      await history();await wait('document.querySelectorAll(".poster").length===2');
      check(await evaluate('document.querySelector(".poster strong").textContent.includes("alice")&&document.querySelector(".poster-caption").textContent.includes("0:42")'),'existing account progress unchanged');
      eq(await evaluate('window.__seasonFixture.guest.length'),2,'original guest history preserved');
    }
  });
  await group('IMPORT failure remains retryable and does not claim success', async () => {
    await reset({open:false});await about();await login();await button('导入本机游客记录');
    await evaluate('window.__seasonFixture.importPlans=[{error:"首次同步尚未完成，请稍后导入"}]');await button('确认导入');
    await wait('document.querySelector(".account-error")?.textContent.includes("首次同步")');
    check(await evaluate('!!document.querySelector("#account-import-confirm")'),'failed import retains retry confirmation');
    check(await evaluate('!document.querySelector(".account-section").textContent.includes("已补充")'),'no false success notice');
    await button('确认导入');await wait('document.querySelector(".account-section").textContent.includes("已补充 1 部剧")');
  });
  await group('LOGOUT cancel preserves account; confirm restores guest while favorites remain', async () => {
    await reset({open:false});await evaluate('window.__seasonFixture.library.favorites=[{seriesId:"favorite",title:"本机收藏"}]');await about();await login();
    await button('退出账号');await wait('document.querySelector(".account-confirm")');eq(await evaluate('document.activeElement.textContent'),'取消','least-destructive confirmation focused');await button('取消');eq((await accountCalls('accountLogout')).length,0,'cancel never sends logout');
    await button('退出账号');await button('确认退出');await wait('!!document.querySelector("#account-password")');eq(await evaluate('document.querySelector("#account-password").value'),'','logout form password empty');
    await history();await wait('document.querySelectorAll(".poster").length===2');check(await evaluate('document.querySelector(".section-tabs").textContent.includes("追剧 (1)")'),'favorites unchanged');
  });
  await group('LOGIN navigating away in flight settles library and does not retain password', async () => {
    await reset({open:false});await about();await evaluate('window.__seasonFixture.loginPlans=[{delay:300}]');await fillLogin('alice');await submit();await history();
    await wait('document.querySelector(".poster strong")?.textContent==="alice 账号记录"');
    await about();await loggedIn();check(await evaluate('!document.querySelector("#account-password")'),'unmounted login completes without retaining form');
  });
  await group('EXPIRED session shows re-login form, server and username without old password', async () => {
    await reset({open:false});await about();await login();
    await evaluate('Object.assign(window.__seasonFixture.account,{authenticated:false,loginRequired:true,error:"登录已过期"});window.__androidAccountChanged()');
    await wait('!!document.querySelector("#account-password")');eq(await evaluate('document.querySelector("#account-username").value'),'alice','correct account retained');eq(await evaluate('document.querySelector("#account-password").value'),'','no password restored');
    check(await evaluate('document.querySelector(".account-status").textContent.includes("登录已过期")'),'expiry explicit');await field('account-password','fixture Password!');await submit();await loggedIn();
  });
  await group('LIBRARY shortcut opens account form and cache copy retains five episode behavior', async () => {
    await reset({open:false});await nav('我的追剧');await button('账号与同步');await accountReady();
    await wait('document.activeElement.id==="account-server"');eq(await evaluate('document.activeElement.id'),'account-server','shortcut focuses editable server');
    check(await evaluate('document.querySelector(".cache-note").textContent.includes("后面 5 集")'),'five episode cast prefetch explanation retained');
    check(await evaluate('document.querySelector(".cache-note").textContent.includes("不自动跨季")'),'no stale cross-season auto play claim');
    await screenshot('phone-account-guest.png');
  });
  await group('ISOLATION no browser network, exceptions or unexpected native calls', async () => {
    eq(report.externalRequests,[],'zero external requests');eq(report.pageErrors,[],'zero browser exceptions');eq(await evaluate('window.__seasonFixture.notes'),[],'zero unexpected RPCs');
  });
  await call('Page.close');ws.close();ws=null;
} catch(error) {
  if(!failed){failed++;report.tests.push({name:'runner',result:'FAIL',error:error.stack});console.error(error.stack);}
} finally {
  if(ws){try{ws.close();}catch{}}
  if(server)await new Promise(resolve=>server.close(resolve));
  if(chrome){chrome.kill('SIGTERM');await Promise.race([new Promise(resolve=>chrome.once('exit',resolve)),sleep(4000)]);if(chrome.exitCode===null)chrome.kill('SIGKILL');}
  fs.rmSync(temporary,{recursive:true,force:true});
  report.summary={groupsPassed:passed,groupsFailed:failed,assertions:checks,networkRequests:report.externalRequests.length,deviceTests:'not_run',adb:'not_used',emulator:'not_used',cleanup:'own local server and Chrome stopped; temporary profile removed'};
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(report,null,2)+'\n');
  console.log(`ANDROID_ACCOUNT_UI_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('MOCK_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode=failed?1:0;
