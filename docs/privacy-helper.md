# Gizlilik Yardımcısı — kaynaklar ve doğrulama kaydı

**Kontrol tarihi:** 4 Kasım 2026 (tüm satırlar). **Yer:** Ayarlar → Gizlilik → **Gizlilik Yardımcısı**.

Yardımcı yalnızca okunur, yerel bir rehberdir. VPN/proxy kullanmaz, ağ isteği yapmaz, DPI tüneline dokunmaz, hiçbir alan girdi almaz; kimlik bilgisi, şifre, yaş belirteci ya da oturum bilgisi toplamaz, kaydetmez, loglamaz. Platformların yaş ve erişim kontrollerini atlatmaya yönelik yol göstermez. Tek dış eylem, izinli resmî bir adresi `ACTION_VIEW` ile açmaktır.

Kanıt düzeyleri: **Doğrulandı** = resmî sayfa bu ortamda açılıp okundu · **Kısmen** = resmî kaynak var ama sayfa tam okunamadı/oturum gerektiriyor · **Doğrulanamadı** = kanıt bulunamadı.

## 1. Yönetmelik ne istiyor? (Resmî Gazete 10.10.2026, Sayı 33396)

Kaynak: [Sosyal Ağ Sağlayıcı Tarafından Çocuklara Özgü Ayrıştırılmış Hizmet Sunulmasına ve Yaş Doğrulamaya İlişkin Yönetmelik](https://www.resmigazete.gov.tr/eskiler/2026/10/20261010-15.pdf) — 6 sayfanın tamamı okundu.

| Konu | Hüküm |
|---|---|
| Yaş sınırı | 15 yaşını doldurmamışa hizmet yok; 15–18 için ayrıştırılmış hizmet (Md. 5, 8) |
| Veri ilkesi | Kimlik tespiti yerine yalnız yaş eşiği (Md. 5/3) |
| Yeni hesap | Hesap oluşturmada belirteçle doğrulama zorunlu (Md. 6/2) |
| Sonraki kontroller | Platform kendi tekli/katmanlı yöntemlerini işletir; makul şüphede askı + yeniden belirteç (Md. 6/3) |
| Belirteç | e-Devlet Kapısı'nda Başkanlık hizmetiyle üretilir; yalnız ≥15 ve ≥18 bilgisini içerir; ad, kullanıcı adı ya da belirleyici veri içermez; kullanıcı platforma yükler; platform Başkanlık'a teyit ettirir, başka amaçla kullanamaz (Md. 7/1-a…ç) |
| Geçici bulunan yabancılar | Muaf; başvururlarsa yalnız pasaport bilgisiyle istisnai belirteç (Md. 7/1-d) |
| Web / uygulama | Ayrım yok; ayrıştırılmış hizmet kuralları uygulama, internet sitesi ve tüm arayüzlerde (Md. 8/5-ğ) |
| İtiraz | Ücretsiz, Türkçe, insan incelemeli; itiraz sonuçlanana kadar veri silinmez; Başkanlık'a şikâyet ve yargı yolu (Md. 10) |
| Teknik rehber | Başkanlık hazırlar, yalnız platformlarla paylaşır (Md. 12) — devlet tarafında hangi kaydın tutulduğu **yazmıyor** |
| Mevcut hesap | 31.03.2027'ye kadar en az bir kez belirteçle doğrulama (Geçici Md. 1) |
| Yürürlük | 01.11.2026 (Md. 13) |

**Fiilî durum (resmî kanallar):**

| Kanal | Bulgu | Kaynak |
|---|---|---|
| e-Devlet | "yaş doğrulama", "yaş belirteci", "belirteç" aramalarında belirteç hizmeti **listelenmiyor** | [turkiye.gov.tr arama](https://www.turkiye.gov.tr/arama?aranan=ya%C5%9F+do%C4%9Frulama) |
| Siber Güvenlik Başkanlığı | Haberler/duyurularda yaş doğrulamaya ilişkin duyuru **yok** (son duyuru 21.10.2026, PHP açığı) | [siberguvenlik.gov.tr](https://siberguvenlik.gov.tr/) |
| Basın (ikincil, uygulamada link verilmez) | 1–3 Kasım haberleri hizmetin doğrudan bağlantısının ve ekran adımlarının bağımsız doğrulanamadığını yazıyor | [Fotomaç, 01/03.11.2026](https://www.fotomac.com.tr/haber-ozel/2026/11/02/sosyal-medya-yas-dogrulamasi-nasil-yapilir-e-devlet-ekrani-nereden-acilir) |

## 2. Platform × hesap × yüzey

Tüm platformlarda "yönetmeliğin istediği" aynıdır (yukarıdaki tablo). Aşağıdaki "platformda fiilen" sütunu platformun **kendi** resmî sayfalarına dayanır.

| Platform | Yeni · Web | Yeni · Uygulama | Mevcut · Web | Mevcut · Uygulama | Kaynak |
|---|---|---|---|---|---|
| Instagram | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | [Yaşınızı onaylama](https://help.instagram.com/966909308115586) (genel 13 yaş + Yoti; Türkiye/e-Devlet yok) |
| TikTok | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | [Reşit olmama itirazları](https://www.tiktok.com/support/faq_detail?id=7611808266502560268) (genel 13 yaş; Türkiye yok) |
| YouTube | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | [Google yaş şartları](https://support.google.com/accounts/answer/1350409?hl=tr) (Türkiye ayrı listelenmiyor → 13), [Yaş tahmini](https://support.google.com/youtube/answer/16422785?hl=tr) (ülke listesinde Türkiye yok) |
| Facebook | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | [Yaşınızı onaylama](https://www.facebook.com/help/958848942357089) (genel 13 yaş + Yoti; Türkiye yok) |
| X | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | Doğrulanamadı | [Age Assurance](https://help.x.com/en/rules-and-policies/age-assurance) — bu ortamdan HTTP 403, yalnız arama özeti okundu |

Mevcut hesaplar için yasal son tarih (31.03.2027) henüz gelmedi. Web ile uygulama için ayrı bir Türkiye adımı hiçbir platformda belgelenmemiş. YouTube Türkiye'nin 15 yaş altı hesapları kapatmak zorunda kalabileceğine dair 12.10.2026 tarihli açıklaması yalnız basından ([haberler.com](https://www.haberler.com/teknoloji/15-yas-alti-sosyal-medya-duzenlemesi-1-kasim-da-20320253-haberi/)) görüldü; YouTube'un kendi sayfasında bulunamadı.

## 3. Doğrulamada veri kime gider?

**Belirteç yolu (yönetmelik, her platformda aynı):** Kullanıcı → e-Devlet (kimlik doğrulaması) → belirteç (yalnız 15/18 eşiği) → kullanıcı platforma yükler → platform Başkanlık'a teyit ettirir. Devlet tarafındaki kayıt/saklama: **Doğrulanamadı**.

**Platformların kendi kontrolleri (Md. 6/3'e göre isteyebilirler; itirazlarda kullanılır):**

| Platform | Yöntem → alıcı | Saklama | Kanıt |
|---|---|---|---|
| Instagram | Selfie videosu ya da kimlik → Yoti; profilde gösterilmez | Instagram sayfasında süre yok (Meta'nın Facebook sayfaları: selfie görseli ve kimlik 30 gün içinde silinir) | Doğrulandı / süre için Kısmen — [IG](https://help.instagram.com/966909308115586), [kimlik](https://help.instagram.com/293775921768331) |
| Facebook | Selfie'den bir görsel → yaş tahmini tedarikçisi (kişiyi tanımlamaz); kimlik → Yoti | Selfie görseli tahminden sonra 30 gün içinde, kimlik 30 gün içinde silinir; kimlik no gibi gereksiz bilgi fiziksel olarak kapatılabilir | Doğrulandı — [selfie](https://www.facebook.com/help/1386337538619854), [kimlik](https://www.facebook.com/help/671565971895323), [kimlik türleri](https://www.facebook.com/help/159096464162185) |
| TikTok | Yüz tahmini → sağlayıcı (yalnız tahmini gönderir, özçekimi siler); kimlik+özçekim → Incode; kimlik+3 özçekim; kredi kartı (18+) | Incode sen silene kadar saklar; itiraz bitince TikTok silme sürecini başlatır, itiraz kaydı ve doğum tarihi kalır | Doğrulandı — [TikTok](https://www.tiktok.com/support/faq_detail?id=7611808266502560268) |
| YouTube | Yaş tahmini modeli (Türkiye listede yok); Google Hesabı'nda kimlik, kredi kartı ya da selfie | Kimlik doğrulamadan sonra silinir; kart verisi yasal süre boyunca | Doğrulandı — [tahmin](https://support.google.com/youtube/answer/16422785?hl=tr), [doğrulama](https://support.google.com/accounts/answer/10071085?hl=tr) |
| X | Sinyaller → canlı selfie tahmini ya da kimlik | 30 güne kadar | Kısmen — [X](https://help.x.com/en/rules-and-policies/age-assurance) (403) |

**e-Devlet gerektirmeyen resmî seçenek:** Türkiye belirteç adımının yerine geçtiği platformca belgelenmiş bir seçenek **hiçbir platformda bulunamadı**; uygulama bu yüzden göstermiyor. Yukarıdaki yöntemler platformların kendi yaş kontrolleri ve itirazları içindir. Yönetmelikteki tek e-Devlet kimlik doğrulamasız yol, Türkiye'de mukim olmayan geçici kullanıcılar için pasaportla istisnai belirteçtir (Md. 7/1-d) ve o da e-Devlet Kapısı üzerinden yürür.

## 4. Yaş yanlış tespit edilirse

- **Yönetmelik:** Platform ücretsiz, Türkçe, insan incelemeli itiraz yolu kurar; itiraz sürerken veri silinmez; sonuca karşı Başkanlık'a şikâyet ve yargı (Md. 10). Platformların Md. 10 kanalı: **beşinde de Doğrulanamadı**.
- **TikTok:** uygulamadaki itiraz bildirimi → İtiraz; 113 gün (silme 120. gün). Doğrulandı.
- **Instagram / Facebook:** yaş onayı ekranındaki Yoti seçenekleri; doğum günü ayarlardan gözden geçirilir. Doğrulandı.
- **YouTube:** Google Hesabı doğrulaması (kimlik/kart/selfie); doğum günü: Google Hesabı → Kişisel bilgiler → Doğum günü ([kaynak](https://support.google.com/youtube/answer/3159776?hl=tr)); yaş şartı karşılanmazsa 14 gün süre. Doğrulandı.
- **X:** Kısmen (sayfa okunamadı).

## 5. Gizlilik ayarı adımları

| Platform | Web (izinli resmî adres) | Uygulama (elle) | Kanıt |
|---|---|---|---|
| Instagram | `www.instagram.com/accounts/privacy_and_security/` | Profil → ☰ → Ayarlar → İçeriklerini kimler görebilir? → Hesap gizliliği → Gizli hesap | Kısmen ([yardım](https://help.instagram.com/448523408565555), adres girişe yönlendiriyor) |
| Facebook | `www.facebook.com/privacy/checkup/` | Profil resmi → Ayarlar ve gizlilik → Gizlilik Ayarı Kontrolü | Kısmen ([yardım](https://www.facebook.com/help/443357099140264)) |
| TikTok | — (güvenilir adres doğrulanamadı) | Profil → ☰ → Ayarlar ve gizlilik → Gizlilik → Gizli hesap | Kısmen ([destek](https://support.tiktok.com/en/account-and-privacy/account-privacy-settings/making-your-account-public-or-private)) |
| YouTube | `myaccount.google.com/personal-info` | Google Hesabı → Kişisel bilgiler → Doğum günü; YouTube: profil → "YouTube'daki verileriniz" | Kısmen ([doğum günü](https://support.google.com/youtube/answer/3159776?hl=tr), [gizlilik](https://support.google.com/youtube/answer/10364219?hl=tr)) |
| X | `x.com/settings/audience_and_tagging` | Ayarlar ve gizlilik → Gizlilik ve güvenlik → Hedef kitle ve etiketleme → Gönderilerini koru | Kısmen ([yardım](https://help.x.com/en/safety-and-security/how-to-make-x-private-and-public), 403) |

Mobil uygulamaların ilgili ayarına güvenilir, belgelenmiş bir doğrudan bağlantı bulunamadığı için uygulama içi adımlar elle verilir.

## 6. Uygulama yapısı

| Dosya | Görev |
|---|---|
| `privacy/PrivacyCatalog.kt` | Kaynaklı veri: her iddia = metin + kanıt düzeyi + kaynak(lar) + kontrol tarihi; satırlarda yönetmelik ve gözlem ayrı |
| `privacy/PrivacyLinks.kt` | İzinli alan adı listesi (tam eşleşme), yalnız https; `user@`, port, ters bölü, boşluk, büyük harf hilesi reddedilir; `ACTION_VIEW` + `BROWSABLE`, extra/bileşen yok |
| `ui/privacy/PrivacyHelperScreen.kt` | Genel bakış (ne yapar, dikkat listesi, yönetmelik, platformlar) ve platform ayrıntısı; açılamayan bağlantıda görünür hata |
| `ui/settings/SettingsScreen.kt` | "Gizlilik" bölümü, tek düğme |
| `ui/nav/Navigation.kt`, `ui/MainActivity.kt` | `settings/privacy` rotası (Ayarlar sekmesine ait) |

### Doğrulama kaydı (4 Kasım 2026, Linux bulut konteyneri, JDK 21, Android SDK 35, cihaz/emülatör yok)

| Komut | Sonuç |
|---|---|
| `./gradlew testDebugUnitTest` | **211/211** geçti, 0 atlanan. Yeni: `PrivacyCatalogTest` 10/10, `PrivacyHelperUiTest` 8/8 (Robolectric SDK 34) |
| `./gradlew lintDebug` | 0 hata, 29 uyarı; yeni dosyalarda uyarı yok |
| `./gradlew assembleDebug` | debug APK üretildi |

`PrivacyCatalogTest`: beş platform; her platformda yeni/mevcut × web/uygulama dört ayrı satır; yönetmelik iddiası Resmî Gazete'ye dayanıyor; kanıtsız platform durumu "Doğrulanamadı"; her iddiada kaynak ve kontrol tarihi; e-Devlet'siz seçenek yalnız doğrulanmışsa; her platformda itiraz yolu; katalog VPN/proxy/atlatma önermiyor; tüm adresler izinli listede; tehlikeli adresler (http, `user@`, port, benzer alan adı, `javascript:`, `intent:`, `file:`, ters bölü…) reddediliyor; yardımcı kaynaklarında log, kalıcı depolama, metin girişi, ağ istemcisi, VPN servisi ya da `putExtra` yok.

`PrivacyHelperUiTest`: Ayarlar'daki tek düğme yardımcıyı açıyor ve rota Ayarlar sekmesine ait; genel bakışta beş platform ve "VPN veya proxy kullanmaz" metni; her platform ayrıntısında dört durum kartı, veri akışı, e-Devlet'siz seçenek, itiraz ve ayar kartları; web ayar düğmesi yalnız `ACTION_VIEW` + `BROWSABLE`, beklenen https adresi, extra ve bileşen olmadan; yardım bağlantıları denetleyiciden geçiyor; açılamayan bağlantıda hata kartı; izinsiz adres hiçbir şey başlatmıyor; kapat düğmesi çalışıyor.

## 7. Cihazda test EDİLMEYENLER

- Gerçek telefonda düğme, kaydırma ve geri hareketi (testler Robolectric/JVM).
- Bağlantıların gerçek tarayıcıda ya da App Links ile resmî uygulamada açılması ve oturum açıkken doğru ayar sayfasına inilmesi.
- Uygulama içi elle adımların güncel Instagram/TikTok/YouTube/Facebook/X sürümlerindeki menü adları.
- e-Devlet belirteç akışı (hizmet kontrol tarihinde listelenmiyordu) ve herhangi bir platformun belirteç ekranı.
