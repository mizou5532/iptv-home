import type { Sample } from './summary';

export type Engine = 'natif' | 'hls.js' | 'mpegts.js';
export type FailureReason = 'contenu-mixte' | 'format' | 'cors-ou-reseau' | 'delai' | 'inconnu';

export interface PlayResult {
  sample: Sample;
  engine: Engine;
  ok: boolean;
  msToFirstFrame?: number;
  reason?: FailureReason;
  detail?: string;
}

export function classifyFailure(ctx: {
  pageProtocol: string; url: string; mediaErrorCode?: number; engineNetworkError?: boolean; timedOut?: boolean;
}): FailureReason {
  if (ctx.pageProtocol === 'https:' && ctx.url.toLowerCase().startsWith('http:')) return 'contenu-mixte';
  if (ctx.mediaErrorCode === 3 || ctx.mediaErrorCode === 4) return 'format';
  if (ctx.timedOut) return 'delai';
  if (ctx.engineNetworkError) return 'cors-ou-reseau';
  return 'inconnu';
}

export function enginesFor(sample: Sample, support: { hls: boolean; mpegts: boolean }): { engine: Engine; ext: string }[] {
  if (sample.kind !== 'live') return [{ engine: 'natif', ext: sample.ext }];
  const list: { engine: Engine; ext: string }[] = [{ engine: 'natif', ext: 'm3u8' }];
  if (support.hls) list.push({ engine: 'hls.js', ext: 'm3u8' });
  if (support.mpegts) list.push({ engine: 'mpegts.js', ext: 'ts' });
  return list;
}

/**
 * Tente de lire `url` dans `video` avec le moteur donné. Réussite dès que la lecture avance (currentTime > 0.3).
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

  const outcome = await new Promise<{ ok: boolean; timedOut?: boolean; mediaErrorCode?: number }>((resolve) => {
    let done = false;
    const finish = (r: { ok: boolean; timedOut?: boolean; mediaErrorCode?: number }) => { if (!done) { done = true; resolve(r); } };
    const timer = setTimeout(() => finish({ ok: false, timedOut: true }), timeoutMs);
    const onTime = () => { if (video.currentTime > 0.3) { clearTimeout(timer); finish({ ok: true }); } };
    const onError = () => { clearTimeout(timer); finish({ ok: false, mediaErrorCode: video.error?.code }); };
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('error', onError);
    const removeListeners = () => { video.removeEventListener('timeupdate', onTime); video.removeEventListener('error', onError); };

    const fail = (detail: string, network: boolean) => {
      engineDetail = detail; engineNetworkError = network; clearTimeout(timer); finish({ ok: false });
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
            if (data.fatal) fail(`hls.js: ${data.type} / ${data.details}`, data.type === Hls.ErrorTypes.NETWORK_ERROR);
          });
          hls.loadSource(url);
          hls.attachMedia(video);
          cleanup = () => { removeListeners(); hls.destroy(); };
        } else {
          const mpegts = (await import('mpegts.js')).default;
          const player = mpegts.createPlayer({ type: 'mpegts', isLive: true, url });
          player.on(mpegts.Events.ERROR, (type: string, details: string) => fail(`mpegts.js: ${type} / ${details}`, type === mpegts.ErrorTypes.NETWORK_ERROR));
          player.attachMediaElement(video);
          player.load();
          cleanup = () => { removeListeners(); player.pause(); player.unload(); player.detachMediaElement(); player.destroy(); };
        }
        await video.play().catch(() => undefined);
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
    pageProtocol: location.protocol, url, mediaErrorCode: outcome.mediaErrorCode, engineNetworkError, timedOut: outcome.timedOut,
  });
  const detail = engineDetail || (outcome.mediaErrorCode ? `MediaError ${outcome.mediaErrorCode}${outcome.mediaErrorCode === 4 ? " (format non supporté ou fichier inaccessible)" : ""}` : outcome.timedOut ? `rien après ${timeoutMs} ms` : '');
  return { ok: false, reason, detail };
}
