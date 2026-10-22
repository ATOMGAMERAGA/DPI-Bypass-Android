package net.atom.dpibypass.social

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.json.JSONObject
import kotlin.coroutines.resume
import java.io.ByteArrayInputStream

/** Operation ownership prevents an old view restoring the cookie policy of a newer anonymous trial. */
private object PublicWebCookieLease {
    private var owner: String? = null
    private var previous = true
    @Synchronized fun acquire(operation: String): Boolean {
        val cookies = CookieManager.getInstance()
        if (owner != null && cookies.acceptCookie()) return false
        if (owner == null) previous = cookies.acceptCookie()
        owner = operation; cookies.setAcceptCookie(false); return true
    }
    @Synchronized fun release(operation: String) {
        if (owner != operation) return
        val cookies = CookieManager.getInstance()
        if (!cookies.acceptCookie()) cookies.setAcceptCookie(previous)
        owner = null
    }
}

/** Public content only; this WebView has no access to native-app/browser account sessions. */
@Composable
fun ControlledWebProbe(state: TrialState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val target = state.target ?: return
    val lifecycle = LocalLifecycleOwner.current
    // Gözlemci betiği ana iş parçacığını bekletmeden okunur; hazır olana kadar gözlem başlamaz.
    val script by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { context.assets.open("social-observer.js").bufferedReader().use { it.readText() } }
    }
    var visible by remember { mutableStateOf(true) }
    var navigation by remember { mutableLongStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val latestDocument = rememberUpdatedState(state.documentGeneration)
    val observerDeadline = remember(state.operationId, state.documentGeneration) { android.os.SystemClock.elapsedRealtime() + 20_000L }
    val view = remember(state.operationId) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            // This is the app's only WebView, and this adapter is anonymous-only. Do not send/import account cookies.
            val anonymous = PublicWebCookieLease.acquire(state.operationId)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            // Never read or export cookies, passwords, body text, HTML or screenshots.
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val permitted = Target.resourceAllowed(url) && (request.isForMainFrame.not() || Target.parse(target.platformId, url)?.contentId == target.contentId)
                    return if (permitted) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val next = Target.parse(target.platformId, request.url.toString())
                    return next?.contentId != target.contentId
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { navigation++; failed = false }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) { if (request?.isForMainFrame == true) failed = true }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel(); failed = true }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { failed = true; return true }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onShowFileChooser(webView: WebView?, filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                    filePathCallback?.onReceiveValue(null); return true
                }
            }
            if (anonymous) loadUrl(target.url) else failed = true
        }
    }
    DisposableEffect(view, lifecycle) {
        val ownedOperation = state.operationId
        SocialAccessRuntime.adapter.registerRenderer(ownedOperation) { requested -> withContext(Dispatchers.Main.immediate) {
            if (requested != ownedOperation) false else {
                view.stopLoading(); view.onPause()
                view.evaluateJavascript("if(window.__dpiAlphaObservationV1&&window.__dpiAlphaObservationV1.dispose)window.__dpiAlphaObservationV1.dispose();delete window.__dpiAlphaObservationV1", null)
                PublicWebCookieLease.release(ownedOperation)
                true
            }
        } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { visible = false; view.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) { visible = true; view.onResume() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            SocialAccessRuntime.launch { rendererLost(ownedOperation, latestDocument.value) }
            lifecycle.lifecycle.removeObserver(observer)
            view.stopLoading(); view.webChromeClient = null; view.webViewClient = WebViewClient()
            view.evaluateJavascript("if(window.__dpiAlphaObservationV1&&window.__dpiAlphaObservationV1.dispose)window.__dpiAlphaObservationV1.dispose()", null)
            view.destroy()
            PublicWebCookieLease.release(ownedOperation)
            SocialAccessRuntime.adapter.forgetRenderer(ownedOperation)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    LaunchedEffect(state.operationId, state.documentGeneration, state.phase, visible, navigation, script) {
        val observerScript = script ?: return@LaunchedEffect
        if (state.phase != Phase.AUTO_EVALUATING) {
            view.evaluateJavascript("if(window.__dpiAlphaObservationV1&&window.__dpiAlphaObservationV1.dispose)window.__dpiAlphaObservationV1.dispose();delete window.__dpiAlphaObservationV1", null)
            return@LaunchedEffect
        }
        if (android.os.SystemClock.elapsedRealtime() >= observerDeadline) {
            SocialAccessRuntime.launch { rendererLost(state.operationId, state.documentGeneration) }
            return@LaunchedEffect
        }
        view.evaluateJavascript("if(window.__dpiAlphaObservationV1&&window.__dpiAlphaObservationV1.dispose)window.__dpiAlphaObservationV1.dispose();delete window.__dpiAlphaObservationV1", null)
        val began = android.os.SystemClock.elapsedRealtime()
        val thisNavigation = navigation
        var firstPositive = 0L
        var positives = 0
        var finalObservation: Observation? = null
        repeat(18) {
            delay(1000)
            if (state.operationId != SocialAccessRuntime.coordinator.state.value.operationId || navigation != thisNavigation) return@LaunchedEffect
            val actual = Target.parse(target.platformId, view.url ?: "")
            val json = if (visible && !failed) runCatching {
                val raw = suspendCancellableCoroutine<String> { continuation ->
                    view.evaluateJavascript(observerScript) { answer -> if (continuation.isActive) continuation.resume(answer ?: "null") }
                }
                check(raw.length <= 16384)
                val value = JSONObject(JSONTokener(raw).nextValue() as String)
                val allowed = setOf("contentId", "canonicalMatch", "contentPresent", "blockingGate", "blockingClass", "mediaProgress", "mediaProgressSeconds",
                    "freshEvidence", "documentVisible", "seeking", "advertisement", "preview", "needsInteraction", "knownLayout")
                check(value.keys().asSequence().all { it in allowed })
                check(setOf("canonicalMatch", "seeking", "advertisement", "preview", "needsInteraction", "knownLayout").all { value.opt(it) is Boolean })
                value
            }.getOrNull() else null
            fun tri(key: String) = Tri.entries.firstOrNull { it.name == json?.optString(key) } ?: Tri.UNKNOWN
            val exact = actual?.contentId == target.contentId && json?.optString("contentId") == target.contentId && json?.optBoolean("canonicalMatch") == true
            val positive = exact && tri("contentPresent") == Tri.TRUE && tri("blockingGate") == Tri.FALSE && tri("documentVisible") == Tri.TRUE
            if (positive) { if (positives == 0) firstPositive = android.os.SystemClock.elapsedRealtime(); positives++ } else { positives = 0; firstPositive = 0 }
            val o = Observation(sessionId = state.sessionId, attemptId = state.attemptId, operationId = state.operationId,
                contextGeneration = state.contextGeneration, modeGeneration = state.modeGeneration, documentGeneration = state.documentGeneration,
                goalHash = state.goal.hashCode().toString(), targetRef = target.targetRef, observerVersion = GoalEvaluator.VERSION,
                evaluatorVersion = GoalEvaluator.VERSION, actualSurface = "CONTROLLED_WEB_VIEW", identityContext = "ANONYMOUS",
                routeProvenance = "EXACT_TARGET_PATH", targetMatched = if (exact) Tri.TRUE else Tri.UNKNOWN,
                contentPresent = tri("contentPresent"), blockingGate = tri("blockingGate"), mediaProgress = tri("mediaProgress"),
                freshEvidence = tri("freshEvidence"), documentVisible = if (visible) tri("documentVisible") else Tri.FALSE,
                observedCapabilities = buildSet { if (positive) add("VIEW_SELECTED_CONTENT"); if (tri("mediaProgress") == Tri.TRUE) add("PLAY_MEDIA") },
                stableSamples = positives, stableDurationMs = if (firstPositive > 0) android.os.SystemClock.elapsedRealtime() - firstPositive else 0,
                mediaProgressSeconds = json?.optDouble("mediaProgressSeconds", 0.0) ?: 0.0,
                seeking = json?.optBoolean("seeking") ?: false, advertisement = json?.optBoolean("advertisement") ?: false,
                preview = json?.optBoolean("preview") ?: false, observerError = failed || json == null,
                needsInteraction = "PLAY_MEDIA" in state.goal!!.requiredCapabilities && json?.optBoolean("needsInteraction") == true,
                blockingClass = json?.optString("blockingClass", "UNKNOWN") ?: "UNKNOWN")
            finalObservation = o
            val verdict = GoalEvaluator.evaluate(state.goal, o)
            // The evaluator's minimum is 2s; this renderer leaves an extra quiet window for delayed gates.
            if (verdict == Verdict.PASS && o.stableDurationMs >= 5000 || verdict in setOf(Verdict.FAIL, Verdict.NEEDS_INTERACTION) || failed || !visible) {
                SocialAccessRuntime.launch { observe(o) }; return@LaunchedEffect
            }
            if (android.os.SystemClock.elapsedRealtime() >= observerDeadline || android.os.SystemClock.elapsedRealtime() - began >= 18_000) {
                SocialAccessRuntime.launch { observe(o) }; return@LaunchedEffect
            }
        }
        finalObservation?.let { observation -> SocialAccessRuntime.launch { observe(observation) } }
    }
}
