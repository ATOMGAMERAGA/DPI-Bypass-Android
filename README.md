# DPI Bypass

Türkiye'deki ISS'lerin (Türk Telekom, Turkcell/Superonline, Vodafone) **DPI (Deep
Packet Inspection)** ve **DNS hijacking** ile kısıtladığı Discord gibi meşru
servislere erişimi, **kullanıcının kendi cihazından çıkan kendi trafiğini** yerelde
yeniden düzenleyerek (paket parçalama / desync + şifresiz DNS'i DoH ile değiştirme)
açan bir Android uygulaması.

> **Bu araç kişisel, yasal erişim amaçlıdır. Trafiğinizi ŞİFRELEMEZ.** Uzak sunucuya
> trafik göndermez, VPN gibi IP gizlemez. Sadece DPI'ın SNI/Host okumasını bozar ve
> DNS'i DoH ile çözer. GoodbyeDPI / ByeDPI / SplitWire-Turkey ile aynı meşru,
> açık kaynak anti-sansür yaklaşımıdır. Gizlilik/anonimlik gerekiyorsa VPN ayrı bir konudur.

## Mimari (kanıtlanmış yığın)

```
[Uygulamalar]  ──(tüm TCP/UDP)──►  Android VpnService ──► TUN (tun0)
     │
     ▼
hev-socks5-tunnel (native)  ── TUN paketlerini SOCKS5'e çevirir ── 127.0.0.1:1080
     │
     ▼
ByeDPI / ciadpi (native)  ── SOCKS5 proxy + DPI desync (--split/--disorder/--fake/--oob/--tlsrec/--auto)
     │
     ▼
[İnternet]  (gerçek hedef sunucu — trafik yerelde işlenir, dışarı proxy YOK)
```

DPI motoru sıfırdan yazılmamıştır; kanıtlanmış açık kaynak bileşenler kullanılır
(bkz. `NOTICE`). Native katman `git submodule` olarak eklidir:

- `app/src/main/cpp/byedpi` → [hufrea/byedpi](https://github.com/hufrea/byedpi) (CMake ile derlenir)
- `app/src/main/jni/hev-socks5-tunnel` → [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (ndk-build ile derlenir)

## Özellikler

- **Otomatik mod:** ISS'yi **o an gerçekten bağlı olunan ağa göre** tespit eder
  (Wi-Fi/kablolu ise dış IP'nin ASN'si, mobil veride SIM MCC+MNC — tünel açıkken
  alttaki gerçek ağ okunur), preset havuzundaki her stratejiyi **tek tek test
  eder**, DoH ile çözüp TLS el sıkışması yaparak çalışanı bulur ve **en düşük
  ping'liyi** seçer. Test canlı görünür (✓/✗ + ms).
- **Manuel mod:** ISS + preset seçilebilir; "Gelişmiş" alanına serbest ByeDPI
  argümanı girilebilir.
- **DNS seçimi gerçekten uygulanır:** Cloudflare / AdGuard (filtresiz veya reklam
  engelli) / Google — ya da kendi adresiniz. Özel alan üç biçimi de kabul eder: tam
  DoH adresi, yalnızca alan adı (IP'si seçili sağlayıcının DoH'u ile çözülür) veya
  düz IP. Seçim iki yere birden gider: uygulamanın kendi sorguları DoH ile (IP
  bootstrap → DNS hijack aşılır), tünel açıkken cihazdaki uygulamalara da o
  sağlayıcının adres çifti verilir. **Tünel açıkken yapılan değişiklik anında
  uygulanır**, yeniden bağlanmak gerekmez. Ayarlar ekranı hangi adreslerin
  verileceğini ve o an hangilerinin etkin olduğunu yazar.
- **Uygulama ayırma (split tunneling):** Tümü / Yalnızca seçili / Seçili hariç.
- **Quick Settings tile:** Hızlı Panel'e eklenir, tek dokunuşla bağlar/keser; servis
  ölmüşse yeniden başlatır. İkon beyaz/tek renk.
- **Foreground bildirim + watchdog:** Arka planda ölmez; ölürse otomatik toparlar.
- **Samsung Now Bar / Android 16 Live Update:** tünel açıkken kilit ekranındaki Now
  Bar'da ve durum çubuğu chip'inde canlı gösterge. Android 16'nın "promoted
  ongoing" sözleşmesi eksiksiz uygulanır (ongoing + başlık + colorized + promote
  edilebilir stil + IMPORTANCE_MIN üstü kanal + `requestPromotedOngoing`).
- **Pil muafiyeti** kartı, açılışta otomatik bağlan, opsiyonel UDP/QUIC düşürme.
- **Ayarlar → Bilgi:** uygulamanın kendi veri kullanımı (indirilen/gönderilen/toplam),
  işlemci süresi, pil optimizasyonu durumu ve cihaz/sürüm özeti.

## Arayüz — "Aurora Ambient" tasarım sistemi

Arayüz iki güncel referansa dayanır: **Material 3 Expressive** (süre/easing yerine
yay fiziğiyle hareket, güçlü renk-rolü hiyerarşisi) ve **One UI 8.5 Ambient Design**
(saydam, bulanık, yüzen yüzeyler; içeriği öne çıkaran sakin zemin).

- **Tek kaynaklı tasarım sistemi** (`ui/theme` + `ui/design`): renk rolleri ve
  yüzey basamakları, tam tipografi ölçeği (ölçümlerde tabular rakam), şekil
  ölçeği, yay (spring) hareket jetonları — *spatial* (konum/boyut, hafif taşmalı)
  ve *effects* (renk/opaklık, taşmasız) ayrımıyla.
- **Canlı ortam zemini:** yavaşça süzülen aurora ışıkları; zemin **bağlantı
  durumunun rengini alır** (bağlıyken yeşile, hatada kırmızıya kayar).
- **Kahraman bağlan dairesi:** nefes alan ışıma, duruma göre dönen gradyan halka,
  test sırasında belirsiz ilerleme yayı, basınca fiziksel küçülme + haptik, canlı
  bağlı kalma süresi.
- **Her yerde gerçek buzlu cam (backdrop blur).** Yarı saydam düz renkler tamamen
  kaldırıldı. Arka plan bir `GraphicsLayer`'a kaydedilir, her cam yüzey o katmanın
  yalnızca kendi arkasına denk gelen dilimini kendi katmanına çizip `BlurEffect`
  uygular; üstüne renk tonu, üst kenar parlaması ve şeklin konturundan çizilen saç
  teli kenarlık biner. İki katman vardır: içerik kartları yalnızca aurora zeminini
  örnekler, yüzen kabuk (dock, başlık şeridi, sihirbaz) zemin **ve** içeriği
  örnekler — yani altlarından kayan yazılar gerçekten bulanıklaşır. Aurora katmanı
  ekran dışı dokuya alınır, bulanıklık desteklemeyen cihazlarda (API < 31) cam
  otomatik olarak yoğunlaşır.
- **Ana ekranda canlı ölçümler:** taşıyıcı (Wi-Fi/mobil) rozeti, sağlayıcı, seçilen
  strateji, ölçülen gecikme ve bağlı kalma süresi; altında hızlı işlem kartları.
- **İkon-only yüzen dock:** dolu/boş ikon çiftiyle seçim, hedefe koşarken hareket
  yönünde esneyip toparlanan (squash & stretch) gösterge ve sayfa kaydırıldıkça
  küçülüp yerine oturan dock.
- **Başlık devir teslimi:** büyük başlık küçülüp yukarı süzülürken çubuktaki küçük
  başlık aşağıdan gelir; cam şerit ve alt çizgi ayrı bir eğriyle biraz geç katılır.
  Kaydırma değeri kompozisyona hiç sızmaz (yalnızca çizim aşamasında okunur).
- **Sekme değiştirince ekran hep en üstten başlar** — bıraktığınız kaydırma
  konumunda uyanmazsınız.
- Kart tonları, satır basma vurguları, ikon takasları, ölçüm değeri sayaçları,
  rozetler, segment denetimleri, seçim işaretleri, metin alanı odağı, diyalog
  girişleri ve liste yeniden sıralamaları — hepsi yay fiziğiyle animasyonlu.
- **Uygulama listesinde gerçek uygulama ikonları**, arama, seçilenler en üstte.
- **Haptik ayarı artık gerçekten çalışıyor:** bağlanma/seçim/sekme değişimi
  şiddeti farklı dokunsal geri bildirim verir; sistemde animasyonlar kapalıysa
  sonsuz döngülü animasyonlar durur (erişilebilirlik + pil).
- Açık/koyu tema, gece/gündüz pencere zemini (açılışta siyah yanıp sönme yok).

### Performans — yavaşlatma / ping artışı YOK

Trafik uzak sunucuya yönlendirilmez; yalnızca **bağlantı kurulum aşamasındaki ilk
paketler** (ClientHello/Host) yerelde parçalanır — veri akışına dokunulmaz. Bu yüzden
bant genişliği kaybı ve ping artışı olmaz. Otomatik test de stratejileri **en düşük
gecikmeye göre** puanlar (başarı birincil, latency ikincil), böylece seçilen strateji
en hızlı çalışandır.

## Derleme

Gereksinimler: JDK 17+, Android SDK, **NDK 26.3.11579264**, **CMake 3.22.1**.

```bash
git clone --recurse-submodules <repo-url>
cd DPI-Bypass-DC
./gradlew assembleDebug
```

> Submodülleri unuttuysanız: `git submodule update --init --recursive`

**Release derlemesi imzalama anahtarı ister.** `keystore.properties.example`
dosyasını `keystore.properties` olarak kopyalayıp doldurun, sonra
`./gradlew assembleRelease`. Anahtar tanımlı değilse derleme açık bir hatayla
durur — debug anahtarına **düşmez** (debug imzalı sürüm dağıtılamaz: imzası her
makinede farklıdır, üzerine güncelleme kurulmaz ve Play Protect uyarır).
Bilinçli test için `./gradlew assembleRelease -PallowDebugSigning=true`.
Adım adım: [`docs/Imzalama-Rehberi.md`](docs/Imzalama-Rehberi.md)

## Sürüm çıkarma (tek kaynak)

Sürüm **tek kaynaktan** yönetilir: `gradle.properties` içindeki `VERSION_NAME` /
`VERSION_CODE`. `app/build.gradle.kts` bunları okur; uygulama içi sürüm
`BuildConfig.VERSION_NAME`'den gelir.

- **CI (`.github/workflows/ci.yml`):** her push + PR → submodülleri çeker, JDK17 +
  NDK + CMake kurar, `testDebugUnitTest` + `lintDebug` + `assembleDebug` çalıştırır,
  debug APK'yı artifact yapar. **Release yok.**
- **Release (`.github/workflows/release.yml`):** Actions → Release → Run workflow →
  sürüm (ör. `1.2.0`) girersin → SemVer doğrular, `versionCode` hesaplar,
  `gradle.properties`'i günceller, testleri kapı olarak çalıştırır, imzalı APK üretir,
  sürüm bump commit'i + `vX.Y.Z` etiketi atar, GitHub Release olarak yayınlar.

### İmzalama secret'ları (Repo → Settings → Secrets and variables → Actions)

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Dördü de
tanımlı değilse Release workflow'u **hata verip durur** (sessizce debug imzasına
düşmez); yalnızca "SADECE TEST" kutusu işaretlendiğinde debug anahtarına izin
verilir ve o çalıştırma tag/release oluşturmaz.

```bash
keytool -genkeypair -v -keystore dpibypass-release.jks -alias dpibypass \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
base64 -w0 dpibypass-release.jks > keystore.b64   # macOS: base64 -i ... -o keystore.b64
```

`keystore.b64` içeriğini `KEYSTORE_BASE64`'e yapıştırın. **Keystore repoya
EKLENMEZ** (`.gitignore`) ve **kaybedilirse aynı uygulamaya güncelleme
yayınlanamaz** — yedekleyin. Yerel derleme `keystore.properties`, CI ise
`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD` env değişkenlerini kullanır.
Tam rehber: [`docs/Imzalama-Rehberi.md`](docs/Imzalama-Rehberi.md)

## Dağıtım / Google

- **Bildirim için Google dosyası gerekmez.** Bağlı/değil bildirimi yerel foreground
  service bildirimidir; `google-services.json` yalnızca FCM (sunucudan push)
  kullanılırsa gerekir. Bu proje Firebase/Play Services **kullanmaz** (temiz, F-Droid
  uyumlu).
- **Dağıtım:** GitHub Releases (`release.yml` üretir) + otomatik güncelleme için
  kullanıcılara **Obtainium** önerilir (GitHub Releases'i kaynak gösterir).
- **Geliştirici doğrulaması:** Eylül 2026'dan itibaren sertifikalı cihazlarda
  (sideload dahil) doğrulanmış geliştirici zorunluluğu başlıyor (ilk ülkeler
  Brezilya/Endonezya/Singapur/Tayland; Türkiye 2027+). Detay: `CI-CD-ve-Google-Rehberi.md`.

## Hızlı Panel'e (Quick Settings) ekleme — Samsung One UI

Bildirim panelini tam aç → **⋮ / Kalem (Düzenle)** → **DPI Bypass** tile'ını üstteki
aktif alana sürükle → Bitti. Artık tek dokunuşla bağlanıp kesebilirsin.

## Gerçekçi uyarılar

- Kazanan strateji ISS'e ve zamana göre değişir; **tek sabit çözüm yoktur** — otomatik
  test + güncellenebilir preset havuzu şarttır. Presetler `strategy/Strategy.kt`
  içinde; kolayca JSON'a taşınıp uzaktan güncellenebilir.
- **QUIC/UDP (HTTP/3)** bazı DPI'larda farklı davranır. Ayarlardan "UDP/QUIC'i tünelde
  bırakma" ile UDP düşürülüp uygulamalar TCP'ye zorlanabilir (DNS/sesli görüşmeyi
  etkileyebilir — varsayılan kapalı).
- **DNS notu:** DoH çözümlemesi otomatik strateji testinde ve sağlık kontrolünde
  gerçek DoH (IP bootstrap) ile yapılır. Cihazdaki diğer uygulamalar için tünel,
  seçili sağlayıcının **iki** düz DNS adresini yazar (`addDnsServer`) ve sorgular
  desync tüneli üzerinden gider. İkinci adres bilinçlidir: tek adres yazıldığında o
  sunucuya ulaşılamadığı anda Android ağın kendi — yani ele geçirilmiş — sunucusuna
  düşerdi. UDP'yi düşüren ayar açıksa DNS sorguları da düşer; bu yüzden varsayılan
  kapalıdır.

## Lisans

GPL-3.0 (bkz. `LICENSE`, `NOTICE`). ByeDPI ve ByeDPIAndroid GPL-3.0 olduğundan bu
uygulama da GPL-3.0 yayımlanır.
