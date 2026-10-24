/** Android-only JSON RPC. No Electron or browser networking fallback. */
const METHODS = new Set([
  'getInfo', 'httpGet', 'getLibrary', 'toggleFavorite', 'play',
  'getAccount', 'accountLogin', 'accountLogout', 'syncHistory', 'importGuestHistory',
  'download', 'getDownloads', 'playDownload', 'openExternal',
  'getPlaybackCache', 'clearPlaybackCache',
  'appRequest', 'appResolveResult', 'getPlaybackOptions', 'setPlaybackOptions',
  'getSearchHistory', 'addSearchHistory', 'removeSearchHistory', 'clearSearchHistory', 'searchQuery',
]);
const pending = new Map();
let sequence = 0;

const PLAYBACK_PREFERENCES = 'hongguo.playbackPreferences';

/** Defaults apply to both streamed episodes and manually downloaded episodes. */
export function getPlaybackPreferences() {
  let saved = {};
  try { saved = JSON.parse(globalThis.localStorage?.getItem(PLAYBACK_PREFERENCES) || '{}') || {}; } catch { /* Older/invalid preferences use defaults. */ }
  return { autoplay: saved.autoplay !== false, cleanupPlaybackCache: saved.cleanupPlaybackCache !== false, keepPreviousEpisodes: 1 };
}

export function setPlaybackPreference(key, value) {
  const preferences = getPlaybackPreferences();
  if (key !== 'autoplay' && key !== 'cleanupPlaybackCache') return preferences;
  preferences[key] = !!value;
  try { globalThis.localStorage?.setItem(PLAYBACK_PREFERENCES, JSON.stringify(preferences)); } catch { /* Current screen can still use the selected value. */ }
  return preferences;
}

// Initial play can include a bounded web preflight plus a fresh App lookup.
export function nativeCall(method, params = {}, timeoutMs = method === 'download' ? 180000 : method === 'play' ? 75000 : 45000) {
  if (!METHODS.has(method)) return Promise.reject(new Error('未支持的设备操作'));
  if (!globalThis.window?.AndroidBridge?.postMessage) {
    return Promise.reject(new Error('请在安卓安装包中打开；网页预览未连接设备功能。'));
  }
  const id = `android-${Date.now()}-${++sequence}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      const error = new Error('设备操作超时，请检查网络后重试。');
      if (method === 'appRequest') error.code = 'ETIMEDOUT';
      reject(error);
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer });
    try {
      window.AndroidBridge.postMessage(JSON.stringify({ id, method, params }));
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(error instanceof Error ? error : new Error(String(error)));
    }
  });
}

if (typeof window !== 'undefined') {
  window.__androidResponse = (message) => {
    if (!message || typeof message !== 'object' || typeof message.id !== 'string') return;
    const request = pending.get(message.id);
    if (!request) return;
    clearTimeout(request.timer);
    pending.delete(message.id);
    if (message.error != null) request.reject(new Error(String(message.error)));
    else request.resolve(message.result);
  };
  window.__androidResume = () => window.dispatchEvent(new Event('androidresume'));
  window.__androidAccountChanged = () => window.dispatchEvent(new Event('androidaccountchanged'));
}

export async function requestHtml(url) {
  const response = await nativeCall('httpGet', { url });
  if (!response || response.status < 200 || response.status >= 300) {
    throw new Error(`请求失败（HTTP ${response?.status ?? '未知'}），请稍后重试。`);
  }
  if (typeof response.body !== 'string') throw new Error('服务器返回了无效页面。');
  return response.body;
}
