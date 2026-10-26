import desktopAppSource from '../../native/app-source.js';
import desktopQuality from '../../native/download-quality.js';
import { setNativeCall } from './axios-native.mjs';
const TRANSIENT_LOOKUP_CODES = new Set(['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENETUNREACH', 'EPIPE']);
const RESPONSE_LOOKUP_CODES = new Set(['APP_EMPTY_RESPONSE', 'APP_INVALID_RESPONSE']);

/** Protocol, signatures, Spade parsing and quality selection use the shared Mac module. */
export async function fetchAppPlayUrl(vid, options = {}) {
  const quality = desktopQuality.normalizeDownloadQuality(options.quality);
  let info;
  try { info = await desktopAppSource.fetchAppPlayUrl(vid, { ...options, quality }); }
  catch (error) {
    if (quality !== 'auto' && /暂无尺寸可确认且满足/.test(error?.message || '')) error.code = 'QUALITY_UNAVAILABLE';
    throw error;
  }
  const rows = [info, ...(Array.isArray(info.variants) ? info.variants : [])];
  if (quality !== 'auto' && rows.some(row => !Number.isSafeInteger(row.width) || !Number.isSafeInteger(row.height) || !desktopQuality.meetsDownloadQuality(row, quality))) {
    throw Object.assign(new Error(`本集暂无尺寸可确认且满足 ${quality} 的片源，未降级。`), { code: 'QUALITY_UNAVAILABLE' });
  }
  // The desktop list includes the primary; Android accepts eight distinct URLs total.
  // Validate every candidate's quality above before removing duplicates or trimming.
  const seen = new Set([info.url]);
  const variants = Array.isArray(info.variants) ? info.variants.filter(row => {
    if (seen.has(row.url)) return false;
    seen.add(row.url); return true;
  }).slice(0, 7).map(row => ({ ...row, requestedQuality: quality })) : null;
  return { ...info, requestedQuality: quality, ...(variants ? { variants } : {}) };
}
export function registerAppResolver(nativeCall) {
  setNativeCall(nativeCall);
  if (typeof globalThis.window === 'undefined') throw new Error('需要安卓应用页面环境');
  const handler = (requestId, seriesId, vid, quality = 'auto') => Promise.resolve().then(() => {
    if (typeof requestId !== 'string' || !requestId || requestId.length > 128 ||
        typeof seriesId !== 'string' || !/^\d{1,30}$/.test(seriesId) || typeof vid !== 'string' || !/^\d{1,30}$/.test(vid)) throw new Error('App 分集请求信息无效');
    desktopQuality.normalizeDownloadQuality(quality);
    return fetchAppPlayUrl(vid, { quality });
  }).then(
    playInfo => nativeCall('appResolveResult', { requestId, seriesId, vid, requestedQuality: quality, playInfo }),
    error => nativeCall('appResolveResult', { requestId, seriesId, vid, requestedQuality: quality, error: error?.message || 'App 片源暂不可用',
      ...(TRANSIENT_LOOKUP_CODES.has(error?.code) || RESPONSE_LOOKUP_CODES.has(error?.code) || error?.code === 'QUALITY_UNAVAILABLE' ? { errorCode: error.code } : {}) }),
  ).catch(() => undefined);
  window.__androidAppResolve = handler;
  return () => { if (window.__androidAppResolve === handler) delete window.__androidAppResolve; };
}
