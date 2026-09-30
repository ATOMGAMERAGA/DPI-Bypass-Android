# CI/CD Kurulumu + Google Tarafı — Rehber

Bu dosya iki şeyi açıklar:
1. `ci.yml` ve `release.yml` workflow'larının çalışması için projede yapılması gereken kablolama.
2. Google'la ilgili soruların net cevabı (bildirim için Google'dan dosya gerekir mi + uygulamayı Google'a nasıl doğrulatırsın).

---

## 1. CI/CD kablolaması

İki workflow dosyasını şuraya koy:
```
.github/workflows/ci.yml        # her push/commit + PR'de test (release yok)
.github/workflows/release.yml   # manuel veya v* etiketiyle release
```

### 1.1. Sürümü tek kaynağa bağla ("her yerden değişsin")
`gradle.properties` (proje kökü) — **tek gerçek kaynak**:
```properties
VERSION_NAME=1.0.0
VERSION_CODE=1
```

`app/build.gradle.kts` bunları okusun (böylece uygulama içi sürüm, APK adı, release etiketi hepsi buradan türer):
```kotlin
android {
    defaultConfig {
        applicationId = "net.atom.dpibypass"
        versionName = providers.gradleProperty("VERSION_NAME").get()
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

> İmzalama bloğunun güncel hâli `app/build.gradle.kts` içindedir: anahtar bilgisi
> `keystore.properties` dosyasından **veya** ortam değişkenlerinden okunur, hiçbiri
> yoksa release derlemesi **hata verip durur** (debug anahtarına düşmez).
> Ayrıntı: [`docs/Imzalama-Rehberi.md`](docs/Imzalama-Rehberi.md)
Uygulama içinde sürümü göstermek için `BuildConfig.VERSION_NAME` kullan. `release.yml` yalnızca `gradle.properties`'i günceller → her yer otomatik değişir.

### 1.2. GitHub Secrets (Repo > Settings > Secrets and variables > Actions)
İmzalı release APK için gerekli:
- `KEYSTORE_BASE64` — keystore dosyasının base64'ü
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Keystore oluştur ve base64'e çevir (adım adım: [`docs/Imzalama-Rehberi.md`](docs/Imzalama-Rehberi.md)):
```bash
keytool -genkeypair -v -keystore dpibypass-release.jks -alias dpibypass \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
# Linux:
base64 -w0 dpibypass-release.jks > keystore.b64
# macOS:
base64 -i dpibypass-release.jks -o keystore.b64
```
`keystore.b64` içeriğini `KEYSTORE_BASE64` secret'ine yapıştır. **`release.jks` dosyasını repoya EKLEME** (`.gitignore`'a al). Kaybedersen aynı imzayla güncelleme yayınlayamazsın; yedekle.

### 1.3. Akış özeti
- **CI (`ci.yml`)**: her push/commit + PR → submodülleri çeker, JDK17 + NDK + CMake kurar, `testDebugUnitTest` + `lintDebug` + `assembleDebug` çalıştırır, debug APK'yı artifact yapar. **Release yok.**
- **Release (`release.yml`)**: Actions sekmesinden sürüm girerek manuel çalıştırılabilir. Ayrıca `gradle.properties` sürümünü güncelleyip ana dala gönderdikten sonra aynı commit'e `v1.2.0` gibi bir etiket gönderirsen otomatik başlar. Her iki yolda SemVer doğrulanır, testler çalışır, imzalı APK derlenir ve GitHub Release yayımlanır. Manuel yol sürüm bump commit'i ile etiketi kendi oluşturur; etiket yolu mevcut etiketi kullanır.

> Not: `release.yml` submodülleri de çektiği için native (ByeDPI/hev-socks5-tunnel) derlenir. İlk çalıştırmadan önce keystore secret'lerini eklediğinden emin ol, yoksa iş "KEYSTORE_BASE64 tanımlı değil" ile durur.

---

## 2. Google tarafı

### 2.1. "Bildirim için Google'dan dosya almam gerekiyor mu?" — HAYIR (bu uygulama için)
Karışıklık şuradan: `google-services.json` dosyası **yalnızca Firebase** kullanırsan gerekir — özellikle **FCM (Firebase Cloud Messaging)**, yani **sunucudan cihaza push bildirimi** göndereceksen.

Bu uygulamanın bildirimleri **yerel (local) bildirim**: "Bağlısın / Bağlı değilsin" bilgisini gösteren **foreground service** bildirimi. Bu tamamen Android'e ait (`NotificationManager` + `NotificationChannel`), **hiçbir Google dosyası/hesabı gerektirmez**.

Yani:
- Bağlı/değil bildirimi, Quick Settings tile, watchdog → **Google dosyası GEREKMEZ.**
- `google-services.json` **sadece** ileride sunucudan push, Firebase Analytics veya Crashlytics eklersen gerekir. Bu proje için önerilmez (anti-sansür aracı; Google bağımlılığı olmaması daha temiz, F-Droid'e de uygun).

**Sonuç:** Uygulamayı tamamen Google Play Services'sız derleyebilirsin. `google-services.json` ekleme.

### 2.2. "Uygulamayı Google'a nasıl doğrulatırım?" — İki ayrı şey var

**A) Geliştirici kimlik doğrulaması (yeni zorunluluk — herkesi ilgilendirir).**
Google, **Eylül 2026**'dan itibaren "sertifikalı" Android cihazlara (Play Services yüklü cihazlar) kurulan tüm uygulamaların **doğrulanmış bir geliştirici** tarafından kaydedilmiş olmasını şart koşuyor — **Play Store dışı / sideload dahil**.
- İlk zorunlu ülkeler (Eylül 2026): **Brezilya, Endonezya, Singapur, Tayland**. **Türkiye ilk dalgada değil**; küresel yayılım **2027 ve sonrası**. Yani şu an (2026 ortası) Türkiye'de sideload normal çalışıyor, ama bu geliyor.
- Doğrulama nerede yapılır:
  - Play'de yayınlayacaksan → **Google Play Console**.
  - Sadece Play dışı dağıtacaksan (senin durumun muhtemelen bu) → yeni **Android Developer Console** üzerinden kimliğini doğrulayıp **paket adını (`net.atom.dpibypass`) kaydedersin** ve **kendi imza anahtarınla imzalanmış APK'yı** vererek sahipliği kanıtlarsın.
  - İstenenler: yasal ad, adres, e-posta, telefon; gerekirse resmi kimlik. Kurumsalsan D-U-N-S numarası + web sitesi. Tam dağıtım hesabı ~**$25** tek seferlik.
- **Hobici/öğrenci için ücretsiz hesap**: sınırlı sayıda cihaza, resmi kimlik vermeden dağıtım. Sadece kendine + birkaç arkadaşına kuracaksan bu yeter.
- **"Advanced flow" ve ADB**: Güç kullanıcıları, doğrulanmamış geliştiricinin uygulamasını riskleri kabul edip tek seferlik bir kurulumla yine de yükleyebilir. Ayrıca kendi cihazına **ADB** ile kurmak her zaman serbest (geliştirme için). Yani kendi telefonunda test/kullanım her hâlükârda mümkün.

**B) Google Play'de yayınlama (isteğe bağlı — VPN uygulaması ek kurallar).**
Eğer Play Store'a koymak istersen:
1. **Play Console** hesabı aç (~$25 tek seferlik), kimlik doğrulaması yap.
2. **Play App Signing**'i kabul et (Google imza anahtarını yönetir).
3. **VpnService kullanan uygulamalar** için Play politikası: VPN'i uygulamanın **çekirdek işlevi** olarak kullanmalı ve Console'da **"VPN" beyanını/deklarasyonunu** doldurmalısın (gizlilik politikası, verinin ne yapıldığı vb.).
4. Uyarı: DPI-bypass/anti-sansür bir VPN uygulaması Play incelemesinde **takılabilir** (bölgesel yasal hassasiyet). Reddedilirse tek yol sideload'dur.

**Öneri (bu uygulama için gerçekçi yol):**
- Dağıtımı **GitHub Releases** üzerinden yap (zaten `release.yml` bunu üretiyor). Kullanıcılar APK'yı indirip kurar.
- Otomatik güncelleme için kullanıcılara **Obtainium** öner: GitHub Releases'i kaynak gösterip yeni sürümde otomatik bildirir/günceller. (Kendi güncelleyicini yazmana gerek kalmaz.)
- Türkiye küresel doğrulama dalgasına girene kadar sideload sorunsuz. Girdikten sonra, geniş dağıtım yapacaksan **Android Developer Console**'da paket adını kaydettir; sadece kişisel/az cihaz için ücretsiz hobici hesabı veya advanced flow/ADB yeterli.

---

## 3. Ana prompt dosyasına eklenenler
`DPI-Bypass-ClaudeCode-Prompt.md` içine Bölüm 15 (CI/CD) eklendi ve "Sana verilecek dosyalar" listesi `ci.yml` + `release.yml` içerecek şekilde güncellendi. Claude Code'a hepsini birlikte ver.
