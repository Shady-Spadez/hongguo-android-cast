/**
 * Followed-series silent cache: the page owns the episode directory (it already
 * fetches detail pages), native owns storage and the single background download
 * thread. Enqueue order is watching priority — episodes after the last watched
 * one first, already-seen episodes as backfill — so the next thing the user
 * opens is usually ready before they get there.
 */
import { completedEpisode } from './history-view.mjs';
import { requestOfficialSeries } from './official-series.mjs';

const REFRESH_MS = 30 * 60 * 1000;
const SERIES_STAGGER_MS = 2000;
const SERIES_ID = /^\d{1,30}$/;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export function createFollowCache({ nativeCall, source }) {
  let sweeping = false;
  let latest = null;

  async function enqueueSeries(seriesId, lastWatchedIndex) {
    const detail = await source.detail(seriesId);
    const episodes = Array.isArray(detail?.episodes) ? detail.episodes.filter(ep => SERIES_ID.test(ep.vid)) : [];
    if (!episodes.length) return 0;
    // Unseen episodes first; watched ones only fill leftover budget later.
    const ordered = lastWatchedIndex > 0
      ? episodes.filter(ep => ep.index > lastWatchedIndex).concat(episodes.filter(ep => ep.index <= lastWatchedIndex))
      : episodes;
    const compact = { seriesId: detail.seriesId, title: detail.title, coverUrl: detail.coverUrl, episodeCount: detail.episodeCount, episodeLabel: detail.episodeLabel, tags: [] };
    const rows = ordered.map(ep => ({ vid: ep.vid, index: ep.index, webAvailable: !!ep.webAvailable, availability: ep.availability || 'unknown' }));
    // The library keeps the best tier a series offers (native falls back 720p → auto
    // per episode when 1080p is unavailable); it never follows the live quality setting.
    const result = await nativeCall('followCacheEnqueue', { series: compact, episodes: rows, quality: '1080p' }, 60000);
    return Number(result?.queued) || 0;
  }

  /** A followed multi-season show means the whole story: cache playable siblings too.
   *  Season listing is best-effort and never blocks the followed season itself. */
  async function enqueueSiblings(seed) {
    let queued = 0;
    if (!seed.multiSeason) return queued;
    try {
      const seasons = await requestOfficialSeries(seed.seriesId, { timeoutMs: 20000 });
      const items = Array.isArray(seasons?.items) ? seasons.items : [];
      for (const season of items) {
        if (!season || season.seriesId === seed.seriesId || season.upcoming || !season.playable) continue;
        try { queued += await enqueueSeries(season.seriesId, 0); } catch { /* Try the remaining seasons. */ }
        await sleep(SERIES_STAGGER_MS);
      }
    } catch { /* Sibling seasons are optional. */ }
    return queued;
  }

  async function sweep(library) {
    if (sweeping) return;
    const favorites = Array.isArray(library?.favorites) ? library.favorites : [];
    if (!favorites.length) return;
    sweeping = true;
    try {
      try {
        const status = await nativeCall('getFollowCacheStatus', {}, 10000);
        if (status?.enabled === false) return;
      } catch { /* Native answers enabled by default; enqueue is a no-op when off. */ }
      const history = Array.isArray(library?.history) ? library.history : [];
      const completed = new Set(history.filter(completedEpisode).map(row => String(row.seriesId)));
      const lastWatch = new Map(history.map(row => [String(row.seriesId), Number(row.index) || 0]));
      const recency = row => Number(row.updatedAt) || Number(row.favoriteAt) || 0;
      const watchedAt = new Map(history.map(row => [String(row.seriesId), recency(row)]));
      const ordered = favorites
        .filter(item => item && SERIES_ID.test(String(item.seriesId)) && !completed.has(String(item.seriesId)))
        .sort((a, b) => (watchedAt.get(String(b.seriesId)) || Number(b.favoriteAt) || 0)
          - (watchedAt.get(String(a.seriesId)) || Number(a.favoriteAt) || 0));
      // Every explicitly followed season first — the user's own list outranks seasons
      // inferred from a multi-season family — then the inferred siblings.
      for (const series of ordered) {
        try { await enqueueSeries(series.seriesId, lastWatch.get(String(series.seriesId)) || 0); }
        catch { /* Individual series retry on the next sweep. */ }
        await sleep(SERIES_STAGGER_MS);
      }
      for (const series of ordered) {
        try { await enqueueSiblings(series); } catch { /* Siblings are optional. */ }
        await sleep(SERIES_STAGGER_MS);
      }
    } finally { sweeping = false; }
  }

  return {
    /** Remember the library and enqueue everything worth caching, most recent first. */
    refresh(library) {
      if (!library || typeof library !== 'object') return;
      latest = library;
      sweep(library).catch(() => {});
    },
    /** Re-run the last sweep — used right after re-enabling the cacher. */
    resweep() { if (latest) sweep(latest).catch(() => {}); },
    /** A favorite was just added: start caching it without waiting for a sweep. */
    favoriteAdded(series) {
      if (!series || !SERIES_ID.test(String(series.seriesId))) return;
      (async () => {
        await enqueueSeries(series.seriesId, 0);
        await enqueueSiblings(series);
      })().catch(() => {});
    },
    /** Periodic new-episode discovery for ongoing shows; pauses with hidden pages. */
    start() {
      setInterval(() => {
        if (latest && document.visibilityState !== 'hidden') sweep(latest).catch(() => {});
      }, REFRESH_MS);
    },
  };
}
