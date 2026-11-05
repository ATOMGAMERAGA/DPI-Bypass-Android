'use strict';
// node --test test/  (Playwright + yerel Chromium gerekir; telefon gerekmez)
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { chromium } = require('playwright');
const { classify, compare, redactUrl } = require('../classify');
const { screenTextFromUiDump, appNetworkErrors, measureWeb, PLATFORMS } = require('../diagnose');

test('sınıflandırıcı: e-Devlet talebi, genel yaş kontrolü, ağ hatası ve engelsiz ayrı çıkar', () => {
  assert.equal(classify({ visibleText: 'Devam etmek için e-Devlet üzerinden yaş doğrulama belirteci yükle' }).category, 'TR_EDEVLET');
  assert.equal(classify({ visibleText: 'Yaşını onayla: selfie videosu çek (Yoti)' }).category, 'AGE_GENERIC');
  assert.equal(classify({ visibleText: '', networkErrors: ['net::ERR_CONNECTION_RESET https://i.instagram.com/api/v1/'] }).category, 'NETWORK');
  assert.equal(classify({ visibleText: '', networkErrors: ['SSLHandshakeException'] }).category, 'NETWORK');
  assert.equal(classify({ visibleText: 'Ana sayfa\nProfil', httpStatuses: [200] }).category, 'NONE');
  assert.equal(classify({ visibleText: '', httpStatuses: [500] }).category, 'UNKNOWN');
  // Aynı ekranda iki ifade varsa Türkiye talebi önceliklidir.
  assert.equal(classify({ visibleText: 'Yaşını doğrula. e-Devlet ile devam et' }).category, 'TR_EDEVLET');
});

test('karşılaştırma: yalnız kapalıyken ağ hatası, açıkken engelsiz ise ağ düzeltmesi sayılır', () => {
  const net = { networkErrors: ['net::ERR_CONNECTION_RESET'] };
  const ok = { visibleText: 'Akış', httpStatuses: [200] };
  const tr = { visibleText: 'e-Devlet belirteci gerekli' };
  assert.equal(compare(net, ok).verdict, 'DPI_FIXES_NETWORK');
  assert.equal(compare(tr, tr).verdict, 'SERVER_REQUIRES_EDEVLET_TOKEN');
  assert.equal(compare(net, tr).verdict, 'SERVER_REQUIRES_EDEVLET_TOKEN');
  assert.equal(compare(ok, ok).verdict, 'NO_BLOCK_WITHOUT_APP');
  assert.equal(compare(net, net).verdict, 'INCONCLUSIVE');
});

test('gizlilik: adreslerin sorgu kısmı atılır, metin kutuları ve parola alanları okunmaz', () => {
  assert.equal(redactUrl('https://i.instagram.com/challenge/?token=GIZLI#x'), 'https://i.instagram.com/challenge/');
  const xml = '<hierarchy><node class="android.widget.TextView" text="e-Devlet ile doğrula" content-desc="" />'
    + '<node class="android.widget.EditText" text="kullanici_adim" content-desc="" />'
    + '<node class="android.view.View" password="true" text="sifre123" content-desc="" /></hierarchy>';
  const t = screenTextFromUiDump(xml);
  assert.match(t, /e-Devlet ile doğrula/);
  assert.doesNotMatch(t, /kullanici_adim|sifre123/);
  assert.deepEqual(appNetworkErrors('E/x: java.net.UnknownHostException: a\nI/y: ok\nW/z: javax.net.ssl.SSLHandshakeException: b'), ['UnknownHostException', 'SSLHandshakeException']);
});

/**
 * Tarayıcı ölçümü gerçek bir CDP bağlantısı üzerinden: yerel Chromium + yerel
 * sunucu. Telefondaki Chrome'a bağlanmanın yerine geçmez; aynı kod yolunu sınar.
 */
test('tarayıcı ölçümü (CDP): ekran metni, ağ hatası, sınıf; sorgu ve girilen metin kayda geçmez', async () => {
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'text/html; charset=utf-8');
    if (req.url.startsWith('/edevlet')) {
      res.end('<p>Hesabını kullanmaya devam etmek için e-Devlet üzerinden yaş doğrulama belirteci yükle.</p><input id="u">');
    } else {
      res.end(`<p>Bir sorun oluştu</p><script>fetch("http://127.0.0.1:${closedPort}/api").catch(()=>{})</script>`);
    }
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  // Kapalı bir port: aç, numarasını al, kapat.
  const probe = http.createServer();
  await new Promise((r) => probe.listen(0, '127.0.0.1', r));
  const closedPort = probe.address().port;
  await new Promise((r) => probe.close(r));
  const launched = await chromium.launch({ args: ['--remote-debugging-port=9333'] });
  PLATFORMS.selftest = { pkg: 'none', hosts: ['127.0.0.1'] };
  try {
    const connect = async () => ({ browser: await chromium.connectOverCDP('http://127.0.0.1:9333'), version: 'yerel-chromium' });
    const country = async () => 'ölçülmedi (yerel test)';

    // 1) Sunucu Türkiye'ye özel belirteç istiyor.
    const page = await launched.newPage();
    const rec1 = {};
    await measureWeb({ platform: 'selftest' }, rec1, {
      connect, country,
      ask: async () => {
        await page.goto(`http://127.0.0.1:${port}/edevlet?session=GIZLI_OTURUM`);
        await page.fill('#u', 'girilen-gizli-metin');
      },
    });
    assert.equal(classify(rec1).category, 'TR_EDEVLET');
    assert.ok(rec1.httpStatuses.includes(200));
    const dump1 = JSON.stringify(rec1);
    assert.doesNotMatch(dump1, /GIZLI_OTURUM|girilen-gizli-metin/);

    // 2) Ağ katmanında bağlantı kurulamıyor.
    const rec2 = {};
    await measureWeb({ platform: 'selftest' }, rec2, {
      connect, country,
      ask: async () => { await page.goto(`http://127.0.0.1:${port}/neterr`); await page.waitForTimeout(1500); },
    });
    assert.ok(rec2.networkErrors.some((e) => /ERR_CONNECTION_REFUSED/.test(e)), JSON.stringify(rec2.networkErrors));
    assert.equal(classify(rec2).category, 'NETWORK');
  } finally {
    delete PLATFORMS.selftest;
    await launched.close();
    server.close();
  }
});
