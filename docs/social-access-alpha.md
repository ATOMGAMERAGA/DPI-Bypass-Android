# Sosyal Erişim Deneyleri — Alpha (Android)

> **Güncel davranış: 23 Ekim 2026 revizyonu aşağıdadır.** Daha eski bölümler tarihsel kayıttır; çeliştikleri yerde bu revizyon geçerlidir (özellikle: N4 profilleri artık otomatik kipte ağ yolu üzerinden değerlendirilir; onaylanan profil ayar/ağ değişikliğinde ve başka hedefin denemesinde geri alınmaz).

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
