'use strict';
// Erişim sorunu sınıflandırıcı (saf fonksiyonlar, ağ/cihaz bağımlılığı yok).
//
// Girdi: bir ölçümün kaydı — ekranda görünen metin, ağ hataları, HTTP durumları,
// uygulama logundaki istisna sınıfları. Çıktı: üç ayrı sınıftan biri ve gerekçesi.
//
//   NETWORK      — bağlantı hiç kurulamıyor/kopuyor (DNS, sıfırlama, zaman aşımı, TLS).
//                  DPI Bypass'ın etkileyebildiği tek katman budur.
//   AGE_GENERIC  — platformun kendi genel yaş kontrolü (selfie, kimlik, Yoti, Incode...).
//   TR_EDEVLET   — Türkiye'ye özel e-Devlet / yaş doğrulama belirteci talebi.
//   NONE         — erişim engeli görülmedi.
//   UNKNOWN      — kayıt sınıflandırmaya yetmiyor.
//
// Metin eşleşmesi kasıtlı olarak tutucudur: e-Devlet/belirteç ifadesi görülürse
// sonuç TR_EDEVLET'tir, aynı ekranda genel yaş ifadesi de olsa.

const NETWORK_ERRORS = [
  /ERR_CONNECTION_RESET/, /ERR_CONNECTION_CLOSED/, /ERR_CONNECTION_REFUSED/, /ERR_CONNECTION_TIMED_OUT/,
  /ERR_TIMED_OUT/, /ERR_NAME_NOT_RESOLVED/, /ERR_NAME_RESOLUTION_FAILED/, /ERR_ADDRESS_UNREACHABLE/,
  /ERR_SSL_PROTOCOL_ERROR/, /ERR_SSL_VERSION_OR_CIPHER_MISMATCH/, /ERR_CERT_[A-Z_]+/, /ERR_QUIC_PROTOCOL_ERROR/,
  /ERR_EMPTY_RESPONSE/, /ERR_NETWORK_CHANGED/, /ERR_INTERNET_DISCONNECTED/, /ERR_TUNNEL_CONNECTION_FAILED/,
  /UnknownHostException/, /SocketTimeoutException/, /ConnectException/, /SSLHandshakeException/,
  /SSLException/, /ECONNRESET/, /Connection reset/, /NoRouteToHostException/,
];

const TR_EDEVLET = [
  /e-?devlet/i, /turkiye\.gov\.tr/i, /ya[şs]\s*do[ğg]rulama\s*belirte[çc]/i, /belirte[çc]/i,
  /age\s*verification\s*token/i, /T\.?C\.?\s*kimlik/i,
];

const AGE_GENERIC = [
  /ya[şs][ıi]n[ıi]\s*(onayla|do[ğg]rula)/i, /ya[şs]\s*(onay[ıi]|do[ğg]rulama)/i, /confirm\s+your\s+age/i,
  /verify\s+your\s+age/i, /age\s+(check|verification|assurance)/i, /\byoti\b/i, /\bincode\b/i,
  /video\s*selfie/i, /selfie\s*videosu/i, /do[ğg]um\s*(tarihi|g[üu]n[üu])/i, /date\s+of\s+birth/i,
  /minimum\s+ya[şs]/i, /under\s*1[3-8]/i,
];

function firstMatch(patterns, text) {
  for (const p of patterns) {
    const m = text.match(p);
    if (m) return m[0];
  }
  return null;
}

/**
 * @param {object} rec
 * @param {string}   [rec.visibleText]   ekrandaki metin (giriş alanlarının değerleri hariç)
 * @param {string[]} [rec.networkErrors] tarayıcı net::ERR_* ya da uygulama istisnaları
 * @param {number[]} [rec.httpStatuses]  ana belge/istek durumları
 */
function classify(rec) {
  const text = String(rec.visibleText || '');
  const errors = (rec.networkErrors || []).map(String);
  const statuses = rec.httpStatuses || [];

  const tr = firstMatch(TR_EDEVLET, text);
  if (tr) return { category: 'TR_EDEVLET', evidence: `ekranda: "${tr}"` };

  const age = firstMatch(AGE_GENERIC, text);
  if (age) return { category: 'AGE_GENERIC', evidence: `ekranda: "${age}"` };

  for (const e of errors) {
    const n = firstMatch(NETWORK_ERRORS, e);
    if (n) return { category: 'NETWORK', evidence: `ağ hatası: ${n}` };
  }
  if (statuses.some((s) => s === 451)) return { category: 'NETWORK', evidence: 'HTTP 451 (hukuki engel)' };

  if (text.trim().length > 0 && statuses.every((s) => s < 400)) {
    return { category: 'NONE', evidence: 'engel ifadesi ya da ağ hatası görülmedi' };
  }
  return { category: 'UNKNOWN', evidence: 'kayıt yetersiz (metin yok ya da yalnız HTTP hata kodu)' };
}

/**
 * Aynı hesap işleminin DPI kapalı/açık iki ölçümünü karşılaştırır. Yalnız
 * kapalıyken NETWORK, açıkken NONE ise ağ katmanı düzeltmesi olarak sayılır.
 */
function compare(off, on) {
  const a = classify(off);
  const b = classify(on);
  let verdict;
  if (a.category === 'NETWORK' && b.category === 'NONE') verdict = 'DPI_FIXES_NETWORK';
  else if (a.category === 'TR_EDEVLET' || b.category === 'TR_EDEVLET') verdict = 'SERVER_REQUIRES_EDEVLET_TOKEN';
  else if (a.category === 'AGE_GENERIC' || b.category === 'AGE_GENERIC') verdict = 'PLATFORM_AGE_CHECK';
  else if (a.category === 'NONE') verdict = 'NO_BLOCK_WITHOUT_APP';
  else verdict = 'INCONCLUSIVE';
  return { off: a, on: b, verdict };
}

/** Kayda girmeden önce adreslerden sorgu/parça kısmını atar (belirteç sızmasın). */
function redactUrl(u) {
  try {
    const x = new URL(u);
    return `${x.protocol}//${x.host}${x.pathname}`;
  } catch (_) {
    return '[geçersiz adres]';
  }
}

module.exports = { classify, compare, redactUrl };
