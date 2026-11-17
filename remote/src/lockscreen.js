// Lock screen controls: the TV's show on the phone's lock screen (iPhone "Now Playing", Android's
// media notification), with play or pause and back or forward 10 seconds for the TV.
//
// A web page only gets those controls while it is playing audio, so this plays a silent clip on a
// loop and hands the lock screen's buttons to the TV through the Media Session API. It is a switch
// on this phone (TV menu, Lock screen), off unless someone turns it on, because playing audio
// stops whatever else the phone was playing (music, a podcast), and it keeps the page awake.
// iOS only starts audio from a tap, so after the app is reopened the first tap anywhere starts it.

const KEY = 'tv.lockscreen';
const read = () => { try { return localStorage.getItem(KEY) === '1'; } catch { return false; } };
const write = (on) => { try { localStorage.setItem(KEY, on ? '1' : '0'); } catch { /* private mode */ } };

export const lockSupported = () => typeof navigator !== 'undefined' && 'mediaSession' in navigator && typeof Audio !== 'undefined';
export const lockOn = () => lockSupported() && read();

let audio = null;
let send = null;          // (key) => sends a key to the TV
let localAt = 0;          // when a lock screen button last changed play or pause here
let last = null;          // what was last shown, so the lock screen is only touched on a change

// 30 seconds of silence as a WAV made here, so it needs no file (the TV's own server, option C,
// cannot answer the range requests iOS sends for a media file).
function silence() {
  const rate = 8000, n = rate * 30, buf = new ArrayBuffer(44 + n), v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + n, true); str(8, 'WAVE'); str(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate, true); v.setUint16(32, 1, true); v.setUint16(34, 8, true);
  str(36, 'data'); v.setUint32(40, n, true);
  new Uint8Array(buf, 44).fill(128);   // 8 bit audio is silent at 128
  return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
}

function el() {
  if (!audio) {
    audio = new Audio(silence());
    audio.loop = true;
    audio.setAttribute('playsinline', '');
    audio.setAttribute('aria-hidden', 'true');
  }
  return audio;
}

const play = () => el().play().catch(() => {});
const pause = () => { if (audio) audio.pause(); };

function handlers(on) {
  const ms = navigator.mediaSession;
  const set = (a, f) => { try { ms.setActionHandler(a, on ? f : null); } catch { /* not on this phone */ } };
  const press = (k, then) => () => { localAt = Date.now(); then?.(); send?.(k); };
  set('play', press('play_pause', play));
  set('pause', press('play_pause', pause));
  set('seekbackward', press('rewind'));
  set('seekforward', press('fast_forward'));
  // no previous or next track: with these set, iOS shows its back and forward 10 s buttons instead
  set('previoustrack', null);
  set('nexttrack', null);
}

// Turn it on. Must be called inside a tap (iOS starts audio only then).
export function startLock(onKey) {
  if (!lockSupported()) return false;
  write(true);
  send = onKey;
  handlers(true);
  play();
  return true;
}

export function stopLock() {
  write(false);
  pause();
  if (!lockSupported()) return;
  handlers(false);
  last = null;
  try { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; } catch { /* ignore */ }
}

// Switched on earlier: start again on the first tap after the app opens.
export function armLock(onKey) {
  if (!lockOn()) return () => {};
  send = onKey;
  const go = () => { if (lockOn()) startLock(onKey); };
  document.addEventListener('pointerup', go, { once: true, capture: true });
  return () => document.removeEventListener('pointerup', go, { capture: true });
}

// Show what is on the TV. tv is /api/now; appName and appArt describe the app in front.
export function updateLock(tv, appName, appArt, tvName, onKey) {
  if (!lockOn() || !audio) return;
  if (onKey) send = onKey;
  const ms = navigator.mediaSession;
  const p = tv?.playing;
  const off = !tv || tv.power === 'off' || !tv.tv_connected;
  const ep = p && [p.season && `S${p.season}`, p.episode && `E${p.episode}`].filter(Boolean).join(' ');
  const title = off ? (tvName || 'TV') : p?.title || appName || tvName || 'TV';
  const artist = off ? '' : p ? [ep, p.episode_title].filter(Boolean).join(' · ') || appName || '' : (appName ? tvName || '' : '');
  const art = (!off && (p?.poster || p?.backdrop || appArt)) || '/icons/icon-512.png';
  const key = [title, artist, art].join('|');
  if (key !== last) {
    last = key;
    try {
      ms.metadata = new MediaMetadata({ title, artist, album: appName || '', artwork: [{ src: new URL(art, location.href).href, sizes: '512x512' }] });
    } catch { /* ignore */ }
  }
  // Where the show is up to, when the TV says. Otherwise a live stream (no bar), so the silent
  // clip's own 30 seconds never show as the show's length.
  try {
    if (!off && p?.duration_ms && p.position_ms != null) {
      const since = p.state === 'playing' && p.updated ? Math.max(0, Date.now() - new Date(p.updated).getTime()) : 0;
      const pos = Math.min(p.duration_ms, p.position_ms + since) / 1000;
      ms.setPositionState({ duration: p.duration_ms / 1000, position: pos, playbackRate: 1 });
    } else {
      ms.setPositionState({ duration: Infinity, position: 0, playbackRate: 1 });
    }
  } catch { /* ignore */ }
  // Follow the TV's play or pause, but not for a few seconds after a press here (the TV takes a
  // moment to report it). A TV that does not say keeps what was pressed here.
  if (Date.now() - localAt < 4000) return;
  if (off || p?.state === 'paused' || p?.state === 'stopped') {
    pause(); try { ms.playbackState = 'paused'; } catch { /* ignore */ }
  } else if (p?.state === 'playing' || p?.state === 'buffering') {
    play(); try { ms.playbackState = 'playing'; } catch { /* ignore */ }
  }
}
