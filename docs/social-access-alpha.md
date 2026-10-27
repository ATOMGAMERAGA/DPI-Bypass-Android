# Sosyal Erişim — Alpha (Android)

## Revizyon — 27 Ekim 2026: e-Devlet doğrulaması olmadan erişim (yurt dışı çıkış)

**Amaç:** Instagram, X, TikTok gibi platformları Türkiye'de e-Devlet kimlik doğrulaması istenmeden kullanabilmek. Önceki sürümler yalnız DPI engelini aşmaya çalışıyordu; platformlar doğrulamayı bağlantının geldiği ülkeye (IP) göre istediği için bu amaca hiç yaklaşmıyordu.

### Ne değişti

- **Yeni sekme:** Alt gezinmede **Sosyal Erişim** (Durum'un yanında). Ana ekranda ve Ayarlar'da da "e-Devlet doğrulaması olmadan" satırı aynı sayfayı açar.
- **Ekran düzeni:** üstte tek bakışta durum kartı (Kapalı / Platform seç / Sunucu gerekli / Hazır — bağlan / 🇩🇪 Almanya üzerinden / Sunucu Türkiye'de / Sunucuya ulaşılamıyor), canlı sayaçlar (yurt dışından geçen ve kesilen bağlantılar, son yurt dışı alan adı), ardından "Nasıl çalışır" şeması (Telefon → Sunucu → Platform) ve üç adım: **1 · Platformları seç**, **2 · Yurt dışı sunucu** (adres + "Sunucuyu test et"), **3 · Bağlan**. Altta "Doğrulama yine çıkarsa" (konum izni, oturumu yeniden açma, +90 numara/SIM, sunucu sahibinin görebildikleri) ve "Ek alan adları".
- **Eski adım adım DPI rehberi** kaldırılmadı; "Platform hiç açılmıyor mu? → Bağlantı sorunu rehberi" olarak bu sayfadan ve Ayarlar'dan açılır. Aşağıdaki bölümler o rehberi anlatır.

### Nasıl çalışır (teknik)

```
TUN -> hev-socks5-tunnel -> ExitRouter (127.0.0.1:1081)
                              |-- platform alan adı --> yurt dışı SOCKS5/HTTP proxy --> platform
                              '-- diğer her şey -----> ByeDPI (127.0.0.1:1080) --> İnternet
```

- `vpn/ExitRouter.kt`: yerel SOCKS5 yönlendirici. 443/80 portlu bağlantılarda ilk paketi okur (`social/ForeignExit.kt` → `Sniff`: TLS ClientHello SNI ya da HTTP Host), alan adı seçili platformlardan biriyse (`ExitMatcher`, alt alan adları dahil) bağlantıyı alan adıyla yurt dışı sunucuya açar (`social/ExitConnector.kt`; ad çözümlemesi sunucuda yapılır), değilse isteği ve okunan baytları aynen ByeDPI'a iletir — desync stratejisi eskisi gibi çalışır. Diğer portlar doğrudan ByeDPI'a geçer.
- **Sızıntı kuralları:** yurt dışı sunucuya ulaşılamazsa bağlantı kesilir, doğrudan yola düşülmez; UDP/443 (QUIC) düşürülür ki uygulamalar ayrıştırılabilen TLS'e dönsün (DNS, oyun, ses UDP'si ByeDPI'a iletilir); yurt dışı çıkış açıkken IPv6 da tünele alınır.
- **Çıkış ülkesi:** `ExitProbe` sunucu üzerinden `https://www.cloudflare.com/cdn-cgi/trace` açar ve `loc=` alanını okur. Bağlanınca otomatik, "Sunucuyu test et" ile elle çalışır. `TR` çıkarsa ekran sunucuyu reddeder.
- Ayarlar: `exitEnabled`, `exitServer`, `exitPlatforms`, `exitExtraDomains`; değişince tünel `TunnelConfig.exit` üzerinden yeniden kurulur.
- Uygulama kapsamı seçili platform uygulamalarını tünele almıyorsa ekran uyarır ve tek dokunuşla tünele alır.

### Sınırlar (ekranda da yazar)

- Uygulama sunucu sağlamaz; kullanıcı kendi ya da güvendiği bir SOCKS5/HTTP proxy'yi girer. Sunucu sahibi hangi platforma bağlanıldığını görür; içerik TLS ile şifreli kalır.
- Platform IP dışında GPS konumu, SIM/uygulama mağazası bölgesi, +90 telefon numarası ya da önceden başlatılmış doğrulamaya da bakabilir; bunları hiçbir bağlantı ayarı değiştiremez.
- Kendi protokolüyle doğrudan IP'ye bağlanan mesajlaşma uygulamaları (WhatsApp, Telegram, Signal) alan adıyla ayrıştırılamaz; listede yoktur.
- ECH kullanan sitelerde dış SNI gerçek alan adı olmadığından eşleşme olmaz (listedeki platformlar şu an ECH kullanmıyor).
- Gerçek cihaz/operatör/hesap doğrulaması yapılmadı. Yönlendirici gerçek soketlerle birim testlidir (`ForeignExitTest`), ekran durumları `SocialExitScreenshotTest` ile çizilir; sunucu testi gerçek bir HTTP proxy üzerinden çalıştırıldı.

---

> **Bağlantı sorunu rehberinin güncel davranışı: "Revizyon — 26 Ekim 2026 (2): adım adım rehber" aşağıdadır.** Eski tek sayfalık ekranı (Durum · Uygulamalar · Erişim yolu kartı · Seçenekler · Başlat · Deneme · Tanılama) anlatan bölümler tarihsel kayıttır; o ekrandaki düğmeler artık yoktur, karşılıkları aşağıdaki eşleme tablosundadır. Bu revizyonda da **gerçek cihaz/operatör/hesap doğrulaması yoktur**.

## Revizyon — 26 Ekim 2026 (2): adım adım rehber (temel: `224e048`, PR #17 merge)

### 1. Nasıl kullanılır (güncel)

Ayarlar → **"Bir uygulama açılmıyor mu?"** satırı rehberi açar. Her sayfada tek soru ya da tek görev, üstte **Hazırlık · Kontrol · Sonuç** göstergesi vardır (koşullu akışta sabit "3/8" sayacı kullanılmaz). Geri tuşu/hareketi ve sayfadaki geri oku aynı işi yapar: cevaplar silinmez, yeni test başlamaz.

1. **Karşılama** — ilk açılışta "Başlayalım" (Sosyal Erişim kapalıysa açar). Kayıtlı çalışan ayar varsa kısa özet + **"Instagram'ı aç"** + "Sorun yaşıyorum". Yarım kalan akış varsa **"Kaldığın yerden devam et"** (motor durumu yeniden okunur; eski test tekrar çalıştırılmaz).
2. **Hangi uygulama açılmıyor?** — kurulu olanlar önce; kurulu değilse "tarayıcıda denenebilir" yazar (hiçbir şey indirilmez). "Listede yok / site adresi gireceğim" ayrı sayfaya gider (özel alan adı, paylaşım bağlantısı, platform algılama).
3. **Ne yapmak istiyorsun?** — platformun desteklediği işlemler (içerik, video, hesabım, mesajlaşma, paylaşım, sesli görüşme) + "Emin değilim, genel kontrol yap". Seçim gerçek `Goal.requiredCapabilities`'e çevrilir (`AccessAction.capability`); eski `PLAY_MEDIA`/`VIEW_SELECTED_CONTENT` ikiliği yoktur.
4. **Belirli bir içerik mi?** — yalnız içerik/video işleminde; isteğe bağlı. Başka platforma ait bağlantı sessizce test edilmez.
5. **Sorun ne?** — Hiç açılmıyor / video yüklenmiyor / çok yavaş / giriş-doğrulama / emin değilim. "Giriş veya doğrulama" yerel profilleri döndürmez: önce yollar kontrol edilir.
6. **Bağlantıyı hazırla** (yalnız gerekiyorsa) — internet yok, VPN izni, izin reddi, başka VPN, bağlantı kurulamadı, kapsam dışı uygulama, boş "yalnızca seçili" listesi, tarayıcı yok: her biri ayrı sayfa ve tek düzeltme eylemi. Android'in gerçek izin penceresi açılır; dönüşte aynı rehbere gelinir.
7. **Tarayıcı** (yalnız web yoluna geçilince ve geçerli kayıtlı tercih yoksa).
8. **Şu anda çalışıyor mu?** (yalnız "Emin değilim" dendiyse) — "Evet" ise "Şu anda çalışıyor" sonucu; başarı yazılmaz.
9. **Nasıl deneyelim?** — "Otomatik kontrol (Beta)" ya da "Adım adım kendim deneyeceğim"; mevcut tercih sessizce değişmez. "Başka uygulamalar da sorunsuz kalsın" buradan (ve işlem sayfasından) açılır.
10. **Mobil veride ek veri?** (yalnız otomatik + herkese açık belirli içerik + ölçülü ağ).
11. **Deneme** — sayfa motorun GERÇEK durumundan türetilir: "Şu anki durum ölçülüyor / Yöntem uygulanıyor / Bağlantı yolları kontrol ediliyor" (sahte yüzde/süre yok), "Durdur" her zaman görünür.
12. **Şimdi Instagram'da dene** — tek görev; ana düğme "Instagram'ı aç" (resmî paket) ya da "Instagram · <tarayıcı> ile aç" (o paket). Mesaj/paylaşım/arama kullanıcının kendi eylemidir. Açılamazsa test kaydı düşülmez.
13. **Instagram'da ne oldu?** — Çalıştı / Bir kısmı çalıştı / Çalışmadı / Doğrulama istedi / Test edemedim·Emin değilim. Hesap işleminde ayrı soru: **"Kendi hesabın açık mıydı?"**. Tekrar kontrolde ayrı soru: **"Uygulamayı yeniden başlatıp yeni içerik denedin mi?"** (kullanıcı adına işaretlenmez).
14. **Sonuç** — "Instagram'da video çalıştı." + "Senin bildirimin" + "İlk denemede çalıştı"/"Ayrı zamanlarda doğrulandı"; **"Bu ayarla devam et"** → karşılamada "Instagram'ı aç". İsteğe bağlı kısa tekrar kontrol en erken ilk bildirimden 10 dk sonra açılır (arka planda sayaç çalışmaz).

Gelişmiş ayarlar ve Tanılama ayrı sayfalardır; rehberden/yardımdan açılır, geri dönmek akışı ve cevapları korur.

### 2. Sayfa ve dal haritası

```
Karşılama ─┬─ (kayıt var) Uygulamayı aç │ Sorun yaşıyorum → İşlem
           ├─ (yarım) Kaldığın yerden devam et │ Baştan başla
           └─ Başlayalım → Uygulama ─┬─ Listede yok → Site adresi ─(başka platform)→ o platform
                                     └─ İşlem ─(içerik/video)→ Bağlantı? → Sorun
Sorun ─→ [Hazırla: ağ yok│izin│ret│başka VPN│kurulamadı│kapsam│boş liste│tarayıcı yok] → [Tarayıcı] →
        ├─ (doğrulama) Yol kontrolü: Şimdi dene → Ne oldu → [Hesap?] → Sonuç
        └─ [Şu an çalışıyor mu?] → Nasıl deneyelim → [Ek veri?] → Deneme
Deneme (motor durumundan) ─┬─ Çalışıyor (Durdur · Adım adım · Açıldı, burada dur)
   ├─ Test penceresi (oynat'a dokun → devam)          ├─ Şimdi dene → Ne oldu → [Hesap?] → (motor)
   ├─ Kısmi: Böyle kullan │ Diğer yolu dene           ├─ Duraklatıldı: bekleme│bütçe│bağlantı gerekli│test edilemedi│
   ├─ Bağlantın değişti → Kontrolü tekrarla           │                yanıt yok│ağ değişti│internet yok│kalıcılaştırılamadı
   ├─ Kurtarma → Geri almayı yeniden dene             └─ Yarıda kaldı → Aynı cevaplarla yeniden başlat
   └─ Sonuç: çalıştı │ kısmi │ çalışmadı (sıradaki yöntem / tarayıcıda dene) │ doğrulama (tarayıcıda da kontrol et /
             bağlantı yöntemlerini dene) │ şu an çalışıyor │ bulunamadı │ otomatik karar veremedi (adım adım) │ durduruldu
```

### 3. Eski özellik → yeni yer → nasıl doğrulandı

Kanıt: **J** = JVM testi (gerçek `ExperimentCoordinator` + sözleşmeyi izleyen motor taklidi), **U** = Robolectric Compose arayüz testi, **G** = Robolectric ekran görüntüsü (sayfa çizildi), **S** = statik inceleme, **C** = cihaz (yok).

| Eski yetenek | Yeni yer | Doğrulama |
|---|---|---|
| Çoklu platform / ortak plan, gerileme kontrolü | Uygulama + "Başka uygulamalar da sorunsuz kalsın" sayfası; Gelişmiş → Uygulamalar; motor değişmedi | J `MultiTargetTest` (mevcut), S |
| Odak uygulama | Açık birincil seçim (`socialPrimaryPlatform`); enum sırasından çıkarılmaz | U `firstRunIsOneQuestionPerPage…`, S |
| Özel alan adı, paylaşım linki, platform algılama | "Listede yok / site adresi" ve "Belirli bir içerik mi?" sayfaları; başka platform bağlantısında "X olarak devam et" | J `genuinelyDifferentPlatformLinkIsNotSilentlyTested` |
| Manuel + Otomatik Beta | "Nasıl deneyelim?"; deneme sürerken "Adım adım kendim deneyeceğim"; otomatik karar veremezse "Adım adım devam et"; Gelişmiş → kip | J `firstRunAutoFlow…`, `manualFailureRollsBack…` |
| Resmî uygulama / web ve tarayıcı seçimi | "Şimdi dene" (uygulama ya da açık tarayıcı paketi), "Başka tarayıcı seç", Tarayıcı sayfası, Gelişmiş → Tarayıcı | J `browserEvidenceStaysWithItsBrowser`, `missingAppUsesExplicitWeb…`, U `missingAppShowsVisibleFix…` |
| Anonim kontrollü WebView ve medya gözlemi | Yalnız otomatik + herkese açık belirli içerik dalında "Test penceresinde bakılıyor"; "Bende hâlâ sorun var" | J `controlledWindowAndMeteredQuestionAreConditional`, mevcut `SocialCoordinatorTest`, G |
| Ölçülü ağda ek veri izni | "Mobil veride ek veri kullanılsın mı?" (yalnız gerekince; "Hayır" ile sürer) | J (aynı test) |
| Başlangıç ölçümü / feedback / report / route ledger | Tek "Ne oldu?" bildirimi → yol kaydı + (deneme içindeyse) geri bildirim ya da (tur dışında) hedef raporu; tek kez | J `doubleTapsProduceSingleTrialAndSingleReport`, `firstRunAutoFlow…` |
| Aynı hesap, yeni içerik/yeniden başlatma, tekrar teyidi | "Kendi hesabın açık mıydı?" ve tekrar kontrolde "yeniden başlattın mı?" ayrı sorular; ≥10 dk kuralı korunur | J `accountActionWithoutSameAccount…`, `reverifyNeedsGapAndUserDeclaredFreshStart`, mevcut `AccessRoutesTest` |
| Hesap açılış dönemi | Gelişmiş → "<platform> · işlem bazında yollar" altında isteğe bağlı; ilk kurulumu engellemez | S, mevcut `AccessRoutesTest.transitionNote…` |
| Planı koruma, ana ayar yapma, planı kaldırma | Sonuç → "Bu ayarla devam et", "Bu ayarı ana bağlantı ayarım da yap"; Gelişmiş → Plan (kaldırma onaylı, etkisi yazılı) | J `stopClearPlanDisableAndLeaveAreDistinct` |
| Duraklat/devam, bütçe, cooldown, kurtarma | Duraklatıldı sayfaları (neden başına tek eylem), Kurtarma sayfası | J `startBlockedByCooldownIsShownNotHung`, `keepFailureDoesNotHangInPreparing`, mevcut `BudgetAndWaitRegressionTest`, G |
| Paket kapsamı, DNS/QUIC/Vodafone | "Bağlantıyı hazırla" kapsam düzeltmesi (yalnız ilgili paket eklenir); Gelişmiş → Bağlantı ayarları / Uygulama filtresi / önceki filtreyi geri yükle | J `preparationIssuesNeverLookReady`, mevcut `PlanAndScopeTest` |
| Seçilileri şimdi kontrol et, hedef başına durum, çakışma | Gelişmiş → Uygulamalar | S |
| Tanılama önizle/dışa aktar, geçmiş temizle | Tanılama sayfası (dışa aktarma hedefi açılamazsa başarı denmez; silme onaylı, kurtarma/bekleme/plan korunur) | S, mevcut `AccessRoutesTest.diagnosticExportIsMasked` |
| "Nasıl çalışır?" | Rehberde "?" yardım | G |

### 4. Son merge'deki inceleme noktaları (statik bulgu → yeniden üretim → düzeltme)

| # | Bulgu | Durum |
|---|---|---|
| 1 | Önerilen web yolu, kanıtın alındığı değil SEÇİLİ tarayıcıyı açıyordu | `AccessRoutes.launchFor`: kanıt yalnız kendi tarayıcısında; tarayıcı yoksa "yeniden kontrol" (J) |
| 2 | Hedefli açılış başarısızsa hedefsiz `ACTION_VIEW` (App Links → uygulama) | Kaldırıldı; `AccessLauncher.open` yüzey dışına düşmez, sayfada hata + tek düzeltme (U, S) |
| 3 | Tek yüzey denenmişken "bütün yollar" sonucu | `decide` desteklenen yüzeyleri (`surfaces`) ve `untested` listesini kullanır; desteklenmeyen web işlemi kaydedilmez (J) |
| 4 | Kontrol adresine ulaşmak "kesin sunucu şartı", UNKNOWN "kesin ağ arızası" diye sunuluyordu | `GateCause`: `CHECK_ADDRESS_REACHABLE`, `NETWORK_PROBLEM_SUSPECTED` (yalnız FAIL), `NETWORK_UNCERTAIN`, `NETWORK_NOT_MEASURED`; metinler "büyük olasılıkla / belli değil" (J) |
| 5 | Ayrı feedback/report/recordRoute düğmeleri; çift kayıt | Tek bildirim planı (`GuideFlow.plan`), gönderilmiş kimlik kümesi, motor kabulü dönüş değeri (J) |
| 6 | Bağlam yalnız coroutine başında karşılaştırılıyordu | `AccessRoutes.record`/`commit`: ölçüm öncesi ve KABUL ANINDA; motor geri bildirimi aynı anlık görüntüyle (J `contextChangeDuringMeasurement…`) |
| 7 | Kapat/geri/özelliği kapat/planı kaldır karışıyordu | Rehberden çıkmak hiçbir şeyi durdurmaz; "Durdur" geçici denemeyi geri alır; özelliği kapatmak planı korur (onay penceresinde yazılı); "Planı kaldır" onaylı (J) |
| 8 | Kayıt/I-O hatası "ağ değişti" diye gösteriliyordu | `RouteSave.WriteFailed` ayrı; "Kaydı tekrar dene" yalnız kaydı ve yalnız açılış bağlamına yazar; okunamayan dosya yedeklenemezse üzerine yazılmaz (J) |
| + | Kalıcılaştırma (commit) başarısız olunca tur SELECTING'de asılı kalıyordu (sonsuz "hazırlanıyor") | `KEEP_FAILED` duraklaması, aday kuyruğa döner (J `keepFailureDoesNotHangInPreparing`) |
| + | Deneme sırasında alınan bildirim, aynı ayar kalıcılaşınca bayatlıyordu (bağlam motorun gerçek ayarını değil plan defterini içeriyordu) | `RouteContexts.of`: deneme sırasında adayın ayarı, plan defteri profile girmez (J) |

### 5. Yaşam döngüsü

- Rehber durumu süreç kapsamlıdır (`SocialGuideHost`) ve `noBackupFilesDir/social-guide.json`'a yazılır: ekrandan çıkmak/başka sekmeye gitmek cevapları ve süren testi kaybettirmez.
- Süreç ölümü: cevaplar geri gelir; bekleyen dış testin süreç kimliği uyuşmadığı için rapor alınmaz ("Bağlantın değişti → Kontrolü tekrarla"); eski deneme yoksa "Deneme yarıda kaldı → Aynı cevaplarla yeniden başlat". Eski PASS diriltilmez (J `processDeathRestoresAnswersButNotPendingPass`).
- Döndürme: Activity `configChanges` ile yeniden yaratılmaz; durum zaten süreç kapsamlıdır.
- Yan etkiler kompozisyonda başlamaz; denetleyici niyetleri tek sahipli (`starting`/`stopping`/`acting` bayrakları, gönderilmiş bildirim kimlikleri).
- Kayıt şeması: rehber durumu `schema=1` (bilinmeyen/bozuk → karşılama). Yol kaydı şeması değişmedi; eski kayıtların bağlam anahtarında `plan=` bulunduğu için güncellemeden sonra "yeniden kontrol gerekiyor" görünürler (zaten süreç kimliğine bağlıydılar).

### 6. Doğrulama kaydı — bu revizyon

Bu ortam: Linux x86_64 bulut konteyneri, JDK 21 (Gradle hedefi 17), Android SDK 35 / build-tools 35.0.0 / NDK 26.3.11579264, Node 22. **Bağlı cihaz yok, `/dev/kvm` yok** (emülatör çalıştırılamadı). Gerçek One UI / API / WebView sürümü test ortamından **tespit edilemedi**; Robolectric arayüz testleri SDK 34 taklidinde koştu.

| Eksen | Komut | Sonuç |
|---|---|---|
| Başlangıç (temel `224e048`, değişiklik öncesi) | `./gradlew testDebugUnitTest lintDebug assembleDebug` | 140/140 JVM testi geçti; lint **2 hata** (`WrongConstant` `DpiTileService.kt:100`, `ProduceStateDoesNotAssignValue` `AppsScreen.kt:393`), 25 uyarı; debug APK üretildi |
| Derleme | `./gradlew clean testDebugUnitTest lintDebug assembleDebug` | BUILD SUCCESSFUL |
| JVM (birim + motorla bütünleşik rehber) | aynı | **169/169** geçti (0 atlanan); yeni: `SocialGuideTest` 24 test |
| Arayüz (Robolectric Compose, JVM) | aynı | `SocialGuideUiTest` 4/4, `SocialGuideScreenshotTest` 1/1 (21 sayfa görüntüsü: `app/build/outputs/guide-screenshots`) |
| Lint | `lintDebug` (rapordaki sayı okundu; `abortOnError=false`'a güvenilmedi) | **0 hata**, 28 uyarı (25 eski + 3 yeni test bağımlılığı sürüm uyarısı). İki eski hata kök nedeninden düzeltildi: kutucuk `PendingIntentActivityWrapper`'a `FLAG_IMMUTABLE` verilmez (değişmezliği `isMutable=false` ekler); anahtarlı `produceState` aşırı yüklemesinde lint'in atamayı görmediği yanlış pozitif → `remember` + `LaunchedEffect`. Bastırma eklenmedi |
| JavaScript gözlemci | `node --test tests/observer.test.cjs` | 8/8 geçti |
| Cihaz (enstrümantasyon) | `./gradlew connectedDebugAndroidTest` | **NOT_RUN** — `DeviceException: No connected devices!`. `SocialGuideDeviceTest` derleniyor ama hiç koşmadı |
| Saha (S24 Ultra, operatör, gerçek hesap) | — | **FIELD_UNVERIFIED** — aşağıdaki 5 dakikalık listeyle kullanıcı tarafından yapılmalı |

Bu revizyondaki mutasyon kontrolü: bekleyen testin bağlam kontrolü, çift gönderim koruması, "denenmemiş yüzey varken bütün yollar yok" ve kabul anı bağlam kontrolü tek tek bozulduğunda ilgili `SocialGuideTest` testleri kırmızıya döndü; geri alınınca yeşil.

Mevzuat notu (Gelişmiş ayarlar → hesap açılış dönemi): Resmî Gazete sayfasına bu ortamdan erişilemedi (zaman aşımı); not "bu sürümde yeniden doğrulanamadı" etiketiyle gösterilir ve ana akışta yer almaz.

APK (kurulabilir **debug/test** APK; release anahtarı bu ortamda yok):
- Paket `net.atom.dpibypass.debug`, sürüm `2.4.1-debug` (versionCode 20401), minSdk 26, targetSdk 35
- İmza: **Android Debug** sertifikası (v2 şeması). Mevcut release kurulumunun üzerine güncelleme olarak kurulamaz; yan yana ayrı paket olarak kurulur. Veri silmek varsayılan çözüm değildir.
- ABI: arm64-v8a, armeabi-v7a, x86_64; arm64-v8a içinde `libbyedpi.so`, `libhev-socks5-tunnel.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` bulunduğu `unzip -l` ile doğrulandı
- SHA-256 ve indirme: CI çalıştırmasının `app-debug` artifact'ı (aynı kaynaktan yeniden üretilir; yerel derlemenin özeti PR açıklamasındadır)

### 7. S24 Ultra — 5 dakikalık saha kabul listesi (kullanıcı tarafından; yapılmadı)

Her satır için sonucu ✔ / ✘ / NOT_RUN olarak not et. Hesap ve içerik bilgisi paylaşma.

1. Kurulum (≤1 dk): APK'yi kur (debug imzalı; mevcut kurulumla imza farklıysa güncelleme kurulmayabilir — veriyi silmek varsayılan çözüm değildir, önce sor). Ayarlar → "Bir uygulama açılmıyor mu?" → Başlayalım.
2. Seçim (≤1 dk): Instagram → "Video izlemek" → bağlantısız → "Açılıyor ama video yüklenmiyor". Geri oku ile bir adım dön: seçimlerin duruyor mu?
3. İzin (≤30 sn): "Bağlantıyı hazırla" → sistem penceresi. Bir kez **Reddet** → "İzin verilmedi" sayfası mı? "Tekrar dene" → İzin ver → rehbere geri dönüldü mü?
4. Deneme (≤1,5 dk): "Adım adım kendim deneyeceğim" → "Yöntemleri dene" → "Instagram'ı aç" gerçekten Instagram'ı mı açtı? Video oynat → geri dön → "Ne oldu?" sayfası geldi mi (başa atmadan)? Cevapla → sonuç sayfası ve "Bu ayarla devam et".
5. Ağ değişimi (≤1 dk): Karşılamada "Instagram'ı aç" çalışıyor mu? Wi-Fi'ı kapatıp mobil veriye geç, rehberi aç: "Ağın değişti" notu / yeniden kontrol isteniyor mu? Bir test açıkken ağı değiştirip dönünce "Bağlantın değişti → Kontrolü tekrarla" görünüyor mu (sonuç yazılmadan)?

## Revizyon — 26 Ekim 2026 (1) (temel: `5488ab9`, v2.4.1) — tarihsel

> Bu bölümdeki "Sosyal Erişim → Erişim yolu kartı", "Bildirimi kaydet", "Bu yolla aç" gibi düğmeler eski tek sayfalık ekrana aittir ve artık yoktur; karşılıkları yukarıdaki eşleme tablosundadır.

### 1. Kaynak araştırması: yaş doğrulama yönetmeliği

Bu tarihte okunan resmî metin:

| Kaynak | Yayım | Doğrudan doğrulanan |
|---|---|---|
| [Resmî Gazete 10.10.2026, sayı 33396 — fihrist](https://www.resmigazete.gov.tr/eskiler/2026/10/20261010.htm) ve [yönetmelik PDF'i](https://www.resmigazete.gov.tr/eskiler/2026/10/20261010-15.pdf) | 10 Ekim 2026 | "Sosyal Ağ Sağlayıcı Tarafından Çocuklara Özgü Ayrıştırılmış Hizmet Sunulmasına ve Yaş Doğrulamaya İlişkin Yönetmelik", Cumhurbaşkanlığı (Siber Güvenlik Başkanlığı). Dayanak: 5651 s. Kanun ek 4. madde 7/20/25. fıkralar (Madde 3). Kapsam: Türkiye'den erişilebilen yurt içi/yurt dışı sosyal ağ sağlayıcılar **ve kullanıcılar** (Madde 2). 15 yaşını doldurmamışlara hizmet sunulamaz (Madde 5/1). Hesap oluşturma aşamasında yaş doğrulama belirteci zorunlu (Madde 6/2); makul şüphede hesap askıya alınıp yeniden doğrulama istenir (Madde 6/3). Doğrulama e-Devlet Kapısı üzerinden; belirteç kullanıcı tarafından platforma yüklenir, yalnız 15/18 eşiğini içerir (Madde 7/a–ç). Türkiye'de mukim olmayıp geçici bulunanlar muaf; pasaportla istisnai belirteç (Madde 7/d). Mevcut hesaplar **31/3/2027'ye kadar** en az bir kez doğrulanmalı (Geçici Madde 1). Yürürlük **1/11/2026** (Madde 13). |
| Meta/Instagram yardım merkezi (arama) | — | Türkiye'ye özgü e-Devlet belirteci uygulamasını anlatan resmî platform belgesi **bulunamadı**. Platformların hangi gün, hangi istemcide (uygulama/web), hangi akışta uygulayacağı **doğrulanmadı**. |
| Haberler (Diken, BirGün, bianet, biometricupdate vb.) | Ekim 2026 | Yalnız ipucu; yukarıdaki maddeler resmî metinden okundu. |

Sonuçlar (yalnız metnin söylediği):
- Yönetmelik VPN, IP ya da erişim yolundan söz etmez; "Türkiye'den erişilebilen … kullanıcılar"ı kapsar. Platformun kullanıcının Türkiye'de olduğunu nasıl belirleyeceği metinde yoktur — **varsayılmadı**.
- Bu uygulama yerel bir VpnService kullanır: trafik cihazdan çıkmadan yeniden düzenlenir, IP adresi ve ülke değişmez. Uzak VPN, ülke değiştirme, kimlik/belirteç taklidi bu revizyonda da **dışlanmıştır**.
- Doğrulama kaydı platformun sunucusunda tutulur (belirteç platforma yüklenir, sağlayıcı teyit eder). Yerel DNS/DPI işlemleri bu kaydı **değiştiremez**.
- Mevcut hesaplar için 31/3/2027'ye kadar süre vardır: 1 Kasım'dan sonra bir hesabın açılmaya devam etmesi bu uygulamanın etkisi değil, geçiş süresi olabilir (Hipotez 3).

### 2. Kanıt tablosu

Kanıt: **T** = önce kırmızı düşen JVM testi (5488ab9'da kırmızı, bu sürümde yeşil); **S** = yerel TLS test sunucusu (MockWebServer + kendi test CA'mız); **C** = gerçek cihaz (**yok**).

| Belirti | Yeniden üretim | Dosya / sembol | Kök neden | Düzeltme | Test |
|---|---|---|---|---|---|
| Gözlemcinin bildirdiği yaş/giriş/bot sınıfı kayboluyor | JSON `blockingClass=AGE_OR_ID_VERIFICATION_REQUIRED` | `SocialModels.kt` `Observation.fromJson` | Değer yapıcıya aktarılmıyordu (varsayılan UNKNOWN) | Bilinen sınıflar korunur, bilinmeyen UNKNOWN kalır (`Observation.blockingClass`); WebView yolu da aynı süzgeçten geçer | T `BudgetAndWaitRegressionTest.observationJsonKeepsKnownBlockingClassAndRejectsUnknown` |
| Odak dışı hedef 429 verince arama sürüyor | Başlangıçta / aday altında Instagram `Retry-After` | `ExperimentCoordinator.runRound` | Yalnız odak hedefin `retryAfterSeconds`'ı okunuyordu | Her ölçüm (`measure`) Retry-After'da hedefe özel bekleme yazar; tur `pauseForCooldown` ile durur, geçici aday geri alınır, sonraki profil denenmez; `start` seçili herhangi bir hedef beklemedeyse başlamaz | T `secondaryTargetRetryAfterAtBaselineStopsSearch`, `…UnderCandidateRollsBackAndStops` |
| Kararlılık ölçümündeki bekleme isteği "kararsız" sayılıp sonraki profile geçiliyor | 2. ölçümde 429 | `confirmStable`, `applyAndConfirmBest` | Boolean dönüş: bekleme = başarısızlık | `Stability {STABLE, UNSTABLE, WAIT, BUDGET}`; WAIT turu durdurur | T `stabilityMeasurementRetryAfterIsNotTreatedAsFailure` |
| HEAD→GET iki istek tek sayılıyor | HEAD 405 | `measure` / `NetworkEvidence.requests` | Ölçüm başına sabit +1 | Her istekten önce 2 istek + 256 KiB rezerv; sonra gerçek değer; zaman aşımında rezerv harcanmış sayılır | T `headThenGetCountsBothRequests`; S `HttpPathProbeTest.headNotAllowedFallsBackToSingleRangedGet` |
| Eski içerik onayı yeni bağlamda "Çalışıyor" görünüyor | RESOLVED → bağlam değişimi → yeniden doğrulama | `reverify` (`sameEvidence`) | Yeni ağ PASS'i eski kullanıcı onayını güncel sonuca kopyalıyordu | `TargetResult.contentGeneration` / `contentCurrent`; yeniden doğrulama ve "Seçilileri kontrol et" eski onayı yalnız geçmiş olarak taşır | T `reverifyCannotResurrectPreviousContentConfirmation`, `ReportOutcomeTest.networkCheckCannotRefreshUserReport` |
| Çok hedefte arama başlamadan "bütçe doldu" | Bütün katalog (12 hedef) | `runRound` toplu bayt rezervi (`262 144 × hedef × 2`) + başarısız denemenin 256 KiB sayılması | 24 × 256 KiB = 6 MiB > 5 MiB: ilk adaydan önce durma | Toplu rezerv kaldırıldı; istek başı denetim; başarısız deneme 16 KiB tahmini (`HttpPathProbe.FAILED_ATTEMPT_BYTES`); tavan `24 + 10×(n−1)`, en çok 120 gerçek istek; bütçe aday ortasında biterse aday başarısız sayılmaz, kuyruğa döner | T `allCatalogTargetsFindCommonPlanWithinBudget`, `budgetExhaustedMidCandidateIsNotAFailureAndResumes`; `eightTargetsFindCommonPlanAtLastCandidate` her iki sürümde yeşil (koruma testi) |
| Güvenilmeyen sertifika "SNI/DPI müdahalesi" diye gösteriliyor | İstemcinin güvenmediği CA | `ProbeClassifier.fromException` | `SSLHandshakeException` tek sınıfa düşüyordu | Neden zincirinde `CertificateException`/`CertPathValidatorException` → `TLS_CERT_UNTRUSTED` (giriş portalı/araya giren sayfa/saat) | S `untrustedCertificateIsNotReportedAsDpi` (eski satırla kırmızı doğrulandı) |
| Resmî web denemesi native uygulamada açılabiliyor | A1 / "Hedefi aç" | `AndroidTrialAdapter.apply`, `SocialAccessScreen.openUrl` | Hedefsiz `ACTION_VIEW`: App Links bağlantıyı native uygulamaya verebilir | Niyet seçilen ya da varsayılan **tarayıcı paketine** hedeflenir (`AccessLauncher.openInBrowser`); uygulama düğmesi resmî pakete | Statik (C yok) |

5488ab9 üzerinde yeni regresyon dosyasının sonucu: **9 testten 8'i FAIL** (beklenen), bu sürümde 9/9 PASS.

### 3. Mekanizma hipotezleri (deney kartları)

Gerçek bir doğrulama engeli bu ortamda **gözlenemedi** (cihaz, hesap, Türkiye ağı yok; yönetmelik henüz yürürlükte değil). Bu yüzden üç kartın da saha sonucu **FIELD_UNVERIFIED / NOT_TESTED**'dır; aşağıdaki "sonuç" alanı yalnız ölçüm mantığının durumudur.

**H1 — İstemci/akış farkı (uygulama ↔ resmî web)**
- Gözlenen engel: yok (saha). Hedef işlem: Instagram, kendi hesabınla akış/profil (ACCOUNT_ACCESS).
- Kontrol edilen değişken: yalnız yüzey (resmî uygulama paketi ↔ açıkça seçilen tarayıcı). Değişmeyen: hesap, ağ, motor profili, DNS/QUIC/kapsam, uygulama süreci.
- Mekanizma: platform doğrulamayı yüzeylere farklı zamanda uygularsa, güncel kanıtı olan yüzey işlem bazında seçilir. Destekleyen bulgu: yönetmelik uygulama akışını platforma bırakıyor (Madde 6/3, 12). Çürüten olası bulgu: iki yüzey de aynı hesapta doğrulama istiyorsa (sunucu tarafı hesap durumu) fark yoktur.
- Önce/sonra: aynı işlem, iki yüzey, aynı bağlam; en az **iki ayrı zamanda** (≥10 dk) ve en az biri uygulama yeniden başlatılıp yeni içerikle.
- Sunucunun kabulü: kullanıcı kendi hesabıyla işlemin gerçekten yapıldığını teyit eder ("Kendi hesabımla açıktı"); ekranın kaybolması yetmez. Mesaj/paylaşım testini kullanıcı yapar.
- Yanlış pozitif: açık oturum/önbellek (→ yeniden başlatma şartı), girişsiz içerik (→ ayrı hesap bağlamı), geçiş süresi (→ H3). Yan etki yok; geri alma: kayıt silinebilir.
- Sonuç: **NOT_TESTED (saha)**; kayıt/karar mantığı JVM'de sınandı (`AccessRoutesTest`).

**H2 — Ağ sorununun doğrulama sorunu sanılması**
- Hedef işlem: aynı. Kontrol edilen değişken: ağ yolu (ortak plan araması); değişmeyen: yüzey ve hesap.
- Mekanizma: gerekli bir uç noktaya bağlantı kesildiğinde uygulama doğrulama/yükleme döngüsü gösterebilir. "Doğrulama istiyor" bildirildiği **anda**, o yüzeyin gerçek güzergâhından (paket tünelde ise yerel SOCKS, değilse doğrudan) platformun kontrol adresine tek bir ölçüm yapılır: ağ kapalıysa `NETWORK_PROBLEM_SUSPECTED` (önce ağı düzelt, sonra aynı işlemi tekrar dene), açıksa `SERVER_REQUIREMENT_NETWORK_OPEN`.
- Sınır: tek kontrol adresi platformun bütün API/CDN uç noktalarını kapsamaz; açık ağ yolu "bütün servisler çalışıyor" demek değildir.
- Sonuç: sınıflandırma **JVM'de SUPPORTED (mantık)**, saha **NOT_TESTED**.

**H3 — Oturum/önbellek/geçiş dönemi etkisi**
- Kontrol edilen: kullanıcı beyanı "hesap 1 Kasım 2026'dan önce/sonra açıldı" (hesap kimliği saklanmaz), uygulamanın yeniden başlatılması, ayrı zamanlarda tekrar, süreç kimliği.
- Mekanizma: 1/11/2026–31/3/2027 arasında mevcut hesabın açılması geçiş süresinden olabilir; uygulama bunu "yöntem başarısı" diye yazmaz, notla gösterir. Hesaptan çıkış, oturum temizliği, yeni hesap açma **yapılmaz**.
- Sonuç: **JVM'de SUPPORTED (mantık)**, saha **NOT_TESTED**.

Elenen açıklamalar (deneme yapılmadan, gerekçeyle): DNS/DPI ile doğrulama kaydını değiştirmek (sunucu tarafı; metin belirteci platforma yükletir); doğrulama uç noktasını engellemek / ekranı gizlemek / istemci bayrağı yazmak (sunucu kabulünü kanıtlamaz, ayrıca hizmet şartlarına aykırı olabilir); kullanıcı aracısı veya çerez değiştirmek (gizli istemci değişikliği; yasak); uzak VPN/ülke değiştirme (görev dışı).

### 4. Ürünleşen mekanizma: işlem bazında doğrulanmış erişim yolu

Sosyal Erişim → **Erişim yolu** kartı (odak platform için):
1. İşlev seç: İçerik görüntüleme · Video/medya · Hesabıma erişim · Mesajlaşma · Paylaşım · Sesli görüşme (platformun desteklediği kadarı).
2. **Uygulamada aç** (niyet resmî pakete) ya da **Tarayıcıda aç** (niyet açıkça seçilen tarayıcı paketine; App Links native'e çeviremez). Açılış anındaki bağlam saklanır.
3. Dönüşte: **Çalışıyor / Kısmen / Çalışmıyor / Doğrulama istiyor / Emin değilim**, hesap bağlamı (kendi hesabım / oturum açık değildi / emin değilim), "yeniden başlattım ve yeni içerik denedim".
4. Kayıt, ağ (taşıyıcı + ISS ailesi), profil (motorun stratejisi, plan, DNS, QUIC, kapsam, Vodafone, bağlam kuşağı) ve süreç kimliğine bağlanır; bildirim anında tek ağ ölçümü eklenir. Ağ/profil/süreç değişirse kayıt "yeniden kontrol gerekiyor" olur; başka platform için profil değişse bile (motor ortak) bütün kayıtlar bayatlar.
5. Karar: **Doğrulanmış yol** yalnız aynı bağlamda ≥2 ayrı zamanda (≥10 dk) "Çalışıyor" + en az biri yeniden başlatmalı + hesap işlevinde "kendi hesabımla" teyidiyle. Son bildirim kazanır. Tek onay "tek onay" kalır. Bütün denenen yüzeyler ağ açıkken doğrulama istiyorsa **"Platform doğrulama istiyor"** — yüzey değiştirmek bunu çözmez denir.
6. **Bu yolla aç** düğmesi güncel kanıtı olan yüzeyi tek dokunuşla açar.

Ne DEĞİLDİR: aşma yöntemi değildir; kullanıcı aracısı, çerez, hesap verisi değiştirmez/taşımaz; doğrulama ekranını gizlemez; kullanıcı bildirimi otomatik kanıt gibi kaydedilmez. Tam kullanım için yalnız bu yönlendirme **yeterli değildir** ve bir yüzeyin işe yaradığı sahada gösterilmedi.

Kalıcılık: `noBackupFilesDir/social-access-routes.json` (sürüm 1, en çok 200 kayıt, 30 gün). Bu sürümün okuyamadığı/bozuk dosya **ezilmeden** `social-access-routes.unreadable.json` olarak yedeklenir. Eski arayüz sürümünden gelen bildirim (`UI_VERSION`) ve açıldıktan sonra bağlamı değişmiş bildirim reddedilir. Tanılama çıktısında bağlantı, süreç kimliği, tarayıcı paketi ve ISS adı yoktur (yalnız taşıyıcı türü).

### 5. Destek matrisi (bu revizyon)

"Oto" = kodla ölçülür; "Bildirim" = kullanıcı bildirimiyle, bağlam ve zamanla; "—" = yok. **Hiçbir hücre gerçek cihaz/operatörde doğrulanmadı.**

| Platform | Ağ yolu | İçerik | Medya | Hesap erişimi / doğrulama | Mesaj · Paylaşım | Ses | Erişim yolu kartı |
|---|---|---|---|---|---|---|---|
| Instagram | Oto | Oto (yalnız herkese açık gönderi, test penceresi, doğrudan yol) / Bildirim | Bildirim | Bildirim (+ doğrulama anı ağ ölçümü) | Bildirim | — | Uygulama + web |
| YouTube | Oto | Oto (test penceresi) / Bildirim | Oto (test penceresi) / Bildirim | Bildirim | Paylaşım: Bildirim | — | Uygulama + web |
| X, TikTok, Facebook, Reddit | Oto | Bildirim | Bildirim | Bildirim | Bildirim | — | Uygulama + web |
| Discord | Oto (discord.com; bağlanırken gateway/cdn) | Bildirim | — | Bildirim | Bildirim | Bildirim (QUIC/UDP engeli açıkken "kapalı") | Uygulama + web |
| Telegram, WhatsApp, Signal | Oto (web/servis uç noktası) | — | — | Bildirim | Mesaj: Bildirim | Bildirim | Uygulama + web |
| Twitch | Oto | Bildirim | Bildirim | Bildirim | Paylaşım: Bildirim | — | Uygulama + web |
| Snapchat | Oto | — | — | Bildirim | Bildirim | — | Uygulama + web |
| Özel alan adı | Oto | Bildirim | — | — | — | — | — |

### 6. Tünel yolu kanıt düzeyleri

| Düzey | Bu revizyonda |
|---|---|
| Doğrudan kontrol (uygulamanın kendi süreci, tünel dışı) | Kod + yerel TLS sunucusu (S) |
| Yerel SOCKS (motorun o anki planı) | Kod; JVM'de taklit motor. Gerçek ByeDPI/SOCKS yolu bu ortamda çalıştırılmadı |
| Gerçek TUN üzerinden test istemcisi | **Yok** (ayrı UID'li test istemcisi eklenmedi; mevcut tüneli kapatacak ikinci VPN açılmadı) |
| Kullanıcı tarafından native uygulamada doğrulama | Bildirim altyapısı var; `routePath` paketin bildirim anında tünelde olup olmadığını kaydeder. **Saha yok** |

`routeProvenance` hiçbir yerde sabit "doğrulandı" ile doldurulmaz; WebView gözlemi yine yalnız doğrudan yoldur ve N4 adayına kredi yazamaz.

### 7. Kabul senaryoları → kanıt

| # | Senaryo | Nerede | Durum |
|---|---|---|---|
| 1 | 200/giriş duvarı → yalnız ağ | S `loginWall200IsNetworkPathOnly`; JS gözlemci testleri | PASS |
| 2 | Sunucu yaş/kimlik doğrulaması | T `verificationRequiredAfterAutoNetworkPassDoesNotRotateProfiles`, `verificationRequiredDuringManualTrialStopsSearch`; `AccessRoutesTest.allSurfacesGatedIsReportedHonestly` | PASS (JVM) |
| 3 | Ağ bozuk, aday iyileştiriyor | `SocialRegressionTest.automaticModeEvaluatesNativeCandidateOnItsRealPath`, `MultiTargetTest` | PASS (JVM, taklit motor) |
| 4 | Başlangıçta çalışıyor | `alreadyReachableTargetGivesNoMethodCredit`, `alreadyWorkingTargetGetsNoMethodCreditFromUserReport` | PASS |
| 5 | A'yı bozan aday | `candidateThatBreaksAWorkingAppIsRejectedAndCommonPlanIsKept` | PASS |
| 6 | Kısmi ortak plan | `noCommonPlanKeepsBestPartialOnlyAfterSecondMeasurement` | PASS |
| 7 | Ayar değişimi / ikinci test / soğuk açılış | `keptNativeMethodSurvivesTunnelSettingsChange`, `testingSecondPlatformDoesNotRevertFirstPlatformsKeptMethod`, `SettingsRepositoryTest`, R5 testi | PASS (JVM); Activity yeniden oluşturma cihazda yok |
| 8 | İptal / gecikmiş callback | `cancellationDuringProbeRollsBackAndIsNotACandidateFailure`, eski kuşak testleri | PASS |
| 9 | Wi-Fi/mobil geçişi | `settingsChangeKeepsPlanAndReverifiesOnceWithoutLoop`, `NetworkContextTracker` testleri; `profileNetworkOrProcessChangeMakesRecordsStale` | PASS (JVM); gerçek geçiş yok |
| 10 | SOCKS başarılı, paket kapsam dışı | `TargetStatusRules` OUT_OF_SCOPE testi; Erişim yolu kartı `routePath=DIRECT` + uyarı | PASS (JVM) + statik |
| 11 | WebView doğrudan yol başarılı, aday bozuk | `webViewObservationCannotCreditANativeCandidate` | PASS |
| 12 | 429/5xx, DNS, sertifika, giriş portalı | `HttpPathProbeTest` (9), R2/R3 testleri | PASS (S) |
| 13 | UDP/QUIC değişimi → ses | Arayüz ses satırı; otomatik gözlem yok | Yalnız statik; cihazda yapılmalı |
| 14 | Bozuk ayar / journal | `corruptSettingsFileIsReportedNotSilentlyDefaulted`, `jsonRoundTripAndFutureSchemaIsNotOverwritten`, `malformedOrUnknownValuesAreDroppedNotPromoted` | PASS |
| 15 | IPv4/IPv6, boş include | `emptyOrRemovedIncludeListNeverBecomesAllTraffic` | PASS; IPv6/NAT64 cihazda yok |
| 16 | Tanılama maskeli | `diagnosticExportIsMasked` | PASS |

### 8. Ana hedef değerlendirmesi

**NOT_DEMONSTRATED.** Bu revizyon: (a) ağ ölçümü ve arama döngüsündeki gerçek regresyonları önce kırmızı testle gösterip düzeltti, (b) doğrulama ekranının ağ sorunu mu sunucu şartı mı olduğunu ayıran ve işlem bazında çalışan resmî yüzeyi kaydeden/açan bir deney+ürün altyapısı ekledi. Hiçbir sosyal platformda yaş/kimlik doğrulamasının aşıldığı, hesabınla tam kullanımın sağlandığı ya da bir yüzeyin diğerinden farklı davrandığı **gösterilmedi** (cihaz, hesap ve Türkiye ağı yok; yönetmelik 1 Kasım'da yürürlüğe giriyor). Hesap doğrulama gereksinimi sunucu tarafındadır ve yerel DNS/DPI ile **çözülemez**; uygulama bunu gizlemez, "Platform doğrulama istiyor" olarak gösterir.

### 9. S24 Ultra saha kontrol listesi (kullanıcı tarafından, kendi hesabınla)

Önce: Ayarlar → Telefon hakkında → Yazılım bilgileri'nden One UI/Android sürümünü; Ayarlar → Uygulamalar → Android System WebView sürümünü; Instagram uygulama sürümünü not edin. Parola/kod paylaşmayın.

1. Mobil veri (Vodafone), Wi-Fi kapalı. Bağlanın. Sosyal Erişim → Instagram seçili → **Seçilileri şimdi kontrol et**: satır durumunu not edin.
2. **Erişim yolu** → "Hesabıma erişim" → **Uygulamada aç**. Akışınız kendi hesabınızla yükleniyor mu? Dönün → sonucu + "Kendi hesabımla açıktı" seçin → Kaydet.
3. Aynı işlem → **Tarayıcıda aç** (tarayıcıyı kendiniz seçin). Aynı şekilde kaydedin. Doğrulama ekranı görürseniz "Doğrulama istiyor" seçin; kart ağın o an açık mı kapalı mı olduğunu yazar.
4. Doğrulama + "ağ kapalıydı" çıkarsa: **Ortak yöntem ara** ile planı bulun, sonra 2–3'ü tekrarlayın.
5. 10+ dakika sonra uygulamayı son uygulamalardan kapatıp yeniden açın, **yeni** bir gönderi açın; 2–3'ü "yeniden başlattım" işaretiyle tekrarlayın. Kart "Doğrulanmış yol" demeli ya da çalışmayanı göstermeli.
6. Mesaj gönderme/paylaşımı **kendiniz** deneyin ve "Mesajlaşma"/"Paylaşım" işlevinde ayrıca kaydedin.
7. Wi-Fi'a geçin: kayıtlar "Ağ değişti, yeniden kontrol gerekiyor" olmalı; döngü olmamalı. 2–3'ü Wi-Fi'da tekrarlayın.
8. "Bu hesap ne zaman açıldı?" sorusunu yanıtlayın; 1 Kasım–31 Mart arası notu not edin.
9. Ekranı kilitleyin/arka plana alın, DNS ayarını değiştirin, uygulamayı yeniden başlatın: tercihler ve plan korunmalı, eski sonuç güncel gösterilmemeli.
10. Discord'da sesli kanal ve YouTube'da video ilerlemesini ayrıca kontrol edin.
11. Tanılama → önizle → dosyaya kaydedin. Paylaşmadan önce içinde bağlantı/hesap olmadığını kontrol edin.

Sonuçları tarih, ağ (Vodafone/ev Wi-Fi), uygulama sürümü ile kaydedin; bir ağdaki sonucu bütün Türkiye'ye genellemeyin.

### 10. Doğrulama kaydı — 26 Ekim 2026

Linux bulut konteyneri, OpenJDK 21.0.12, Gradle 8.9 (wrapper), Android SDK 35, build-tools 35.0.0, NDK 26.3.11579264, CMake 3.22.1, Node 22.22. Fiziksel cihaz veya emülatör **yoktu**; S24 Ultra'da test **yapılmadı**, Android/WebView sürümü cihazdan okunamadı.

- Temel (`5488ab9`, değişiklik öncesi): `./gradlew testDebugUnitTest` → 102/102 PASS.
- `BudgetAndWaitRegressionTest` değişiklik öncesi koda karşı: **8/9 FAIL** (beklenen; 8 hedefli koruma testi iki sürümde de yeşil). `HttpPathProbeTest.untrustedCertificateIsNotReportedAsDpi` eski sınıflandırma satırıyla FAIL (`TLS_HANDSHAKE_FAILED`).
- Son durum: `./gradlew testDebugUnitTest lintDebug assembleDebug` → **BUILD SUCCESSFUL**; **140/140 JVM testi PASS**, 0 atlanan (38 yeni: `BudgetAndWaitRegressionTest` 9, `HttpPathProbeTest` 9, `AccessRoutesTest` 15, `ReportOutcomeTest` 5).
- `node --test tests/observer.test.cjs` → **8/8 PASS**.
- `lintDebug`: 2 hata + 25 uyarı. İki hata da bu değişiklikten önce vardı ve dokunulmayan dosyalardadır (`tile/DpiTileService.kt:100` WrongConstant, `ui/apps/AppsScreen.kt:393` ProduceStateDoesNotAssignValue).
- APK: `app/build/outputs/apk/debug/app-debug.apk`, `net.atom.dpibypass.debug` 2.4.1-debug (versionCode 20401), **Android Debug sertifikasıyla** imzalı (`apksigner verify` geçti; resmî/release imzası değildir, mevcut release kurulumunun üzerine kurulamaz — paket adı `.debug` sonekli olduğu için yan yana kurulur, veriler ayrıdır). `lib/arm64-v8a/libbyedpi.so` ve `libhev-socks5-tunnel.so` mevcut (ayrıca armeabi-v7a, x86_64).
- Instrumentation/native/TUN testleri çalıştırılmadı (cihaz yok).

### 11. Bilinen sınırlar (bu revizyonda değişmeyen)

- Motor tekil; uygulama başına ayrı strateji yok (arayüzde de sunulmuyor).
- Erişim yolu kartındaki ağ ölçümü platformun tek kontrol adresidir; bütün API/CDN uç noktalarını kapsamaz.
- `probeHosts` her platform için ana alan adını (Discord'da gateway/cdn ek) içerir; uygulamaların bütün işlevleri için gereken uç noktaların tamamı **doğrulanmadı** (paylaşılan CDN'ler bilinçli olarak eklenmedi).
- StrategyPool'da P2/P7 yakın varyantlardır (TTL 5/6 + `--auto=torst`); bayrakların hepsi sabitlenmiş ByeDPI v0.13 `main.c` seçenek tablosunda mevcuttur, yeni bayrak eklenmedi.
- QUIC kapatma tüneldeki bütün UDP'yi etkiler (ses dahil); hedefe özel QUIC kontrolü mevcut motorla yok ve sessizce uygulanmıyor.
- IPv6 (Vodafone modu dışında) tünel dışında; NAT64/IPv6-only ağ cihazda sınanmadı.
- Gerçek TUN üzerinden ayrı UID'li test istemcisi yok.
- Pil/veri etkisi ölçülmedi; erişim yolu bildirimi başına tek HEAD isteği yapılır, arka planda periyodik iş yoktur.

> 23 Ekim 2026 revizyonu (aşağıda) tarihsel kayıttır. Değişenler: bütçe (artık 24 + 10×(n−1), en çok 120 gerçek istek; istek başı denetim), Retry-After tüm seçili hedeflerde turu durdurur, içerik onayı bağlam kuşağına bağlıdır.

## Revizyon — 23 Ekim 2026 (temel: `5d92f5e`)

### Kök neden tablosu

Kanıt türü: **T** = birim testiyle yeniden üretildi (yeni test `5d92f5e` üzerinde kırmızı, bu sürümde yeşil), **S** = statik kod incelemesi (Android/native yolu cihazda doğrulanmadı), **C** = gerçek cihaz (bu revizyonda **yok**).

| Belirti | Yeniden üretim | Dosya / sembol | Kanıt | Düzeltme | Doğrulama |
|---|---|---|---|---|---|
| Ayar değişince çalışan yöntem kapanıyor | KEEP → `contextChanged()` | `DpiVpnService.reconfigureTunnel` → `SocialAccessRuntime.settingsChanging` → `ExperimentCoordinator.contextChanged` → `stop()` → `rollback()` | T: `SocialRegressionTest.keptNativeMethodSurvivesTunnelSettingsChange` eski kodda `expected 0 but was 1` (geri alma) | Onaylanan aday servis tabanına alınır (`commitAlpha`, `currentBaseArgs`); bağlam değişimi yalnız geçici denemeyi geri alır, planı korur, sonuçları bayatlatıp bir kez yeniden doğrular | T + `MultiTargetTest.settingsChangeKeepsPlanAndReverifiesOnceWithoutLoop` |
| Bir uygulamayı düzeltirken diğeri bozuluyor (1) | A'da KEEP → B için `start()` | `ExperimentCoordinator.start` → `stop()` | T: `testingSecondPlatformDoesNotRevertFirstPlatformsKeptMethod` eski kodda geri alma sayısı 0→1 | `start()` yalnız geçici denemeyi durdurur (`stopTrial`); plan korunur | T |
| Bir uygulamayı düzeltirken diğeri bozuluyor (2) | B için aday A'yı bozuyor | Tek global motor (`applyAlpha`), tek hedefli `TrialState` | S (eski kodda ölçüm yoktu) | Her DPI adayı seçili BÜTÜN hedeflerde adayın yolundan ölçülür; önceden çalışan hedefte gerileme varsa aday reddedilir ve geri alınır | T: `candidateThatBreaksAWorkingAppIsRejectedAndCommonPlanIsKept` |
| Bir uygulamayı düzeltirken diğeri bozuluyor (3) | KEEP sonrası ikinci deneme | `AlphaJournal.stage`: KEPT kaydı silinmiyordu; sonraki `APPLY_INTENT_DURABLE` "Unresolved operation" atardı | S (eski kod bunu her seferinde KEEP'i geri alarak gizliyordu; Android `AtomicFile` birim testinde yok) | KEPT tamamlanmış işlemdir; kayıt silinir | Dolaylı T (çoklu hedef testleri), cihazda doğrulanmadı |
| Otomatik test çalışan yöntemi seçemiyor | Otomatik + N4 adayları | `launchNext()`: `!observerAvailable || !meterAllowed || candidate.strategyId != null` → her N4 adayı ertelenip geri alınıyordu | T: `automaticModeEvaluatesNativeCandidateOnItsRealPath` eski kodda `AUTO_UNAVAILABLE` | N4 adayları adayın gerçek yolundan (yerel SOCKS) ağ düzeyinde değerlendirilir: başlangıç (motorun o anki planı + doğrudan kontrol) → aday → kazanç/gerileme → ikinci ölçümle kararlılık → plan. İçerik için onay ayrı istenir | T |
| WebView sonucu adayın etkisi sanılabilir | — | `ControlledWebProbe` uygulamanın kendi WebView'i; uygulama paketi `applySplitTunneling`'de tünel dışında | S | WebView yalnız A5 (test penceresi yüzeyi) için kullanılır; N4 adayına gelen gözlem `observe()` içinde reddedilir; arayüz "doğrudan yol; DPI profilinin etkisini ölçmez" der | T: `webViewObservationCannotCreditANativeCandidate` (savunma katmanı) |
| Zorunlu hedef DNS'te çözülemiyorken tam başarı | — | `StrategyTester.resolve/verify` paydayı çözülen alt kümeye indiriyordu | S | `totalTargets` zorunlu hedeflerin tamamı; `unresolvedTargets` raporlanır; `fullCoverage` asla true olmaz; kazanan: kapsama → en yavaş hedefin el sıkışması | T: `coverageBeatsSingleFastHost…` |
| Hızlı art arda uygulama seçimi kayboluyor | İki dokunuş, eski anlık görüntü | `AppViewModel.toggleApp` (StateFlow'dan oku-değiştir-yaz) | T: `snapshotReadModifyWriteWasTheLostUpdateMechanism` mekanizmayı gösterir | `SettingsRepository.updateSelectedApps` DataStore `edit` içinde atomik | T: 40 eşzamanlı ekle + 20 çıkar, kayıp yok |
| "Yalnızca seçili" boşken tüm trafik tünelde | Liste boş / paketler kaldırılmış | `applySplitTunneling`: hiç `addAllowedApplication` yoksa Android hepsini alır | S (Android davranışı) | `AppScope.decide` → `EmptyInclude`: tünel kurulmaz, ana ekranda neden yazılır | T: `emptyOrRemovedIncludeListNeverBecomesAllTraffic` |
| Strateji testi ile bağlanma aynı anda native motoru kullanabiliyor | Test sürerken Bağlan | ByeDPI global durumu; `canRunTest` yalnız başlangıçta kontrol | S | `EngineOwnership`: VPN testi iptal ettirir ve bırakmasını bekler; VPN sahipken test başlamaz | T: `connectPreemptsRunningStrategyTestAndWaitsForRelease` |
| Kurtarma gerekirken DNS/QUIC/kapsam ayarı uygulanmıyor | RECOVERY_REQUIRED + ayar değişimi | `reconfigureTunnel` erken `return` | S | Ayar her durumda uygulanır; kurtarma ayrı yürür | S |
| Açılışta varsayılan değer gerçek tercih gibi uygulanıyor | Soğuk açılış | `stateIn(…, Settings())`; `MainActivity` sihirbazı `onboardingDone=false` varsayılanıyla açıyordu | S (Sosyal Erişim için önlem zaten vardı; sihirbaz için yoktu) | `Settings.loaded`; sihirbaz ve Sosyal Erişim ekranı yalnız okunmuş değerle çizilir; okuma hatası `readError` olarak gösterilir | T: `defaultStateFlowValueIsMarkedNotLoaded`, `corruptSettingsFileIsReportedNotSilentlyDefaulted` |
| "Sadece Discord" kullanıcının listesini siliyor | Kısayol | `enableDiscordOnlyMode` üç ayrı yazım, yedek yok | S | Tek atomik yazım + kapsam yedeği + geri yükleme | T: `discordOnlyIsAtomicAndRestorable` |
| "Kaydedildi" kaydetmeden önce gösteriliyor | — | `SocialAccessScreen` `onKeep` | S | Mesaj yalnız kayıt başarılıysa; hata `saveError` ile gösterilir | S |

Ön incelemedeki 7. madde (açılışta `Settings()` varsayılanının denemeyi durdurması) bu sürümden önce `AppViewModel`'de zaten düzeltilmişti; yeniden uygulanmadı. Kalan açık sihirbaz kapısıydı (yukarıda).

### Kavramlar

| Kavram | Nerede | Ömür |
|---|---|---|
| Kullanıcının istediği ayar | DataStore (`SettingsRepository`): açık/kapalı, Manuel/Otomatik, seçili platformlar, bağlantı | Kalıcı |
| Kalıcı profil | Otomatik kipte ağ profili (`saveNetworkProfile`); manuel kipte yalnız kullanıcı "Manuel stratejim de bu olsun" derse | Kalıcı |
| Denenen geçici ayar | `TrialState.resourceDisposition = TRIAL_ACTIVE`, servis `alphaOperation` | Tek deneme; her bağlam değişiminde geri alınır |
| Motora uygulanmış ayar | Servis `currentBaseArgs` (+ Vodafone ekleri) = `PlanState.installed` | Bağlantı süresince |
| Güncel doğrulama sonucu | `TrialState.results` (hedef başına; `stale`), `noBackupFilesDir/social-access-results.json` | Kalıcı ama yeniden açılışta **bayat**; ölçülmeden güncel gösterilmez |

### Çoklu uygulama trafik planı

Native motor tekildir ve kapsamdaki bütün uygulamalara aynı ayarla uygulanır; hedef (SNI/alan adı) bazlı ayrı kural bu sürümde **eklenmedi**: sabitlenen ByeDPI sürümü (`078842b`) ve mevcut mimaride hedefe göre güvenilir ayrım (paylaşılan CDN, ECH, aynı süreçte birden çok servis) doğrulanmadı. Bunun yerine:

1. Seçili hedeflerin hepsi başlangıçta motorun o anki planıyla ölçülür (odak hedef ayrıca doğrudan kontrol yolundan).
2. Her DPI adayı uygulanır ve **bütün** hedefler adayın yolundan ölçülür.
3. Önceden açık bir hedef kapanırsa aday reddedilir ve geri alınır (çakışma hedefin satırında gösterilir).
4. Bütün kapalı hedefleri açan ilk aday ikinci bir ölçümle doğrulanırsa plan olur. Hiçbiri hepsini açmazsa en çok hedefi açan aday yeniden uygulanır, ikinci kez ölçülür ve **kısmi kapsama** diye açıkça işaretlenerek korunur.
5. Plan motorun tabanına yazılır; Otomatik bağlanma kipinde bu ağın profiline de kaydedilir. Bağlanırken yapılan strateji doğrulaması seçili platformların alan adlarını da içerir, yani bir sonraki bağlantıda da hepsine bakılır.

Bir yöntemin bütün uygulamalar için başarısız olduğu durum gizlenmez: hedef satırı "Ağ yolu kapalı" ve ölçülen hata sınıfıyla kalır.

### Doğrulanmış destek matrisi

"Oto" = bu sürümde kodla ölçülür; "Onay" = kullanıcı bildirimi gerekir; "—" = desteklenmez. **Hiçbir hücre gerçek operatör/cihaz ölçümüyle doğrulanmadı.**

| Platform | Ağ yolu | İçerik | Medya | Ses | Native uygulama |
|---|---|---|---|---|---|
| YouTube | Oto | Oto (yalnız belirli herkese açık video, test penceresi, doğrudan yol) / Onay | Oto (test penceresi) / Onay | — | Onay |
| Instagram | Oto | Oto (yalnız belirli herkese açık gönderi, test penceresi) / Onay | Onay | — | Onay |
| X, TikTok, Reddit, Facebook, Twitch | Oto | Onay | Onay | — | Onay |
| Discord | Oto (discord.com; bağlanırken gateway.discord.gg, cdn.discordapp.com da) | Onay | — | Onay (QUIC/UDP düşürme açıksa "kapalı" gösterilir) | Onay |
| Telegram, WhatsApp, Signal | Oto (web istemcisi / servis uç noktası) | — | — | Onay | Onay |
| Snapchat | Oto | — | — | — | Onay |
| Özel alan adı | Oto | Onay | — | — | — |

Telegram, WhatsApp, Signal, Twitch ve Snapchat engellenmiş varsayılarak eklenmedi; kullanıcı kendi ağında sorun görürse aynı ortak plana dahil edebilsin diye vardır. Paket adları resmî Android paketleridir; paylaşılan CDN'ler platforma ait sayılmaz.

### Ölçüm anlamı

- Doğrudan kontrol ve aday ölçümü aynı alttaki ağdan çıkar (uygulamanın kendi süreci tünel dışında; ByeDPI de bu süreçte çalışır) ve oturum boyunca **sabitlenmiş aynı DNS cevabına** gider; fark DNS'ten değil taşıma yolundan gelir. Sistem DNS'i ile şifreli DNS ayrıca karşılaştırılır ("DNS engeli şüphesi" DPI sonucundan ayrı yazılır).
- Doğrulanmış TLS üzerinden gelen her HTTP yanıtı (2xx–4xx, 429 hariç) yalnız **ağ yolu açık** demektir. 5xx belirsiz (kesinti olabilir). `HEAD` 405/501 dönerse tek bir `GET Range: bytes=0-0` yapılır; gövde okunmaz.
- Hata sınıfları: DNS hatası, DNS'in özel adres döndürmesi, TCP reddi/sıfırlama, TLS el sıkışması, sertifika uyuşmazlığı (giriş portalı olabilir), zaman aşımı (tek ölçümle kesin değil), hız sınırı, sunucu hatası, doğrulanmış internet yok.
- `Retry-After` saniye ve HTTP-date kabul eder; bekleme hedef başınadır ve süre dolana kadar o hedefe başka adayla da istek yapılmaz.
- Başlangıçta ağ yolu zaten açıksa DPI profillerine otomatik başarı yazılmaz; profiller manuel incelemeye bırakılır ("sorun içerik/hesap düzeyinde olabilir").
- Otomatik ağ başarısı kullanıcı "Hâlâ açılmıyor" derse yanlış pozitif sayılır: plan geri alınır, aday bu oturumda yeniden başarı sayılmaz, sıradaki adaya geçilir.
- Bağlam değişince (ağ, DNS, QUIC, kapsam, bağlantı yeniden kurulumu) sonuçlar bayatlar; 3 sn sonra bilinen hedefler (en çok 8) bir kez yeniden ölçülür. Aynı bağlam kuşağında ikinci tur yapılmaz, profil döndürülmez.
- Android 12+ üzerinde sistemin seçtiği alttaki ağ (`registerBestMatchingNetworkCallback`) izlenir; eski sürümlerde Ethernet > Wi-Fi > hücresel sıralaması yaklaşık yedektir. Doğrulanmamış ağda/giriş portalında deneme başlamaz.

### Bütçeler

Tur başına istek bütçesi `24 + 8 × (hedef − 1)`, en çok 80 (önceki 24, tek hedef içindi; ek hedeflerin her biri aday başına bir başlangıç + iki ölçüm gerektirir). Uygulanan aday en çok 12, toplam ölçüm süresi 120 sn, istek başına 18 sn taşıma / 20 sn koordinatör sınırı, tanı metadata'sı 5 MiB (istek başı 256 KiB rezerv; gerçek değer yalnız yanıt başlıkları — HEAD gövde okumaz, GET yalnız 1 bayt ister). "Seçilileri kontrol et" en çok 12 hedef, hedef başına 1 istek; yeniden doğrulama en çok 8 istek. Pil/veri/CPU etkisi ölçülmedi.

### Bilinen sınırlar

- Hedef bazlı (SNI) kural yok: plan kapsamdaki bütün uygulamalara uygulanır; bu yüzden gerileme kontrolü yapılır.
- WebView adayın (SOCKS) yolunu kullanmaz; `ProxyController` ile yönlendirme eklenmedi (ek bağımlılık ve cihaz doğrulaması gerektirir).
- Native uygulama içeriği, hesap/giriş/yaş doğrulaması ve sesli görüşme otomatik ölçülmez.
- IPv6: tünel (Vodafone modu dışında) yalnız IPv4 rotası alır; çift yığın ağda uygulamaların IPv6 trafiği tünel dışında kalır, DPI işlemi uygulanmaz. Bu sürüm bunu değiştirmez; IPv6-only/NAT64 ağlarda davranış cihazda doğrulanmadı.
- Süreç ölümünde motor ve geçici deneme yok olur; tamamlanmış plan kimliği ve sonuçlar saklanır ama bayattır. Yarım geçici mutasyon journal'dan güvenle temizlenir; yeniden oynatılmaz.
- Kullanıcının kapatması, VPN izninin geri çekilmesi veya force-stop "kendini onarma" ile tersine çevrilmez.

### Doğrulama kaydı — 23 Ekim 2026

Linux bulut konteyneri, OpenJDK 21.0.12 (Gradle 8.9), Android SDK 35, build-tools 35.0.0, NDK 26.3.11579264, CMake 3.22.1. Fiziksel cihaz veya emülatör **yoktu**.

- `./gradlew testDebugUnitTest`: **102/102 PASS** (önceki 62 + 40 yeni: `SocialRegressionTest` 4, `MultiTargetTest` 15, `PlanAndScopeTest` 14, `SettingsRepositoryTest` 7).
- `SocialRegressionTest` `5d92f5e` üzerinde ayrı bir çalışma ağacında çalıştırıldı: **3/4 FAIL** (beklenen; yukarıdaki tabloda), bu sürümde 4/4 PASS.
- `node --test tests/observer.test.cjs`: **8/8 PASS** (2 yeni: 200 boş kabuk, giriş/onay sayfasına yönlendirme).
- `./gradlew lintDebug`: tamamlandı; 1 hata (`WrongConstant`, `tile/DpiTileService.kt:100`, bu değişiklikten önce de vardı) + 18 uyarı.
- `./gradlew assembleDebug`: **PASS**; `app/build/outputs/apk/debug/app-debug.apk`, Android Debug sertifikasıyla imzalı (`apksigner verify` geçti), ByeDPI ve hev-socks5-tunnel üç ABI için derlendi. Resmî sürüm değildir.
- Testler sahte motor adaptörleriyle orkestrasyonu doğrular; gerçek Android VpnService/native/WebView yolu ve Türkiye'deki gerçek engeller bu testlerin kapsamında değildir.

### Cihaz kabul testi (yapılmadı — kullanıcı tarafından)

Samsung Galaxy S24 Ultra / güncel One UI (sürümü cihazdan okuyun; tahmin edilmedi):

1. Ayarlar → Uygulamalar → DPI Bypass → Pil → **Kısıtlanmamış**. Uyku modundaki uygulamalar listesinde olmadığını kontrol edin.
2. Bağlanın. Sosyal Erişim'i açın, **YouTube + Discord + Instagram**'ı seçin, Otomatik (Beta). "Seçilileri şimdi kontrol et" → her satırın ayrı durumunu not edin.
3. "Ortak yöntem ara". Bitince ana ekranda "Sosyal Erişim planı etkin (Px)" görünmeli. Üç uygulamayı tek tek açın; her satırda **Çalışıyor / Kısmen / Çalışmıyor** bildirin. Discord'da sesli kanala girip ses satırını ayrıca kontrol edin.
4. Ayarlar → DNS'i değiştirin. Plan aynı kalmalı (ana ekran), satırlar "Yeniden doğrulanmalı"ya geçip birkaç saniyede güncellenmeli; döngü olmamalı.
5. Tema ve titreşimi değiştirin: plan ve bağlantı süresi sayacı değişmemeli.
6. Uygulamalar → "Yalnızca seçili", Instagram'ı listeden çıkarın: Sosyal Erişim'de Instagram "Kapsam dışı" + **Kapsama ekle** görünmeli.
7. Wi-Fi ↔ mobil veri geçişi; Wi-Fi açıkken mobil veriyi kapatıp açma. Yalnız gerçek geçişte satırlar bayatlamalı.
8. Uygulamayı son uygulamalardan kaydırıp kapatın, yeniden açın: açık tercihi ve seçili uygulamalar korunmalı; eski sonuçlar "Yeniden doğrulanmalı" görünmeli.
9. Bildirimden bağlantıyı kapatın; Ayarlar → VPN'den izni kaldırın: uygulama "bağlı değil" göstermeli ve kendiliğinden yeniden bağlanmamalı.
10. Tanılama → önizle: URL, hesap, çerez olmamalı; özel alan adı "custom" görünmeli.

Operatör test planı (Vodafone, Turkcell, Türk Telekom, en az iki farklı ev Wi-Fi'ı): her ağda 2–3 ve 7. adımları tekrarlayın; ağ türü/ISS, seçilen plan, hedef başına satır durumu ve tanılama dosyasını kaydedin. **Bu revizyonda hiçbir operatör ağı doğrulanmadı.**


Implementation base: 3032d6c; task specification v3, 14 October 2026; implementation review 17 October 2026. Feature is opt-in and old installs default to MANUAL. Existing normal DPI, DoH and app filter settings are preserved.

## Requirement → integration → verification

| Requirement | Existing integration / change | Verification |
|---|---|---|
| One native engine | DpiVpnService implements AlphaEngine, borrows its existing ByeDPI under the service mutex | Unit sequencing tests; API35 emulator normal VPN/JNI/TUN startup and system revoke smoke PASS; Alpha N4 on-device trial NOT_RUN |
| One coordinator, two modes | ExperimentCoordinator; SettingsRepository independent opt-in fields; dedicated SocialAccessScreen (Settings keeps a one-row SocialAccessEntry) | Shared evaluator vectors, coordinator tests |
| Real network diagnostics | Bounded HEAD request to exact canonical target; direct control or explicit localhost SOCKS on port 1080; no redirects or silent direct fallback | Source review; live transport NOT_RUN |
| Real public content observation | ControlledWebProbe evaluates packaged social-observer.js in app-owned WebView, current canonical content identity and structural signals only | Shared evaluator vectors; actual WebView device/DOM validation NOT_RUN |
| Recovery / rollback | AtomicFile noBackupFilesDir journal; undo arguments synced before native mutation; operation ownership and expected-current comparison | Unit ordering/recovery failure tests; native restart tests NOT_RUN |
| Context and cancellation | NOT_VPN connectivity callbacks; stop/revoke/settings-change call coordinator before service mutation | Unit stale-mode tests; API35 emulator system VPN revoke and service cleanup PASS; physical network transitions NOT_RUN |

## Real scope and route

The existing rootless TUN → hev-socks5-tunnel → local ByeDPI architecture requires Android's VPN permission and system icon. It has no remote VPN exit. N4 changes the running service's desync profile; existing app filters determine affected traffic. It is **not isolated to a single platform, host or tab**. The trial card names this scope. DNS/region/account settings are not changed by Alpha. The normal service watchdog continues using the current owned arguments; its mutex serializes native restarts.

The app package is excluded from its own VPN to prevent loops. The diagnostic HEAD uses explicit local SOCKS for N4 and is labelled THROUGH_CANDIDATE. Ordinary WebView traffic uses its own direct app path. Consequently a WebView result **cannot establish that an N4 change repaired native app or external browser traffic**. N4 is automatically probed, then deferred for manual content review; successful TLS/HTTP is never content/account success. Surface-only A5 observation uses EXACT_TARGET_PATH for the selected WebView target itself and always reports causalAttribution NOT_ESTABLISHED.

The controlled renderer supports anonymous, selected public YouTube video/watch/short links and Instagram p/reel links, subject to recognised current DOM. It does not inspect native applications, import cookies, read login/password fields, collect account IDs or export HTML/body text/screenshots. It uses evaluateJavascript return values; no addJavascriptInterface bridge, debugger port, accessibility service, root, certificate exception, hidden UA, camera, microphone, file chooser or geolocation access. Hostile or unknown layout remains INCONCLUSIVE. Instagram's shell/avatar alone is insufficient. Recognised canonical article/media/time/permalink layout is required. YouTube requires the selected video identity, canonical identity, known player structure and appropriate controls. A recognised gate prevents PASS. The media evaluator requires at least three seconds of natural selected-media progress, rejects ads/previews/seeks, and requires two stable observations separated by at least two seconds. READY_STATE alone does not suffice.

The public renderer temporarily disables sending/accepting cookies for this app's only WebView, without deleting or reading them; it restores the prior policy only if the current value still matches its installed policy. External browser and native application sessions are unaffected. This is anonymous playback testing, not a clean-profile or same-account capability. Main-frame navigation stays on the selected content; subresources are HTTPS-only on a fixed official/media host allowlist. The app does not navigate arbitrary URLs or local/private addresses. The actual renderer uses a five-second positive quiet window to allow delayed gates, within the same 20-second deadline. See [CookieManager policy documentation](https://developer.android.com/reference/android/webkit/CookieManager#setAcceptCookie(boolean)); verified 17 October 2026.

Automatic requests stop on success, uncertainty consumes no false failure credit, and a user resolution overrides technical uncertainty. Mode/document/context/operation generations reject late observations; feedback also uses state version and single-use event ID. Unknown enum or evidence never authorises PASS. The user can correct an automatic result and continue manually; the local target/goal/observer-version policy is durably quarantined and suppresses automatic PASS until the observer version changes. There is no automatic account login, CAPTCHA solving, age submission, installation or account action.

A0 runs a real bounded DIRECT_CONTROL target HEAD diagnostic even when the native service is unavailable. This cannot determine content or account status. The current content baseline is the user's declaration for the original native/account surface; the controlled anonymous window is an explicitly allowed alternative and does not silently replace that original baseline. When the original surface cannot be observed, baseline remains UNKNOWN and causal claims are withheld. The controlled renderer can establish a narrow alternate-surface result, not universal platform success.

## Candidate assessment

All families were reviewed against existing Android runtime and the constraints. IMPLEMENTED means an executable real handler, not an assertion of live bypass success. MANUAL_GUIDED is guidance only. No excluded or research-only family enters the executable catalog.

| Family | Status | Mechanism, scope, reason / evidence |
|---|---|---|
| A0 current state | IMPLEMENTED | User-bound original target baseline; “already accessible” applies zero candidates and earns no method credit. Native screen cannot be independently observed. |
| A1 official web | IMPLEMENTED | Canonical official HTTPS ACTION_VIEW while preserving existing app/browser sessions. Destination app is not independently verified: manual confirmation only. Browser closure is not owned; rollback releases Alpha resources only. Sources Android WebView/VPN docs below. |
| A2 official presentation | MANUAL_GUIDED | User may select a site's supported official presentation in the browser. No UA spoof or claim of independent backend; not an executable automatic candidate. |
| A3 clean context | EXCLUDED | WebView is separate from native/browser sessions but Android WebView shares app-scoped cookie/storage state. No certified temporary browser profile capability; do not erase user cookies or label it a clean profile. |
| A4 alternative official interface | RESEARCH_ONLY | No currently verified independent official YouTube/Instagram interface. Historical Reddit anecdotes do not transfer. |
| A5 public reading/playback | IMPLEMENTED | Controlled public WebView with packaged structural/media observer; exact selected target and surface only. No anonymous result becomes same-account success. Sources WebView, sandbox and media docs. |
| A6 local client/extractor | RESEARCH_ONLY | NewPipe and yt-dlp are maintained upstream projects, but this app has no pinned compatible client/intent/extractor integration or verified all-direct fallback path. No APK/download/install/token transfer. PO Token is not age proof. |
| N1 DNS/DoH | MANUAL_GUIDED | Existing SettingsRepository/DnsPlan/DohResolver normal settings remain available. Alpha does not claim a safe target-isolated DNS override; changing normal DNS triggers trial context invalidation. |
| N2 address family | RESEARCH_ONLY | Current service is primarily IPv4; Vodafone mode has a specific IPv6 capture policy. No honest per-target dual-stack/DNS64/NAT64 comparison is available, so no global IPv6 toggle. |
| N3 QUIC/TCP | EXCLUDED | Existing disableQuic affects the whole configured tunnel and also DNS/voice. No verified target/client-scoped H3 comparison. Do not multiply this global switch into Alpha trials. |
| N4 existing DPI profiles | IMPLEMENTED | Seven current StrategyPool profiles, fixed compiled arguments, one service engine, no-op effect excluded, journal and undo. Profiles are tried only for network/media/unknown symptoms, not repeatedly for known server account gates. Sources local StrategyPool/ByeDPI, pinned native subtree and upstream ByeDPI. |
| N5 ECH | RESEARCH_ONLY | RFC 9849 describes real negotiated ECH. The current ByeDPI/WebView adapter cannot attest client/DNS/server negotiation; GREASE cannot be labelled ECH success. |
| N6 media dependencies | RESEARCH_ONLY | Media progress is observed, but no independently verified target-isolated media-CDN strategy exists. N4 may affect existing captured traffic; no random CDN/IP forwarding or host impersonation. |

Remote VPN/proxies, Tor, WARP, remote Invidious/Redlib, Control D traffic redirection, old proxy-dependent bypass scripts, other users' accounts/tokens, country/SIM/GPS/region changes, fake identity/age evidence, downgrades and TLS bypasses are excluded. Hiding an overlay or disabling JavaScript does not establish content access.

## Budgets, resources and lifecycle

Each group admits at most 12 distinct effects, 24 diagnostic HEAD calls, 5 MiB diagnostic metadata and 120 seconds cumulative probe time. Each request has an 18-second transport deadline and a 20-second coordinator deadline, no retry or redirect. HEAD does not consume response bodies. Renderer loads are counted separately, at most six, with at most 18 one-second evidence samples per document. Renderer transfer bytes are **ESTIMATED**, not a guaranteed 20 MiB limit. Metered automatic rendering is off without the one-session data preference. User traffic is never stopped at diagnostic byte limits. Manual inactivity uses one suspended 15-minute deadline, no repeated probes; moving to another app preserves manual waiting. HTTP 429/503 triggers rollback/cooldown rather than hammering the next profile. Success stops probing and observation; a kept public WebView remains visible until explicitly stopped/closed.

Atomic journal stages are APPLY_INTENT_DURABLE, APPLIED, KEEP_INTENT_DURABLE/ROLLBACK_INTENT_DURABLE, KEPT/ROLLED_BACK. The VPN service adds previous and expected installed argument arrays durably before mutation. Rollback verifies operation ownership and expected current arguments, restores the previous existing engine and removes a completed record. External edits or failed rollback yield RECOVERY_REQUIRED and block the next apply. No global DNS reset, firewall flush, browser close or other VPN shutdown is used. Native engine/listener and WebView are app-process resources: after a full process death they cannot survive. On process reinitialisation a valid old record is reconciled as gone and removed, never replayed; corrupt/unknown recovery records block Alpha. Normal VPN startup selects its normal settings, not a replayed Alpha profile. Journal is excluded from backup and exports, contains no target URL or account data, and resides in the Android app sandbox.

A separate app-private bounded diagnostic history retains non-sensitive attempt summaries for 14 days (at most 200 records), pruned on the next event; no background timer or telemetry runs. Current counters are durably snapshotted separately from recovery. False-positive quarantine uses an app-private HMAC of canonical target + goal + observer version; the key and digest never enter reports. Application restart never automatically resumes searching or replays success. Known limitations still requiring review/device validation: historical groups are review-only and a newly started session requires a fresh target/baseline. Timed renderer unload/navigation behaviour, service crash mid-apply, OEM process death, multiple simultaneous underlying networks, VPN lockdown, native singleton joins and native rollback health must be device-tested. A process alive with an unexpectedly destroyed VPN service needs explicit app recovery rather than guessing another engine's ownership. No signed remote catalog, clean browser profile, native account observer or second renderer is bundled.

## User guide

Open Settings → Sosyal Erişim Deneyleri (its own page) and enable it. Select Manuel or Otomatik (Beta), platform, symptom and optionally the official content HTTPS link (pasting a share link or share text selects the platform automatically; an empty field tests the platform home page in manual mode). Same-account/native is the default. Opt into official web and/or the separate anonymous test window only if those surfaces satisfy your goal. For network profiles connect normal DPI first using the existing VPN permission flow. Start performs the selected reversible group. In manual mode report Hâlâ var, Yok açıldı, Test edemedim or partial access. In automatic mode a recognised current target may be observed; missing capability remains uncertain and the manual button is available. Durdur / geri al removes Alpha's owned changes; normal protection remains. Turning Alpha off does not disable ordinary DPI. After a real network change start again with a fresh baseline. A result for one public video/post is not success for an account, feed, messages or another application.

## Revision — 22 October 2026

Performance: the frosted-glass modifier (`ui/design/Glass.kt`) now records and blurs only the part of a surface that intersects its sampled backdrop (the screen). Previously every glass card re-blurred its full height every frame, so the single, very tall Alpha card at the top of Settings caused visible jank even on flagship devices. The feature moved to a dedicated `social` route split into short cards; the WebView test window no longer lives inside the Settings scroll. Diagnostic file reads and the observer script load moved off the main thread; the HMAC key is created lazily.

Fixes: enable/disable is synchronised from the persisted DataStore value in `AppViewModel` (the Settings StateFlow's default could previously call `enabled(false)` and stop a running trial); `enabled()` and `manual()` are idempotent while idle; `contextChanged()` (network or tunnel-settings change) is a no-op without an active trial or owned resource, so a finished result is not overwritten. Network context is tracked by `NetworkContextTracker`: only a change of the primary underlying network (Ethernet > Wi-Fi > cellular), debounced two seconds, pauses a trial — an always-on cellular network appearing beside Wi-Fi no longer does. `retryRecovery()` gives RECOVERY_REQUIRED an exit: it retries the owned rollback, or clears a stale/corrupt journal when no operation is held in this process. N4 ordering follows the detected ISP family. HEAD 3xx counts as a reachable network path. User-visible messages no longer show internal reason codes (`SocialText`).

Platforms: X/Twitter, TikTok, Discord, Reddit and Facebook join YouTube and Instagram for manual trials (network diagnostic + N4 profiles + official web). Their links are host-allowlisted, path-whitelisted and stripped of query/fragment except platform-required keys; the controlled renderer and automatic observation remain YouTube/Instagram only. YouTube additionally accepts /live/, /embed/ and music.youtube.com; Instagram accepts /reels/, /tv/ and username-prefixed links. A kept N4 profile can be saved as this network's default ("Bu ağ için varsayılan yap"), reusing the strategy-test persistence path; it does not restart the tunnel.

Verification: `./gradlew testDebugUnitTest` 62/62 PASS (9 new tests), `lintDebug` PASS, `node --test tests/observer.test.cjs` 6/6 PASS. Device scrolling performance and physical network transitions remain to be checked on hardware.

## Source register

Checked 17 October 2026, documentation claims only; no live platform bypass measurement. Publication/update dates are not assumed when pages do not supply them. Local implementation is tied to base commit 3032d6c and the existing native subtree revisions, not current upstream HEAD.

| Primary source | Scope and counterexample |
|---|---|
| [Android VPN](https://developer.android.com/develop/connectivity/vpn) | System permission/icon, one VPN and app filters. A local VpnService does not imply remote exit; nor does control-socket success prove target app access. |
| [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) | Event callbacks and network capabilities. VALIDATED does not prove a selected platform works. |
| [Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview) | Owned embedded web rendering/evaluation. Embedded playback/login can be unsupported; a WebView is not the native application's session. |
| [WebView native bridges](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges) | Broad frame-accessible bridges carry origin risks. This implementation uses no such interface. |
| [Android app sandbox](https://source.android.com/docs/security/app-sandbox) | Isolation explains native-account observation limit; not permission to import private app data. |
| [ByeDPI upstream](https://github.com/hufrea/byedpi) | Local SOCKS/desync mechanism. Cannot change platform account authorisation or guarantee access. |
| [NewPipe](https://github.com/TeamNewPipe/NewPipe) | Alternative local client exists; no verified installed compatible integration is bundled here. |
| [yt-dlp extractors](https://github.com/yt-dlp/yt-dlp/wiki/Extractors), [PO Token](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) | Extractor/session restrictions; PO Token is origin context, not age proof. |
| [ECH RFC 9849](https://datatracker.ietf.org/doc/html/rfc9849) | Negotiated encrypted ClientHello requirements; random/GREASE ECH is not proof. |
| [Control D](https://docs.controld.com/docs/getting-started) | Traffic redirection differs from resolver-only DNS and can introduce remote egress, excluded here. |
| [Media readyState](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/readyState), [play](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/play) | Ready/play does not attest natural selected-video progress; user gesture may be required. |

## Four separate delivery axes

| Axis | Status |
|---|---|
| IMPLEMENTATION_STATUS | Alpha code integrated; native/device execution and some lifecycle/history limitations remain as above. Not a universal bypass completion claim. |
| AUTOMATED_TEST_STATUS | PASS: full Android debug build and all 53 JUnit tests, including 18 Alpha tests, 32 shared evaluator cases and 8 shared event sequences; 6 observer fixtures. Both native engines compiled for all three configured ABIs. |
| DEVICE_TEST_STATUS | EMULATOR_SMOKE_PASS: API35 x86_64/WHPX launch, Alpha defaults/mode persistence, real VPN permission/start/revoke/cleanup. Physical/OEM/metered and controlled WebView live-device behaviour remain NOT_RUN. |
| FIELD_EFFECT_STATUS | FIELD_UNVERIFIED: no live user platform/account/ISP target measurement. Fixture PASS is engineering evidence only. |

### Verification record — 18 October 2026 (final source)

- `tests/run-social-core.ps1`: exit 0; portable Java 17.0.20.1, Kotlin compiler 2.0.21, kotlinx-coroutines 1.8.1, JUnit 4.13.2; Kotlin core compilation and **18 JUnit tests passed**. One test runs all **32 identical shared JSON evaluator vectors**, including account/surface mismatch, gate, schema/selector drift, cache, hidden document, ad, preview, seek, malformed evidence and autoplay interaction. The same **8 language-independent event sequences** as Windows/Linux assert phases, finish reasons, owned resources and next candidate. Other tests exercise disabled default, baseline, manual resolution/replay, failure→rollback→next, auto FAIL→rollback→PASS, failed rollback, stale mode/document, inactivity, budget continuation, user resolution during AUTO_EVALUATING, mode change after success, persisted cooldown and public destination policy. Negative/uncertain vectors produced zero false PASS.
- `node --test tests/observer.test.cjs`: **6 tests passed** against the actual packaged JavaScript detector using controlled DOM/media fixtures, including recognised YouTube/Instagram layouts and gate/drift/cache/visibility/ad/seek negatives. This does not assert a current live site's DOM is supported.
- SDK-less Kotlin PSI grammar check: **51 production Kotlin files parsed with no syntax errors**; this is grammar-only, not Android API/type checking. The checker normalises Windows CRLF before parsing.
- `git diff --check --ignore-submodules`: exit 0; only Git line-ending notices. Normal `git status` is blocked by existing submodule metadata permissions; `--ignore-submodules` works. No submodule content changed.
- Full `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon`: **PASS, exit 0**, `BUILD SUCCESSFUL in 2m 58s`. All **53 JUnit tests passed**, with zero failures, errors or skips: 18 Alpha tests and 35 existing DNS, strategy, usage and Vodafone policy tests. Production Kotlin, Compose and Android API type checking completed. ByeDPI CMake and hev-socks5-tunnel ndk-build compiled and linked for **arm64-v8a, armeabi-v7a and x86_64**. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`; unit report: `app/build/reports/tests/testDebugUnitTest/index.html`; full log: ignored `.local-tools/android-build.log`. `apksigner verify --verbose` exited 0 and verified its development v2 signature. This is a debug test artifact, not an official release or a device/field result.
- The build used portable Microsoft JDK 17.0.18+8, pinned Gradle 8.9, SDK platform 35, Android build tools 34.0.0 (AGP default), NDK 26.3.11579264 and CMake 3.22.1 in ignored `.local-tools/`. Verification also installed build tools 35.0.0 for APK inspection. Process-local `JAVA_HOME`, `GRADLE_USER_HOME`, `ANDROID_HOME` and `ANDROID_USER_HOME` selected the tooling. Java DNS resolution needed a task-local `-Djdk.net.hosts.file` populated from the official download hosts' current HTTPS addresses; TLS verification stayed enabled and system DNS was unchanged. The later APK installation used only the disposable emulator described below.
- Development-only portable runtime/compiler/test libraries were downloaded into ignored `.local-tools/`; no runtime, compiler, APK or new client is bundled in source. Large compiler downloads reset repeatedly; range assembly passed full ZIP CRC integrity before compilation.

Self-review fixed a Kotlin `Target`/annotation wildcard-import collision found by real compilation, a test-runner output JAR accidentally included in its own classpath, disabled-state authority races, stale renderer disposal after resumed document generations, false manual exhaustion labels for permission-deferred candidates, and deferred-manual continuation reapplying unrelated failed candidates. Full Android compilation then exposed and fixed an OkHttp 4 `Dns` interface mismatch: the adapter now implements `lookup` explicitly while retaining public-address filtering. The native build also exposed Windows Git symlink placeholders in pinned upstream headers. Gradle stages JNI sources under `app/build/intermediates/nativeSources`, validates in-tree targets and generates relative include forwarders only there, leaving submodule content and pins untouched. The corrected full build and all 53 tests passed. Device review is still required before distribution.

Final integration also rejects malformed observation scalars/unknown evidence versions, makes media-path goals require actual media progress, and keeps possible throttling INCONCLUSIVE without a benchmark. User resolution cancels automatic observation; changing mode after an accepted result does not restart searching. Cooldown survives restart and is separate from clearable diagnostic history. The controlled observation response is capped at 16 KiB. HEAD diagnostics use checked public numeric DNS answers; the existing local SOCKS path receives those numeric destinations while normal URL-host TLS/SNI verification remains enabled. The Android-dependent adapter and service additions passed the full Android API/type compilation described above; device transport and lifecycle validation remain separate.

Pinned native subtrees were not changed: ByeDPI `078842b084853bc30f33eaaec7acc510cf67e560`, hev-socks5-tunnel `677bb4530cfc867cd44d88d298960c8d8d9fbfad`. No physical device, user account, production browser profile or host system DNS/VPN setting was changed.

### Actual emulator smoke — 18 October 2026

Official emulator 37.2.12, Google APIs API35 x86_64 image, isolated AVD/ADB port5038, existing WHPX accelerator (no host driver/feature installation). Wi-Fi and mobile data were disabled before launching the app. The debug APK installed and opened. Initial Alpha off/MANUAL was observed; AUTO_BETA selection survived force-stop/restart, MANUAL could be selected again and Alpha could be disabled.

The actual Android VPN permission dialog was accepted in this isolated emulator. Both compiled native engines/TUN service started; UI reported protection running and dumpsys showed foreground DpiVpnService. Android Settings → VPN → Forget VPN revoked permission, after which the app returned to protection stopped, no active app service remained and the app crash buffer was empty. Assertions over saved XML/service/crash evidence passed; `.local-tools/emulator-evidence/summary.json` records them. This validates local lifecycle, not content access, a physical/OEM network or throughput. No account or external social target request was used. WebView field behaviour, metered/lockdown/OEM conditions and actual user ISP/identity gates remain separate field gates.
