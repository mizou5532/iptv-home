import type { Sample } from './summary';

export type Engine = 'natif' | 'hls.js' | 'mpegts.js';
export type FailureReason = 'contenu-mixte' | 'format' | 'refus-fournisseur' | 'cors-ou-reseau' | 'delai' | 'inconnu';

/** Codes HTTP par lesquels un fournisseur refuse (dont « trop de connexions »). */
const REFUSAL_CODES = new Set([401, 403, 429, 456, 458, 509]);
export const httpRefusal = (code: number | undefined): boolean => code !== undefined && REFUSAL_CODES.has(code);

/** La lecture avance vraiment : elle a démarré (t0) et la tête a progressé, sans pause ni saut en cours. */
export function isProgressing(v: { paused: boolean; seeking: boolean; currentTime: number; t0: number | null }): boolean {
  return v.t0 !== null && !v.paused && !v.seeking && v.currentTime - v.t0 > 0.3;
}

export interface PlayResult {
  sample: Sample;
  engine: Engine;
  ok: boolean;
  msToFirstFrame?: number;
  reason?: FailureReason;
  detail?: string;
}

export function classifyFailure(ctx: {
  pageProtocol: string; url: string; mediaErrorCode?: number; engineNetworkError?: boolean; timedOut?: boolean; httpStatus?: number;
}): FailureReason {
  if (ctx.pageProtocol === 'https:' && ctx.url.toLowerCase().startsWith('http:')) return 'contenu-mixte';
  if (ctx.mediaErrorCode === 3 || ctx.mediaErrorCode === 4) return 'format';
  if (httpRefusal(ctx.httpStatus)) return 'refus-fournisseur';
  if (ctx.timedOut) return 'delai';
  if (ctx.engineNetworkError) return 'cors-ou-reseau';
  return 'inconnu';
}

export function enginesFor(sample: Sample, support: { hls: boolean; mpegts: boolean }): { engine: Engine; ext: string }[] {
  if (sample.kind !== 'live') return sample.ext === 'inconnu' ? [] : [{ engine: 'natif', ext: sample.ext }];
  const list: { engine: Engine; ext: string }[] = [{ engine: 'natif', ext: 'm3u8' }];
  if (support.hls) list.push({ engine: 'hls.js', ext: 'm3u8' });
  if (support.mpegts) list.push({ engine: 'mpegts.js', ext: 'ts' });
  return list;
}

/**
 * Tente de lire `url` dans `video` avec le moteur donné. Réussite quand la lecture avance vraiment (voir isProgressing).
 * La lecture est toujours arrêtée et détachée avant de répondre : une seule connexion chez le fournisseur.
 */
export async function tryPlay(
  video: HTMLVideoElement, url: string, engine: Engine, timeoutMs = 10_000,
): Promise<Omit<PlayResult, 'sample' | 'engine'>> {
  const started = performance.now();
  video.muted = true;
  video.playsInline = true;
  let cleanup: () => void = () => {};
  let engineNetworkError = false;
  let engineDetail = '';
  let httpStatus: number | undefined;
  let playError = '';

  const outcome = await new Promise<{ ok: boolean; timedOut?: boolean; mediaErrorCode?: number }>((resolve) => {
    let done = false;
    const finish = (r: { ok: boolean; timedOut?: boolean; mediaErrorCode?: number }) => { if (!done) { done = true; resolve(r); } };
    const timer = setTimeout(() => finish({ ok: false, timedOut: true }), timeoutMs);
    let t0: number | null = null;
    const onPlaying = () => { if (t0 === null) t0 = video.currentTime; };
    const onTime = () => {
      if (isProgressing({ paused: video.paused, seeking: video.seeking, currentTime: video.currentTime, t0 })) { clearTimeout(timer); finish({ ok: true }); }
    };
    const onError = () => { clearTimeout(timer); finish({ ok: false, mediaErrorCode: video.error?.code }); };
    video.addEventListener('playing', onPlaying);
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('error', onError);
    const removeListeners = () => {
      video.removeEventListener('playing', onPlaying); video.removeEventListener('timeupdate', onTime); video.removeEventListener('error', onError);
    };

    const fail = (detail: string, network: boolean, status?: number) => {
      engineDetail = status ? `${detail} (HTTP ${status})` : detail; engineNetworkError = network; httpStatus = status; clearTimeout(timer); finish({ ok: false });
    };

    (async () => {
      try {
        if (engine === 'natif') {
          video.src = url;
          cleanup = removeListeners;
        } else if (engine === 'hls.js') {
          const Hls = (await import('hls.js')).default;
          const hls = new Hls();
          hls.on(Hls.Events.ERROR, (_e, data) => {
            if (data.fatal) fail(`hls.js: ${data.type} / ${data.details}`, data.type === Hls.ErrorTypes.NETWORK_ERROR, data.response?.code || undefined);
          });
          hls.loadSource(url);
          hls.attachMedia(video);
          cleanup = () => { removeListeners(); hls.destroy(); };
        } else {
          const mpegts = (await import('mpegts.js')).default;
          const player = mpegts.createPlayer({ type: 'mpegts', isLive: true, url });
          player.on(mpegts.Events.ERROR, (type: string, details: string, info?: { code?: number }) =>
            fail(`mpegts.js: ${type} / ${details}`, type === mpegts.ErrorTypes.NETWORK_ERROR, info?.code && info.code > 0 ? info.code : undefined));
          player.attachMediaElement(video);
          player.load();
          cleanup = () => { removeListeners(); player.pause(); player.unload(); player.detachMediaElement(); player.destroy(); };
        }
        await video.play().catch((err: unknown) => { playError = err instanceof Error ? err.name : String(err); });
      } catch (e) {
        fail(`exception: ${String(e)}`, false);
      }
    })();
  });

  const ms = Math.round(performance.now() - started);
  cleanup();
  video.pause();
  video.removeAttribute('src');
  video.load();

  if (outcome.ok) return { ok: true, msToFirstFrame: ms };
  const reason = classifyFailure({
    pageProtocol: location.protocol, url, mediaErrorCode: outcome.mediaErrorCode, engineNetworkError, timedOut: outcome.timedOut, httpStatus,
  });
  const detail = engineDetail || (outcome.mediaErrorCode ? `MediaError ${outcome.mediaErrorCode}${outcome.mediaErrorCode === 4 ? " (format non supporté ou fichier inaccessible)" : ""}` : outcome.timedOut ? `rien après ${timeoutMs} ms` : '');
  return { ok: false, reason, detail: playError ? [detail, `lecture refusée par le navigateur : ${playError}`].filter(Boolean).join(' — ') : detail };
}
