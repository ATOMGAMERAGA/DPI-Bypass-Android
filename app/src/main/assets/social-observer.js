/* ControlledWebProbe 1.0.0. Evaluated only by app-owned WebView; no native JS bridge. */
(function () {
  'use strict';
  const p = new URL(location.href);
  const youtube = ['www.youtube.com', 'm.youtube.com', 'youtube.com'].includes(p.hostname);
  const instagram = ['www.instagram.com', 'instagram.com'].includes(p.hostname);
  const contentId = youtube ? (p.searchParams.get('v') || (p.pathname.startsWith('/shorts/') ? p.pathname.split('/')[2] : ''))
    : instagram ? ((/^\/(?:p|reel)\/([A-Za-z0-9_-]+)\/?$/).exec(p.pathname) || [])[1] : '';
  const canonical = document.querySelector('link[rel="canonical"]');
  let canonicalMatch = false;
  try {
    const c = new URL(canonical ? canonical.href : 'https://invalid.example/');
    canonicalMatch = c.hostname === p.hostname && (youtube ? c.searchParams.get('v') === contentId : c.pathname.replace(/\/$/, '') === p.pathname.replace(/\/$/, ''));
  } catch (_) { /* UNKNOWN */ }
  const player = youtube ? document.querySelector('#movie_player.html5-video-player') : document.querySelector('article');
  const video = player && player.querySelector('video');
  const gate = youtube ? document.querySelector('ytd-player-error-message-renderer, ytd-enforcement-message-view-model, #error-screen:not([hidden])')
    : document.querySelector('form[action*="/accounts/login"], form[action*="/challenge/"], form[action*="/accounts/verify_age"]');
  const blockingClass = document.querySelector('form[action*="/accounts/verify_age"], ytd-enforcement-message-view-model a[href*="verify_age"]') ? 'AGE_OR_ID_VERIFICATION_REQUIRED'
    : document.querySelector('form[action*="/accounts/login"], ytd-enforcement-message-view-model a[href*="accounts.google.com/ServiceLogin"]') ? 'LOGIN_REQUIRED'
    : document.querySelector('form[action*="/challenge/"]') ? 'BOT_CHALLENGE' : 'UNKNOWN';
  const advertisement = !!(youtube && player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
  let actualMediaMatched = !youtube;
  if (youtube && player) {
    const mediaLink = player.querySelector('.ytp-title-link[href]');
    try { actualMediaMatched = !!(mediaLink && new URL(mediaLink.href).searchParams.get('v') === contentId); } catch (_) { actualMediaMatched = false; }
  }
  const knownLayout = !!(canonicalMatch && actualMediaMatched && player && (youtube ? player.querySelector('.ytp-chrome-bottom') && video : player.querySelector('time') && player.querySelector('a[href*="/' + contentId + '/"]')));
  const image = instagram && player && player.querySelector('img');
  const present = knownLayout && (video ? video.readyState >= 2 && video.videoWidth > 0 : image && image.complete && image.naturalWidth > 0);
  const nav = performance.getEntriesByType('navigation')[0];
  const fresh = !!(nav && nav.transferSize > 0 && nav.type !== 'back_forward' && !(navigator.serviceWorker && navigator.serviceWorker.controller));
  let state = window.__dpiAlphaObservationV1;
  if (!state || state.contentId !== contentId || state.video !== video) {
    if (state && state.dispose) state.dispose();
    state = { contentId, video, last: performance.now(), time: video ? video.currentTime : 0, natural: 0, sought: false };
    if (video) {
      const seek = () => { state.sought = true; state.natural = 0; };
      video.addEventListener('seeking', seek);
      state.dispose = () => video.removeEventListener('seeking', seek);
    }
    window.__dpiAlphaObservationV1 = state;
  }
  const now = performance.now();
  if (video) {
    const dt = (now - state.last) / 1000, progress = video.currentTime - state.time;
    if (!video.paused && !video.seeking && !state.sought && !advertisement && progress > 0 && progress <= dt * Math.max(1, video.playbackRate) + 0.6)
      state.natural += progress;
    else if (video.seeking || progress > dt * Math.max(1, video.playbackRate) + 0.6 || advertisement) state.natural = 0;
    state.time = video.currentTime;
  }
  state.last = now;
  return JSON.stringify({ contentId: contentId || '', canonicalMatch, contentPresent: present ? 'TRUE' : 'UNKNOWN',
    blockingGate: gate ? 'TRUE' : knownLayout ? 'FALSE' : 'UNKNOWN', blockingClass,
    mediaProgress: state.natural >= 3 ? 'TRUE' : 'UNKNOWN', mediaProgressSeconds: state.natural,
    freshEvidence: fresh ? 'TRUE' : 'UNKNOWN', documentVisible: document.visibilityState === 'visible' ? 'TRUE' : 'FALSE',
    seeking: state.sought || !!(video && video.seeking), advertisement, preview: !canonicalMatch || !actualMediaMatched,
    needsInteraction: !!(video && video.paused && video.readyState >= 2), knownLayout });
})()
