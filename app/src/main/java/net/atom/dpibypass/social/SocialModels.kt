package net.atom.dpibypass.social

import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.net.InetAddress

enum class TestMode { MANUAL, AUTO_BETA;
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: MANUAL }
}
enum class Tri { TRUE, FALSE, UNKNOWN }
enum class Verdict { PASS, FAIL, INCONCLUSIVE, UNSUPPORTED, NEEDS_INTERACTION, CANCELLED, NOT_RUN }
enum class Phase { DISABLED, IDLE, BASELINING, SELECTING, PREPARING, APPLYING, PROBING, AUTO_EVALUATING,
    WAITING_FOR_USER, CANCELLING, ROLLING_BACK, PAUSED, FINISHED, RECOVERY_REQUIRED }
enum class ProblemClass { NETWORK_UNREACHABLE, DNS_FAILURE_OR_INTERFERENCE, TLS_OR_SNI_INTERFERENCE_SUSPECTED,
    QUIC_OR_UDP_PATH_FAILURE, MEDIA_PATH_FAILURE, POSSIBLE_THROTTLING, LOGIN_REQUIRED,
    AGE_OR_ID_VERIFICATION_REQUIRED, BOT_CHALLENGE, ACCOUNT_RESTRICTION, CONTENT_NOT_AVAILABLE,
    RATE_LIMITED, CAPTIVE_PORTAL, SERVICE_OUTAGE_SUSPECTED, MIXED, UNKNOWN }

object PublicTargetAddress {
    fun allowed(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 16) {
            if (bytes[0] and 254 == 252) return false
            if (bytes.take(12) == listOf(0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0))
                return allowed(InetAddress.getByAddress(address.address.takeLast(4).toByteArray()))
            return true
        }
        return bytes[0] !in setOf(0, 10, 127) && bytes[0] < 224 && !(bytes[0] == 100 && bytes[1] in 64..127) &&
            !(bytes[0] == 169 && bytes[1] == 254) && !(bytes[0] == 172 && bytes[1] in 16..31) &&
            !(bytes[0] == 192 && bytes[1] == 168) && !(bytes[0] == 198 && bytes[1] in 18..19)
    }
}

data class Goal(val platformId: String = "youtube", val problemClass: String = "UNKNOWN",
    val identityContext: String = "SAME_ACCOUNT", val allowedSurfaces: Set<String> = setOf("OFFICIAL_NATIVE_APP"),
    val requiredCapabilities: Set<String> = setOf("VIEW_SELECTED_CONTENT"), val publicFallbackAllowed: Boolean = false) {
    companion object {
        fun fromJson(j: JSONObject) = Goal(j.optString("platformId", "youtube"), j.optString("problemClass", "UNKNOWN"),
            j.optString("identityContext", "UNKNOWN"), j.strings("allowedSurfaces"), j.strings("requiredCapabilities"), j.optBoolean("publicFallbackAllowed"))
    }
}

data class Target(val platformId: String, val url: String, val contentId: String, val targetRef: String = UUID.randomUUID().toString()) {
    companion object {
        fun resourceAllowed(raw: String): Boolean = runCatching {
            val u = URI(raw)
            if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443)) return false
            val host = u.host?.lowercase() ?: return false
            setOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com", "google.com",
                "instagram.com", "cdninstagram.com", "fbcdn.net").any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
        fun parse(platform: String, raw: String): Target? = runCatching {
            val u = URI(raw.trim())
            if (u.scheme != "https" || u.userInfo != null || u.port !in listOf(-1, 443) || u.fragment != null) return null
            val host = u.host?.lowercase() ?: return null
            val path = u.rawPath ?: "/"
            val canonical: String
            val id: String
            when (platform) {
                "youtube" -> {
                    if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")) return null
                    id = if (host == "youtu.be") path.removePrefix("/") else if (path == "/watch") {
                        u.rawQuery?.split('&')?.singleOrNull { it.startsWith("v=") }?.removePrefix("v=") ?: return null
                    } else if (path.startsWith("/shorts/")) path.removePrefix("/shorts/") else return null
                    if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
                    canonical = "https://www.youtube.com/watch?v=$id"
                }
                "instagram" -> {
                    if (host !in setOf("instagram.com", "www.instagram.com")) return null
                    val match = Regex("/(p|reel)/([A-Za-z0-9_-]{3,80})/?").matchEntire(path) ?: return null
                    id = match.groupValues[2]
                    canonical = "https://www.instagram.com/${match.groupValues[1]}/$id/"
                }
                else -> return null
            }
            Target(platform, canonical, id)
        }.getOrNull()
    }
}

data class Observation(val schemaVersion: Int = 2, val sessionId: String = "", val attemptId: String = "",
    val operationId: String = "", val contextGeneration: Long = 0, val modeGeneration: Long = 0,
    val documentGeneration: Long = 0, val goalHash: String = "", val targetRef: String = "",
    val observerVersion: String = "", val evaluatorVersion: String = "", val actualSurface: String = "UNKNOWN",
    val identityContext: String = "UNKNOWN", val identityEvidence: String = "UNKNOWN", val routeProvenance: String = "UNVERIFIED",
    val targetMatched: Tri = Tri.UNKNOWN, val contentPresent: Tri = Tri.UNKNOWN, val blockingGate: Tri = Tri.UNKNOWN,
    val mediaProgress: Tri = Tri.UNKNOWN, val freshEvidence: Tri = Tri.UNKNOWN, val documentVisible: Tri = Tri.UNKNOWN,
    val observedCapabilities: Set<String> = emptySet(), val stableSamples: Int = 0, val stableDurationMs: Long = 0,
    val mediaProgressSeconds: Double = 0.0, val seeking: Boolean = false, val advertisement: Boolean = false,
    val preview: Boolean = false, val observerError: Boolean = false, val needsInteraction: Boolean = false,
    val blockingClass: String = "UNKNOWN") {
    companion object {
        fun fromJson(j: JSONObject): Observation {
            val allowed = setOf("schemaVersion", "sessionId", "attemptId", "operationId", "contextGeneration", "modeGeneration", "documentGeneration",
                "goalHash", "targetRef", "observerId", "observerVersion", "evaluatorVersion", "actualSurface", "identityContext", "identityEvidence", "routeProvenance",
                "targetMatched", "contentPresent", "blockingGate", "mediaProgress", "freshEvidence", "documentVisible", "observedCapabilities", "stableSamples",
                "stableDurationMs", "mediaProgressSeconds", "seeking", "advertisement", "preview", "observerError", "needsInteraction", "blockingClass",
                "tabId", "signalSource", "observedAtMonotonic", "observationWindow", "budgetAccuracy", "cacheUncertain", "rendererLoadCount", "autoVerdict", "reasonCodes")
            if (j.keys().asSequence().any { it !in allowed } || j.opt("schemaVersion") !is Int && j.opt("schemaVersion") !is Long)
                return Observation(observerError = true)
            val booleanFields = setOf("seeking", "advertisement", "preview", "observerError", "needsInteraction", "cacheUncertain")
            if (booleanFields.any { j.has(it) && j.opt(it) !is Boolean } ||
                j.optString("identityEvidence", "UNKNOWN") !in setOf("UNKNOWN", "USER_BOUND_SESSION", "PLATFORM_SIGNAL") ||
                j.optString("observerId", "youtube-dom") !in setOf("youtube-dom", "controlled-web-v1") ||
                j.opt("stableSamples") !is Int && j.opt("stableSamples") !is Long) return Observation(observerError = true)
            for (key in setOf("observedAtMonotonic", "observationWindow", "mediaProgressSeconds", "stableDurationMs", "rendererLoadCount")) {
                if (j.has(key)) {
                    val number = j.opt(key) as? Number ?: return Observation(observerError = true)
                    if (!number.toDouble().isFinite() || number.toDouble() < 0) return Observation(observerError = true)
                }
            }
            if (j.optBoolean("cacheUncertain", false)) return Observation(observerError = true)
            fun tri(key: String) = Tri.entries.firstOrNull { it.name == j.optString(key) } ?: Tri.UNKNOWN
            return Observation(j.optInt("schemaVersion", -1), j.optString("sessionId"), j.optString("attemptId"), j.optString("operationId"),
                j.optLong("contextGeneration"), j.optLong("modeGeneration"), j.optLong("documentGeneration"), j.optString("goalHash"), j.optString("targetRef"),
                j.optString("observerVersion"), j.optString("evaluatorVersion"), j.optString("actualSurface", "UNKNOWN"), j.optString("identityContext", "UNKNOWN"),
                j.optString("identityEvidence", "UNKNOWN"), j.optString("routeProvenance", "UNVERIFIED"), tri("targetMatched"), tri("contentPresent"), tri("blockingGate"),
                tri("mediaProgress"), tri("freshEvidence"), tri("documentVisible"), j.strings("observedCapabilities"), j.optInt("stableSamples"), j.optLong("stableDurationMs"),
                j.optDouble("mediaProgressSeconds", 0.0), j.optBoolean("seeking"), j.optBoolean("advertisement"), j.optBoolean("preview"), j.optBoolean("observerError"), j.optBoolean("needsInteraction"))
        }
    }
}

object GoalEvaluator {
    const val VERSION = "1.0.0"
    fun evaluate(g: Goal, o: Observation): Verdict {
        if (g.platformId !in setOf("youtube", "instagram") || g.identityContext !in setOf("ANONYMOUS", "SAME_ACCOUNT") ||
            g.requiredCapabilities.isEmpty() || !setOf("VIEW_SELECTED_CONTENT", "PLAY_MEDIA", "VIEW_ACCOUNT_FEED").containsAll(g.requiredCapabilities) ||
            o.actualSurface !in setOf("OFFICIAL_WEB", "OFFICIAL_NATIVE_APP", "CONTROLLED_WEB_VIEW", "LOCAL_ALTERNATIVE_CLIENT") || !o.mediaProgressSeconds.isFinite()) return Verdict.INCONCLUSIVE
        if (o.schemaVersion != 2 || o.observerVersion != VERSION || o.evaluatorVersion != VERSION || o.observerError) return Verdict.INCONCLUSIVE
        if (o.identityEvidence !in setOf("UNKNOWN", "USER_BOUND_SESSION", "PLATFORM_SIGNAL")) return Verdict.INCONCLUSIVE
        if (o.targetMatched != Tri.TRUE || o.actualSurface !in g.allowedSurfaces || o.documentVisible != Tri.TRUE) return Verdict.INCONCLUSIVE
        if (o.identityContext != g.identityContext || (g.identityContext == "SAME_ACCOUNT" && o.identityEvidence !in setOf("USER_BOUND_SESSION", "PLATFORM_SIGNAL"))) return Verdict.INCONCLUSIVE
        if (o.blockingGate == Tri.TRUE) return Verdict.FAIL
        if (o.needsInteraction) return Verdict.NEEDS_INTERACTION
        if (o.routeProvenance != "EXACT_TARGET_PATH" || o.freshEvidence != Tri.TRUE || o.blockingGate != Tri.FALSE || o.contentPresent != Tri.TRUE) return Verdict.INCONCLUSIVE
        if (o.stableSamples < 2 || o.stableDurationMs < 2000 || o.advertisement || o.preview || o.seeking || !o.observedCapabilities.containsAll(g.requiredCapabilities)) return Verdict.INCONCLUSIVE
        if (g.problemClass == "POSSIBLE_THROTTLING") return Verdict.INCONCLUSIVE
        if (("PLAY_MEDIA" in g.requiredCapabilities || g.problemClass == "MEDIA_PATH_FAILURE") && (o.mediaProgress != Tri.TRUE || o.mediaProgressSeconds < 3.0)) return Verdict.INCONCLUSIVE
        return Verdict.PASS
    }
}

internal fun JSONObject.strings(key: String): Set<String> {
    val a = optJSONArray(key) ?: return emptySet()
    return (0 until a.length()).map { a.optString(it) }.toSet()
}

data class CandidateDefinition(val schemaVersion: Int = 2, val id: String, val family: String,
    val displayName: String, val strategyId: String? = null, val surface: String = "OFFICIAL_WEB",
    val effectKey: String = "$family:$strategyId:$surface", val candidateVersion: String = "1.0.0",
    val rollbackKind: String = "RELEASE_OWNED_RESOURCES_ONLY")

data class Feedback(val sessionId: String, val attemptId: String, val operationId: String, val contextGeneration: Long,
    val modeGeneration: Long, val eventId: String, val expectedStateVersion: Long, val outcome: String)

data class TrialState(val phase: Phase = Phase.DISABLED, val mode: TestMode = TestMode.MANUAL, val sessionId: String = "",
    val attemptId: String = "", val operationId: String = "", val contextGeneration: Long = 1, val modeGeneration: Long = 1,
    val documentGeneration: Long = 1, val version: Long = 0, val candidate: CandidateDefinition? = null,
    val finishReason: String? = null, val pauseReason: String? = null, val resourceDisposition: String = "NONE",
    val message: String = "", val technicalOutcome: String = "UNKNOWN", val autoVerdict: Verdict = Verdict.NOT_RUN,
    val userOutcome: String = "NOT_TESTED", val deferred: List<String> = emptyList(), val attempted: Int = 0,
    val requests: Int = 0, val diagnosticBytes: Long = 0, val rendererLoads: Int = 0,
    val causalAttribution: String = "NOT_ESTABLISHED", val goal: Goal? = null, val target: Target? = null) {
    fun feedback(outcome: String) = Feedback(sessionId, attemptId, operationId, contextGeneration, modeGeneration, UUID.randomUUID().toString(), version, outcome)
}
