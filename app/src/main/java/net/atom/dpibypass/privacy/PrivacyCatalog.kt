package net.atom.dpibypass.privacy

// ---------------------------------------------------------------------------
// Gizlilik Yardımcısı — kaynaklı, yerel bilgi kataloğu.
//
// İlkeler:
//  * Her iddianın bir kanıt düzeyi ([Evidence]) ve en az bir kaynağı vardır.
//    Kaynağı okunamamış ya da kanıt bulunamamış iddia "Doğrulanamadı" yazar.
//  * Yönetmeliğin istediği ([StatusRow.required]) ile platformda fiilen
//    görülen ([StatusRow.observed]) AYRI tutulur.
//  * Yaş ve erişim kontrollerini atlatmaya yönelik hiçbir yol önerilmez; yardımcı
//    VPN/proxy kullanmaz, ağ isteği yapmaz, hiçbir girdi almaz ve hiçbir şey kaydetmez.
//  * Bu dosya saf Kotlin'dir (Android bağımlılığı yok) ve JVM testleriyle denetlenir.
// ---------------------------------------------------------------------------

/** Kaynakların kontrol edildiği tarih (tüm satırlar için aynı tur). */
const val PRIVACY_CHECKED_ON = "4 Kasım 2026"

enum class Evidence(val label: String) {
    /** Resmî sayfa bu ortamda açılıp okundu ve iddiayı açıkça söylüyor. */
    VERIFIED("Doğrulandı"),
    /** Resmî kaynak var ama sayfa bu ortamda tam okunamadı ya da oturum gerektiriyor. */
    PARTIAL("Kısmen doğrulandı"),
    /** Kanıt bulunamadı. */
    UNVERIFIED("Doğrulanamadı"),
}

data class Source(val title: String, val url: String, val checkedOn: String = PRIVACY_CHECKED_ON)

data class Claim(val text: String, val evidence: Evidence, val sources: List<Source>)

enum class AccountKind(val label: String) { NEW("Yeni hesap"), EXISTING("Mevcut hesap") }

enum class Surface(val label: String) { WEB("Web"), APP("Mobil uygulama") }

/** Bir platform × hesap türü × yüzey hücresi: yönetmelik ne istiyor, platformda ne görüldü. */
data class StatusRow(
    val account: AccountKind,
    val surface: Surface,
    val required: Claim,
    val observed: Claim,
)

/** Resmî web ayar sayfası (oturum gerektirir) ve uygulama için kısa elle adımlar. */
data class SettingsPath(
    val title: String,
    val webUrl: String?,
    val appSteps: List<String>,
    val evidence: Claim,
)

enum class PlatformId(val label: String, val genitive: String) {
    INSTAGRAM("Instagram", "Instagram'ın"),
    TIKTOK("TikTok", "TikTok'un"),
    YOUTUBE("YouTube", "YouTube'un"),
    FACEBOOK("Facebook", "Facebook'un"),
    X("X", "X'in"),
}

data class PlatformGuide(
    val id: PlatformId,
    val rows: List<StatusRow>,
    /** Platformun KENDİ yaş kontrollerinde verinin kime gittiği. */
    val dataFlow: List<Claim>,
    /** Hesap işlemi için platformun belgelediği, e-Devlet gerektirmeyen seçenek — kanıt yoksa null. */
    val nonEdevletOption: Claim?,
    /** Bulunamadığında ekranda gösterilen açıklama. */
    val nonEdevletNote: Claim,
    val appeal: List<Claim>,
    val settings: List<SettingsPath>,
    val helpLinks: List<Source>,
)

object PrivacySources {
    val REGULATION = Source(
        "Resmî Gazete 10.10.2026, Sayı 33396 — Sosyal Ağ Sağlayıcı Tarafından Çocuklara Özgü Ayrıştırılmış Hizmet Sunulmasına ve Yaş Doğrulamaya İlişkin Yönetmelik",
        "https://www.resmigazete.gov.tr/eskiler/2026/10/20261010-15.pdf",
    )
    val EDEVLET_SEARCH = Source(
        "e-Devlet Kapısı hizmet araması: \"yaş doğrulama\"",
        "https://www.turkiye.gov.tr/arama?aranan=ya%C5%9F+do%C4%9Frulama",
    )
    val SGB = Source("Siber Güvenlik Başkanlığı — Haberler ve Duyurular", "https://siberguvenlik.gov.tr/")

    val IG_AGE = Source("Instagram Yardım: Instagram'da yaşınızı onaylama", "https://help.instagram.com/966909308115586")
    val IG_ID = Source("Instagram Yardım: Neden Instagram'a kimlik yüklemeniz isteniyor?", "https://help.instagram.com/293775921768331")
    val IG_PRIVATE = Source("Instagram Yardım: Instagram hesabınızın gizli hale getirilmesi", "https://help.instagram.com/448523408565555")

    val FB_AGE = Source("Facebook Yardım: Facebook'ta yaşınızı onaylama", "https://www.facebook.com/help/958848942357089")
    val FB_SELFIE = Source("Facebook Yardım: Selfie videosu yaş doğrulaması nasıl çalışır?", "https://www.facebook.com/help/1386337538619854")
    val FB_ID = Source("Facebook Yardım: Yaşınızı doğrulamak için kimliğiniz nasıl kullanılır?", "https://www.facebook.com/help/671565971895323")
    val FB_ID_TYPES = Source("Facebook Yardım: Facebook'un kabul ettiği kimlik türleri", "https://www.facebook.com/help/159096464162185")
    val FB_CHECKUP = Source("Facebook Yardım: Gizlilik Ayarı Kontrolü", "https://www.facebook.com/help/443357099140264")

    val TT_APPEAL = Source(
        "TikTok Destek: TikTok'ta reşit olmamakla ilgili yapılan itirazlar",
        "https://www.tiktok.com/support/faq_detail?id=7611808266502560268",
    )
    val TT_PRIVATE = Source(
        "TikTok Destek: Gizli ya da herkese açık hesap seçimi",
        "https://support.tiktok.com/en/account-and-privacy/account-privacy-settings/making-your-account-public-or-private",
    )

    val YT_AGE_ESTIMATION = Source("YouTube Yardım: YouTube'da yaş tahmini hakkında", "https://support.google.com/youtube/answer/16422785?hl=tr")
    val YT_BIRTHDAY = Source("YouTube Yardım: Doğum gününüzü doğrulama", "https://support.google.com/youtube/answer/3159776?hl=tr")
    val YT_PRIVACY = Source("YouTube Yardım: YouTube uygulamalarında gizlilik ile ilgili temel bilgiler", "https://support.google.com/youtube/answer/10364219?hl=tr")
    val G_AGE_REQ = Source("Google Hesabı Yardım: Google Hesaplarında yaş şartları", "https://support.google.com/accounts/answer/1350409?hl=tr")
    val G_VERIFY = Source("Google Hesabı Yardım: Yaş kısıtlamalı içerik ve özelliklere erişme", "https://support.google.com/accounts/answer/10071085?hl=tr")

    val X_AGE = Source("X Yardım: Age Assurance on X", "https://help.x.com/en/rules-and-policies/age-assurance")
    val X_PROTECT = Source("X Yardım: How to protect and unprotect your Posts", "https://help.x.com/en/safety-and-security/how-to-make-x-private-and-public")
}

object PrivacyCatalog {
    private val S = PrivacySources

    // ---- Yönetmelik: tüm sosyal ağ sağlayıcıları için aynı ----

    /** Yönetmelik web ile uygulamayı ayırmaz; bu not her iki yüzeyde de gösterilir. */
    private const val SURFACE_NOTE =
        " Yönetmelik web ile uygulamayı ayırmaz; ayrıştırılmış hizmet kuralları uygulama, internet sitesi ve tüm arayüzlerde geçerlidir (Md. 8/5-ğ)."

    val requiredNew = Claim(
        "Hesap açılırken e-Devlet Kapısı'ndan alınan yaş doğrulama belirteci zorunlu (Md. 6/2, Md. 7). " +
            "15 yaşını doldurmamış kişinin hesap açma talebi reddedilir (Md. 6/4-a). Yürürlük: 1 Kasım 2026 (Md. 13)." + SURFACE_NOTE,
        Evidence.VERIFIED, listOf(S.REGULATION),
    )
    val requiredExisting = Claim(
        "Belirteçle doğrulanmamış mevcut hesaplar 31 Mart 2027'ye kadar en az bir kez belirteçle doğrulanır (Geçici Md. 1). " +
            "15 yaşını doldurmadığı tespit edilen hesaplar bilgi verilerek kapatılır." + SURFACE_NOTE,
        Evidence.VERIFIED, listOf(S.REGULATION),
    )

    /** Yönetmeliğin özeti (ekrandaki "Yönetmelik ne istiyor?" kartı). */
    val regulation: List<Claim> = listOf(
        Claim(
            "Sosyal ağ sağlayıcı 15 yaşını doldurmamış çocuklara hizmet sunamaz; 15–18 yaş için ayrıştırılmış hizmet sunar (Md. 5, Md. 8).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Yaş doğrulamada kimlik tespiti yerine yalnızca yaş eşiğini belirleyen yöntem kullanılır (Md. 5/3).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Belirteç e-Devlet Kapısı'nda, Başkanlık'ın çevrim içi hizmetiyle üretilir; yalnızca 15 ve 18 yaş eşiklerini içerir. " +
                "Ad, kullanıcı adı ya da seni belirlenebilir kılan başka veri içermez. Belirteci platforma sen yüklersin; platform onu yalnız yaş doğrulama için kullanabilir (Md. 7/1).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "İlk doğrulamadan sonra platform kendi belirleyeceği tekli ya da katmanlı yöntemleri işletir; makul şüphede hesabı askıya alıp yeniden belirteç ister (Md. 6/3).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Türkiye'de mukim olmayıp geçici bulunanlar kontrolden muaftır; başvururlarsa e-Devlet Kapısı'nda yalnız pasaport bilgisiyle istisnai belirteç üretilir (Md. 7/1-d).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "İtiraz: platform ücretsiz, Türkçe, insan incelemeli bir başvuru/itiraz yolu kurmak zorunda; itiraz sonuçlanana kadar içerik ve veriler silinmez; sonuca karşı Başkanlık'a şikâyet ve yargı yolu açıktır (Md. 10).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Devlet tarafında belirteç üretilirken hangi kayıtların tutulduğu yönetmelikte yazmıyor; belirtecin teknik rehberi yalnız platformlarla paylaşılır (Md. 12).",
            Evidence.UNVERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Kontrol tarihinde e-Devlet hizmet aramasında yaş doğrulama belirteci hizmeti listelenmiyordu; Siber Güvenlik Başkanlığı sitesinde yaş doğrulamaya ilişkin duyuru yoktu (son duyuru 21.10.2026).",
            Evidence.VERIFIED, listOf(S.EDEVLET_SEARCH, S.SGB),
        ),
    )

    /** Belirteç yolunda verinin kime gittiği — yönetmeliğe göre, her platformda aynı. */
    val tokenFlow: List<Claim> = listOf(
        Claim(
            "Sen → e-Devlet Kapısı: kimlik doğrulaması burada yapılır ve belirteç Başkanlık'ın hizmetiyle üretilir (Md. 7/1-a).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Sen → platform: yalnız belirteç. İçinde 15 ve 18 yaş eşiği vardır; ad, kullanıcı adı, T.C. kimlik no ya da seni belirlenebilir kılan veri yoktur (Md. 7/1-b, 7/1-c).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Platform → Başkanlık: belirtecin gerçekten e-Devlet'te üretildiğini teyit eder; belirteci yaş doğrulama dışında kullanamaz (Md. 7/1-ç).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "e-Devlet/Başkanlık tarafında hangi kaydın ne kadar tutulduğu yönetmelikte yazmıyor.",
            Evidence.UNVERIFIED, listOf(S.REGULATION),
        ),
    )

    /** Platformdan bağımsız, gereksiz veri paylaşımını azaltan kısa kontrol listesi. */
    val minimalDataTips: List<Claim> = listOf(
        Claim(
            "Belirteci yalnız turkiye.gov.tr ya da resmî e-Devlet uygulamasından al. Yönetmelikte belirteci kullanıcı üretir ve platforma kendisi yükler; e-Devlet şifreni ya da SMS kodunu bir platforma, uygulamaya veya mesajdaki bağlantıya girmen gerekmez (Md. 7/1-a, 7/1-c).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "\"Hesabın hemen kapanacak\" mesajlarına acele etme: mevcut hesaplar için son tarih 31 Mart 2027 (Geçici Md. 1).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Belirteç yalnızca yaş eşiğini taşır. Platform belirteç adımında ad, T.C. kimlik no ya da doğum tarihi isterse bu yönetmeliğin tarif ettiği adım değildir; durup platformun resmî yardım sayfasından kontrol et (Md. 7/1-b).",
            Evidence.VERIFIED, listOf(S.REGULATION),
        ),
        Claim(
            "Platform kendi yöntemiyle ek kontrol isterse (Md. 6/3) en az veri veren seçeneği seç. Örnek: TikTok'ta yüze dayalı yaş tahmininde sağlayıcı yalnız tahmini gönderip özçekimi siler; kimlik + özçekim yolunda Incode bilgileri sen silene kadar saklar.",
            Evidence.VERIFIED, listOf(S.REGULATION, S.TT_APPEAL),
        ),
        Claim(
            "Kimlik yüklemen gerekirse, incelemede gerekmeyen bilgileri (ör. kimlik numarası) fotoğraftan önce fiziksel olarak kapatabilirsin; Facebook bunu açıkça öneriyor. Dijital olarak gizlenmiş fotoğraf kabul edilmez.",
            Evidence.VERIFIED, listOf(S.FB_ID_TYPES),
        ),
    )

    // ---- Platformlar ----

    private fun observedAll(text: String, evidence: Evidence, vararg sources: Source): (AccountKind, Surface) -> Claim =
        { account, surface ->
            val tail = when (account) {
                AccountKind.NEW -> ""
                AccountKind.EXISTING -> " Mevcut hesaplar için yasal son tarih (31 Mart 2027) henüz gelmedi."
            }
            val surfaceText = when (surface) {
                Surface.WEB -> " Web için ayrı bir Türkiye adımı belgelenmemiş."
                Surface.APP -> " Mobil uygulama için ayrı bir Türkiye adımı belgelenmemiş."
            }
            Claim(text + surfaceText + tail, evidence, sources.toList())
        }

    private fun rows(observed: (AccountKind, Surface) -> Claim): List<StatusRow> =
        AccountKind.entries.flatMap { account ->
            Surface.entries.map { surface ->
                StatusRow(
                    account = account,
                    surface = surface,
                    required = if (account == AccountKind.NEW) requiredNew else requiredExisting,
                    observed = observed(account, surface),
                )
            }
        }

    private fun noNonEdevlet(platform: String, vararg sources: Source) = Claim(
        "$platform için Türkiye belirteç adımının yerine geçen, platformca belgelenmiş ve e-Devlet gerektirmeyen bir seçenek bulunamadı; bu yüzden gösterilmiyor. " +
            "Aşağıdaki yöntemler platformun kendi yaş kontrolleri ve itirazları içindir, belirtecin yerine geçtiğine dair kaynak yok.",
        Evidence.UNVERIFIED, sources.toList() + S.REGULATION,
    )

    private fun noTurkishAppeal(platform: PlatformId, vararg sources: Source) = Claim(
        "${platform.genitive} yönetmelik Md. 10'a göre kurduğu Türkiye'ye özel itiraz kanalı bulunamadı.",
        Evidence.UNVERIFIED, sources.toList() + S.REGULATION,
    )

    val instagram = PlatformGuide(
        id = PlatformId.INSTAGRAM,
        rows = rows(observedAll(
            "Instagram Yardım Merkezi'nde Türkiye'ye özel belirteç adımı bulunamadı; Türkçe \"yaşınızı onaylama\" sayfası genel 13 yaş kuralını ve Yoti yöntemlerini anlatıyor. e-Devlet aramasında belirteç hizmeti listelenmiyor.",
            Evidence.UNVERIFIED, S.IG_AGE, S.EDEVLET_SEARCH,
        )),
        dataFlow = listOf(
            Claim(
                "Instagram yaş onayı için Yoti ile çalışıyor: selfie videosu ya da kimlik yükleme. Verilen bilgi yaşı onaylamak için kullanılır, profilde gösterilmez.",
                Evidence.VERIFIED, listOf(S.IG_AGE),
            ),
            Claim(
                "Kimlik istenebilecek durumlar: hesabın sana ait olduğunu, yaşını onaylama, ödeme, kötüye kullanımı önleme. İnceleme sırasında erişim geçici olarak kaybedilebilir.",
                Evidence.VERIFIED, listOf(S.IG_ID),
            ),
            Claim(
                "Meta, selfie görselini yaş tahmini paylaşıldıktan sonra 30 gün içinde, kimliği 30 gün içinde sildiğini Facebook yardım sayfalarında yazıyor; Instagram'a ayrıca uygulandığı Instagram sayfasında yazmıyor.",
                Evidence.PARTIAL, listOf(S.FB_SELFIE, S.FB_ID),
            ),
        ),
        nonEdevletOption = null,
        nonEdevletNote = noNonEdevlet("Instagram", S.IG_AGE),
        appeal = listOf(
            noTurkishAppeal(PlatformId.INSTAGRAM, S.IG_AGE),
            Claim(
                "Yaş onayı istendiğinde ekrandaki seçeneklerle (Yoti selfie videosu ya da kimlik) doğum gününü kanıtlayabilirsin; doğum gününü ayarlarından gözden geçirip düzenleyebilirsin.",
                Evidence.VERIFIED, listOf(S.IG_AGE),
            ),
        ),
        settings = listOf(
            SettingsPath(
                title = "Hesabı gizli yap",
                webUrl = "https://www.instagram.com/accounts/privacy_and_security/",
                appSteps = listOf(
                    "Profil → ☰ (Daha fazla) → Ayarlar",
                    "\"İçeriklerini kimler görebilir?\" altında Hesap gizliliği",
                    "Gizli hesap düğmesini aç → Gizli hesaba geç",
                ),
                evidence = Claim(
                    "Adımlar Instagram yardım sayfasından (bilgisayar sürümü). Web adresi resmî alan adında, oturum açma sayfasına yönlendiriyor; oturum açıkken ve uygulamada cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.IG_PRIVATE),
                ),
            ),
        ),
        helpLinks = listOf(S.IG_AGE, S.IG_ID, S.IG_PRIVATE),
    )

    val facebook = PlatformGuide(
        id = PlatformId.FACEBOOK,
        rows = rows(observedAll(
            "Facebook Yardım Merkezi'nde Türkiye'ye özel belirteç adımı bulunamadı; Türkçe \"yaşınızı onaylama\" sayfası genel 13 yaş kuralını ve Yoti yöntemlerini anlatıyor. e-Devlet aramasında belirteç hizmeti listelenmiyor.",
            Evidence.UNVERIFIED, S.FB_AGE, S.EDEVLET_SEARCH,
        )),
        dataFlow = listOf(
            Claim(
                "Selfie videosu: selfie'den bir görsel yaş tahmini yapan tedarikçiyle paylaşılır; tedarikçi kişiyi tanımlamaz. Tahmin paylaşıldıktan sonra görsel 30 gün içinde silinir; tedarikçiyle başka bilgi paylaşılmaz.",
                Evidence.VERIFIED, listOf(S.FB_SELFIE),
            ),
            Claim(
                "Kimlik: Yoti üzerinden; yüz fotoğrafı ve doğum tarihi olan kimlik (pasaport, ehliyet). Kimlik 30 gün içinde silinir.",
                Evidence.VERIFIED, listOf(S.FB_ID),
            ),
            Claim(
                "Kimlikteki incelemede gerekmeyen bilgiler (kimlik numarası gibi) dikkate alınmaz; fotoğraftan önce fiziksel olarak kapatılabilir.",
                Evidence.VERIFIED, listOf(S.FB_ID_TYPES),
            ),
        ),
        nonEdevletOption = null,
        nonEdevletNote = noNonEdevlet("Facebook", S.FB_AGE),
        appeal = listOf(
            noTurkishAppeal(PlatformId.FACEBOOK, S.FB_AGE),
            Claim(
                "Yaş onayı istendiğinde selfie videosu ya da kimlik ile doğrulayabilirsin; sonuç bildirim olarak gelir (selfie için yaklaşık 20 dakika). Doğum gününü ayarlarından gözden geçirebilirsin.",
                Evidence.VERIFIED, listOf(S.FB_AGE, S.FB_SELFIE),
            ),
        ),
        settings = listOf(
            SettingsPath(
                title = "Gizlilik Ayarı Kontrolü",
                webUrl = "https://www.facebook.com/privacy/checkup/",
                appSteps = listOf(
                    "Profil resmi → Ayarlar ve gizlilik",
                    "Gizlilik Ayarı Kontrolü",
                    "\"Paylaştıklarını kimler görebilir\" ile doğum günü, telefon ve e-posta görünürlüğünü daralt",
                ),
                evidence = Claim(
                    "Adımlar Facebook yardım sayfasından (bilgisayar). Web adresi resmî alan adında, oturum açma sayfasına yönlendiriyor; uygulamada cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.FB_CHECKUP),
                ),
            ),
        ),
        helpLinks = listOf(S.FB_AGE, S.FB_SELFIE, S.FB_ID, S.FB_CHECKUP),
    )

    val tiktok = PlatformGuide(
        id = PlatformId.TIKTOK,
        rows = rows(observedAll(
            "TikTok Destek'te Türkiye'ye özel belirteç adımı bulunamadı; itiraz sayfası genel 13 yaş kuralını anlatıyor. e-Devlet aramasında belirteç hizmeti listelenmiyor.",
            Evidence.UNVERIFIED, S.TT_APPEAL, S.EDEVLET_SEARCH,
        )),
        dataFlow = listOf(
            Claim(
                "Yüze dayalı yaş tahmini: üçüncü taraf sağlayıcı özçekimden yaşı tahmin eder, TikTok'a yalnız tahmini gönderir ve özçekimi siler. Konuma göre sunulmayabilir.",
                Evidence.VERIFIED, listOf(S.TT_APPEAL),
            ),
            Claim(
                "Kimlik + özçekim (Incode): Incode gönderilenleri saklar; Incode hesabından silme talebi gönderebilirsin. Doğrulamadan sonra doğum tarihini TikTok'la paylaşman istenir.",
                Evidence.VERIFIED, listOf(S.TT_APPEAL),
            ),
            Claim(
                "Kredi kartı (18+): küçük, iade edilen geçici ücret. İtiraz bitince özçekim, kimlik fotoğrafı ve e-posta için silme süreci başlar; itiraz kaydı ve doğum tarihi saklanır.",
                Evidence.VERIFIED, listOf(S.TT_APPEAL),
            ),
        ),
        nonEdevletOption = null,
        nonEdevletNote = noNonEdevlet("TikTok", S.TT_APPEAL),
        appeal = listOf(
            noTurkishAppeal(PlatformId.TIKTOK, S.TT_APPEAL),
            Claim(
                "Yaş nedeniyle yasaklanan hesap: uygulamadaki itiraz bildirimini aç → İtiraz → talimatları izle. İtiraz ve veri indirme için yasak tarihinden itibaren 113 gün var; itiraz edilmezse 120. günde hesap silinir.",
                Evidence.VERIFIED, listOf(S.TT_APPEAL),
            ),
        ),
        settings = listOf(
            SettingsPath(
                title = "Hesabı gizli yap",
                webUrl = null,
                appSteps = listOf(
                    "Profil → ☰ Menü → Ayarlar ve gizlilik",
                    "Gizlilik → Gizli hesap düğmesini aç",
                ),
                evidence = Claim(
                    "Adımlar TikTok destek sayfasından (İngilizce). Güvenilir bir web ayar adresi doğrulanamadığı için yalnız elle adımlar veriliyor; cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.TT_PRIVATE),
                ),
            ),
        ),
        helpLinks = listOf(S.TT_APPEAL, S.TT_PRIVATE),
    )

    val youtube = PlatformGuide(
        id = PlatformId.YOUTUBE,
        rows = rows(observedAll(
            "YouTube/Google yardım sayfalarında Türkiye'ye özel belirteç adımı bulunamadı. Google Hesabı yaş şartları sayfası Türkiye'yi ayrı listelemiyor (listede olmayan ülkelerde sınır 13); YouTube yaş tahmini ülke listesinde Türkiye yok.",
            Evidence.UNVERIFIED, S.G_AGE_REQ, S.YT_AGE_ESTIMATION, S.EDEVLET_SEARCH,
        )),
        dataFlow = listOf(
            Claim(
                "YouTube 18 yaş altını belirlemek için yaş tahmini modeli kullanıyor (listelenen ülkelerde; Türkiye listede yok). Yanlışsa Google Hesabı'nda resmî kimlik, kredi kartı ya da selfie ile doğrulama yapılabilir.",
                Evidence.VERIFIED, listOf(S.YT_AGE_ESTIMATION),
            ),
            Claim(
                "Kimlik: güvenli saklanır, herkese açık yapılmaz ve doğum tarihi doğrulandıktan sonra silinir. Kredi kartı: ödeme değil provizyon; Google bu verileri yasal süre boyunca saklar.",
                Evidence.VERIFIED, listOf(S.G_VERIFY),
            ),
        ),
        nonEdevletOption = null,
        nonEdevletNote = noNonEdevlet("YouTube", S.YT_AGE_ESTIMATION),
        appeal = listOf(
            noTurkishAppeal(PlatformId.YOUTUBE, S.YT_AGE_ESTIMATION),
            Claim(
                "Yaş tahmini yanlışsa: Google Hesabı doğrulama sürecinde resmî kimlik, kredi kartı ya da selfie. Doğum günü: Google Hesabı → Kişisel bilgiler → Doğum günü → Kaydet.",
                Evidence.VERIFIED, listOf(S.YT_AGE_ESTIMATION, S.YT_BIRTHDAY),
            ),
            Claim(
                "Google Hesabı yaş şartını karşılamıyorsa hesabı güncellemek için 14 gün verilir; aksi hâlde hesap devre dışı kalır.",
                Evidence.VERIFIED, listOf(S.G_AGE_REQ),
            ),
        ),
        settings = listOf(
            SettingsPath(
                title = "Doğum gününü gözden geçir",
                webUrl = "https://myaccount.google.com/personal-info",
                appSteps = listOf(
                    "Google Hesabı → Kişisel bilgiler",
                    "Doğum günü → doğrula ya da güncelle → Kaydet",
                ),
                evidence = Claim(
                    "Adımlar YouTube yardım sayfasından. Web adresi resmî alan adında, oturum açma sayfasına yönlendiriyor; oturum açıkken cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.YT_BIRTHDAY),
                ),
            ),
            SettingsPath(
                title = "YouTube gizlilik ayarları",
                webUrl = null,
                appSteps = listOf("Profil resmi → \"YouTube'daki verileriniz\" ya da Google Hesabı → gizlilik ayarları"),
                evidence = Claim(
                    "YouTube yardım sayfası gizlilik ayarlarının bu iki yerden yönetildiğini söylüyor; menü yolu cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.YT_PRIVACY),
                ),
            ),
        ),
        helpLinks = listOf(S.YT_AGE_ESTIMATION, S.YT_BIRTHDAY, S.G_AGE_REQ, S.G_VERIFY, S.YT_PRIVACY),
    )

    val x = PlatformGuide(
        id = PlatformId.X,
        rows = rows(observedAll(
            "X'in Türkiye'ye özel belirteç adımına dair kaynak bulunamadı; X yardım sayfaları bu kontrol ortamından açılamadı (HTTP 403). e-Devlet aramasında belirteç hizmeti listelenmiyor.",
            Evidence.UNVERIFIED, S.X_AGE, S.EDEVLET_SEARCH,
        )),
        dataFlow = listOf(
            Claim(
                "X önce mevcut sinyallere (beyan edilen yaş, önceki kimlik doğrulaması, hesap yaşı) bakıyor; yetmezse canlı selfie ile yaş tahmini ya da resmî kimlik istiyor. Kimlik görseli ve selfie 30 güne kadar saklanabiliyor.",
                Evidence.PARTIAL, listOf(S.X_AGE),
            ),
        ),
        nonEdevletOption = null,
        nonEdevletNote = noNonEdevlet("X", S.X_AGE),
        appeal = listOf(
            noTurkishAppeal(PlatformId.X, S.X_AGE),
            Claim(
                "X yaş kararı için selfie ya da kimlik yolunu ve 30 günlük saklamayı itiraz imkânıyla ilişkilendiriyor; ayrıntılı itiraz adımları bu ortamda okunamadı.",
                Evidence.PARTIAL, listOf(S.X_AGE),
            ),
        ),
        settings = listOf(
            SettingsPath(
                title = "Gönderilerini koru",
                webUrl = "https://x.com/settings/audience_and_tagging",
                appSteps = listOf(
                    "Profil menüsü → Ayarlar ve gizlilik → Gizlilik ve güvenlik",
                    "Hedef kitle ve etiketleme → Gönderilerini koru",
                ),
                evidence = Claim(
                    "Adımlar X yardım sayfasının arama özetinden; sayfa bu ortamda açılamadı. Web adresi resmî alan adında, giriş sayfasına yönlendiriyor; cihazda denenmedi.",
                    Evidence.PARTIAL, listOf(S.X_PROTECT),
                ),
            ),
        ),
        helpLinks = listOf(S.X_AGE, S.X_PROTECT),
    )

    val platforms: List<PlatformGuide> = listOf(instagram, tiktok, youtube, facebook, x)

    fun platform(id: PlatformId): PlatformGuide = platforms.first { it.id == id }

    /** Katalogdaki tüm açılabilir adresler (kaynaklar + ayar sayfaları). */
    fun allUrls(): List<String> = buildList {
        (regulation + tokenFlow + minimalDataTips).forEach { c -> c.sources.forEach { add(it.url) } }
        platforms.forEach { p ->
            p.rows.forEach { r -> (r.required.sources + r.observed.sources).forEach { add(it.url) } }
            (p.dataFlow + p.appeal + listOfNotNull(p.nonEdevletOption, p.nonEdevletNote)).forEach { c -> c.sources.forEach { add(it.url) } }
            p.settings.forEach { s -> s.webUrl?.let(::add); s.evidence.sources.forEach { add(it.url) } }
            p.helpLinks.forEach { add(it.url) }
        }
    }.distinct()
}
