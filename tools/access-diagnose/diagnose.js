#!/usr/bin/env node
'use strict';
// Erişim sorunu ölçüm aracı — kendi telefonunda, kendi hesabınla, girişi SEN yaparken.
//
// Ne kaydeder: zaman, cihaz modeli/Android sürümü, uygulama ya da Chrome sürümü,
// DPI Bypass tünelinin açık olup olmadığı, ekranda görünen metin (giriş alanlarının
// içeriği HARİÇ), ağ hataları (net::ERR_*, uygulama logundaki ağ istisnaları), HTTP
// durumları ve bağlantının cihazdan görülen ülkesi (Cloudflare trace "loc").
//
// Ne KAYDETMEZ: şifre, çerez, istek başlıkları, istek gövdeleri, adreslerin sorgu
// kısmı (oturum/yaş belirteci taşıyabilir), metin kutularının değerleri. Hiçbir şey
// bir sunucuya gönderilmez; çıktı yalnız ./runs altına yazılır.
//
// Kullanım:  node diagnose.js web|app --platform instagram --dpi off|on [--label "..."] [--serial ID] [--screenshot]
//            node diagnose.js compare runs/a.json runs/b.json

const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { classify, compare, redactUrl } = require('./classify');

const PLATFORMS = {
  instagram: { pkg: 'com.instagram.android', hosts: ['instagram.com'] },
  tiktok: { pkg: 'com.zhiliaoapp.musically', hosts: ['tiktok.com'] },
  youtube: { pkg: 'com.google.android.youtube', hosts: ['youtube.com', 'google.com'] },
  facebook: { pkg: 'com.facebook.katana', hosts: ['facebook.com'] },
  x: { pkg: 'com.twitter.android', hosts: ['x.com', 'twitter.com'] },
};
const DPI_PACKAGES = ['net.atom.dpibypass', 'net.atom.dpibypass.debug'];

function args(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
      out[k] = v;
    } else out._.push(a);
  }
  return out;
}

function adb(serial, ...rest) {
  const a = serial ? ['-s', serial, ...rest] : rest;
  return execFileSync('adb', a, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function ask(q) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((r) => rl.question(q, (x) => { rl.close(); r(x); }));
}

function deviceInfo(serial) {
  const prop = (k) => adb(serial, 'shell', 'getprop', k).trim();
  return { model: prop('ro.product.model'), android: prop('ro.build.version.release'), sdk: prop('ro.build.version.sdk') };
}

function packageVersion(serial, pkg) {
  const out = adb(serial, 'shell', 'dumpsys', 'package', pkg);
  const m = out.match(/versionName=(\S+)/);
  return m ? m[1] : null;
}

/** DPI Bypass'ın VPN servisi gerçekten çalışıyor mu (beyana güvenmeden). */
function dpiTunnelRunning(serial) {
  for (const pkg of DPI_PACKAGES) {
    const out = adb(serial, 'shell', 'dumpsys', 'activity', 'services', pkg);
    if (/DpiVpnService/.test(out) && /app=ProcessRecord/.test(out)) return { running: true, pkg };
  }
  return { running: false, pkg: null };
}

/** uiautomator dökümünden görünen metin; EditText ve parola alanları atlanır. */
function screenTextFromUiDump(xml) {
  const texts = [];
  const re = /<node\b([^>]*)>/g;
  let m;
  while ((m = re.exec(xml))) {
    const attrs = m[1];
    const get = (k) => { const r = attrs.match(new RegExp(`\\b${k}="([^"]*)"`)); return r ? r[1] : ''; };
    if (/EditText/.test(get('class')) || get('password') === 'true') continue;
    for (const k of ['text', 'content-desc']) {
      const v = get(k).replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim();
      if (v) texts.push(v);
    }
  }
  return [...new Set(texts)].join('\n');
}

const LOG_NET = /(UnknownHostException|SocketTimeoutException|ConnectException|SSLHandshakeException|SSLException|NoRouteToHostException|Connection reset|ECONNRESET|ERR_[A-Z_]+)/;

function appNetworkErrors(logcat) {
  const found = new Set();
  for (const line of logcat.split('\n')) {
    const m = line.match(LOG_NET);
    if (m) found.add(m[1]);
  }
  return [...found];
}

async function measureApp(o, rec) {
  const pf = PLATFORMS[o.platform];
  rec.client = { package: pf.pkg, version: packageVersion(o.serial, pf.pkg) };
  if (!rec.client.version) throw new Error(`${pf.pkg} cihazda kurulu değil`);
  adb(o.serial, 'logcat', '-c');
  await ask(`\n${pf.pkg} uygulamasını aç, kendi hesabınla işlemi yap (${o.label || 'giriş + engellenen işlem'}).\nHata ya da sonuç ekrandayken Enter'a bas... `);
  rec.timeEnd = new Date().toISOString();
  const pid = adb(o.serial, 'shell', 'pidof', pf.pkg).trim().split(/\s+/)[0] || '';
  const log = pid ? adb(o.serial, 'logcat', '-d', '--pid', pid) : '';
  rec.networkErrors = appNetworkErrors(log);
  adb(o.serial, 'shell', 'uiautomator', 'dump', '/sdcard/ad-ui.xml');
  const xml = adb(o.serial, 'exec-out', 'cat', '/sdcard/ad-ui.xml');
  adb(o.serial, 'shell', 'rm', '-f', '/sdcard/ad-ui.xml');
  rec.visibleText = screenTextFromUiDump(xml);
  rec.httpStatuses = [];
}

async function connectChrome(serial) {
  const { chromium } = require('playwright');
  adb(serial, 'forward', 'tcp:9222', 'localabstract:chrome_devtools_remote');
  const ver = await (await fetch('http://127.0.0.1:9222/json/version')).json();
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9222');
  return { browser, version: ver.Browser };
}

/** Bağlantının cihazdan görülen ülkesi — varsayılmaz, ölçülür. */
async function deviceCountry(context) {
  const p = await context.newPage();
  try {
    await p.goto('https://www.cloudflare.com/cdn-cgi/trace', { timeout: 20000 });
    const t = await p.evaluate(() => document.body.innerText);
    const m = t.match(/^loc=(\w+)/m);
    return m ? m[1] : null;
  } catch (e) {
    return `ölçülemedi (${e.message.split('\n')[0]})`;
  } finally {
    await p.close();
  }
}

async function measureWeb(o, rec, deps = {}) {
  const connect = deps.connect || connectChrome;
  const prompt = deps.ask || ask;
  const country = deps.country || deviceCountry;
  const pf = PLATFORMS[o.platform];
  const { browser, version } = await connect(o.serial);
  rec.client = { browser: version };
  const context = browser.contexts()[0];
  rec.country = await country(context);
  const failed = []; const statuses = []; const urls = new Set();
  const onPage = (page) => {
    page.on('requestfailed', (r) => failed.push(`${r.failure()?.errorText || 'hata'} ${redactUrl(r.url())}`));
    page.on('response', (r) => {
      if (r.request().resourceType() === 'document' || r.request().resourceType() === 'xhr' || r.request().resourceType() === 'fetch') {
        if (pf.hosts.some((h) => r.url().includes(h))) { statuses.push(r.status()); urls.add(`${r.status()} ${redactUrl(r.url())}`); }
      }
    });
  };
  context.pages().forEach(onPage);
  context.on('page', onPage);
  await prompt(`\nTelefondaki Chrome'da ${pf.hosts[0]} adresini aç, kendi hesabınla işlemi yap (${o.label || 'giriş + engellenen işlem'}).\nHata ya da sonuç ekrandayken Enter'a bas... `);
  rec.timeEnd = new Date().toISOString();
  const page = context.pages().find((p) => pf.hosts.some((h) => p.url().includes(h)));
  rec.pageUrl = page ? redactUrl(page.url()) : null;
  // innerText giriş kutularının değerlerini içermez.
  rec.visibleText = page ? await page.evaluate(() => document.body ? document.body.innerText : '') : '';
  rec.networkErrors = failed;
  rec.httpStatuses = statuses;
  rec.responses = [...urls].slice(0, 200);
  await browser.close().catch(() => {}); // CDP bağlantısını bırakır; telefondaki Chrome açık kalır.
}

async function main() {
  const o = args(process.argv.slice(2));
  const mode = o._[0];
  if (mode === 'compare') {
    const [a, b] = o._.slice(1).map((f) => JSON.parse(fs.readFileSync(f, 'utf8')));
    console.log(JSON.stringify(compare(a, b), null, 2));
    return;
  }
  if (!['web', 'app'].includes(mode) || !PLATFORMS[o.platform] || !['on', 'off'].includes(o.dpi)) {
    console.error('Kullanım: node diagnose.js web|app --platform instagram|tiktok|youtube|facebook|x --dpi off|on [--label ".."] [--serial ID] [--screenshot]');
    process.exit(2);
  }
  const rec = {
    tool: 'access-diagnose/1', platform: o.platform, surface: mode, label: o.label || null,
    dpiDeclared: o.dpi, timeStart: new Date().toISOString(),
    tz: Intl.DateTimeFormat().resolvedOptions().timeZone,
  };
  rec.device = deviceInfo(o.serial);
  rec.dpiTunnel = dpiTunnelRunning(o.serial);
  if ((o.dpi === 'on') !== rec.dpiTunnel.running) {
    console.warn(`UYARI: --dpi ${o.dpi} dedin ama DPI Bypass tüneli ${rec.dpiTunnel.running ? 'AÇIK' : 'KAPALI'} görünüyor.`);
  }
  if (mode === 'app') await measureApp(o, rec); else await measureWeb(o, rec);
  if (o.screenshot) rec.screenshot = 'kaydedildi';
  rec.result = classify(rec);
  const dir = path.join(__dirname, 'runs');
  fs.mkdirSync(dir, { recursive: true });
  const base = `${rec.timeStart.replace(/[:.]/g, '-')}-${o.platform}-${mode}-dpi${o.dpi}`;
  if (o.screenshot) fs.writeFileSync(path.join(dir, `${base}.png`), execFileSync('adb', [...(o.serial ? ['-s', o.serial] : []), 'exec-out', 'screencap', '-p']));
  fs.writeFileSync(path.join(dir, `${base}.json`), JSON.stringify(rec, null, 2));
  console.log(`\nSonuç: ${rec.result.category} — ${rec.result.evidence}\nKayıt: ${path.join(dir, base)}.json`);
}

if (require.main === module) main().catch((e) => { console.error(e.message); process.exit(1); });
module.exports = { screenTextFromUiDump, appNetworkErrors, measureWeb, PLATFORMS };
