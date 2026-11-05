# Erişim sorunu ölçüm aracı

Kendi telefonunda, kendi hesabınla ve girişi **sen** yaparken, bir platformda gördüğün erişim sorununun hangi katmanda olduğunu ölçer:

| Sınıf | Anlamı | DPI Bypass etkileyebilir mi? |
|---|---|---|
| `NETWORK` | Bağlantı kurulamıyor/kopuyor (DNS, `ERR_CONNECTION_RESET`, zaman aşımı, TLS) | **Evet**: tek etkileyebildiği katman |
| `AGE_GENERIC` | Platformun genel yaş kontrolü (selfie, kimlik, Yoti, Incode…) | Hayır: karar platform sunucusunda |
| `TR_EDEVLET` | Türkiye'ye özel e‑Devlet / yaş doğrulama belirteci talebi | Hayır: karar platform sunucusunda |
| `NONE` | Engel görülmedi | — |

`compare` modu aynı hesap işleminin DPI Bypass **kapalı** ve **açık** iki ölçümünü karşılaştırır. Yalnızca kapalıyken `NETWORK`, açıkken `NONE` ise sonuç `DPI_FIXES_NETWORK` olur.

## Gizlilik

Şifre, çerez, istek başlığı ya da gövdesi, adreslerin sorgu kısmı (oturum veya belirteç taşıyabilir) ve metin kutularının içeriği kaydedilmez. Kayıtlar yalnızca `runs/` klasörüne yazılır (git'e girmez) ve hiçbir yere gönderilmez. `--screenshot` verirsen ekran görüntüsü de yalnızca `runs/` klasörüne yazılır.

## Gerekenler

- USB hata ayıklaması açık bir **gerçek Android telefon**, Türkiye'deki normal bağlantı (Wi‑Fi ya da mobil veri, VPN kapalı).
- Bilgisayarda `adb`, Node 18+ ve `npm i playwright` (tarayıcı modu için).
- Tarayıcı modu için telefonda Chrome açık olmalı.

## Bir platform için akış (örnek: Instagram)

```bash
# 1) DPI Bypass KAPALI: önce tarayıcı, sonra resmî uygulama. Aynı hesap, aynı işlem.
node diagnose.js web --platform instagram --dpi off --label "giriş + profil düzenle"
node diagnose.js app --platform instagram --dpi off --label "giriş + profil düzenle"
# 2) DPI Bypass'ı uygulamadan aç, aynı iki ölçümü tekrarla.
node diagnose.js web --platform instagram --dpi on  --label "giriş + profil düzenle"
node diagnose.js app --platform instagram --dpi on  --label "giriş + profil düzenle"
# 3) Karşılaştır (web ile web, uygulama ile uygulama).
node diagnose.js compare runs/<...>-web-dpioff.json runs/<...>-web-dpion.json
```

Her kayıtta şunlar bulunur: başlangıç/bitiş zamanı ve saat dilimi, cihaz modeli, Android sürümü, uygulama ya da Chrome sürümü, DPI Bypass tünelinin **gerçekten** çalışıp çalışmadığı (beyana güvenilmez), bağlantının cihazdan ölçülen ülkesi (`loc`, yalnızca tarayıcı modunda), ekrandaki tam metin, ağ hataları, HTTP durumları ve sınıf.

## Test

```bash
NODE_PATH=$(npm root -g) node --test test/
```

Sınıflandırıcıyı, gizlilik ayıklamasını ve tarayıcı ölçümünü gerçek bir CDP bağlantısı üzerinden (yerel Chromium ve yerel sunucuyla) sınar. Telefon gerektirmez ve telefondaki ölçümün yerine geçmez.
