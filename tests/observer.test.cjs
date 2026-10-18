const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const script = readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/social-observer.js'), 'utf8');

// Controlled DOM/media fixture: exercises the actual packaged detector, not an alternative evaluator.
function fixture({platform='youtube', gate=false, drift=false, cached=false, advertisement=false, hidden=false}={}) {
  let clock = 0;
  const id = platform === 'youtube' ? 'abcdefghijk' : 'ABC123';
  const href = platform === 'youtube' ? `https://www.youtube.com/watch?v=${id}` : `https://www.instagram.com/p/${id}/`;
  const listeners = new Map();
  const video = {currentTime:0, readyState:4, videoWidth:1280, paused:false, seeking:false, playbackRate:1,
    addEventListener:(name, fn)=>listeners.set(name,fn), removeEventListener:(name)=>listeners.delete(name)};
  const player = {classList:{contains:()=>advertisement}, querySelector:(s)=>s==='video'?video:drift?null:s==='.ytp-title-link[href]'?{href}:{}};
  const context = vm.createContext({URL, location:{href}, window:{}, navigator:{}, performance:{now:()=>clock, getEntriesByType:()=>[{transferSize:cached?0:1200,type:'navigate'}]},
    document:{visibilityState:hidden?'hidden':'visible', querySelector:s=>s==='link[rel="canonical"]'?{href}:s==='#movie_player.html5-video-player'||s==='article'?player:gate?{}:null}});
  return {video, listeners, observe(seconds=1){clock+=seconds*1000;video.currentTime+=seconds;return JSON.parse(vm.runInContext(script,context));}};
}
test('both selected target layouts produce structural positive signals',()=>{
  for(const platform of ['youtube','instagram']) {const f=fixture({platform}); const o=f.observe(); assert.equal(o.contentPresent,'TRUE');assert.equal(o.blockingGate,'FALSE');assert.equal(o.canonicalMatch,true);}
});
test('unrecognised DOM cannot establish gate absence',()=>{
  const o=fixture({drift:true}).observe();assert.equal(o.blockingGate,'UNKNOWN');assert.equal(o.contentPresent,'UNKNOWN');
});
test('recognised gate overrides otherwise present media',()=>assert.equal(fixture({gate:true}).observe().blockingGate,'TRUE'));
test('cached and hidden observations preserve uncertainty',()=>{
  assert.equal(fixture({cached:true}).observe().freshEvidence,'UNKNOWN');assert.equal(fixture({hidden:true}).observe().documentVisible,'FALSE');
});
test('natural selected-media progress needs three seconds and rejects advertisements',()=>{
  const f=fixture(); f.observe(); f.observe(); f.observe(); const o=f.observe();assert.equal(o.mediaProgress,'TRUE');
  const ad=fixture({advertisement:true});for(let i=0;i<5;i++)assert.equal(ad.observe().mediaProgress,'UNKNOWN');
});
test('seek event permanently invalidates this observation window',()=>{
  const f=fixture();f.observe();f.listeners.get('seeking')();for(let i=0;i<5;i++)assert.equal(f.observe().mediaProgress,'UNKNOWN');
});
