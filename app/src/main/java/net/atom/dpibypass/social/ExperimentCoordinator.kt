package net.atom.dpibypass.social

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Bir ağ yolu ölçümünün sonucu. [technicalOutcome]: PASS = doğrulanmış TLS üzerinden
 * HTTP yanıtı alındı (yalnız ağ yolu), FAIL = ölçülebilir bağlantı hatası,
 * UNKNOWN = kesin olmayan (zaman aşımı, sunucu hatası, hız sınırı).
 */
data class NetworkEvidence(val technicalOutcome: String, val bytes: Long = 0, val requests: Int = 1,
    val reason: String = "NETWORK_PATH_ONLY", val retryAfterSeconds: Long = 0, val failureClass: String = "",
    val httpStatus: Int = 0)
class IneligibleCandidate(val reasonCode: String) : IllegalStateException(reasonCode)

/** The existing VPN service remains the single native engine owner. */
interface TrialAdapter {
    fun available(): Boolean
    suspend fun apply(candidate: CandidateDefinition, operationId: String)
    suspend fun rollback(operationId: String): Boolean
    /**
     * Uygulanmış DPI adayını motorun kalıcı tabanı yapar: ayar değişikliği, tünel
     * yeniden kurulumu ve sonraki denemeler artık bunun üstüne kurulur.
     */
    suspend fun commit(operationId: String): Boolean = true
    /** [commit] edilmiş planı, kurulmadan önceki motor ayarına geri alır. */
    suspend fun revertPlan(operationId: String): Boolean = true
    /**
     * [throughCandidate] true: yerel SOCKS (motorun o anki ayarı) üzerinden;
     * false: doğrudan kontrol yolu. İkisi de aynı alttaki ağı ve oturum boyunca
     * sabitlenmiş aynı DNS cevabını kullanır.
     */
    suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence
    /** Alttaki ağ doğrulanmış internete sahip mi? Bilinmiyorsa true. */
    fun networkUsable(): Boolean = true
    /** Sistem DNS'i ile şifreli DNS cevabının karşılaştırması (DPI ölçümünden ayrı). */
    suspend fun dnsDiagnosis(target: Target): String = "UNKNOWN"
    /** Yeni tur: DNS sabitlemesi gibi tur kapsamlı önbellekler sıfırlanır. */
    fun beginSession() {}
    fun journal(operationId: String, stage: String, candidate: CandidateDefinition?)
    fun persist(state: TrialState) {}
    fun persistResults(results: Collection<TargetResult>, planStrategyId: String?) {}
    fun loadResults(): Pair<Map<String, TargetResult>, String?> = emptyMap<String, TargetResult>() to null
    fun quarantine(target: Target, goal: Goal) {}
    fun quarantined(target: Target, goal: Goal): Boolean = false
    fun cooldownRemainingMillis(key: String): Long = 0
    fun persistCooldown(key: String, delayMillis: Long) {}
    /** Süreç yeniden başladıktan sonra artık geçerli olmayan kurtarma kaydını siler. */
    fun clearRecovery() {}
    fun wallClockMs(): Long = System.currentTimeMillis()
}

/**
 * Sosyal Erişim orkestratörü.
 *
 * Temel akış: doğru ağı kontrol et → başlangıcı ölç (odak hedef doğrudan; bütün
 * hedefler motorun o anki planı üzerinden) → adayı uygula → hazır olduğunu doğrula
 * → adayın GERÇEK yolundan (yerel SOCKS) bütün hedefleri ölç → çalışan hedeflerde
 * gerileme varsa adayı reddet → kazancı değerlendir → planı koru ya da geri al →
 * gerekirse sıradakini dene.
 *
 * Kavramlar ayrı tutulur:
 *  - deneme (TRIAL_ACTIVE): geçici, geri alınabilir aday
 *  - plan ([PlanState]): onaylanmış ve motorda kalıcılaştırılmış aday; ayar/ağ
 *    değişikliği onu geri ALMAZ, yalnız sonuçlarını bayatlatıp kısa doğrulama yapar
 *  - sonuçlar ([TargetResult]): hedef başına; biri diğerinin yerine geçmez
 *
 * Mutasyonlar sırayla ([mutex]) yürür; eski oturum/deneme/işlem/bağlam/belge
 * kuşaklarından gelen geç sonuçlar reddedilir.
 */
class ExperimentCoordinator(private val adapter: TrialAdapter, private val scope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(initialState())
    val state = mutable.asStateFlow()
    private var pending = ArrayDeque<CandidateDefinition>()
    private var catalog = emptyList<CandidateDefinition>()
    private val eventIds = mutableSetOf<String>()
    private var work: Job? = null
    private var inactivity: Job? = null
    private var reverifyJob: Job? = null
    private var probeMs = 0L
    private var groupAttempts = 0
    private var autoCorrected = false
    private var meterAllowed = false
    private var observerAvailable = false
    private var recoveryBlocked = false
    private var autoEvaluated = false
    /** Turun başında, motorun o anki planıyla hedef başına ağ yolu sonucu. */
    private var before = emptyMap<String, String>()
    /** Bu aday altında ölçülen hedef sonuçları (henüz kalıcı değil). */
    private var candidateOutcomes = emptyMap<String, NetworkEvidence>()
    /** Otomatik aramada tam kapsama bulunamazsa en iyi kısmi aday. */
    private var best: Pair<CandidateDefinition, Int>? = null
    /** Kullanıcının bu oturumda yanlış-pozitif diye düzelttiği adaylar. */
    private val rejected = mutableSetOf<String>()
    private var lastReverifiedGeneration = -1L
    @Volatile private var enabledFlag = false
    @Volatile private var pendingResolution: Feedback? = null
    private val cooldownUntil = mutableMapOf<String, Long>()

    private fun initialState(): TrialState {
        val (results, plan) = runCatching { adapter.loadResults() }.getOrDefault(emptyMap<String, TargetResult>() to null)
        return TrialState(results = results, plan = PlanState(strategyId = plan, installed = false, source = if (plan != null) "RESTORED" else "NONE"))
    }

    private fun cooldownRemaining(key: String): Long {
        val local = (cooldownUntil[key] ?: 0L) - now()
        return maxOf(local, runCatching { adapter.cooldownRemainingMillis(key) }.getOrDefault(0))
    }

    private fun cooldown(key: String, seconds: Long) {
        val delay = seconds.coerceIn(1, 86_400) * 1000
        cooldownUntil[key] = now() + delay
        runCatching { adapter.persistCooldown(key, delay) }
    }

    suspend fun markRecoveryRequired() { mutex.withLock { recoveryBlocked = true; update { copy(phase = Phase.RECOVERY_REQUIRED,
        resourceDisposition = "RECOVERY_REQUIRED", message = "Kurtarma kaydı bozuk; yeni Alpha denemesi engellendi.") } } }

    /** İdempotent: aynı değer tekrar geldiğinde (ör. ekran yeniden açıldığında) durum yazılmaz. */
    fun enabled(value: Boolean) {
        val changed = enabledFlag != value
        enabledFlag = value
        if (!changed && (value || state.value.phase == Phase.DISABLED)) return
        scope.launch { if (!value) { reverifyJob?.cancel(); stop(true) } else mutex.withLock {
            if (state.value.phase == Phase.DISABLED) update { copy(phase = Phase.IDLE) }
        } }
    }

    /** Sürmekte olan, geri alınabilir bir deneme var mı? (Onaylı plan deneme sayılmaz.) */
    fun active(): Boolean = state.value.let { s ->
        s.resourceDisposition == "TRIAL_ACTIVE" || s.resourceDisposition == "ROLLBACK_PENDING" ||
            s.resourceDisposition == "KEPT_ACTIVE" && s.candidate?.strategyId == null ||
            s.phase !in setOf(Phase.DISABLED, Phase.IDLE, Phase.FINISHED, Phase.RECOVERY_REQUIRED) &&
            !(s.phase == Phase.PAUSED && s.pauseReason in setOf("CONTEXT_CHANGED", "NETWORK_UNAVAILABLE"))
    }

    /**
     * Tur başına GERÇEK istek bütçesi (HEAD→GET iki istektir). Hedef başına bir
     * başlangıç + aday başına bir ölçüm + kararlılık için bir ölçüm gerekir; tavan,
     * bütün katalog seçiliyken bile ortak planın aranabilmesine yeter ama yüzlerce
     * bağlantı açmaz.
     */
    private fun budget(targets: Int) = (24 + 10 * (targets - 1).coerceAtLeast(0)).coerceAtMost(MAX_ROUND_REQUESTS)

    suspend fun start(goal: Goal, target: Target, mode: TestMode, baselineStillPresent: Boolean?,
        metered: Boolean, allowMeteredRenderer: Boolean, candidates: List<CandidateDefinition>, others: List<Target> = emptyList()) {
        if (!enabledFlag) return
        // Hız sınırı bildiren HERHANGİ bir seçili hedef (odak dışı da) beklenir: aynı
        // motor hepsini etkilediği için o hedefe istek atmadan aday değerlendirilemez.
        (listOf(target) + others).firstOrNull { cooldownRemaining(it.key) > 0 }?.let { waiting ->
            mutex.withLock { update { copy(phase = Phase.PAUSED, pauseReason = "COOLDOWN",
                message = "${label(waiting)} hız sınırı bildirdi; bekleme süresi henüz dolmadı. Bekleyin ya da bu uygulamayı seçimden çıkarın.") } }
            return
        }
        stopTrial()
        mutex.withLock {
            if (!enabledFlag || state.value.phase == Phase.RECOVERY_REQUIRED) return
            eventIds.clear(); probeMs = 0; groupAttempts = 0; pendingResolution = null; autoCorrected = adapter.quarantined(target, goal)
            autoEvaluated = false; best = null; candidateOutcomes = emptyMap(); before = emptyMap(); rejected.clear()
            runCatching { adapter.beginSession() }
            meterAllowed = !metered || allowMeteredRenderer
            observerAvailable = goal.identityContext == "ANONYMOUS" && "CONTROLLED_WEB_VIEW" in goal.allowedSurfaces &&
                SocialPlatform.supportsAutoObserver(target.platformId) && target.contentId != Target.HOME_CONTENT
            catalog = candidates.filter { it.schemaVersion == 2 && it.candidateVersion == "1.0.0" }.distinctBy { it.effectKey }
            pending = ArrayDeque(catalog)
            val all = (listOf(target) + others).distinctBy { it.key }
            roundBudget = budget(all.size)
            val previous = state.value
            mutable.value = TrialState(phase = Phase.BASELINING, mode = mode, sessionId = id(), goal = goal, target = target, targets = all,
                results = previous.results, plan = previous.plan, contextGeneration = previous.contextGeneration,
                message = "Başlangıç ölçümü: hedefin şu anki durumu", version = previous.version + 1)
            if (baselineStillPresent == false) {
                update { copy(phase = Phase.FINISHED, finishReason = "ALREADY_ACCESSIBLE", message = "Sorun başlangıçta yok; yöntem başarısı yazılmadı.") }
                return
            }
        }
        launchNext()
    }

    private fun launchNext() {
        work = scope.launch { mutex.withLock { runRound() } }
    }

    /** Bu turun istek bütçesi ([start]'ta hedef sayısına göre belirlenir). */
    private var roundBudget = budget(1)

    /**
     * Tek bir ağ yolu ölçümü. Her istekten ÖNCE bütçe denetlenir: olası en kötü
     * maliyet (HEAD→GET = [REQUEST_RESERVE] istek, [BYTE_RESERVE] başlık baytı)
     * ayrılır; ölçüm bitince gerçek değerle değiştirilir. Zaman aşımı/iptalde
     * rezerv muhafazakâr biçimde harcanmış sayılır.
     *
     * null: bütçe bu isteğe yetmiyor, istek GÖNDERİLMEDİ.
     * Retry-After içeren her yanıt — hangi hedeften gelirse gelsin — o hedefe özel
     * bekleme yazar; çağıran turu durdurmakla yükümlüdür ([waitRequested]).
     */
    private suspend fun measure(target: Target, through: Boolean, timeoutReason: String): NetworkEvidence? {
        val s = state.value
        if (s.requests + REQUEST_RESERVE > roundBudget || s.diagnosticBytes + BYTE_RESERVE > MAX_DIAGNOSTIC_BYTES || probeMs >= MAX_PROBE_MS) return null
        val began = now()
        update { copy(requests = requests + REQUEST_RESERVE, diagnosticBytes = diagnosticBytes + BYTE_RESERVE) }
        var evidence: NetworkEvidence? = null
        try {
            evidence = withTimeoutOrNull(20_000) { adapter.probe(target, through) }
        } finally {
            probeMs += (now() - began).coerceAtLeast(0)
            val used = evidence?.requests?.coerceIn(1, REQUEST_RESERVE) ?: REQUEST_RESERVE
            val bytes = evidence?.bytes?.coerceIn(0, BYTE_RESERVE) ?: BYTE_RESERVE
            update { copy(requests = requests - REQUEST_RESERVE + used, diagnosticBytes = diagnosticBytes - BYTE_RESERVE + bytes) }
        }
        val result = evidence ?: NetworkEvidence("UNKNOWN", bytes = BYTE_RESERVE, requests = REQUEST_RESERVE, reason = timeoutReason, failureClass = "READ_TIMEOUT")
        if (result.retryAfterSeconds > 0) cooldown(target.key, result.retryAfterSeconds)
        return result
    }

    private fun waitRequested(e: NetworkEvidence?) = e != null && e.retryAfterSeconds > 0

    /** Hız sınırı: geçici aday geri alınır, tur durur; sonraki profil DENENMEZ. */
    private suspend fun pauseForCooldown(target: Target, e: NetworkEvidence) {
        if (!rollback()) return
        update { copy(phase = Phase.PAUSED, pauseReason = "COOLDOWN",
            message = "${label(target)} hız sınırı bildirdi (${e.retryAfterSeconds} sn). Deneme geri alındı; süre dolmadan başka yöntem denenmeyecek.") }
    }

    /** Bütçe bir ölçümün ortasında bitti: aday başarısız SAYILMAZ, geri alınır ve kuyruğun başına döner. */
    private suspend fun pauseForBudget(candidate: CandidateDefinition?) {
        candidate?.let { pending.addFirst(it) }
        if (!rollback()) return
        update { copy(phase = Phase.PAUSED, pauseReason = "BUDGET_LIMIT", message = "Tur bütçesi doldu; yarım kalan yöntem başarısız sayılmadı. \"Kalanları dene\" ile sürdürebilirsin.") }
    }

    private fun networkCheck(e: NetworkEvidence) = Check(when (e.technicalOutcome) {
        "PASS" -> CheckStatus.PASS; "FAIL" -> CheckStatus.FAIL; else -> CheckStatus.UNKNOWN }, CheckSource.AUTO, e.reason)

    private fun recordNetwork(target: Target, e: NetworkEvidence, strategyId: String?) {
        val s = state.value
        val old = s.results[target.key]
        // Aynı bağlamda ve aynı planla alınmış içerik sonucu korunur; kuşağı değişmez.
        // Platform doğrulaması (GATED) ağ ölçümüyle silinmez: sunucu şartı ağ yolundan bağımsızdır.
        val keepContent = old != null && (!old.stale && old.strategyId == strategyId && old.contentGeneration == s.contextGeneration ||
            old.content.status == CheckStatus.GATED)
        val result = TargetResult(target.key, target.platformId, networkCheck(e),
            content = if (keepContent) old!!.content else Check(),
            strategyId = strategyId, contextGeneration = s.contextGeneration, verifiedAtMs = adapter.wallClockMs(),
            failureClass = e.failureClass, contentGeneration = if (keepContent) old!!.contentGeneration else -1)
        update { copy(results = results + (target.key to result)) }
    }

    private suspend fun runRound() {
        if (!enabledFlag || state.value.phase in setOf(Phase.RECOVERY_REQUIRED, Phase.DISABLED) || state.value.finishReason?.startsWith("RESOLVED") == true) return
        val targets = state.value.targets.ifEmpty { listOfNotNull(state.value.target) }
        val focus = state.value.target ?: return
        if (state.value.phase == Phase.BASELINING) {
            if (!adapter.networkUsable()) {
                update { copy(phase = Phase.PAUSED, pauseReason = "NETWORK_UNAVAILABLE", message = SocialText.failure("NO_VALIDATED_NETWORK").replaceFirstChar { it.uppercase() } + ".") }
                return
            }
            val dns = runCatching { adapter.dnsDiagnosis(focus) }.getOrDefault("UNKNOWN")
            val direct = measure(focus, false, "BASELINE_TRANSPORT_UNKNOWN") ?: return pauseForBudget(null)
            update { copy(technicalOutcome = direct.technicalOutcome, baselineOutcome = direct.technicalOutcome,
                message = "Başlangıç: ${SocialText.evidence(direct.reason)}." + dnsNote(dns)) }
            if (waitRequested(direct)) return pauseForCooldown(focus, direct)
            // Motor açıksa her hedef, kullanıcının şu an gerçekten yaşadığı yoldan
            // (motorun o anki planı) da ölçülür; gerileme ve kazanç buna göre hesaplanır.
            val engine = adapter.available()
            val plan = state.value.plan.strategyId.takeIf { state.value.plan.installed }
            val measured = mutableMapOf<String, String>()
            for (t in targets) {
                val e = if (!engine && t == focus) direct
                    else measure(t, engine, "BASELINE_TRANSPORT_UNKNOWN") ?: return pauseForBudget(null)
                if (engine) recordNetwork(t, e, plan)
                // Odak dışı hedefin bekleme isteği de turu durdurur.
                if (waitRequested(e)) return pauseForCooldown(t, e)
                measured[t.key] = e.technicalOutcome
            }
            before = measured
            update { copy(phase = Phase.SELECTING) }
        }
        while (pending.isNotEmpty()) {
            if (!enabledFlag) { rollback(); return }
            // Aday başına beklenen en az istek: her hedefte bir ölçüm. Kararlılık
            // ölçümleri ve HEAD→GET ek istekleri her istek öncesi ayrıca denetlenir.
            val needs = if (pending.first().strategyId != null) targets.size else 1
            if (groupAttempts >= 12 || state.value.requests + needs > roundBudget || probeMs >= MAX_PROBE_MS) {
                update { copy(phase = Phase.PAUSED, pauseReason = "BUDGET_LIMIT", message = "Tur bütçesi doldu; yalnız kalanlar sürdürülebilir.") }; return
            }
            val candidate = pending.removeFirst()
            if (candidate.id in rejected) continue
            if (state.value.mode == TestMode.AUTO_BETA && candidate.surface == "OFFICIAL_WEB" && candidate.strategyId == null) {
                update { copy(deferred = deferred + candidate.id, message = "Haricî tarayıcı gözlenemiyor; manuel inceleme için ayrıldı.") }; continue
            }
            if (candidate.strategyId != null && !adapter.available()) {
                update { copy(deferred = deferred + candidate.id, message = "Yerel VPN bağlantısı gerekli; aday uygulanmadı.") }; continue
            }
            // Server/account gates are not a reason to repeatedly rotate packet profiles.
            if (candidate.strategyId != null && state.value.goal?.problemClass in ServerGates) continue
            if (candidate.strategyId != null && candidate.strategyId == state.value.plan.strategyId && state.value.plan.installed) continue
            val operation = id()
            update { copy(phase = Phase.PREPARING, attemptId = id(), operationId = operation, candidate = candidate,
                finishReason = null, pauseReason = null, autoVerdict = Verdict.NOT_RUN, technicalOutcome = "UNKNOWN", userOutcome = "NOT_TESTED", documentGeneration = documentGeneration + 1) }
            try {
                adapter.journal(operation, "APPLY_INTENT_DURABLE", candidate)
                update { copy(phase = Phase.APPLYING) }
                withTimeout(15_000) { adapter.apply(candidate, operation) }
                adapter.journal(operation, "APPLIED", candidate)
                groupAttempts++
                update { copy(phase = Phase.PROBING, resourceDisposition = "TRIAL_ACTIVE", attempted = attempted + 1) }
                val native = candidate.strategyId != null
                val evidence = measure(focus, native, "PROBE_TIMEOUT") ?: return pauseForBudget(candidate)
                candidateOutcomes = mapOf(focus.key to evidence)
                update { copy(technicalOutcome = evidence.technicalOutcome,
                    message = "${candidate.displayName}: ${SocialText.evidence(evidence.reason)}." + if (state.value.mode == TestMode.MANUAL) " Şimdi uygulamada/tarayıcıda kontrol edin." else "") }
                if (waitRequested(evidence)) return pauseForCooldown(focus, evidence)
                if (native) {
                    // Aynı motor bütün kapsamdaki uygulamaları etkiler: diğer hedefler
                    // de adayın gerçek yolundan ölçülür. Önceden çalışan bir hedef
                    // bozulursa aday sessizce kazanan ilan edilmez.
                    for (t in targets) {
                        if (t == focus) continue
                        val e = measure(t, true, "PROBE_TIMEOUT") ?: return pauseForBudget(candidate)
                        candidateOutcomes = candidateOutcomes + (t.key to e)
                        if (waitRequested(e)) return pauseForCooldown(t, e)
                    }
                    val regressed = targets.filter { before[it.key] == "PASS" && candidateOutcomes[it.key]?.technicalOutcome != "PASS" }
                    if (regressed.isNotEmpty()) {
                        val names = regressed.joinToString { label(it) }
                        update { copy(message = "${candidate.displayName}: $names için çalışan bağlantıyı bozdu; aday reddedildi ve geri alındı.",
                            results = results + regressed.associate { t -> t.key to (results[t.key] ?: TargetResult(t.key, t.platformId)).copy(conflict = candidate.displayName) }) }
                        if (!rollback()) return
                        continue
                    }
                }
                val resolution = pendingResolution
                if (resolution != null && resolution.operationId == operation && resolution.sessionId == state.value.sessionId &&
                    resolution.contextGeneration == state.value.contextGeneration && resolution.modeGeneration == state.value.modeGeneration) {
                    pendingResolution = null
                    keep("RESOLVED_USER_REPORTED", "Kullanıcı erişim bildirdi; otomatik arama durdu.", CheckStatus.PASS, CheckSource.USER)
                    return
                }
                if (state.value.mode == TestMode.MANUAL) { waitForUser(); return }
                if (native) {
                    autoEvaluated = true
                    val open = targets.filter { before[it.key] != "PASS" }
                    val gained = open.filter { candidateOutcomes[it.key]?.technicalOutcome == "PASS" }
                    if (open.isEmpty()) {
                        // Ağ yolu zaten açık: DPI profilinin etkisi ağ düzeyinde ölçülemez.
                        // Bütün profilleri boşuna döndürmek yerine manuel incelemeye bırakılır.
                        val natives = (listOf(candidate) + pending.filter { it.strategyId != null }).map { it.id }
                        pending = ArrayDeque(pending.filter { it.strategyId == null })
                        update { copy(deferred = deferred + natives, autoVerdict = Verdict.INCONCLUSIVE,
                            message = "Ağ yolu başlangıçta zaten açıktı; DPI profillerinin etkisi otomatik ölçülemez. Sorun içerik/hesap düzeyinde olabilir; Manuel devam edebilirsin.") }
                        if (!rollback()) return
                        continue
                    }
                    if (gained.size == open.size) {
                        when (confirmStable(focus, gained)) {
                            Stability.STABLE -> {
                                keep("NETWORK_VERIFIED_AUTO", "${candidate.displayName}: seçili hedeflerin ağ yolu bu yöntemle açıldı (iki ölçüm). İçerik/uygulama için onayın gerekiyor.",
                                    if (observerAvailable) CheckStatus.UNKNOWN else CheckStatus.MANUAL_REQUIRED, CheckSource.NONE, causal = "NETWORK_PATH")
                                return
                            }
                            Stability.WAIT -> return
                            Stability.BUDGET -> return pauseForBudget(candidate)
                            Stability.UNSTABLE -> update { copy(message = "${candidate.displayName}: ikinci ölçümde tutarsız; kararsız sayıldı.") }
                        }
                    } else if (gained.isNotEmpty() && (best?.second ?: 0) < gained.size) {
                        best = candidate to gained.size
                    }
                    update { copy(autoVerdict = if (gained.isEmpty()) Verdict.FAIL else Verdict.INCONCLUSIVE) }
                    if (!rollback()) return
                    continue
                }
                if (!observerAvailable || !meterAllowed) {
                    update { copy(deferred = deferred + candidate.id, autoVerdict = Verdict.UNSUPPORTED,
                        message = if (!meterAllowed) "Ölçülü bağlantıda ek veri tercihi olmadan WebView başlatılmaz." else "MANUAL_REVIEW_REQUIRED: bu yüzey/hesap için içerik gözlemi yok.") }
                    if (!rollback()) return
                    continue
                }
                if (state.value.rendererLoads >= 6) {
                    pending.addFirst(candidate)
                    if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "BUDGET_LIMIT") }
                    return
                }
                autoEvaluated = true
                update { copy(phase = Phase.AUTO_EVALUATING, rendererLoads = rendererLoads + 1,
                    message = "Test penceresinde herkese açık içerik (doğrudan yol); veri bütçesi tahmini. Native hesap gözlenmez.") }
                return
            } catch (e: CancellationException) {
                withContext(NonCancellable) { rollback() }; throw e
            } catch (e: Exception) {
                update { copy(deferred = if (e is IneligibleCandidate) deferred else deferred + candidate.id,
                    message = if (e is IneligibleCandidate) "Aday gerçek ayar farkı üretmiyor; denenmiş sayılmadı." else "İşlem/gözlem hatası; aday başarısız sayılmadı.") }
                if (!rollback()) return
            }
        }
        // Tam kapsama bulunamadıysa en çok hedefi açan aday yeniden uygulanır ve
        // ikinci kez ölçülür; tutarlıysa kısmi plan olarak korunur.
        best?.let { (candidate, _) ->
            best = null
            if (applyAndConfirmBest(candidate, focus, targets)) return
        }
        if (state.value.mode == TestMode.MANUAL && state.value.deferred.isNotEmpty()) {
            update { copy(phase = Phase.PAUSED, pauseReason = "PERMISSION_REQUIRED", message = "Bazı yöntemler için yerel DPI bağlantısı veya kullanıcı eylemi gerekiyor; denenmiş sayılmadılar.") }
            return
        }
        update { copy(phase = Phase.FINISHED, finishReason = if (mode == TestMode.AUTO_BETA && !autoEvaluated && !observerAvailable) "AUTO_UNAVAILABLE"
            else if (deferred.isNotEmpty()) "AUTO_INCONCLUSIVE" else if (attempted == 0) "NO_APPLICABLE_METHODS" else "CANDIDATES_EXHAUSTED") }
    }

    private enum class Stability { STABLE, UNSTABLE, WAIT, BUDGET }

    /**
     * Kararlılık: kazanılan hedefler ikinci kez ölçülür; biri bile tutmazsa kazanç
     * sayılmaz. Bekleme isteği (Retry-After) ya da biten bütçe "kararsız" DEĞİLDİR:
     * tur durur, sonraki profile geçilmez.
     */
    private suspend fun confirmStable(focus: Target, gained: List<Target>): Stability {
        for (t in gained) {
            val again = measure(t, true, "PROBE_TIMEOUT") ?: return Stability.BUDGET
            if (waitRequested(again)) { pauseForCooldown(t, again); return Stability.WAIT }
            if (again.technicalOutcome != "PASS") return Stability.UNSTABLE
            if (t == focus) update { copy(technicalOutcome = again.technicalOutcome) }
        }
        return Stability.STABLE
    }

    /** true: tur burada bitti (plan korundu, bekleme ya da bütçe nedeniyle duraklatıldı). */
    private suspend fun applyAndConfirmBest(candidate: CandidateDefinition, focus: Target, targets: List<Target>): Boolean {
        if (state.value.requests + targets.size * REQUEST_RESERVE > roundBudget) return false
        val operation = id()
        update { copy(phase = Phase.APPLYING, attemptId = id(), operationId = operation, candidate = candidate, documentGeneration = documentGeneration + 1) }
        return try {
            adapter.journal(operation, "APPLY_INTENT_DURABLE", candidate)
            withTimeout(15_000) { adapter.apply(candidate, operation) }
            adapter.journal(operation, "APPLIED", candidate)
            update { copy(phase = Phase.PROBING, resourceDisposition = "TRIAL_ACTIVE") }
            val outcomes = mutableMapOf<String, NetworkEvidence>()
            for (t in targets) {
                val e = measure(t, true, "PROBE_TIMEOUT") ?: run { rollback(); return false }
                outcomes[t.key] = e
                if (waitRequested(e)) { pauseForCooldown(t, e); return true }
            }
            candidateOutcomes = outcomes
            val regressed = targets.any { before[it.key] == "PASS" && candidateOutcomes[it.key]?.technicalOutcome != "PASS" }
            val gained = targets.count { before[it.key] != "PASS" && candidateOutcomes[it.key]?.technicalOutcome == "PASS" }
            if (regressed || gained == 0) { rollback(); return false }
            update { copy(technicalOutcome = candidateOutcomes[focus.key]?.technicalOutcome ?: "UNKNOWN") }
            keep("NETWORK_VERIFIED_AUTO", "${candidate.displayName}: hedeflerin bir kısmında ağ yolu açıldı (kısmi kapsama). Diğerleri bu yöntemle açılmadı.",
                CheckStatus.MANUAL_REQUIRED, CheckSource.NONE, causal = "NETWORK_PATH")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { rollback() }; throw e
        } catch (_: Exception) { rollback(); false }
    }

    /**
     * Çalışan adayı korur. DPI adayı motorda kalıcılaştırılır (plan olur); hedef
     * sonuçları adayın gerçek yolundan alınan ölçümlerle güncellenir.
     */
    private suspend fun keep(finish: String, message: String, contentStatus: CheckStatus, contentSource: CheckSource, causal: String = "NOT_ESTABLISHED") {
        val s = state.value
        val candidate = s.candidate
        adapter.journal(s.operationId, "KEEP_INTENT_DURABLE", candidate)
        if (candidate?.strategyId != null && !runCatching { adapter.commit(s.operationId) }.getOrDefault(false)) {
            update { copy(message = "Yöntem motorda kalıcılaştırılamadı; geri alındı, başarı yazılmadı.") }
            rollback(); return
        }
        adapter.journal(s.operationId, "KEPT", candidate)
        val focus = s.target
        val stamp = adapter.wallClockMs()
        val updated = if (candidate?.strategyId != null) s.targets.associate { t ->
            val e = candidateOutcomes[t.key]
            val old = s.results[t.key]
            t.key to TargetResult(t.key, t.platformId, e?.let(::networkCheck) ?: old?.network?.copy() ?: Check(),
                content = if (t == focus) Check(contentStatus, contentSource) else Check(),
                strategyId = candidate.strategyId, contextGeneration = s.contextGeneration, verifiedAtMs = stamp,
                failureClass = e?.failureClass.orEmpty(), conflict = old?.conflict.orEmpty(),
                contentGeneration = if (t == focus) s.contextGeneration else -1)
        } else focus?.let { t -> mapOf(t.key to (s.results[t.key] ?: TargetResult(t.key, t.platformId))
            .copy(content = Check(contentStatus, contentSource), verifiedAtMs = stamp, stale = false,
                contextGeneration = s.contextGeneration, contentGeneration = s.contextGeneration)) } ?: emptyMap()
        update { copy(phase = Phase.FINISHED, resourceDisposition = "KEPT_ACTIVE", finishReason = finish, message = message,
            userOutcome = if (contentSource == CheckSource.USER) "RESOLVED" else userOutcome, causalAttribution = causal,
            results = results + updated,
            plan = if (candidate?.strategyId != null) PlanState(candidate.strategyId, s.operationId, installed = true, source = finish) else plan) }
    }

    suspend fun observe(o: Observation) {
        var next = false
        mutex.withLock {
            val s = state.value
            if (s.phase != Phase.AUTO_EVALUATING || s.mode != TestMode.AUTO_BETA || o.sessionId != s.sessionId || o.attemptId != s.attemptId || o.operationId != s.operationId ||
                o.contextGeneration != s.contextGeneration || o.modeGeneration != s.modeGeneration || o.documentGeneration != s.documentGeneration || o.targetRef != s.target?.targetRef || o.goalHash != s.goal.hashCode().toString()) return
            // Uygulamanın kendi WebView'i tünel dışındadır (doğrudan yol); bir DPI
            // adayının etkisini kanıtlayamaz.
            if (s.candidate?.strategyId != null) return
            val verdict = if (autoCorrected) Verdict.INCONCLUSIVE else GoalEvaluator.evaluate(s.goal!!, o)
            update { copy(autoVerdict = verdict) }
            when (verdict) {
                Verdict.PASS -> keep("RESOLVED_AUTO_OBSERVED", "Seçilen işlem test penceresinde çalıştı. Native uygulama için sonuç değildir; nedensellik kurulmadı.",
                    CheckStatus.PASS, CheckSource.AUTO)
                Verdict.NEEDS_INTERACTION -> update { copy(phase = Phase.PAUSED, pauseReason = "USER_ACTION_REQUIRED", message = "Test penceresinde oynat'a dokunun; sonra devam edin.") }
                else -> {
                    if (verdict == Verdict.FAIL && o.blockingClass in ServerGates) {
                        pending = ArrayDeque(pending.filter { it.strategyId == null })
                        update { copy(message = "Sunucu doğrulama gereksinimi gözlendi; DNS/DPI profilleri yinelenmeyecek.") }
                    }
                    if (verdict != Verdict.FAIL) update { copy(deferred = deferred + candidate!!.id) }
                    if (rollback()) next = true
                }
            }
        }
        if (next) launchNext()
    }

    suspend fun feedback(event: Feedback) {
        var next = false
        val snapshot = state.value
        if (event.outcome == "RESOLVED" && snapshot.phase == Phase.PROBING && event.sessionId == snapshot.sessionId &&
            event.attemptId == snapshot.attemptId && event.operationId == snapshot.operationId && event.contextGeneration == snapshot.contextGeneration &&
            event.modeGeneration == snapshot.modeGeneration && event.expectedStateVersion == snapshot.version) pendingResolution = event
        mutex.withLock {
            val s = state.value
            val accepts = s.phase == Phase.WAITING_FOR_USER || event.outcome == "RESOLVED" &&
                (s.phase == Phase.AUTO_EVALUATING || s.phase == Phase.PAUSED && s.pauseReason == "USER_ACTION_REQUIRED")
            if (event.outcome !in FeedbackOutcomes || !accepts || event.sessionId != s.sessionId || event.attemptId != s.attemptId || event.operationId != s.operationId ||
                event.contextGeneration != s.contextGeneration || event.modeGeneration != s.modeGeneration || event.expectedStateVersion != s.version || !eventIds.add(event.eventId)) return
            inactivity?.cancel()
            update { copy(userOutcome = event.outcome) }
            when (event.outcome) {
                "RESOLVED" -> keep("RESOLVED_USER_REPORTED", "Kullanıcı erişim bildirdi; yeni arama durdu.", CheckStatus.PASS, CheckSource.USER)
                "PARTIAL_ACCEPTED" -> keep("PARTIAL_ACCEPTED", "Kısmi erişimi kabul ettin; yöntem korunuyor.", CheckStatus.PARTIAL, CheckSource.USER)
                "PARTIAL" -> { update { copy(message = "Bir kısmı çalışıyor: böyle kullan veya devam et.") }; waitForUser() }
                "STILL_PRESENT" -> if (rollback()) next = true
                "NOT_TESTED", "UNSURE" -> if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "USER_UNABLE_TO_TEST") }
                // Sunucu şartı: aday onu çözmedi (yerel ağ ayarı hesap kaydını değiştirmez).
                // Geçici aday geri alınır; DPI profilleri bu hedef için yinelenmez.
                "VERIFICATION_REQUIRED" -> if (rollback()) {
                    pending = ArrayDeque(pending.filter { it.strategyId == null })
                    val focus = s.target
                    update { copy(phase = Phase.FINISHED, finishReason = "PLATFORM_GATE",
                        message = "Platform doğrulama istiyor. Yerel DNS/DPI yöntemi sunucudaki hesap şartını değiştiremez; profiller yinelenmedi.",
                        results = if (focus == null) results else results + (focus.key to (results[focus.key] ?: TargetResult(focus.key, focus.platformId))
                            .copy(content = Check(CheckStatus.GATED, CheckSource.USER), contentGeneration = contextGeneration))) }
                }
            }
        }
        if (next) launchNext()
    }

    /**
     * Tur dışında, hedef başına kullanıcı bildirimi ("Çalıştı / Kısmen / Çalışmadı /
     * Test edemedim"). Otomatik ağ doğrulamasıyla korunan plan için "Çalışmadı"
     * yanlış pozitif düzeltmesidir: plan geri alınır, aday bu oturumda yeniden
     * başarı sayılmaz ve sıradaki adaylarla devam edilir.
     */
    suspend fun report(key: String, outcome: String) {
        if (outcome !in ReportOutcomes) return
        var continueSearch = false
        mutex.withLock {
            val s = state.value
            val old = s.results[key] ?: s.targets.firstOrNull { it.key == key }?.let { TargetResult(it.key, it.platformId) } ?: return
            val status = when (outcome) { "RESOLVED" -> CheckStatus.PASS; "PARTIAL" -> CheckStatus.PARTIAL; "STILL_PRESENT" -> CheckStatus.FAIL
                "VERIFICATION_REQUIRED" -> CheckStatus.GATED; else -> CheckStatus.UNKNOWN }
            // Kullanıcı bildirimi otomatik kanıt değildir: kaynağı USER, bağlamı bu kuşak.
            // "Emin değilim"/"Test edemedim" önceki sonucu başarı ya da başarısızlığa çevirmez.
            if (status == CheckStatus.UNKNOWN) {
                update { copy(results = results + (key to old.copy(content = Check(), contentGeneration = -1))) }
                return@withLock
            }
            update { copy(results = results + (key to old.copy(content = Check(status, CheckSource.USER),
                verifiedAtMs = adapter.wallClockMs(), contentGeneration = contextGeneration))) }
            if (status == CheckStatus.GATED) {
                update { copy(message = "${old.key.removePrefix("custom:").let { SocialPlatform.byId(it)?.displayName ?: it }}: platform doğrulama istiyor. " +
                    "Yerel ağ yöntemi hesap şartını değiştiremez; plan geri alınmadı, profiller bu yüzden döndürülmeyecek.") }
                return@withLock
            }
            val autoPlan = s.finishReason == "NETWORK_VERIFIED_AUTO" && s.plan.operationId == s.operationId && s.plan.installed && s.target?.key == key
            if (outcome == "STILL_PRESENT" && autoPlan) {
                s.target?.let { t -> s.goal?.let { g -> runCatching { adapter.quarantine(t, g) } } }
                s.candidate?.let { rejected += it.id }
                if (!revertPlanLocked()) return
                update { copy(phase = Phase.SELECTING, finishReason = null, causalAttribution = "NOT_ESTABLISHED",
                    message = "Yanlış pozitif düzeltildi: yöntem geri alındı, bu oturumda yeniden başarı sayılmayacak. Sıradaki yöntemlere geçiliyor.") }
                continueSearch = enabledFlag && pending.isNotEmpty()
                if (!continueSearch) update { copy(phase = Phase.FINISHED, finishReason = "CANDIDATES_EXHAUSTED") }
            }
        }
        if (continueSearch) launchNext()
    }

    suspend fun manual() {
        // Mod seçici boştayken de çağırır: süren/ertelenmiş bir deneme yoksa
        // durum değişmez (eskiden boştaki ekran "duraklatıldı"ya düşüyordu).
        val s = state.value
        if (s.sessionId.isBlank() || s.phase == Phase.DISABLED || s.finishReason == "CANCELLED" ||
            !active() && s.deferred.isEmpty()) return
        work?.cancelAndJoin()
        var startDeferred = false
        mutex.withLock {
            update { copy(mode = TestMode.MANUAL, modeGeneration = modeGeneration + 1, documentGeneration = documentGeneration + 1, finishReason = null) }
            if (state.value.resourceDisposition == "TRIAL_ACTIVE") waitForUser()
            else if (state.value.phase != Phase.RECOVERY_REQUIRED) {
                val deferred = state.value.deferred.toSet()
                if (deferred.isNotEmpty()) {
                    pending = ArrayDeque((catalog.filter { it.id in deferred } + pending).distinctBy { it.effectKey })
                    update { copy(phase = Phase.SELECTING, deferred = emptyList(), message = "Ertelenen yöntemler manuel değerlendirilecek.") }
                    startDeferred = true
                } else update { copy(phase = Phase.PAUSED, message = "Manuel denemeye devam etmek için başlatın.") }
            }
        }
        if (startDeferred) launchNext()
    }

    suspend fun correctAutoPass() {
        state.value.target?.let { target -> state.value.goal?.let { adapter.quarantine(target, it) } }
        autoCorrected = true; manual()
    }
    suspend fun automatic() {
        mutex.withLock {
            if (state.value.phase == Phase.WAITING_FOR_USER && observerAvailable && meterAllowed && state.value.candidate?.surface == "CONTROLLED_WEB_VIEW")
                update { copy(mode = TestMode.AUTO_BETA, modeGeneration = modeGeneration + 1, documentGeneration = documentGeneration + 1, phase = Phase.AUTO_EVALUATING) }
        }
    }
    suspend fun rendererLost(operationId: String, documentGeneration: Long) {
        mutex.withLock {
            if (state.value.operationId != operationId || state.value.documentGeneration != documentGeneration || state.value.phase != Phase.AUTO_EVALUATING) return
            if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "USER_ACTION_REQUIRED", autoVerdict = Verdict.INCONCLUSIVE,
                message = "Gözlem penceresi kapandı; sonucu başarı saymadık. Yeniden başlatın veya manuel devam edin.") }
        }
    }
    suspend fun resume() {
        mutex.withLock {
            if (state.value.phase != Phase.PAUSED) return
            if (state.value.target?.let { cooldownRemaining(it.key) > 0 } == true) return
            if (state.value.pauseReason == "USER_ACTION_REQUIRED" && state.value.resourceDisposition == "TRIAL_ACTIVE") {
                update { copy(phase = Phase.AUTO_EVALUATING, documentGeneration = documentGeneration + 1, pauseReason = null) }; return
            }
            if (state.value.pauseReason in setOf("CONTEXT_CHANGED", "NETWORK_UNAVAILABLE")) return
            if (state.value.pauseReason == "COOLDOWN") {
                // Bekleme bütün seçili hedefler için dolmalı; süre geçtiği için başlangıç yeniden ölçülür.
                if (state.value.targets.any { cooldownRemaining(it.key) > 0 }) return
                before = emptyMap()
            }
            if (before.isEmpty() && state.value.targets.isNotEmpty()) update { copy(phase = Phase.BASELINING, pauseReason = null) }
            if (state.value.pauseReason == "BUDGET_LIMIT") {
                groupAttempts = 0; probeMs = 0
                update { copy(requests = 0, diagnosticBytes = 0, rendererLoads = 0, pauseReason = null) }
            }
            if (state.value.pauseReason == "PERMISSION_REQUIRED") {
                val deferredIds = state.value.deferred.toSet()
                pending = ArrayDeque((catalog.filter { it.id in deferredIds } + pending).distinctBy { it.effectKey })
                update { copy(deferred = emptyList(), pauseReason = null, phase = Phase.SELECTING) }
            }
        }
        launchNext()
    }

    /**
     * Ağ veya trafik ayarı (DNS, QUIC, kapsam, Vodafone) değişimi.
     *  - Süren geçici deneme geri alınır ve duraklatılır; eski kuşaktan gelen
     *    sonuçlar reddedilir.
     *  - Onaylı plan GERİ ALINMAZ (kullanıcının çalışan yöntemi kaybolmaz); hedef
     *    sonuçları bayatlar ve kısa bir yeniden doğrulama planlanır.
     *  - Etkin deneme, plan ya da sonuç yoksa hiçbir şey yapmaz.
     */
    suspend fun contextChanged() {
        val trial = active()
        val s = state.value
        if (!trial && s.results.isEmpty() && s.plan.strategyId == null) return
        if (trial) {
            stopTrial()
            mutex.withLock { if (state.value.phase != Phase.RECOVERY_REQUIRED) update { copy(phase = Phase.PAUSED,
                pauseReason = "CONTEXT_CHANGED", finishReason = null, message = "Alttaki ağ ya da trafik ayarı değişti; deneme geri alındı. Yeni bağlamda yeniden başlatın.") } }
        }
        mutex.withLock { update { copy(contextGeneration = contextGeneration + 1, results = results.mapValues { it.value.copy(stale = true) }) } }
        scheduleReverify()
    }

    /** Servis motoru başlattı; [strategyId] motorun gerçekten kullandığı taban ayardır. */
    suspend fun engineStarted(strategyId: String?) {
        mutex.withLock {
            val plan = state.value.plan
            update { copy(plan = plan.copy(installed = plan.strategyId != null && plan.strategyId == strategyId),
                results = results.mapValues { it.value.copy(stale = true) }, contextGeneration = contextGeneration + 1) }
        }
        scheduleReverify()
    }

    /** Servis durdu: motor yok; deneme geri alınmış sayılır, plan kurulu değildir. */
    suspend fun engineStopped() {
        reverifyJob?.cancel()
        stopTrial()
        mutex.withLock { update { copy(plan = plan.copy(installed = false), results = results.mapValues { it.value.copy(stale = true) }) } }
    }

    private fun scheduleReverify() {
        if (!enabledFlag) return
        reverifyJob?.cancel()
        reverifyJob = scope.launch { delay(REVERIFY_SETTLE_MS); reverify() }
    }

    /**
     * Bağlam değişince sınırlı yeniden doğrulama: bilinen hedefler (en çok 8)
     * motorun o anki yolundan BİR KEZ ölçülür. Bağlam kuşağı başına yalnız bir
     * tur; profil döndürülmez (sonsuz yeniden başlatma döngüsü yok).
     */
    suspend fun reverify() {
        mutex.withLock {
            val s = state.value
            if (!enabledFlag || active() || s.phase == Phase.RECOVERY_REQUIRED || s.contextGeneration == lastReverifiedGeneration) return
            if (!adapter.available() || !adapter.networkUsable()) return
            lastReverifiedGeneration = s.contextGeneration
            val keys = s.results.keys.take(8)
            val targets = keys.mapNotNull { key -> s.targets.firstOrNull { it.key == key } ?: s.results[key]?.let { r -> Target.home(r.platformId)?.takeIf { it.key == key } } }
            val strategy = s.plan.strategyId.takeIf { s.plan.installed }
            val regressed = mutableListOf<String>()
            for (t in targets) {
                if (cooldownRemaining(t.key) > 0) continue
                val e = withTimeoutOrNull(20_000) { adapter.probe(t, true) } ?: NetworkEvidence("UNKNOWN", reason = "THROUGH_CANDIDATE:READ_TIMEOUT", failureClass = "READ_TIMEOUT")
                if (e.retryAfterSeconds > 0) cooldown(t.key, e.retryAfterSeconds)
                val old = state.value.results[t.key]
                if (old?.network?.status == CheckStatus.PASS && e.technicalOutcome != "PASS") regressed += label(t)
                // Yeni bağlamdaki ağ PASS'i yalnız ağ yolunu günceller. Önceki içerik/kullanıcı
                // bildirimi geçmiş bilgisi olarak ESKİ kuşağıyla kalır ve güncel sayılmaz
                // (yeniden onay gerekir); farklı planla alınmış içerik hiç taşınmaz.
                val carry = old != null && (old.strategyId == strategy || old.content.status == CheckStatus.GATED)
                update { copy(results = results + (t.key to TargetResult(t.key, t.platformId, networkCheck(e),
                    content = if (carry) old!!.content else Check(), strategyId = strategy,
                    contextGeneration = contextGeneration, verifiedAtMs = adapter.wallClockMs(), failureClass = e.failureClass,
                    contentGeneration = if (carry) old!!.contentGeneration else -1))) }
            }
            if (regressed.isNotEmpty() && !active()) update { copy(message = "Yeni bağlamda ${regressed.joinToString()} bu planla açılmadı. Yeni bir arama başlatabilirsin.") }
        }
    }

    /**
     * Motoru DEĞİŞTİRMEDEN seçili hedeflerin ağ yolunu ölçer (çoklu uygulama
     * durumu). Motor açıksa kullanıcının gerçekten yaşadığı yol (yerel SOCKS),
     * değilse doğrudan yol ölçülür. En çok 12 hedef, hedef başına tek istek.
     */
    suspend fun check(targets: List<Target>) {
        reverifyJob?.cancelAndJoin()
        mutex.withLock {
            if (!enabledFlag || active() || state.value.phase == Phase.RECOVERY_REQUIRED || targets.isEmpty()) return
            if (!adapter.networkUsable()) {
                update { copy(message = SocialText.failure("NO_VALIDATED_NETWORK").replaceFirstChar { it.uppercase() } + ".") }; return
            }
            val all = targets.distinctBy { it.key }.take(12)
            runCatching { adapter.beginSession() }
            val through = adapter.available()
            val strategy = state.value.plan.strategyId.takeIf { through && state.value.plan.installed }
            update { copy(targets = (all + this.targets).distinctBy { it.key }, message = "Seçili hedefler kontrol ediliyor…") }
            var open = 0
            for (t in all) {
                if (cooldownRemaining(t.key) > 0) continue
                val e = withTimeoutOrNull(20_000) { adapter.probe(t, through) } ?: NetworkEvidence("UNKNOWN", reason = "PROBE_TIMEOUT", failureClass = "READ_TIMEOUT")
                if (e.retryAfterSeconds > 0) cooldown(t.key, e.retryAfterSeconds)
                if (e.technicalOutcome == "PASS") open++
                recordNetwork(t, e, strategy)
            }
            update { copy(message = "Kontrol: ${all.size} hedeften $open tanesinde ağ yolu açık" +
                (if (through) " (motorun şu anki planıyla)." else " (doğrudan; yerel DPI bağlantısı kapalı).") +
                " Bu yalnız teknik ağ yoludur; içerik ve uygulama için onayın gerekir.") }
        }
    }

    /** Kullanıcının "Durdur ve geri al" komutu: yalnız geçici denemeyi geri alır; onaylı plan korunur. */
    suspend fun stop(disable: Boolean = false) {
        reverifyJob?.cancelAndJoin()
        val wasActive = active() || state.value.phase !in setOf(Phase.IDLE, Phase.DISABLED, Phase.FINISHED)
        work?.cancelAndJoin(); inactivity?.cancel(); work = null
        mutex.withLock {
            if (!rollback()) return
            if (!disable && !wasActive) return
            update { copy(phase = if (disable) Phase.DISABLED else Phase.FINISHED, finishReason = if (wasActive) "CANCELLED" else finishReason, modeGeneration = modeGeneration + 1,
                documentGeneration = documentGeneration + 1, message = if (wasActive) "Deneme durduruldu; geçici değişiklikler geri alındı." else message) }
        }
    }

    /** Yeni tur/bağlam için süren denemeyi durdurur; durum yazısını değiştirmez. */
    private suspend fun stopTrial() {
        // Yeniden doğrulama kilidi uzun süre tutmasın: kullanıcı komutu önceliklidir.
        reverifyJob?.cancelAndJoin()
        work?.cancelAndJoin(); inactivity?.cancel(); work = null
        mutex.withLock { rollback() }
    }

    /** Kullanıcının açık komutu: onaylı planı kaldırır ve motoru önceki ayarına döndürür. */
    suspend fun clearPlan() {
        stopTrial()
        mutex.withLock {
            if (state.value.plan.strategyId == null) return
            val engineOwned = state.value.plan.installed && state.value.plan.operationId.isNotBlank()
            if (revertPlanLocked()) update { copy(message = if (engineOwned) "Sosyal Erişim planı kaldırıldı; bağlantının önceki ayarı geri yüklendi."
                else "Plan kaydı kaldırıldı; motorun şu anki ayarı bağlantının kendi seçimidir.") }
        }
    }

    private suspend fun revertPlanLocked(): Boolean {
        val plan = state.value.plan
        val ok = !plan.installed || plan.operationId.isBlank() || runCatching { adapter.revertPlan(plan.operationId) }.getOrDefault(false)
        if (!ok) { update { copy(message = "Plan geri alınamadı (motor ayarı dışarıdan değişmiş olabilir); mevcut ayar korunuyor.") }; return false }
        update { copy(plan = PlanState(), resourceDisposition = if (resourceDisposition == "KEPT_ACTIVE" && candidate?.strategyId != null) "NONE" else resourceDisposition,
            results = results.mapValues { if (it.value.strategyId == plan.strategyId) it.value.copy(stale = true) else it.value }) }
        return true
    }

    /**
     * Kurtarma kilidini çözmeyi dener. Etkin bir operasyon varsa geri alma yeniden
     * denenir; yoksa (bozuk kayıt, süreç yeniden başlamış — native durum süreç
     * kapsamlıdır) kayıt temizlenip deneme hazır duruma döner.
     */
    suspend fun retryRecovery() {
        work?.cancelAndJoin(); inactivity?.cancel(); work = null
        mutex.withLock {
            if (state.value.phase != Phase.RECOVERY_REQUIRED) return
            val wasBlocked = recoveryBlocked
            recoveryBlocked = false
            val ok = if (state.value.operationId.isBlank()) true else rollback()
            if (!ok) { recoveryBlocked = wasBlocked; return }
            runCatching { adapter.clearRecovery() }
            pending = ArrayDeque(); pendingResolution = null
            update { copy(phase = if (enabledFlag) Phase.IDLE else Phase.DISABLED, resourceDisposition = "NONE", operationId = "",
                finishReason = null, pauseReason = null, message = "Kurtarma tamamlandı; yeni deneme başlatılabilir.") }
        }
    }

    /** Yalnız geçici denemeyi geri alır. Motorda kalıcılaştırılmış plan buradan geri alınmaz. */
    private suspend fun rollback(): Boolean {
        if (recoveryBlocked) return false
        val s = state.value
        if (s.operationId.isBlank() || s.resourceDisposition == "NONE" && s.phase !in setOf(Phase.APPLYING, Phase.PREPARING, Phase.RECOVERY_REQUIRED)) return true
        if (s.resourceDisposition == "KEPT_ACTIVE" && s.candidate?.strategyId != null) return true
        update { copy(phase = Phase.ROLLING_BACK, resourceDisposition = "ROLLBACK_PENDING") }
        val ok = runCatching {
            adapter.journal(s.operationId, "ROLLBACK_INTENT_DURABLE", s.candidate)
            adapter.rollback(s.operationId)
        }.getOrDefault(false)
        if (ok) {
            runCatching { adapter.journal(s.operationId, "ROLLED_BACK", s.candidate) }
            update { copy(resourceDisposition = "NONE", phase = Phase.SELECTING) }
        } else update { copy(phase = Phase.RECOVERY_REQUIRED, resourceDisposition = "RECOVERY_REQUIRED", message = "Geri alma doğrulanamadı. Yeni deneme engellendi.") }
        return ok
    }

    private fun waitForUser() {
        update { copy(phase = Phase.WAITING_FOR_USER, pauseReason = null) }
        val attempt = state.value.attemptId
        inactivity?.cancel()
        inactivity = scope.launch {
            delay(15 * 60_000L)
            mutex.withLock { if (state.value.phase == Phase.WAITING_FOR_USER && state.value.attemptId == attempt && rollback())
                update { copy(phase = Phase.PAUSED, pauseReason = "USER_INACTIVE", message = "Yanıt bekleme süresi doldu; başarısızlık kaydedilmedi.") } }
        }
    }
    private fun update(block: TrialState.() -> TrialState) {
        val previous = state.value
        mutable.value = previous.block().copy(version = previous.version + 1)
        runCatching { adapter.persist(mutable.value) }
        if (mutable.value.results != previous.results || mutable.value.plan.strategyId != previous.plan.strategyId)
            runCatching { adapter.persistResults(mutable.value.results.values, mutable.value.plan.strategyId) }
    }
    private fun label(t: Target) = SocialPlatform.byId(t.platformId)?.takeIf { it != SocialPlatform.CUSTOM }?.displayName ?: t.key.removePrefix("custom:")
    private fun dnsNote(dns: String) = when (dns) {
        "SYSTEM_DNS_FAILED" -> " Sistem DNS'i bu adı çözemedi; şifreli DNS çözdü (DNS engeli şüphesi, DPI'dan ayrı)."
        "SYSTEM_DNS_NON_PUBLIC" -> " Sistem DNS'i yerel/özel bir adres döndürdü (DNS yönlendirmesi şüphesi)."
        else -> ""
    }
    private fun id() = UUID.randomUUID().toString()

    companion object {
        private val ServerGates = setOf("AGE_OR_ID_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "BOT_CHALLENGE", "ACCOUNT_RESTRICTION")
        /** Deneme sırasında kullanıcı yanıtları ("Emin değilim" = UNSURE; başarısızlık değildir). */
        val FeedbackOutcomes = setOf("STILL_PRESENT", "RESOLVED", "PARTIAL", "PARTIAL_ACCEPTED", "NOT_TESTED", "UNSURE", "VERIFICATION_REQUIRED")
        /** Tur dışı hedef bildirimi: Çalışıyor / Kısmen / Çalışmıyor / Doğrulama istiyor / Emin değilim. */
        val ReportOutcomes = setOf("RESOLVED", "PARTIAL", "STILL_PRESENT", "VERIFICATION_REQUIRED", "UNSURE", "NOT_TESTED")
        const val REQUEST_RESERVE = 2
        const val BYTE_RESERVE = 262_144L
        const val MAX_DIAGNOSTIC_BYTES = 5L * 1024 * 1024
        const val MAX_PROBE_MS = 120_000L
        const val MAX_ROUND_REQUESTS = 120
        /** Bağlam değişiminden sonra (tünel yeniden kurulurken) yeniden doğrulamadan önce beklenir. */
        const val REVERIFY_SETTLE_MS = 3_000L
    }
}
