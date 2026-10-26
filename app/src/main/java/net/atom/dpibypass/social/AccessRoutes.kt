package net.atom.dpibypass.social

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

// ---------------------------------------------------------------------------
// İşlem bazında doğrulanmış erişim yolu.
//
// Fikir: aynı hesap ve aynı işlem için resmî uygulama ile resmî web (açıkça seçilen
// tarayıcı) farklı davranabilir. Hangi işlemin hangi resmî yüzeyde, hangi ağda ve
// hangi yerel profille ÇALIŞTIĞINI kullanıcının kendi bildirimleriyle tutar ve
// güncel kanıtı olan yüzeyi tek dokunuşla açtırır.
//
// Bu bir aşma yöntemi değildir: sunucu tarafındaki yaş/kimlik doğrulama kaydını
// değiştirmez, ekran gizlemez, istemci bayrağı yazmaz, kullanıcı aracısı/çerez
// değiştirmez. Bütün yüzeyler aynı sunucu şartıyla engelleniyorsa bunu
// [RouteVerdict.ALL_SURFACES_GATED] olarak AÇIKÇA söyler.
// ---------------------------------------------------------------------------

/**
 * Ayrı değerlendirilen işlevler. Biri diğerinin başarısı sayılmaz.
 * [capability]: deneme hedefinin ([Goal.requiredCapabilities]) gerçek işlev adı;
 * otomatik gözlemci yalnız tanıdığı işlevlerde karar verir, diğerleri kullanıcıya sorulur.
 */
enum class AccessAction(val label: String, val needsAccount: Boolean, val capability: String) {
    VIEW_CONTENT("İçerik görüntüleme", false, "VIEW_SELECTED_CONTENT"),
    PLAY_MEDIA("Video / medya", false, "PLAY_MEDIA"),
    ACCOUNT_ACCESS("Hesabıma erişim (akış, profil)", true, "VIEW_ACCOUNT_FEED"),
    MESSAGING("Mesajlaşma", true, "SEND_MESSAGE"),
    POSTING("Paylaşım", true, "POST_CONTENT"),
    VOICE_CALL("Sesli görüşme", true, "VOICE_CALL");

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** Resmî yüzeyler. Anonim test penceresi bilinçli olarak burada YOK: ayrı tutulur. */
enum class AccessSurface(val label: String) {
    OFFICIAL_APP("Resmî uygulama"),
    OFFICIAL_WEB("Resmî web (seçilen tarayıcı)");

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** Kullanıcının o yüzeyde gördüğü hesap bağlamı (hesap kimliği SAKLANMAZ). */
enum class AccountContext(val label: String) {
    SAME_ACCOUNT_CONFIRMED("Kendi hesabımla açıktı"),
    LOGGED_OUT("Oturum açık değildi"),
    UNKNOWN("Emin değilim");

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/** Kullanıcının dönüşte seçtiği kısa sonuç. */
enum class RouteOutcome(val label: String) {
    WORKS("Çalışıyor"),
    PARTIAL("Kısmen"),
    NOT_WORKING("Çalışmıyor"),
    VERIFICATION_REQUIRED("Doğrulama istiyor"),
    UNSURE("Emin değilim");

    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

/**
 * Bildirimin alındığı bağlam. Hassas veri yok: [networkKey] taşıyıcı + ISS ailesi
 * (SSID/IP yok), [profileKey] motorun plan/DNS/QUIC/kapsam ayarlarının özeti,
 * [processId] her uygulama sürecinde yeni rastgele kimlik.
 * Motor TEK ve bütün uygulamalara ortak olduğu için profil değişimi bütün
 * platformların kayıtlarını bayatlatır.
 */
data class RouteContext(val networkKey: String, val profileKey: String, val processId: String)

/**
 * Tek kullanıcı bildirimi. Değişmezdir: sonradan yapılan ağ kontrolü bir
 * bildirimi "yenileyemez" ya da zamanını güncelleyemez.
 *  - [routePath]: o yüzeyin paketi bildirim anında tünelde miydi (TUNNEL) yoksa
 *    doğrudan mı (DIRECT; kapsam dışı ya da bağlantı kapalı).
 *  - [networkAtReport]: bildirimle AYNI ANDA, aynı güzergâhtan yapılan tek ağ
 *    yolu ölçümü (PASS/FAIL/UNKNOWN/NOT_MEASURED) — doğrulama ekranının ağ
 *    sorunundan mı sunucu şartından mı geldiğini ayırmak için.
 *  - [freshStart]: kullanıcı uygulamayı yeniden başlatıp YENİ bir içerik denediğini
 *    bildirdi (önbellek/açık oturum etkisini ayıklamak için).
 */
data class RouteReport(val platformId: String, val action: AccessAction, val surface: AccessSurface, val browserPackage: String?,
    val account: AccountContext, val outcome: RouteOutcome, val context: RouteContext, val routePath: String,
    val networkAtReport: String, val networkFailureClass: String, val freshStart: Boolean, val atMs: Long,
    val uiVersion: Int = AccessRoutes.UI_VERSION)

/** Bildirimin kabul edilmeme nedenleri; hiçbiri başarıya çevrilmez. */
enum class RouteRejection { STALE_UI, UNKNOWN_PLATFORM, ACTION_NOT_SUPPORTED, SURFACE_NOT_SUPPORTED, BROWSER_REQUIRED, CONTEXT_CHANGED, FUTURE_TIME }

/**
 * Bir bildirimin kaydedilme sonucu. Reddedilen bildirim ile diske yazılamayan
 * bildirim AYRI tutulur: yazma hatası "ağ değişti" diye gösterilmez.
 */
sealed interface RouteSave {
    data object Saved : RouteSave
    data class Rejected(val reason: RouteRejection) : RouteSave
    /** Bildirim geçerliydi ama kayıt dosyasına yazılamadı (disk/izin/okunamayan eski dosya). */
    data object WriteFailed : RouteSave
}

/** Hesabın yönetmelik yürürlüğüne göre ne zaman açıldığına dair kullanıcı beyanı (hesap kimliği yok). */
enum class AccountAge { BEFORE_REGULATION, AFTER_REGULATION, UNKNOWN }

enum class RouteStatus { VERIFIED, SINGLE_CONFIRMATION, PARTIAL, NOT_WORKING, PLATFORM_GATE, UNSURE, PREVIOUS_SESSION, NOT_TESTED }

/**
 * Doğrulama ekranı görüldüğü andaki tek ağ ölçümünün söylediği. Kesin kök neden
 * DEĞİLDİR: kontrol adresine ulaşılması platformun bütün hesap/doğrulama uç
 * noktalarının çalıştığını, sonuçsuz ölçüm de kesin ağ arızasını göstermez.
 *  - [CHECK_ADDRESS_REACHABLE]: kontrol adresine ulaşıldı; istek büyük olasılıkla platformdan
 *  - [NETWORK_PROBLEM_SUSPECTED]: ölçülebilir bağlantı hatası vardı
 *  - [NETWORK_UNCERTAIN]: ölçüm sonuçsuz kaldı (zaman aşımı, sunucu hatası, hız sınırı)
 *  - [NETWORK_NOT_MEASURED]: ölçüm yapılamadı
 */
enum class GateCause { CHECK_ADDRESS_REACHABLE, NETWORK_PROBLEM_SUSPECTED, NETWORK_UNCERTAIN, NETWORK_NOT_MEASURED }

data class SurfaceAssessment(val surface: AccessSurface, val browserPackage: String?, val status: RouteStatus,
    val confirmations: Int, val lastAtMs: Long, val account: AccountContext?, val gateCause: GateCause? = null, val routePath: String = "")

enum class RouteVerdict { VERIFIED_ROUTE, TENTATIVE_ROUTE, ALL_SURFACES_GATED, NO_WORKING_ROUTE, NEEDS_REVERIFICATION, NOT_TESTED }

/**
 * [untested]: platformun bu işlemde desteklediği ama bu bağlamda henüz kesin
 * sonucu olmayan yüzeyler. Boş değilse "bütün yollar kapalı" denmez.
 */
data class RouteDecision(val platformId: String, val action: AccessAction, val verdict: RouteVerdict,
    val recommended: SurfaceAssessment?, val surfaces: List<SurfaceAssessment>, val explanation: String,
    val untested: List<AccessSurface> = emptyList())

/** Önerilen yolun nasıl açılacağı. Kanıt hangi tarayıcıda alındıysa yalnız o tarayıcı açılır. */
sealed interface RouteLaunch {
    data object App : RouteLaunch
    data class Browser(val packageName: String) : RouteLaunch
    /** Kanıtın alındığı tarayıcı artık yok: başka tarayıcıya kanıt TAŞINMAZ, yeniden denenmelidir. */
    data class BrowserMissing(val packageName: String?) : RouteLaunch
}

/** Değişmez kayıt defteri; bütün geçişler saf fonksiyonlardır (JVM'de sınanır). */
data class RouteLedger(val reports: List<RouteReport> = emptyList(), val accountAge: Map<String, AccountAge> = emptyMap())

object AccessRoutes {
    /** Bildirim ekranının sürümü. Eski arayüzden (eski sürüm/eski Activity) gelen rapor reddedilir. */
    const val UI_VERSION = 1
    const val SCHEMA = 1
    /** "Doğrulandı" için iki onay arasında gereken en az süre (aynı ağ + profil). */
    const val REPEAT_GAP_MS = 10 * 60_000L
    const val MAX_REPORTS = 200
    const val RETENTION_MS = 30L * 24 * 60 * 60_000

    /**
     * Resmî Gazete 10.10.2026 / 33396 — "Sosyal Ağ Sağlayıcı Tarafından Çocuklara
     * Özgü Ayrıştırılmış Hizmet Sunulmasına ve Yaş Doğrulamaya İlişkin Yönetmelik":
     * yürürlük 1/11/2026 (Madde 13), mevcut hesaplar için son tarih 31/3/2027
     * (Geçici Madde 1). Platformların bunu hangi gün, hangi istemcide ve nasıl
     * uygulayacağı yönetmelikte yazmaz; burada varsayılmaz.
     */
    val REGULATION_EFFECTIVE: LocalDate = LocalDate.of(2026, 11, 1)
    val EXISTING_ACCOUNT_DEADLINE: LocalDate = LocalDate.of(2027, 3, 31)
    const val REGULATION_SOURCE = "Resmî Gazete 10.10.2026, sayı 33396"

    /** Platform başına anlamlı işlevler (katalogdaki yeteneklerden; özel alan adı yok). */
    fun actions(platform: SocialPlatform): List<AccessAction> = when (platform) {
        SocialPlatform.CUSTOM -> emptyList()
        else -> buildList {
            add(AccessAction.ACCOUNT_ACCESS)
            if (platform.mode(Capability.CONTENT) != CheckMode.UNSUPPORTED) add(AccessAction.VIEW_CONTENT)
            if (platform.mode(Capability.MEDIA) != CheckMode.UNSUPPORTED) add(AccessAction.PLAY_MEDIA)
            if (platform.id in MessagingPlatforms) add(AccessAction.MESSAGING)
            if (platform.id in PostingPlatforms) add(AccessAction.POSTING)
            if (platform.mode(Capability.VOICE) != CheckMode.UNSUPPORTED) add(AccessAction.VOICE_CALL)
        }
    }
    private val MessagingPlatforms = setOf("instagram", "x", "tiktok", "discord", "reddit", "facebook", "telegram", "whatsapp", "signal", "snapchat")
    private val PostingPlatforms = setOf("youtube", "instagram", "x", "tiktok", "discord", "reddit", "facebook", "twitch", "snapchat")

    /**
     * Telefon tarayıcısında kullanılabilir resmî web istemcisi olmadığı için web
     * yolu ÖNERİLMEYEN platformlar (Signal'in web istemcisi yok; WhatsApp ve
     * Snapchat'in web istemcileri telefonda uygulamaya yönlendirir). Bu ürün
     * tarafında temkinli bir seçimdir; sahada sınanmadı. Sesli görüşme web
     * yolunda hiç önerilmez.
     */
    private val NoMobileWeb = setOf("signal", "whatsapp", "snapchat")

    /** Bu işlem için denenmesi anlamlı resmî yüzeyler. Desteklenmeyen web işlemini kullanıcıya denettirmeyiz. */
    fun surfaces(platform: SocialPlatform, action: AccessAction): Set<AccessSurface> = when {
        action !in actions(platform) -> emptySet()
        platform.id in NoMobileWeb || action == AccessAction.VOICE_CALL -> setOf(AccessSurface.OFFICIAL_APP)
        else -> setOf(AccessSurface.OFFICIAL_APP, AccessSurface.OFFICIAL_WEB)
    }

    /** Önerilen yolun açılışı: web kanıtı yalnız kendi tarayıcısında geçerlidir. */
    fun launchFor(rec: SurfaceAssessment, installedBrowsers: Set<String>): RouteLaunch = when (rec.surface) {
        AccessSurface.OFFICIAL_APP -> RouteLaunch.App
        AccessSurface.OFFICIAL_WEB -> rec.browserPackage?.takeIf { it in installedBrowsers }?.let { RouteLaunch.Browser(it) }
            ?: RouteLaunch.BrowserMissing(rec.browserPackage)
    }

    /** Doğrulanmış kabul: geçersiz rapor sessizce başarıya dönüşmez, nedeniyle reddedilir. */
    fun validate(r: RouteReport, current: RouteContext, nowMs: Long): RouteRejection? {
        if (r.uiVersion != UI_VERSION) return RouteRejection.STALE_UI
        val platform = SocialPlatform.byId(r.platformId)?.takeIf { it != SocialPlatform.CUSTOM } ?: return RouteRejection.UNKNOWN_PLATFORM
        if (r.action !in actions(platform)) return RouteRejection.ACTION_NOT_SUPPORTED
        if (r.surface !in surfaces(platform, r.action)) return RouteRejection.SURFACE_NOT_SUPPORTED
        if (r.surface == AccessSurface.OFFICIAL_WEB && r.browserPackage.isNullOrBlank()) return RouteRejection.BROWSER_REQUIRED
        if (r.context != current) return RouteRejection.CONTEXT_CHANGED
        if (r.atMs > nowMs + 60_000) return RouteRejection.FUTURE_TIME
        return null
    }

    fun accept(ledger: RouteLedger, r: RouteReport, current: RouteContext, nowMs: Long): Pair<RouteLedger, RouteRejection?> {
        validate(r, current, nowMs)?.let { return ledger to it }
        // Web yüzeyinde paket adı yalnız tarayıcı seçimi içindir; uygulamada tutulmaz.
        val clean = if (r.surface == AccessSurface.OFFICIAL_APP) r.copy(browserPackage = null) else r
        val kept = (ledger.reports + clean).filter { nowMs - it.atMs in 0..RETENTION_MS }.takeLast(MAX_REPORTS)
        return ledger.copy(reports = kept) to null
    }

    /**
     * Kabul + yazma, tek adımda. Bağlam KABUL ANINDA ([current]) karşılaştırılır:
     * ölçüm sürerken ağ/profil değiştiyse bildirim eski bağlama yazılmaz. [write]
     * hata verirse kayıt değişmez ve [RouteSave.WriteFailed] döner.
     */
    fun commit(ledger: RouteLedger, r: RouteReport, current: RouteContext?, nowMs: Long, write: (RouteLedger) -> Unit): Pair<RouteLedger, RouteSave> {
        if (current == null) return ledger to RouteSave.Rejected(RouteRejection.CONTEXT_CHANGED)
        val (next, rejection) = accept(ledger, r, current, nowMs)
        if (rejection != null) return ledger to RouteSave.Rejected(rejection)
        return try { write(next); next to RouteSave.Saved } catch (_: Exception) { ledger to RouteSave.WriteFailed }
    }

    /**
     * Bir kullanıcı bildirimini kaydeder: bağlam önce (boşuna ölçüm yapılmasın) ve
     * KABUL ANINDA ([current] o anki bağlamı verir) denetlenir; arada [measure] ile
     * o yüzeyin güzergâhından tek bir ağ ölçümü alınır. [accept] kaydın kendisidir
     * (uygulamada dosya, testte bellek).
     */
    suspend fun record(platform: SocialPlatform, action: AccessAction, surface: AccessSurface, browser: String?, account: AccountContext,
        outcome: RouteOutcome, freshStart: Boolean, routePath: String, opened: RouteContext, current: () -> RouteContext?, nowMs: () -> Long,
        measure: suspend () -> NetworkEvidence?, accept: (RouteReport, RouteContext?, Long) -> RouteSave): RouteSave {
        if (current() != opened) return RouteSave.Rejected(RouteRejection.CONTEXT_CHANGED)
        val evidence = measure()
        val network = when (evidence?.technicalOutcome) { null -> "NOT_MEASURED"; "PASS" -> "PASS"; "FAIL" -> "FAIL"; else -> "UNKNOWN" }
        val now = nowMs()
        return accept(RouteReport(platform.id, action, surface, browser, account, outcome, opened, routePath, network,
            evidence?.failureClass.orEmpty(), freshStart, now), current(), now)
    }

    fun setAccountAge(ledger: RouteLedger, platformId: String, age: AccountAge): RouteLedger =
        if (SocialPlatform.byId(platformId) == null) ledger else ledger.copy(accountAge = ledger.accountAge + (platformId to age))

    /**
     * Bir platform + işlem için yüzey değerlendirmesi ve öneri.
     * Yalnız AYNI ağ + AYNI profil bağlamındaki bildirimler kullanılır; farklı süreçte
     * alınmışsa "önceki oturum" olur ve yeşil gösterilmez. En son bildirim kazanır:
     * sonradan gelen "Çalışmıyor" eski "Çalışıyor"u geçersiz kılar.
     */
    fun decide(ledger: RouteLedger, platformId: String, action: AccessAction, current: RouteContext): RouteDecision {
        val relevant = ledger.reports.filter { it.platformId == platformId && it.action == action }
        val sameNetwork = relevant.filter { it.context.networkKey == current.networkKey && it.context.profileKey == current.profileKey }
        val surfaces = sameNetwork.groupBy { it.surface to it.browserPackage }.map { (key, list) -> assess(key.first, key.second, list.sortedBy { it.atMs }, action, current) }
            .sortedWith(compareBy({ rank(it.status) }, { -it.lastAtMs }))
        val olderContexts = relevant.size - sameNetwork.size
        val recommended = surfaces.firstOrNull { it.status == RouteStatus.VERIFIED } ?: surfaces.firstOrNull { it.status == RouteStatus.SINGLE_CONFIRMATION || it.status == RouteStatus.PARTIAL }
        val tested = surfaces.filter { it.status != RouteStatus.PREVIOUS_SESSION && it.status != RouteStatus.UNSURE }
        // "Bütün yollar" yalnız platformun bu işlemde desteklediği HER yüzey bu bağlamda
        // kesin sonuç verdiyse söylenebilir; denenmemiş desteklenen yüzey sonraki adımdır.
        val supported = SocialPlatform.byId(platformId)?.let { surfaces(it, action) }.orEmpty()
        val untested = AccessSurface.entries.filter { s -> s in supported && tested.none { it.surface == s } }
        val verdict = when {
            surfaces.any { it.status == RouteStatus.VERIFIED } -> RouteVerdict.VERIFIED_ROUTE
            recommended != null -> RouteVerdict.TENTATIVE_ROUTE
            tested.isNotEmpty() && untested.isEmpty() &&
                tested.all { it.status == RouteStatus.PLATFORM_GATE && it.gateCause == GateCause.CHECK_ADDRESS_REACHABLE } -> RouteVerdict.ALL_SURFACES_GATED
            tested.isNotEmpty() -> RouteVerdict.NO_WORKING_ROUTE
            surfaces.any { it.status == RouteStatus.PREVIOUS_SESSION } || olderContexts > 0 -> RouteVerdict.NEEDS_REVERIFICATION
            else -> RouteVerdict.NOT_TESTED
        }
        return RouteDecision(platformId, action, verdict, recommended, surfaces, explain(verdict, recommended, surfaces, untested, action), untested)
    }

    private fun rank(s: RouteStatus) = when (s) {
        RouteStatus.VERIFIED -> 0; RouteStatus.SINGLE_CONFIRMATION -> 1; RouteStatus.PARTIAL -> 2; RouteStatus.PREVIOUS_SESSION -> 3
        RouteStatus.UNSURE -> 4; RouteStatus.PLATFORM_GATE -> 5; RouteStatus.NOT_WORKING -> 6; RouteStatus.NOT_TESTED -> 7
    }

    private fun assess(surface: AccessSurface, browser: String?, reports: List<RouteReport>, action: AccessAction, current: RouteContext): SurfaceAssessment {
        val inProcess = reports.filter { it.context.processId == current.processId }
        val last = (inProcess.lastOrNull() ?: reports.last())
        if (inProcess.isEmpty()) return SurfaceAssessment(surface, browser, RouteStatus.PREVIOUS_SESSION, 0, last.atMs, last.account, routePath = last.routePath)
        // En son bildirimden sonraki ardışık "Çalışıyor" serisi (araya başka sonuç girerse seri kopar).
        val streak = inProcess.takeLastWhile { it.outcome == RouteOutcome.WORKS }
        // Hesap gerektiren işlevde yalnız "kendi hesabımla" teyitli bildirim sayılır.
        val counted = streak.filter { !action.needsAccount || it.account == AccountContext.SAME_ACCOUNT_CONFIRMED }
        val status = when (last.outcome) {
            RouteOutcome.WORKS -> when {
                counted.isEmpty() -> RouteStatus.UNSURE
                counted.last().atMs - counted.first().atMs >= REPEAT_GAP_MS && counted.size >= 2 && counted.any { it.freshStart } -> RouteStatus.VERIFIED
                else -> RouteStatus.SINGLE_CONFIRMATION
            }
            RouteOutcome.PARTIAL -> RouteStatus.PARTIAL
            RouteOutcome.NOT_WORKING -> RouteStatus.NOT_WORKING
            RouteOutcome.VERIFICATION_REQUIRED -> RouteStatus.PLATFORM_GATE
            RouteOutcome.UNSURE -> RouteStatus.UNSURE
        }
        val cause = if (status != RouteStatus.PLATFORM_GATE) null else when (last.networkAtReport) {
            "PASS" -> GateCause.CHECK_ADDRESS_REACHABLE
            "FAIL" -> GateCause.NETWORK_PROBLEM_SUSPECTED
            "UNKNOWN" -> GateCause.NETWORK_UNCERTAIN
            else -> GateCause.NETWORK_NOT_MEASURED
        }
        return SurfaceAssessment(surface, browser, status, counted.size, last.atMs, last.account, cause, last.routePath)
    }

    private fun explain(v: RouteVerdict, rec: SurfaceAssessment?, all: List<SurfaceAssessment>, untested: List<AccessSurface>, action: AccessAction): String {
        val untestedText = if (untested.isEmpty()) "" else " Henüz denenmeyen yol: ${untested.joinToString { it.label }}."
        val identity = rec?.account?.let { if (it == AccountContext.LOGGED_OUT) " (girişsiz; hesabın değil)" else "" }.orEmpty()
        return when (v) {
            RouteVerdict.VERIFIED_ROUTE -> "${rec!!.surface.label}$identity bu ağda ve bu ayarla ${rec.confirmations} ayrı bildirimle çalıştı."
            RouteVerdict.TENTATIVE_ROUTE -> "${rec!!.surface.label}$identity: " + (if (rec.status == RouteStatus.PARTIAL) "kısmen çalıştı." else
                "ilk denemede çalıştı. İstersen en az 10 dakika sonra, uygulamayı yeniden başlatıp yeni bir içerikle bir kez daha kontrol edebilirsin.") +
                if (action.needsAccount && rec.account != AccountContext.SAME_ACCOUNT_CONFIRMED) " Kendi hesabınla açık olduğunu teyit etmedin." else ""
            RouteVerdict.ALL_SURFACES_GATED -> "Desteklenen bütün resmî yollarda (uygulama ve tarayıcı) platform doğrulama istedi; o sırada kontrol adresine ulaşılabiliyordu. " +
                "Bu büyük olasılıkla platformun hesap şartıdır ve yerel bağlantı ayarı bunu değiştiremez. Kontrol adresi platformun bütün sunucularını kapsamadığı için kesin değildir."
            RouteVerdict.NO_WORKING_ROUTE -> buildString {
                append("Bu işlem için çalışan bir yol bildirilmedi.")
                if (all.any { it.gateCause == GateCause.NETWORK_PROBLEM_SUSPECTED })
                    append(" Doğrulama/yükleme ekranı görüldüğü anda bağlantı hatası ölçüldü: önce bağlantı yöntemlerini dene, sonra aynı işlemi yeniden kontrol et.")
                if (all.any { it.gateCause == GateCause.NETWORK_UNCERTAIN }) append(" Doğrulama anındaki ağ ölçümü sonuçsuz kaldı; sebep ağ mı platform mu belli değil.")
                if (all.any { it.gateCause == GateCause.NETWORK_NOT_MEASURED }) append(" Doğrulama anında ağ ölçülemedi; sebep ağ mı platform mu belli değil.")
                append(untestedText)
            }
            RouteVerdict.NEEDS_REVERIFICATION -> "Önceki bildirimler başka bir ağda, ayarda ya da uygulama oturumunda alındı; burada yeniden kontrol gerekiyor."
            RouteVerdict.NOT_TESTED -> "Henüz denenmedi."
        }
    }

    /**
     * Hipotez 3 (oturum/geçiş dönemi): erişimin bu uygulamanın etkisi mi, mevcut
     * hesap geçiş süresi mi olduğunu ayırmak için gösterilen not. Yalnız resmî
     * metnin doğruladığı tarihleri kullanır.
     */
    fun transitionNote(age: AccountAge?, today: LocalDate): String? = when {
        today.isBefore(REGULATION_EFFECTIVE) -> "Yaş doğrulama yönetmeliği ${REGULATION_EFFECTIVE.tr()} tarihinde yürürlüğe giriyor ($REGULATION_SOURCE). " +
            "Şu anki erişimin o tarihten sonrası için kanıt değildir."
        age == AccountAge.BEFORE_REGULATION && !today.isAfter(EXISTING_ACCOUNT_DEADLINE) ->
            "Yönetmeliğe göre mevcut hesapların doğrulaması ${EXISTING_ACCOUNT_DEADLINE.tr()} tarihine kadar yapılmalı (Geçici Madde 1). " +
                "Şu an erişebiliyor olman bu geçiş süresinden kaynaklanabilir; bu uygulamanın etkisi değildir."
        age == AccountAge.AFTER_REGULATION -> "Yönetmeliğe göre yeni hesapta yaş doğrulaması hesap açılışında istenir (Madde 6/2). " +
            "Yerel ağ ayarı bu sunucu şartını değiştirmez."
        else -> null
    }

    private fun LocalDate.tr() = "%02d.%02d.%d".format(dayOfMonth, monthValue, year)
    fun today(nowMs: Long): LocalDate = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Europe/Istanbul")).toLocalDate()

    // ---- Kalıcılık: sürümlü JSON. Bozuk/bilinmeyen kayıt atlanır, başarıya çevrilmez. ----

    fun encode(ledger: RouteLedger): JSONObject = JSONObject().put("schemaVersion", SCHEMA)
        .put("accountAge", JSONObject(ledger.accountAge.mapValues { it.value.name }))
        .put("reports", JSONArray(ledger.reports.map { r -> JSONObject().put("platformId", r.platformId).put("action", r.action.name)
            .put("surface", r.surface.name).put("browserPackage", r.browserPackage ?: "").put("account", r.account.name)
            .put("outcome", r.outcome.name).put("networkKey", r.context.networkKey).put("profileKey", r.context.profileKey)
            .put("processId", r.context.processId).put("routePath", r.routePath).put("networkAtReport", r.networkAtReport)
            .put("networkFailureClass", r.networkFailureClass).put("freshStart", r.freshStart).put("atMs", r.atMs).put("uiVersion", r.uiVersion) }))

    /** Okuma sonucu: [readable] false ise dosya bu sürümce anlaşılmadı; üzerine yazılmadan yedeklenmeli. */
    data class Decoded(val ledger: RouteLedger, val readable: Boolean, val dropped: Int)

    fun decode(raw: String?): Decoded {
        if (raw.isNullOrBlank()) return Decoded(RouteLedger(), readable = true, dropped = 0)
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return Decoded(RouteLedger(), readable = false, dropped = 0)
        if (j.optInt("schemaVersion", -1) != SCHEMA) return Decoded(RouteLedger(), readable = false, dropped = 0)
        val array = j.optJSONArray("reports") ?: JSONArray()
        var dropped = 0
        val reports = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i)
            val r = o?.let { decodeReport(it) }
            if (r == null) dropped++
            r
        }
        val ages = j.optJSONObject("accountAge")?.let { a -> a.keys().asSequence().mapNotNull { k ->
            val v = AccountAge.entries.firstOrNull { it.name == a.optString(k) }
            if (SocialPlatform.byId(k) != null && v != null) k to v else null }.toMap() }.orEmpty()
        return Decoded(RouteLedger(reports, ages), readable = true, dropped = dropped)
    }

    private fun decodeReport(o: JSONObject): RouteReport? {
        val platform = o.optString("platformId").takeIf { SocialPlatform.byId(it) != null } ?: return null
        val action = AccessAction.parse(o.optString("action")) ?: return null
        val surface = AccessSurface.parse(o.optString("surface")) ?: return null
        val account = AccountContext.parse(o.optString("account")) ?: return null
        val outcome = RouteOutcome.parse(o.optString("outcome")) ?: return null
        val network = o.optString("networkAtReport").takeIf { it in setOf("PASS", "FAIL", "UNKNOWN", "NOT_MEASURED") } ?: return null
        val at = o.opt("atMs") as? Number ?: return null
        if (o.opt("freshStart") !is Boolean) return null
        val ctx = RouteContext(o.optString("networkKey"), o.optString("profileKey"), o.optString("processId"))
        if (ctx.networkKey.isBlank() || ctx.profileKey.isBlank() || ctx.processId.isBlank()) return null
        return RouteReport(platform, action, surface, o.optString("browserPackage").ifBlank { null }, account, outcome, ctx,
            o.optString("routePath").takeIf { it in setOf("TUNNEL", "DIRECT") } ?: return null, network, o.optString("networkFailureClass"),
            o.getBoolean("freshStart"), at.toLong(), o.optInt("uiVersion", -1))
    }

    /**
     * Maskeli tanılama: işlev/yüzey/sonuç sayıları ve gün. Ağ anahtarı yalnız
     * taşıyıcı türüne indirgenir; süreç kimliği, tarayıcı paketi, bağlantı yoktur.
     */
    fun diagnostic(ledger: RouteLedger, nowMs: Long): JSONArray = JSONArray(ledger.reports.map { r ->
        JSONObject().put("platform", r.platformId).put("action", r.action.name).put("surface", r.surface.name).put("account", r.account.name)
            .put("outcome", r.outcome.name).put("networkAtReport", r.networkAtReport).put("routePath", r.routePath)
            .put("transport", r.context.networkKey.substringBefore(':')).put("freshStart", r.freshStart)
            .put("ageDays", ((nowMs - r.atMs) / 86_400_000L).coerceAtLeast(0))
    })
}
