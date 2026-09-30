package net.atom.dpibypass.strategy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.atom.dpibypass.dns.DohResolver
import net.atom.dpibypass.engine.ByeDpiProxy
import net.atom.dpibypass.vpn.VodafoneModePolicy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Bir stratejinin test durumu (canlı UI için). */
data class StrategyTestResult(
    val strategy: Strategy,
    val state: State = State.Pending,
    val successCount: Int = 0,
    val totalTargets: Int = 0,
    val latencyMs: Long? = null,
) {
    enum class State { Pending, Testing, Success, Failed }
}

/**
 * Otomatik strateji test motoru — "her birini tek tek test ederek hangisinin
 * çalıştığını bul, ve gerçek interneti yavaşlatmadan en düşük ping'liyi seç".
 *
 * Puanlama: başarı (kaç hedef çalıştı) BİRİNCİL, gecikme (ping) İKİNCİL.
 * Böylece hem "çalışsın" hem "ping artmasın" gereksinimi karşılanır.
 *
 * ---------------------------------------------------------------------------
 * NEDEN ESKİDEN HEP P1 SEÇİLİYORDU
 * ---------------------------------------------------------------------------
 * Bağlanırken test "ilk TAM başarılı stratejide dur" (stopOnFirstFullSuccess)
 * kipinde çalışıyordu. Aday listesi ISS ailesine göre sıralı geldiği ve çoğu
 * ailede başta P1 bulunduğu için, P1 hedeflerde bir şekilde çalıştığı anda
 * yarış orada bitiyordu: P5 daha hızlı olsa bile ölçülmüyordu bile. Tanı
 * ekranı hepsini ölçtüğü için "test P5 diyor, bağlanınca P1 oluyor" çelişkisi
 * çıkıyordu. Erken çıkış kaldırıldı; hız artık ölçüm SÜRESİNİ sınırlayarak
 * (timeoutMs/budgetMs) ve hatırlanan kazananı önce doğrulayarak sağlanıyor
 * (bkz. [verify] ve DpiVpnService.resolvePlan).
 *
 * ByeDPI'ın native durumu tekildir (global `params`), bu yüzden stratejiler
 * PARALEL değil sırayla denenir; aynı anda iki motor örneği açılamaz.
 */
class StrategyTester(
    private val scope: CoroutineScope,
    private val doh: DohResolver,
    private val vodafoneMode: Boolean = false,
) {
    private val _results = MutableStateFlow<List<StrategyTestResult>>(emptyList())
    val results: StateFlow<List<StrategyTestResult>> = _results.asStateFlow()

    /**
     * @param strategies  test edilecek stratejiler (ISS'e göre sıralı verilebilir)
     * @param blockedHosts engellenen hedef domainler (ör. discord.com ...)
     * @param timeoutMs   tek bir hedefe TLS el sıkışması için üst sınır. Bağlanma
     *   yarışında kısa (elenen strateji hızlı elensin), tanı ekranında uzun
     *   (yavaş ama çalışan bir strateji yanlışlıkla "başarısız" görünmesin).
     * @param budgetMs    tüm yarış için üst sınır; 0 = sınırsız. Süre dolduğunda
     *   elde bir kazanan varsa yarış orada kesilir — bağlanma asla sürüncemede
     *   kalmaz. Kesilme hâlinde bile o ana kadarki EN İYİSİ döner, "sıradaki"
     *   değil.
     * @return en iyi çalışan strateji ve gecikmesi; hiçbiri çalışmazsa null
     */
    suspend fun run(
        strategies: List<Strategy>,
        blockedHosts: List<String>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        budgetMs: Long = 0L,
    ): Pair<Strategy, Long?>? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        _results.value = strategies.map {
            StrategyTestResult(it, StrategyTestResult.State.Pending, totalTargets = blockedHosts.size)
        }

        val resolved = resolve(blockedHosts)
        if (resolved.isEmpty()) {
            Log.w(TAG, "Hiçbir hedef DoH ile çözülemedi — test iptal")
            return@withContext null
        }

        var best: StrategyTestResult? = null

        for ((index, strategy) in strategies.withIndex()) {
            if (!isActive) break
            updateResult(index) { it.copy(state = StrategyTestResult.State.Testing) }

            val result = testOne(strategy, resolved, timeoutMs)
            updateResult(index) {
                it.copy(
                    state = if (result.successCount > 0) StrategyTestResult.State.Success
                    else StrategyTestResult.State.Failed,
                    successCount = result.successCount,
                    latencyMs = result.latencyMs,
                )
            }

            if (result.successCount > 0 && isBetter(result, best)) {
                best = result.copy(strategy = strategy)
            }

            if (budgetMs > 0L && best != null &&
                System.currentTimeMillis() - startedAt >= budgetMs
            ) {
                Log.i(TAG, "Süre bütçesi doldu; ${strategies.size - index - 1} aday denenmedi")
                break
            }
        }

        best?.let { it.strategy to it.latencyMs }
    }

    /**
     * Tek bir stratejiyi doğrular: TÜM hedeflerde çalışıyorsa gecikmesini, aksi
     * hâlde null döner. Bağlanmanın hızlı yolu budur — hatırlanan kazanan hâlâ
     * iş görüyorsa yarışa hiç girilmez.
     */
    suspend fun verify(
        strategy: Strategy,
        blockedHosts: List<String>,
        timeoutMs: Int = QUICK_TIMEOUT_MS,
    ): Long? = withContext(Dispatchers.IO) {
        val resolved = resolve(blockedHosts)
        if (resolved.isEmpty()) return@withContext null
        val result = testOne(strategy, resolved, timeoutMs)
        if (result.successCount >= resolved.size) result.latencyMs else null
    }

    /**
     * Hedefleri DoH ile PARALEL çöz (birbirinden bağımsız ağ çağrıları) — sıralı
     * çözümdeki gecikme toplanmaz, en yavaş sorgu kadar sürer.
     */
    private suspend fun resolve(hosts: List<String>): Map<String, String> = coroutineScope {
        hosts.map { host ->
            async(Dispatchers.IO) {
                val ip = doh.resolve(host).firstOrNull()
                if (ip == null) {
                    Log.w(TAG, "DoH ile çözülemedi: $host")
                    null
                } else host to ip
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /** Başarı birincil, gecikme ikincil kıyaslama. */
    private fun isBetter(candidate: StrategyTestResult, current: StrategyTestResult?): Boolean {
        if (current == null) return true
        if (candidate.successCount != current.successCount) {
            return candidate.successCount > current.successCount
        }
        val cLat = candidate.latencyMs ?: Long.MAX_VALUE
        val curLat = current.latencyMs ?: Long.MAX_VALUE
        return cLat < curLat
    }

    private suspend fun testOne(
        strategy: Strategy,
        resolved: Map<String, String>,
        timeoutMs: Int,
    ): StrategyTestResult {
        val port = freePort()
        val proxy = ByeDpiProxy()
        var job: Job? = null
        return try {
            job = scope.launch(Dispatchers.IO) {
                val args = VodafoneModePolicy.proxyArgs(strategy.toArgv(port), vodafoneMode)
                val code = proxy.startProxy(args)
                if (code != 0) Log.d(TAG, "${strategy.id} proxy çıkış kodu: $code")
            }

            if (!waitForPort(port)) {
                return StrategyTestResult(strategy, StrategyTestResult.State.Failed)
            }

            // Hedefleri tek ByeDPI örneği üzerinden PARALEL test et (SOCKS5 eşzamanlı
            // bağlantıları destekler). Başarısız bir strateji artık ~3s'de elenir,
            // 3 hedefi sıralı deneyen ~9s yerine.
            val latencies = coroutineScope {
                resolved.map { (host, ip) ->
                    async(Dispatchers.IO) {
                        Socks5TestClient.testTls(port, ip, host, timeoutMs = timeoutMs)
                    }
                }.awaitAll()
            }.filterNotNull()
            val success = latencies.size
            StrategyTestResult(
                strategy = strategy,
                state = if (success > 0) StrategyTestResult.State.Success else StrategyTestResult.State.Failed,
                successCount = success,
                totalTargets = resolved.size,
                latencyMs = latencies.minOrNull(),
            )
        } catch (e: Exception) {
            Log.d(TAG, "${strategy.id} test hatası: ${e.message}")
            StrategyTestResult(strategy, StrategyTestResult.State.Failed)
        } finally {
            try {
                proxy.stopProxy()
            } catch (_: Exception) {
            }
            job?.join()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** ByeDPI'ın portu dinlemeye başlamasını bekler. */
    private suspend fun waitForPort(port: Int, attempts: Int = 30): Boolean {
        repeat(attempts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 100)
                    return true
                }
            } catch (_: Exception) {
                delay(50)
            }
        }
        return false
    }

    private fun updateResult(index: Int, transform: (StrategyTestResult) -> StrategyTestResult) {
        _results.value = _results.value.toMutableList().also {
            if (index in it.indices) it[index] = transform(it[index])
        }
    }

    companion object {
        private const val TAG = "StrategyTester"

        /** Tanı ekranı: cömert; yavaş ama çalışan strateji elenmesin. */
        const val DEFAULT_TIMEOUT_MS = 3000

        /** Bağlanma yolu: kısa; çalışmayan aday saniyeler harcamasın. */
        const val QUICK_TIMEOUT_MS = 1500

        val DEFAULT_BLOCKED_HOSTS = listOf(
            "discord.com",
            "cdn.discordapp.com",
            "gateway.discord.gg",
        )
        const val CONTROL_HOST = "cloudflare.com"
    }
}
