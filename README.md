# DPI Bypass · Android

Telefonunda Discord ve benzeri erişimi kısıtlanan servislere bağlanmak için açık kaynak bir uygulama. Bağlantıyı **telefonun üzerinde** işler: DNS sorguları ve bağlantının ilk paketleri üzerinde çalışır; trafiğini başka ülkedeki bir sunucuya göndermez.

> **Kısa açıklama:** Android, yerel trafik işleme için VPN izni gösterir. DPI Bypass bu izni telefonunda bir ağ tüneli açmak için kullanır. IP adresini gizleyen veya trafiğini şifreleyen bir uzak VPN hizmeti değildir. Erişim sonucu operatöre, ağa ve zamana göre değişebilir.

## İndir ve kullan

1. [Son sürüm sayfasını aç](https://github.com/ATOMGAMERAGA/DPI-Bypass-Android/releases/latest) ve **Assets** altındaki APK dosyasını telefonuna indir. Günlük kullanım için `app-debug` adlı CI test dosyasını değil, yayımlanan sürümü seç.
2. İndirilen APK'yı açıp kur. Android dış kaynaktan kurulum izni isterse, dosyayı açtığın uygulamaya izin ver. Uyarıların metni telefon üreticisine göre değişebilir.
3. **DPI Bypass** uygulamasını aç. İlk kurulum adımlarını tamamla ve ana ekrandaki büyük bağlan düğmesine dokun.
4. Android'in VPN bağlantısı onayını kabul et. Ekranda **Bağlandı** durumunu gördüğünde kullanmak istediğin uygulamayı yeniden açıp dene.

Uygulamayı kapatmak için ana ekrandaki düğmeye tekrar dokunabilir veya bildirimdeki kapatma eylemini kullanabilirsin. İstersen **Ayarlar → Hızlı erişim** üzerinden Hızlı Panel'e tek dokunuşluk kısayol ekleyebilirsin.

### Hangi uygulamalar etkilenir?

**Uygulamalar** sekmesinde üç kapsam var:

| Seçenek | Ne olur? |
| --- | --- |
| Tümü | Telefondaki uygulamalar DPI Bypass tünelini kullanır. |
| Yalnızca seçili | Sadece listede işaretlediğin uygulamalar tünele girer. |
| Seçili hariç | İşaretlediğin uygulamalar tünelin dışında kalır. |

İlk kurulumda “Sadece Discord” seçtiysen kapsam buna göre ayarlanır ve önceki kapsamın saklanır; ana ekrandaki **Sadece Discord** kısayoluna açıkken tekrar dokunmak önceki kapsamı geri getirir. Daha sonra **Uygulamalar** sekmesinden de değiştirebilirsin. “Yalnızca seçili” kipinde listede kurulu uygulama kalmazsa Android bütün trafiği tünele alacağı için bağlantı kurulmaz ve nedeni ana ekranda yazılır. Kapsam telefonun kendi uygulamaları içindir; hotspot ile bağlanan başka bir cihazın trafiğini kapsamaz.

## Bir şey çalışmıyorsa

| Durum | Denenecek adım |
| --- | --- |
| Bağlanmıyor | İnternet bağlantını kontrol et. Android'in VPN izninin verildiğinden emin ol; başka bir VPN açıksa kapatıp yeniden dene. |
| Bağlandı ama hedef uygulama açılmıyor | **Uygulamalar** sekmesinde hedef uygulamanın kapsama girdiğini kontrol et. Ardından **Mod** sekmesinde otomatik yöntemi dene. |
| DNS veya sesli görüşme bozuldu | **Ayarlar** bölümündeki **UDP/QUIC'i tünelde düşür** seçeneğini kapat. Bu ayar DNS sorgularını ve UDP kullanan görüşmeleri etkileyebilir. |
| Telefon uygulamayı arka planda durduruyor | **Ayarlar** bölümündeki pil optimizasyonu yönlendirmesini kullan. Gerekirse uygulamayı tekrar açıp bağlan. |
| Oyunda (ör. Roblox) "bağlantı kesildi" uyarısı ya da oyuna girilemiyor | **Ayarlar**'da **UDP/QUIC'i tünelde düşür** kapalı olmalı; oyun sunucusu UDP kullanır. Roblox kuruluysa ve tünele giriyorsa otomatik mod yöntemi Roblox'ta da doğrular; güncellemeden sonra bağlantıyı bir kez kapatıp aç. |
| Ağ değişince bağlantı bozuldu | Bağlantıyı kapatıp yeniden aç; otomatik mod yeni ağ için yöntemi tekrar değerlendirsin. |
| Bir uygulama çalışıyor, diğeri açılmıyor | **Ayarlar → Bir uygulama açılmıyor mu?** rehberini aç: açılmayan uygulamayı seç, çalışmaya devam etmesi gerekenleri **Başka uygulamalar da sorunsuz kalsın**'da işaretle. Çalışan uygulamayı bozan bir yöntem otomatik reddedilir. Uygulama bağlantının dışındaysa rehber onu eklemeyi önerir. |

Sorun sürerse [GitHub Issues](https://github.com/ATOMGAMERAGA/DPI-Bypass-Android/issues) sayfasında telefon modeli, Android sürümü, operatör, Wi-Fi/mobil veri türü ve ekranda görünen hata ile bildirebilirsin. Hesap, parola veya kişisel trafik kaydı paylaşma.

## Neler sunuyor?

- **Otomatik yöntem:** Bağlı olduğun ağa göre çalışabilen DPI aşma stratejilerini dener ve birini seçer. Önce kaç hedefin açıldığına (kapsama), sonra en yavaş hedefin TLS el sıkışma süresine bakar; gösterilen süre uygulamanın ping'i ya da hızı değildir. Sosyal Erişim'de seçili uygulamaların alan adları da bu doğrulamaya katılır. **Mod** sekmesinden elle seçim de yapabilirsin.
- **DNS seçenekleri:** Cloudflare, AdGuard, Google veya özel adres. Ayar değişince açık tünel yeniden yapılandırılır.
- **Uygulama kapsamı:** Tüm uygulamalar, yalnızca seçilenler veya seçilenler hariç.
- **Sosyal Erişim — e-Devlet doğrulaması olmadan:** Alt gezinmedeki **Sosyal Erişim** sekmesi. Platformlar kimlik doğrulamasını bağlantının geldiği ülkeye bakarak ister; DPI aşma bunu çözmez çünkü trafik yine Türkiye IP'sinden çıkar. Bu yüzden yalnızca seçtiğin platformların (Instagram/Threads, Facebook, X, TikTok, YouTube, Reddit, Discord, Twitch, Snapchat, Bluesky, Kick ya da kendi eklediğin alan adları) bağlantıları **senin verdiğin yurt dışındaki SOCKS5/HTTP proxy sunucusundan** çıkar; bankacılık, e-Devlet, oyunlar ve diğer her şey eskisi gibi yerel DPI motorundan doğrudan gider. Ayrım bağlantının ilk paketindeki alan adına (TLS SNI / HTTP Host) göre cihazda yapılır. Sunucuya ulaşılamazsa platform bağlantısı Türkiye'den gönderilmez, kesilir; QUIC (UDP/443) bu modda kapatılır, IPv6 tünele alınır ki platform trafiği tünelin yanından sızmasın. **Sunucuyu test et** düğmesi sunucunun çıkış ülkesini ölçer ve Türkiye'de olan sunucuyu reddeder. Uygulama sunucu sağlamaz. Hesap +90 numaraya bağlıysa, daha önce doğrulama başlatıldıysa ya da konum izni açıksa platform IP'ye bakmadan da isteyebilir; ekran bunları da açıkça söyler. Ayrıntılar: [docs/social-access-alpha.md](docs/social-access-alpha.md).
- **Bağlantı sorunu rehberi (Alpha):** Platform e-Devlet ekranı değil de hiç açılmıyorsa. Adım adım rehber: her sayfada tek soru ya da görev (uygulama → işlem → sorun → gerekiyorsa bağlantı hazırlığı → deneme → "ne oldu?"). YouTube, Instagram, X, TikTok, Discord, Reddit, Facebook, Telegram, WhatsApp, Signal, Twitch, Snapchat ya da kendi verdiğin bir alan adı için **birlikte çalışan ortak** yerel DPI planını arar. Her yöntem seçili bütün uygulamalarda, yöntemin gerçek yolundan (yerel motor) ölçülür; çalışan bir uygulamayı bozan yöntem reddedilir. Onaylanan plan DNS/QUIC/kapsam değişikliğinde kaybolmaz, yalnızca kısa bir yeniden doğrulama yapılır. Ağ yolu, içerik, medya ve ses ayrı gösterilir: ağ yolunun açılması içeriğin ya da sesli görüşmenin çalıştığını kanıtlamaz; bunlar için onayın istenir. Ayrıntılar: [docs/social-access-alpha.md](docs/social-access-alpha.md).
- **Hızlı Panel ve bildirim:** Uygulamaya dönmeden bağlantıyı yönetme.
- **Tablet düzeni:** Geniş ekranlarda etiketli yan gezinme ve okunabilir genişlikte içerik; telefonun alt gezinmesi korunur.
- **İsteğe bağlı otomatik başlatma:** **Ayarlar → Cihaz açılınca otomatik bağlan**. Android'in VPN izni önceden verilmiş olmalı.
- **Vodafone Sınırsız Modu:** Vodafone hotspot'una bağlanan rootsuz Android telefonda, DPI bağlantısı açıkken yerel proxy'nin giden IPv4 paketlerini TTL 65 ile gönderir. Hotspot telefonundaki bir yönlendirme adımı sonrasında TTL 64 olur. Mod uygulama seçimiyle sınırlanmaz; kapatınca önceki seçimlerin geri gelir. Tercihin sen kapatana kadar saklanır. IPv6 çıkışı bu modda tünel içinde reddedilir; IPv6 gerektiren ağlarda bağlantı etkilenebilir. Operatör paketinin koşullarını değiştirmez ve kota muafiyetini garanti etmez.

**Sınırlar:** Başarıyı her operatörde veya her sitede garanti etmek mümkün değildir. Yerel işleme de cihaz ve ağ koşullarına göre hız ya da gecikmeyi etkileyebilir. Bu uygulama anonimlik sağlamaz. Android VPN servisi telefonun kendi uygulama trafiğine yöneliktir; hotspot'u paylaşan telefonun diğer cihazlar için yönlendirdiği paketleri değiştirmez. [Windows sürümündeki mod](https://github.com/ATOMGAMERAGA/DPI-Bypass-Windows#vodafone-s%C4%B1n%C4%B1rs%C4%B1z-modu) bilgisayarda çalışır; Android'deki mod hotspot'a **bağlanan** telefonda çalışır.

## Geliştiriciler için

**Gereksinimler:** JDK 17, Android SDK, NDK `26.3.11579264` ve CMake `3.22.1`.

```bash
git clone --recurse-submodules https://github.com/ATOMGAMERAGA/DPI-Bypass-Android.git
cd DPI-Bypass-Android
./gradlew testDebugUnitTest lintDebug assembleDebug
node --test tests/observer.test.cjs
```

Windows'ta son satır için `gradlew.bat` kullan. Submodüller eksikse `git submodule update --init --recursive` çalıştır. Debug APK `app/build/outputs/apk/debug/` altında oluşur; son kullanıcıya dağıtılacak sürüm yerine geçmez.

Uygulama Android `VpnService` ile yerel TUN açar; [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) trafiği yerel SOCKS5 bağlantısına, [ByeDPI](https://github.com/hufrea/byedpi) DPI aşma motoruna taşır. Bağımlılık ve lisans bilgileri [NOTICE](NOTICE) dosyasında. Sürüm `gradle.properties` içindeki `VERSION_NAME` ve `VERSION_CODE` ile yönetilir. İmzalı sürüm hazırlama adımları [İmzalama Rehberi](docs/Imzalama-Rehberi.md) ve [CI/CD rehberi](CI-CD-ve-Google-Rehberi.md) içinde.

## Lisans

GPL-3.0 · Ayrıntılar için [LICENSE](LICENSE) ve [NOTICE](NOTICE).
