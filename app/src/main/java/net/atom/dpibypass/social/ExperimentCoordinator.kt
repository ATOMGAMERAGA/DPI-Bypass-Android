package net.atom.dpibypass.social

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class NetworkEvidence(val technicalOutcome: String, val bytes: Long = 0, val requests: Int = 1,
    val reason: String = "NETWORK_PATH_ONLY", val retryAfterSeconds: Long = 0)
class IneligibleCandidate(val reasonCode: String) : IllegalStateException(reasonCode)

/** The existing VPN service remains the single native engine owner. */
interface TrialAdapter {
    fun available(): Boolean
    suspend fun apply(candidate: CandidateDefinition, operationId: String)
    suspend fun rollback(operationId: String): Boolean
    suspend fun probe(target: Target, throughCandidate: Boolean): NetworkEvidence
    fun journal(operationId: String, stage: String, candidate: CandidateDefinition?)
    fun persist(state: TrialState) {}
    fun quarantine(target: Target, goal: Goal) {}
    fun quarantined(target: Target, goal: Goal): Boolean = false
    fun cooldownRemainingMillis(): Long = 0
    fun persistCooldown(delayMillis: Long) {}
}

class ExperimentCoordinator(private val adapter: TrialAdapter, private val scope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(TrialState())
    val state = mutable.asStateFlow()
    private var pending = ArrayDeque<CandidateDefinition>()
    private var catalog = emptyList<CandidateDefinition>()
    private val eventIds = mutableSetOf<String>()
    private var work: Job? = null
    private var inactivity: Job? = null
    private var probeMs = 0L
    private var groupAttempts = 0
    private var autoCorrected = false
    private var meterAllowed = false
    private var observerAvailable = false
    private var recoveryBlocked = false
    @Volatile private var enabledFlag = false
    @Volatile private var pendingResolution: Feedback? = null
    private var cooldownUntil = now() + adapter.cooldownRemainingMillis()

    private fun cooldown(seconds: Long) {
        val delay = seconds.coerceIn(1, 86_400) * 1000
        cooldownUntil = now() + delay
        runCatching { adapter.persistCooldown(delay) }
    }

    suspend fun markRecoveryRequired() { mutex.withLock { recoveryBlocked = true; update { copy(phase = Phase.RECOVERY_REQUIRED,
        resourceDisposition = "RECOVERY_REQUIRED", message = "Kurtarma kaydı bozuk; yeni Alpha denemesi engellendi.") } } }

    fun enabled(value: Boolean) { enabledFlag = value; scope.launch { if (!value) stop(true) else mutex.withLock {
        if (state.value.phase == Phase.DISABLED) update { copy(phase = Phase.IDLE) }
    } } }

    suspend fun start(goal: Goal, target: Target, mode: TestMode, baselineStillPresent: Boolean?,
        metered: Boolean, allowMeteredRenderer: Boolean, candidates: List<CandidateDefinition>) {
        if (!enabledFlag) return
        if (now() < cooldownUntil) {
            mutex.withLock { update { copy(phase = Phase.PAUSED, pauseReason = "COOLDOWN", message = "Hedefin bekleme süresi henüz dolmadı.") } }
            return
        }
        stop()
        mutex.withLock {
            if (!enabledFlag || state.value.phase == Phase.RECOVERY_REQUIRED) return
            eventIds.clear(); probeMs = 0; groupAttempts = 0; pendingResolution = null; autoCorrected = adapter.quarantined(target, goal)
            meterAllowed = !metered || allowMeteredRenderer
            observerAvailable = goal.identityContext == "ANONYMOUS" && "CONTROLLED_WEB_VIEW" in goal.allowedSurfaces
            catalog = candidates.filter { it.schemaVersion == 2 && it.candidateVersion == "1.0.0" }.distinctBy { it.effectKey }
            pending = ArrayDeque(catalog)
            mutable.value = TrialState(phase = Phase.BASELINING, mode = mode, sessionId = id(), goal = goal, target = target,
                message = "A0: özgün hedefin mevcut durumu", version = state.value.version + 1)
            if (baselineStillPresent == false) {
                update { copy(phase = Phase.FINISHED, finishReason = "ALREADY_ACCESSIBLE", message = "Sorun başlangıçta yok; yöntem başarısı yazılmadı.") }
                return
            }
        }
        launchNext()
    }

    private fun launchNext() {
        work = scope.launch {
            mutex.withLock {
                if (!enabledFlag || state.value.phase in setOf(Phase.RECOVERY_REQUIRED, Phase.DISABLED) || state.value.finishReason?.startsWith("RESOLVED") == true) return@withLock
                if (state.value.phase == Phase.BASELINING && state.value.target != null) {
                    val began = now()
                    update { copy(requests = requests + 1, diagnosticBytes = diagnosticBytes + 262_144) }
                    val baseline = withTimeoutOrNull(20_000) { adapter.probe(state.value.target!!, false) }
                        ?: NetworkEvidence("UNKNOWN", bytes = 262_144, reason = "BASELINE_TRANSPORT_UNKNOWN")
                    probeMs += (now() - began).coerceAtLeast(0)
                    update { copy(technicalOutcome = baseline.technicalOutcome, diagnosticBytes = diagnosticBytes - 262_144 + baseline.bytes.coerceIn(0, 262_144),
                        message = "A0: ${baseline.reason}; özgün native/hesap içeriği bilinmiyor.") }
                    if (baseline.retryAfterSeconds > 0) {
                        cooldown(baseline.retryAfterSeconds)
                        update { copy(phase = Phase.PAUSED, pauseReason = "COOLDOWN", message = "Hedef hız sınırı bildirdi; yeni istek durduruldu.") }; return@withLock
                    }
                }
                while (pending.isNotEmpty()) {
                    if (!enabledFlag) { rollback(); return@withLock }
                    if (groupAttempts >= 12 || state.value.requests >= 24 || state.value.diagnosticBytes + 262_144 > 5L * 1024 * 1024 || probeMs >= 120_000) {
                        update { copy(phase = Phase.PAUSED, pauseReason = "BUDGET_LIMIT", message = "Grup bütçesi doldu; yalnız kalanlar sürdürülebilir.") }; return@withLock
                    }
                    val candidate = pending.removeFirst()
                    if (state.value.mode == TestMode.AUTO_BETA && candidate.surface == "OFFICIAL_WEB" && candidate.strategyId == null) {
                        update { copy(deferred = deferred + candidate.id, message = "Haricî tarayıcı gözlenemiyor; manuel inceleme için ayrıldı.") }; continue
                    }
                    if (candidate.strategyId != null && !adapter.available()) {
                        update { copy(deferred = deferred + candidate.id, message = "Yerel VPN bağlantısı gerekli; aday uygulanmadı.") }; continue
                    }
                    // Server/account gates are not a reason to repeatedly rotate packet profiles.
                    if (candidate.strategyId != null && state.value.goal?.problemClass in setOf("AGE_OR_ID_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "BOT_CHALLENGE", "ACCOUNT_RESTRICTION")) continue
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
                        val began = now()
                        // Reserve one request and the HTTP implementation's maximum header allocation before transport.
                        update { copy(requests = requests + 1, diagnosticBytes = diagnosticBytes + 262_144) }
                        val evidence = withTimeoutOrNull(20_000) { adapter.probe(state.value.target!!, candidate.strategyId != null) }
                            ?: NetworkEvidence("UNKNOWN", bytes = 262_144, reason = "PROBE_TIMEOUT")
                        probeMs += (now() - began).coerceAtLeast(0)
                        update { copy(technicalOutcome = evidence.technicalOutcome,
                            diagnosticBytes = diagnosticBytes - 262_144 + evidence.bytes.coerceIn(0, 262_144),
                            message = "${candidate.displayName}: ${evidence.reason}. Bağlantı testi içerik/hesap başarısı değildir.") }
                        if (evidence.retryAfterSeconds > 0) {
                            cooldown(evidence.retryAfterSeconds)
                            if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "COOLDOWN", message = "Hız sınırı: ${evidence.retryAfterSeconds} saniye bekleyin.") }
                            return@withLock
                        }
                        val resolution = pendingResolution
                        if (resolution != null && resolution.operationId == operation && resolution.sessionId == state.value.sessionId &&
                            resolution.contextGeneration == state.value.contextGeneration && resolution.modeGeneration == state.value.modeGeneration) {
                            pendingResolution = null
                            adapter.journal(operation, "KEEP_INTENT_DURABLE", candidate); adapter.journal(operation, "KEPT", candidate)
                            update { copy(phase = Phase.FINISHED, resourceDisposition = "KEPT_ACTIVE", userOutcome = "RESOLVED",
                                finishReason = "RESOLVED_USER_REPORTED", message = "Kullanıcı erişim bildirdi; otomatik arama durdu.") }
                            return@withLock
                        }
                        if (state.value.mode == TestMode.MANUAL) { waitForUser(); return@withLock }
                        if (!observerAvailable || !meterAllowed || candidate.strategyId != null) {
                            update { copy(deferred = deferred + candidate.id, autoVerdict = Verdict.UNSUPPORTED,
                                message = if (!meterAllowed) "Ölçülü bağlantıda ek veri tercihi olmadan WebView başlatılmaz." else "MANUAL_REVIEW_REQUIRED: bu yüzey/hesap için içerik gözlemi yok.") }
                            if (!rollback()) return@withLock
                            continue
                        }
                        if (state.value.rendererLoads >= 6) {
                            pending.addFirst(candidate)
                            if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "BUDGET_LIMIT") }
                            return@withLock
                        }
                        update { copy(phase = Phase.AUTO_EVALUATING, rendererLoads = rendererLoads + 1,
                            message = "Test penceresinde herkese açık içerik; veri bütçesi tahmini. Native hesap gözlenmez.") }
                        return@withLock
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { rollback() }; throw e
                    } catch (e: Exception) {
                        update { copy(deferred = if (e is IneligibleCandidate) deferred else deferred + candidate.id,
                            message = if (e is IneligibleCandidate) "Aday gerçek ayar farkı üretmiyor; denenmiş sayılmadı." else "İşlem/gözlem hatası; aday başarısız sayılmadı.") }
                        if (!rollback()) return@withLock
                    }
                }
                if (state.value.mode == TestMode.MANUAL && state.value.deferred.isNotEmpty()) {
                    update { copy(phase = Phase.PAUSED, pauseReason = "PERMISSION_REQUIRED", message = "Bazı yöntemler için yerel DPI bağlantısı veya kullanıcı eylemi gerekiyor; denenmiş sayılmadılar.") }
                    return@withLock
                }
                update { copy(phase = Phase.FINISHED, finishReason = if (mode == TestMode.AUTO_BETA && !observerAvailable) "AUTO_UNAVAILABLE"
                    else if (deferred.isNotEmpty()) "AUTO_INCONCLUSIVE" else if (attempted == 0) "NO_APPLICABLE_METHODS" else "CANDIDATES_EXHAUSTED") }
            }
        }
    }

    suspend fun observe(o: Observation) {
        var next = false
        mutex.withLock {
            val s = state.value
            if (s.phase != Phase.AUTO_EVALUATING || s.mode != TestMode.AUTO_BETA || o.sessionId != s.sessionId || o.attemptId != s.attemptId || o.operationId != s.operationId ||
                o.contextGeneration != s.contextGeneration || o.modeGeneration != s.modeGeneration || o.documentGeneration != s.documentGeneration || o.targetRef != s.target?.targetRef || o.goalHash != s.goal.hashCode().toString()) return
            val verdict = if (autoCorrected) Verdict.INCONCLUSIVE else GoalEvaluator.evaluate(s.goal!!, o)
            update { copy(autoVerdict = verdict) }
            when (verdict) {
                Verdict.PASS -> {
                    adapter.journal(s.operationId, "KEEP_INTENT_DURABLE", s.candidate)
                    adapter.journal(s.operationId, "KEPT", s.candidate)
                    update { copy(phase = Phase.FINISHED, finishReason = "RESOLVED_AUTO_OBSERVED", resourceDisposition = "KEPT_ACTIVE",
                        message = "Seçilen işlem test penceresinde çalıştı. Native uygulama için sonuç değildir; nedensellik kurulmadı.") }
                }
                Verdict.NEEDS_INTERACTION -> update { copy(phase = Phase.PAUSED, pauseReason = "USER_ACTION_REQUIRED", message = "Test penceresinde oynat'a dokunun; sonra devam edin.") }
                else -> {
                    if (verdict == Verdict.FAIL && o.blockingClass in setOf("AGE_OR_ID_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "BOT_CHALLENGE", "ACCOUNT_RESTRICTION")) {
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
            if (event.outcome !in setOf("STILL_PRESENT", "RESOLVED", "PARTIAL", "PARTIAL_ACCEPTED", "NOT_TESTED") || !accepts || event.sessionId != s.sessionId || event.attemptId != s.attemptId || event.operationId != s.operationId ||
                event.contextGeneration != s.contextGeneration || event.modeGeneration != s.modeGeneration || event.expectedStateVersion != s.version || !eventIds.add(event.eventId)) return
            inactivity?.cancel()
            update { copy(userOutcome = event.outcome) }
            when (event.outcome) {
                "RESOLVED", "PARTIAL_ACCEPTED" -> {
                    adapter.journal(s.operationId, "KEEP_INTENT_DURABLE", s.candidate); adapter.journal(s.operationId, "KEPT", s.candidate)
                    update { copy(phase = Phase.FINISHED, resourceDisposition = "KEPT_ACTIVE", finishReason = if (event.outcome == "RESOLVED") "RESOLVED_USER_REPORTED" else "PARTIAL_ACCEPTED", message = "Kullanıcı erişim bildirdi; yeni arama durdu.") }
                }
                "PARTIAL" -> { update { copy(message = "Bir kısmı çalışıyor: böyle kullan veya devam et.") }; waitForUser() }
                "STILL_PRESENT" -> if (rollback()) next = true
                "NOT_TESTED" -> if (rollback()) update { copy(phase = Phase.PAUSED, pauseReason = "USER_UNABLE_TO_TEST") }
            }
        }
        if (next) launchNext()
    }

    suspend fun manual() {
        work?.cancelAndJoin()
        var startDeferred = false
        mutex.withLock {
            update { copy(mode = TestMode.MANUAL, modeGeneration = modeGeneration + 1, documentGeneration = documentGeneration + 1, finishReason = null) }
            if (state.value.resourceDisposition in setOf("TRIAL_ACTIVE", "KEPT_ACTIVE")) waitForUser()
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
            if (now() < cooldownUntil) return
            if (state.value.pauseReason == "USER_ACTION_REQUIRED" && state.value.resourceDisposition == "TRIAL_ACTIVE") {
                update { copy(phase = Phase.AUTO_EVALUATING, documentGeneration = documentGeneration + 1, pauseReason = null) }; return
            }
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

    suspend fun contextChanged() {
        stop()
        mutex.withLock { if (state.value.phase != Phase.RECOVERY_REQUIRED) update { copy(phase = Phase.PAUSED, contextGeneration = contextGeneration + 1,
            pauseReason = "CONTEXT_CHANGED", finishReason = null, message = "Alttaki ağ değişti; hedefi yeniden başlatıp baseline alın.") } }
    }

    suspend fun stop(disable: Boolean = false) {
        work?.cancelAndJoin(); inactivity?.cancel(); work = null
        mutex.withLock {
            if (!rollback()) return
            update { copy(phase = if (disable) Phase.DISABLED else Phase.FINISHED, finishReason = "CANCELLED", modeGeneration = modeGeneration + 1,
                documentGeneration = documentGeneration + 1, message = "Alpha kaynakları geri alındı.") }
        }
    }

    private suspend fun rollback(): Boolean {
        if (recoveryBlocked) return false
        val s = state.value
        if (s.operationId.isBlank() || s.resourceDisposition == "NONE" && s.phase !in setOf(Phase.APPLYING, Phase.PREPARING, Phase.RECOVERY_REQUIRED)) return true
        update { copy(phase = Phase.ROLLING_BACK, resourceDisposition = "ROLLBACK_PENDING") }
        val ok = runCatching {
            adapter.journal(s.operationId, "ROLLBACK_INTENT_DURABLE", s.candidate)
            adapter.rollback(s.operationId)
        }.getOrDefault(false)
        if (ok) {
            adapter.journal(s.operationId, "ROLLED_BACK", s.candidate)
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
        mutable.value = state.value.block().copy(version = state.value.version + 1)
        adapter.persist(mutable.value)
    }
    private fun id() = UUID.randomUUID().toString()
}
