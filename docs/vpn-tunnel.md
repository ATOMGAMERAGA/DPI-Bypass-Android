# VPN Tüneli — Windscribe / Proton VPN ile DPI Bypass aynı anda

## Sorun

Android aynı anda yalnızca **bir** VPN uygulaması çalıştırır. Windscribe ya da Proton VPN uygulaması açıkken DPI Bypass'ı açarsan Android ilkini kapatır. Bu bir uygulama hatası değil, sistem kuralıdır. Uygulama içinden aşılamaz.

## Çözüm: tek tünel, üç yol

DPI Bypass, sağlayıcının **WireGuard yapılandırma dosyasını** kendi tünelinin içinde çalıştırır. Sağlayıcının uygulaması gerekmez. Bağlandığında:

| Uygulama | Nereden gider? |
| --- | --- |
| **VPN Tüneli**'nde seçtiğin uygulamalar | WireGuard → sağlayıcının sunucusu (yurt dışı) |
| **Uygulamalar** sekmesindeki kapsam (Tümü / Yalnızca seçili / Seçili hariç) | Yerel DPI motoru (ByeDPI) |
| Geri kalan her şey | Doğrudan; tünel dışında |

VPN'de seçtiğin uygulamalar, DPI kapsamı ne olursa olsun tünele alınır. "Yalnızca seçili" listesine eklenir, "Seçili hariç" listesinden çıkarılır. Örnekler:

- DPI kapsamı **Yalnızca seçili: Discord**, VPN'de **Instagram, X**. Discord DPI motorundan, Instagram ve X VPN'den gider. Banka ve oyunlar doğrudan gider.
- DPI kapsamı **Seçili hariç: banka uygulamaları**, VPN'de **TikTok**. TikTok VPN'den, bankalar doğrudan, diğer her şey DPI motorundan gider.
- DPI kapsamı **Tümü**, VPN'de **YouTube**. YouTube VPN'den, diğer her şey DPI motorundan gider.

## Kurulum

1. **Dosyayı al.**
   - **Proton VPN (ücretsiz hesapla olur):** account.protonvpn.com → *Downloads* → *WireGuard configuration*. Platform olarak Android'i seç, ücretsiz bir sunucu seç, *Create* ve ardından *Download*.
   - **Windscribe (Pro ya da Build-a-Plan):** windscribe.com → *Hesabım* → *Config Generator* → *WireGuard*. Konumu ve portu seç (emin değilsen 443), sonra *Download Config*. Windscribe ücretsiz hesaplara WireGuard dosyası vermiyor.
   - .conf dosyası veren başka bir WireGuard sağlayıcısı da olur (Mullvad, IVPN, kendi sunucun…).
2. **Ekle.** **Sosyal Erişim → 2 · Yurt dışı çıkış → VPN Tüneli** ya da **Uygulamalar** sekmesinin üstündeki kart. **Dosya seç** ya da **Yapıştır** kullan.
3. **Test et.** **VPN'i test et** dokunuşu tüneli kısa süre açar ve çıkış ülkesini ölçer. DPI Bypass kapalıyken de çalışır.
4. **Uygulamaları seç.** Seçtiğin her uygulamanın bütün trafiği VPN'den çıkar: TCP, UDP, DNS ve sesli görüşme.
5. **Bağlan.** DPI Bypass bağlıyken yapılan değişiklikler birkaç saniye içinde uygulanır. Bağlı değilken ekran "Hazır — bağlanayım mı?" diye sorar.

**Sosyal platformları da VPN'den çıkar** anahtarını açarsan Sosyal Erişim'in yurt dışı çıkışı da bu VPN olur. Seçili platformların (Instagram, X, TikTok…) alan adları, hangi uygulamadan ya da tarayıcıdan açılırsa açılsın VPN'den gider. Kendi proxy sunucun gerekmez.

## Başka bir VPN açıkken

Bağlan'a dokunduğunda Windscribe, Proton ya da başka bir VPN açıksa uygulama önce sorar:

- **VPN Tüneli kuruluysa:** "Sorun değil" der ve bağlanır. Android diğer VPN'i kapatır. Seçtiğin uygulamalar DPI Bypass'ın içindeki VPN'den gider.
- **Kurulu değilse:** **VPN Tünelini kur**, **Yine de bağlan** ya da **Vazgeç** seçeneklerini sunar.

Bu uyarıyı **VPN Tüneli → Başka VPN açıkken uyar** anahtarıyla kapatabilirsin.

## Güvenlik ve sınırlar

- **Sızıntı yok:** VPN'e ulaşılamazsa VPN uygulamalarının bağlantıları kesilir. Bu bağlantılar Türkiye'den doğrudan gönderilmez. IPv6 da tünele alınır.
- **Anahtar:** WireGuard özel anahtarı ayrı bir dosyada, yalnızca bu cihazda saklanır. Android bulut yedeğine ve cihazdan cihaza aktarıma girmez.
- **Hız ve kota:** VPN uygulamaları sağlayıcının hız ve kota sınırlarına tabidir. DPI motorundan ya da doğrudan giden uygulamalar bu sınırlardan etkilenmez.
- **Sağlayıcı neyi görür:** Sağlayıcı, VPN'den giden uygulamaların bağlandığı adresleri görür. TLS ile şifrelenmiş içeriği göremez.
- **Uygulama tespiti:** Uygulama bazlı ayrım Android 10 ve sonrasında sistemin bağlantı sahibi API'siyle yapılır, Android 8–9'da `/proc/net` tablolarıyla. Bir bağlantının sahibi bulunamazsa bağlantı VPN uygulamasına ait sayılmaz ve DPI yolundan gider.

## Teknik ayrıntı

```
TUN ─ hev-socks5-tunnel (yamalı: kaynak adres → SOCKS kullanıcı adı)
        └─ ExitRouter (127.0.0.1:1081)
              ├─ bağlantı sahibi VPN uygulaması → wgbridge (127.0.0.1:1082) → WireGuard (gVisor netstack) → sağlayıcı
              ├─ Sosyal Erişim alan adı        → yurt dışı çıkış (VPN Tüneli ya da kendi proxy)
              └─ diğer                         → ByeDPI (127.0.0.1:1080)
```

- `app/src/main/go/wgbridge`: wireguard-go ve gVisor netstack ile WireGuard → yerel SOCKS5 köprüsü (CONNECT ve UDP ASSOCIATE). `go test ./...` gerçek bir yerel WireGuard eşiyle uçtan uca sınar.
- `vpn/ExitRouter.kt`: hev'in gönderdiği kaynak adresle `ConnectivityManager.getConnectionOwnerUid` çağrılır ve bağlantı UID'ye göre yönlendirilir.
- hev yaması derleme sırasında yalnızca kopyalanan kaynak ağacına uygulanır (`app/build.gradle.kts`, `patchHevSourceAddress`); submodül değişmez. Kaynak adres yalnızca yönlendirici çalışırken gönderilir. ByeDPI kimlik doğrulama kabul etmediği için doğrudan bağlantıda eski davranış korunur.
