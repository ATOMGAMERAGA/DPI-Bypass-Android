package net.atom.dpibypass.social

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException

/**
 * Resmî yüzeyi AÇIKÇA açar:
 *  - uygulama düğmesi niyeti platformun resmî paketine hedefler;
 *  - web düğmesi niyeti kullanıcının seçtiği tarayıcı paketine hedefler. Niyet
 *    belirli bir pakete gittiği için Android'in App Links doğrulaması onu native
 *    uygulamaya ÇEVİRMEZ (tarayıcının kendi "uygulamada aç" önerisi ayrı bir
 *    şeydir ve kullanıcı eylemi ister).
 * Kullanıcı aracısı, çerez ya da hesap verisi değiştirilmez/taşınmaz.
 */
object AccessLauncher {
    data class Browser(val packageName: String, val label: String)
    enum class LaunchResult { OPENED, NOT_INSTALLED, NO_BROWSER, FAILED }

    /** Tarayıcı keşfi için platformlara ait OLMAYAN genel bir adres. */
    private val probeUri: Uri = Uri.parse("https://example.com/")

    fun installedPackage(context: Context, platform: SocialPlatform): String? =
        platform.packages.firstOrNull { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess }

    /** Genel https adresini açabilen tarayıcılar; sosyal platform uygulamaları ve bu uygulama hariç. */
    fun browsers(context: Context): List<Browser> {
        val pm = context.packageManager
        val excluded = SocialPlatform.entries.flatMap { it.packages }.toSet() + context.packageName
        val intent = Intent(Intent.ACTION_VIEW, probeUri).addCategory(Intent.CATEGORY_BROWSABLE)
        return runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }.distinct().filter { it !in excluded }
            .map { pkg -> Browser(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Sistemin varsayılan tarayıcısı (seçim ekranı/"android" ise null). */
    fun defaultBrowser(context: Context): String? {
        val resolved = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, probeUri).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()?.activityInfo?.packageName ?: return null
        return resolved.takeIf { pkg -> browsers(context).any { it.packageName == pkg } }
    }

    /** Seçilen tarayıcı kurulu değilse varsayılana, o da yoksa ilk tarayıcıya düşer; hiçbiri yoksa null. */
    fun effectiveBrowser(context: Context, chosen: String?): String? {
        val all = browsers(context)
        return chosen?.takeIf { c -> all.any { it.packageName == c } } ?: defaultBrowser(context) ?: all.firstOrNull()?.packageName
    }

    fun openInApp(context: Context, platform: SocialPlatform, url: String?): LaunchResult {
        val pkg = installedPackage(context, platform) ?: return LaunchResult.NOT_INSTALLED
        val view = url?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (view != null) try { context.startActivity(view); return LaunchResult.OPENED } catch (_: ActivityNotFoundException) { /* uygulama bu bağlantıyı almıyor */ }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return LaunchResult.FAILED
        return try { context.startActivity(launch); LaunchResult.OPENED } catch (_: Exception) { LaunchResult.FAILED }
    }

    fun openInBrowser(context: Context, browserPackage: String?, url: String): LaunchResult {
        val pkg = browserPackage ?: return LaunchResult.NO_BROWSER
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); LaunchResult.OPENED } catch (_: ActivityNotFoundException) { LaunchResult.NO_BROWSER } catch (_: Exception) { LaunchResult.FAILED }
    }

    /**
     * İstenen yüzeyi açar; başarısızlıkta BAŞKA bir yüzeye düşmez. Eskiden son çare
     * olarak hedefsiz bir `ACTION_VIEW` gönderiliyordu: App Links bunu native
     * uygulamaya verebilir ve tarayıcı denemesi uygulamada yapılmış olurdu.
     */
    fun open(context: Context, surface: AccessSurface, platform: SocialPlatform?, url: String, browserPackage: String?): LaunchResult = when (surface) {
        AccessSurface.OFFICIAL_APP -> platform?.let { openInApp(context, it, url) } ?: LaunchResult.NOT_INSTALLED
        AccessSurface.OFFICIAL_WEB -> openInBrowser(context, browserPackage, url)
    }
}

/**
 * Erişim yolu kayıtlarının uygulama-özel, yedeklenmeyen deposu. Bu sürümün
 * okuyamadığı (daha yeni ya da bozuk) dosya SESSİZCE ezilmez: bir kez yanına
 * yedeklenir ve kullanıcıya bildirilir.
 */
class AccessRouteStore(context: Context) {
    private val dir = context.noBackupFilesDir
    private val file = AtomicFile(File(dir, "social-access-routes.json"))
    private val mutable = MutableStateFlow(RouteLedger())
    val ledger: StateFlow<RouteLedger> = mutable.asStateFlow()
    @Volatile var recoveredUnreadable = false; private set
    /**
     * Okunamayan eski dosya yedeklenemediyse kayıt dosyasına YAZILMAZ: yoksa
     * kullanıcının (belki daha yeni sürümün) verisi sessizce ezilirdi.
     */
    @Volatile var writeBlocked = false; private set
    @Volatile private var loaded = false

    @Synchronized fun load() {
        if (loaded) return
        val raw = try { file.openRead().bufferedReader().use { it.readText() } } catch (_: FileNotFoundException) { null } catch (_: Exception) { "" }
        val decoded = AccessRoutes.decode(raw)
        if (!decoded.readable && !raw.isNullOrEmpty()) {
            val backup = File(dir, "social-access-routes.unreadable.json")
            val saved = backup.exists() || runCatching { backup.writeText(raw) }.isSuccess
            recoveredUnreadable = true
            writeBlocked = !saved
        }
        mutable.value = decoded.ledger
        loaded = true
    }

    /** Bağlam [current] ile KABUL ANINDA karşılaştırılır; yazma hatası ayrı sonuçtur. */
    @Synchronized fun accept(report: RouteReport, current: RouteContext?, nowMs: Long): RouteSave {
        load()
        val (next, result) = AccessRoutes.commit(mutable.value, report, current, nowMs) { save(it) }
        mutable.value = next
        return result
    }

    @Synchronized fun setAccountAge(platformId: String, age: AccountAge): Boolean {
        load()
        return runCatching { save(AccessRoutes.setAccountAge(mutable.value, platformId, age)) }.isSuccess
    }

    @Synchronized fun clear(): Boolean { load(); return runCatching { save(RouteLedger(accountAge = mutable.value.accountAge)) }.isSuccess }

    private fun save(next: RouteLedger) {
        check(!writeBlocked) { "UNREADABLE_FILE_NOT_BACKED_UP" }
        val stream = file.startWrite()
        try { stream.write(AccessRoutes.encode(next).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = next
    }
}
