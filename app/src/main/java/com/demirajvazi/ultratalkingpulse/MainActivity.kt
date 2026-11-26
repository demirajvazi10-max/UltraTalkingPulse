package com.demirajvazi.ultratalkingpulse

import android.Manifest
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.presagetech.smartspectra.CameraSelection
import com.presagetech.smartspectra.SmartSpectraConfig
import com.presagetech.smartspectra.SmartSpectraSdk
import com.presagetech.smartspectra.proto.MetricTypesProto.MetricType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREFS = "ultra_talking_pulse"
        // v2: the notice now also covers the optional AI explanation, so everyone sees it once more.
        const val KEY_CONSENT = "consent_v2"
        const val KEY_SPEECH = "speech"
        const val KEY_CUES = "cues"
        const val KEY_AI = "ai_explanation"
        const val KEY_READINESS = "readiness_verdict"
        const val KEY_READINESS_SDK = "readiness_sdk_version"

        /** How long a validation code must stay the same before it is spoken. */
        const val SETTLE_MS = 800L
        /** Minimum pause after one message finishes before the next one starts. */
        const val GAP_MS = 700L
        /** How long the position must be good before measuring starts. */
        const val OK_HOLD_MS = 1500L
        /** Measuring time, counted only while the position is good. HRV needs about 60 seconds. */
        const val MEASURE_SECONDS = 60
        const val MEASURE_MS = MEASURE_SECONDS * 1000L
        const val TALK_WARNING_INTERVAL_MS = 12_000L
        const val TICK_MS = 200L
        /** If the SDK sends no status at all within this time, the start is treated as failed. */
        const val START_TIMEOUT_MS = 25_000L
        /** How long to wait for the AI explanation before giving up. */
        const val INSIGHT_TIMEOUT_MS = 30_000L
        /** The phone check is tried this many times while the SDK is still signing in, this far apart. */
        const val READINESS_ATTEMPTS = 3
        const val READINESS_RETRY_MS = 3_000L
        /** Extra time the screen stays on after the last message, for slower speech rates. */
        const val SCREEN_RELEASE_MARGIN_MS = 15_000L

        // Guided breathing: in for 4 seconds, out for 6 (6 breaths a minute), 12 breaths = 2 minutes.
        const val INHALE_MS = 4_000L
        const val EXHALE_MS = 6_000L
        const val EXERCISE_BREATHS = 12
        /** When the live breathing rate is read out during the exercise. */
        const val EXERCISE_FEEDBACK_AT_MS = 60_000L
        /** During the exercise, a lost position is mentioned only after this long... */
        const val LOST_POSITION_MS = 5_000L
        /** ...and at most this often, so the breathing rhythm isn't drowned out. */
        const val LOST_POSITION_REPEAT_MS = 20_000L

        const val OK = "OK"
    }

    private enum class Mode { MEASURE, BREATHE }

    private enum class Phase { IDLE, CHECKING, POSITIONING, MEASURING, EXERCISING, EXPLAINING }

    private data class Reading(val value: Double, val confidence: Float)

    private val sdk by lazy { SmartSpectraSdk.shared }
    private lateinit var prefs: SharedPreferences
    private lateinit var announcer: Announcer

    // Views
    private lateinit var guidanceText: TextView
    private lateinit var progressText: TextView
    private lateinit var startButton: Button
    private lateinit var breatheButton: Button
    private lateinit var checkButton: Button
    private lateinit var pulseText: TextView
    private lateinit var breathingText: TextView
    private lateinit var hrvText: TextView
    private lateinit var insightText: TextView
    private lateinit var aiSwitch: Switch

    // Session state
    private var mode = Mode.MEASURE
    private var phase = Phase.IDLE
    private var tickerJob: Job? = null
    private var readinessJob: Job? = null
    private var screenReleaseJob: Job? = null
    private var currentCode: String? = null
    private var currentHint: String? = null
    private var candidateCode: String? = null
    private var candidateSince = 0L
    private var lastSpokenCode: String? = null
    private var okSince = 0L
    private var measuredMs = 0L
    private var lastTickAt = 0L
    private var halfwayAnnounced = false
    private var lastTalkWarningAt = 0L
    private var lastMessage: String = ""
    private var sessionStartedAt = 0L
    private var gotFirstStatus = false
    private var pulse: Reading? = null
    private var breathing: Reading? = null
    private var hrvMs: Double? = null

    // Guided breathing state
    private var exerciseMs = 0L
    private var nextCueAtMs = 0L
    private var breathsStarted = 0
    private var inhaleNext = true
    private var startPulse: Reading? = null
    private var startBreathing: Reading? = null
    private var feedbackGiven = false
    private var notOkSince = 0L
    private var lastPositionWarnAt = 0L

    // AI explanation state
    private var pendingInsightId: Int? = null
    private var explainStartedAt = 0L
    private var aiReply: String? = null
    private var resultsSummary: String = ""

    // What to do once the camera permission is granted.
    private var pendingMode: Mode? = null
    private var pendingCheckOnly = false

    // "Talking" detection is a nice extra. If the API key is not allowed to use it,
    // the session is restarted once without it.
    private var talkingRequested = true
    private var retryInFlight = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            showMessage(getString(R.string.camera_denied), speak = true)
        } else if (pendingCheckOnly) {
            runReadinessCheck(thenStart = null)
        } else {
            pendingMode?.let { proceedAfterPermission(it) }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        announcer = Announcer(this).apply {
            speechEnabled = prefs.getBoolean(KEY_SPEECH, true)
            cuesEnabled = prefs.getBoolean(KEY_CUES, true)
        }

        configureSdk()
        buildUi()
        bindSdk()

        lastMessage = getString(R.string.idle_hint)
        guidanceText.text = lastMessage

        if (!prefs.getBoolean(KEY_CONSENT, false)) {
            showConsentDialog(firstRun = true)
            // TalkBack reads the dialog itself; without a screen reader, say the key points aloud.
            if (!announcer.isScreenReaderOn()) announcer.say(getString(R.string.first_run_spoken), guidanceText)
        }
    }

    override fun onPause() {
        super.onPause()
        if (phase != Phase.IDLE) endSession(message = null)
        announcer.stopSpeaking()
    }

    override fun onDestroy() {
        super.onDestroy()
        announcer.shutdown()
    }

    // ---------------------------------------------------------------- SDK setup

    private fun configureSdk() {
        // Two ways to authenticate: an API key baked in at build time, or OAuth through
        // res/xml/presage_services.xml (no key in the app at all). OAuth is used when no key is set.
        if (BuildConfig.PRESAGE_API_KEY.isNotBlank()) sdk.config.apiKey = BuildConfig.PRESAGE_API_KEY
        sdk.config.imageOutputEnabled = false
        // Anonymous SDK usage statistics and readiness reports are opt-out; Ultra Talking Pulse opts out.
        sdk.config.enableTelemetry = false
        runCatching { sdk.useCamera(CameraSelection.Front) }
        applyRequestedMetrics()
    }

    private fun applyRequestedMetrics() {
        val extras = if (talkingRequested) listOf(MetricType.TALKING) else emptyList()
        sdk.config.requestedMetrics =
            SmartSpectraConfig.breathingMetrics + SmartSpectraConfig.cardioMetrics + extras
    }

    private fun bindSdk() {
        sdk.validationStatus.observe(this) { status ->
            if (status == null) return@observe
            onValidation(status.code.name, status.hint)
        }

        sdk.error.observe(this) { error ->
            if (error != null) log("SDK error: ${error.code.name} retryable=${error.retryable} phase=$phase: ${error.message}")
            if (error == null || !isSessionPhase()) return@observe
            onSessionFailure(error.code.name, error.message)
        }

        sdk.metrics.observe(this) { metrics ->
            if (metrics == null || !isSessionPhase()) return@observe

            if (metrics.hasCardio()) {
                metrics.cardio.pulseRateList.lastOrNull { it.timestamp > 0 }?.let {
                    if (pulse == null) log("first pulse: ${it.value} conf=${it.confidence} phase=$phase")
                    pulse = Reading(it.value.toDouble(), it.confidence)
                }
                metrics.cardio.hrvList.lastOrNull { it.rmssd > 0.0 && it.confidence > 0f }?.let {
                    if (hrvMs == null) log("first HRV: rmssd=${it.rmssd} conf=${it.confidence} stable=${it.stable}")
                    hrvMs = it.rmssd
                }
            }
            if (metrics.hasBreathing()) {
                metrics.breathing.rateList.lastOrNull { it.timestamp > 0 }?.let {
                    if (breathing == null) log("first breathing: ${it.value} conf=${it.confidence} phase=$phase")
                    breathing = Reading(it.value.toDouble(), it.confidence)
                }
            }
            if (phase == Phase.EXERCISING) {
                if (startPulse == null) startPulse = pulse
                if (startBreathing == null) startBreathing = breathing
            }
            if (talkingRequested && phase == Phase.MEASURING && metrics.hasFace()) {
                if (metrics.face.talkingList.lastOrNull()?.detected == true) warnAboutTalking()
            }
        }

        // AI explanation replies. Automatic "vitals check-in" insights also arrive here;
        // only the reply to our own request (matching request ID) is used.
        sdk.insight.observe(this) { insight ->
            if (insight != null) {
                log("insight received: requestId=${insight.requestId} waitingFor=$pendingInsightId phase=$phase " +
                    "hasAnalysis=${insight.hasAnalysis()}: ${insight.toString().replace('\n', ' ').take(600)}")
            }
            if (insight == null || phase != Phase.EXPLAINING) return@observe
            val id = pendingInsightId ?: return@observe
            if (insight.requestId != id) return@observe
            aiReply = if (insight.hasAnalysis()) cleanForSpeech(insight.analysis) else ""
        }
    }

    /** Diagnostic log (debug builds only). Read it with: adb logcat -d -s UTP */
    private fun log(message: String) {
        // Warning level: some phones (Honor, Huawei) hide info-level logs from apps.
        if (BuildConfig.DEBUG) android.util.Log.w("UTP", message)
    }

    private fun isSessionPhase() =
        phase == Phase.POSITIONING || phase == Phase.MEASURING ||
            phase == Phase.EXERCISING || phase == Phase.EXPLAINING

    // ---------------------------------------------------------------- starting

    private fun onModeButtonPressed(requested: Mode) {
        if (phase == Phase.CHECKING) {
            cancelReadinessCheck()
            return
        }
        if (phase != Phase.IDLE) {
            endSession(getString(if (mode == Mode.BREATHE) R.string.exercise_stopped else R.string.stopped))
            return
        }
        if (!preflightOk()) return
        pendingMode = requested
        pendingCheckOnly = false
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        proceedAfterPermission(requested)
    }

    private fun onCheckButtonPressed() {
        if (phase == Phase.CHECKING) {
            cancelReadinessCheck()
            return
        }
        if (phase != Phase.IDLE) return
        if (!preflightOk()) return
        pendingMode = null
        pendingCheckOnly = true
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        runReadinessCheck(thenStart = null)
    }

    /** Consent, credentials and internet: everything that must be true before the camera starts. */
    private fun preflightOk(): Boolean {
        if (!prefs.getBoolean(KEY_CONSENT, false)) {
            showMessage(getString(R.string.consent_needed), speak = true)
            showConsentDialog(firstRun = true)
            return false
        }
        if (BuildConfig.PRESAGE_API_KEY.isBlank() && !hasOAuthConfig()) {
            showMessage(getString(R.string.no_api_key), speak = true)
            return false
        }
        if (!isOnline()) {
            showMessage(getString(R.string.err_offline), speak = true)
            return false
        }
        return true
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Runs the phone check once per SDK version; after that, sessions start directly. */
    private fun proceedAfterPermission(requested: Mode) {
        val checkedFor = prefs.getString(KEY_READINESS_SDK, null)
        val verdict = prefs.getString(KEY_READINESS, null)
        if (checkedFor == sdk.version && (verdict == "READY" || verdict == "LIMITED")) {
            beginSession(requested)
        } else {
            runReadinessCheck(thenStart = requested)
        }
    }

    /** True when the phone has a working internet connection (Wi-Fi or mobile data). */
    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Suppress("DiscouragedApi") // Looked up by name because the file is optional and not committed.
    private fun hasOAuthConfig(): Boolean =
        resources.getIdentifier("presage_services", "xml", packageName) != 0

    // ---------------------------------------------------------------- phone check (Device Readiness)

    private fun runReadinessCheck(thenStart: Mode?) {
        phase = Phase.CHECKING
        setButtonsForChecking()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showMessage(getString(R.string.readiness_checking), speak = true)

        readinessJob = lifecycleScope.launch {
            var outcome = runCatching { sdk.checkDeviceReadiness() }
            // Right after the app starts, the SDK may still be signing in to Presage. Give it a moment.
            var attempts = 1
            while (attempts < READINESS_ATTEMPTS && outcome.getOrNull()?.let { isAuthNotReady(it.verdict.name, it.reason) } == true) {
                delay(READINESS_RETRY_MS)
                attempts++
                outcome = runCatching { sdk.checkDeviceReadiness() }
            }
            if (outcome.exceptionOrNull() is CancellationException) return@launch
            phase = Phase.IDLE
            readinessJob = null
            restoreIdleButtons()
            releaseScreenAfterSpeech(extraDelay = true)

            val result = outcome.getOrNull()
            if (result == null) {
                val e = outcome.exceptionOrNull()
                val text = getString(R.string.readiness_error, e?.message ?: e.toString())
                if (thenStart != null) offerToTryAnyway(text, thenStart) else showMessage(text, speak = true)
                return@launch
            }
            val reason = result.reason
            log("readiness: verdict=${result.verdict.name} attempts=$attempts reason=$reason")
            when (result.verdict.name) {
                "READY" -> {
                    saveReadiness("READY")
                    if (thenStart != null) {
                        beginSession(thenStart, prefix = getString(R.string.readiness_ready_short))
                    } else {
                        showMessage(getString(R.string.readiness_ready), speak = true)
                    }
                }
                "LIMITED" -> {
                    saveReadiness("LIMITED")
                    val text = getString(R.string.readiness_limited, reason)
                    if (thenStart != null) offerToTryAnyway(text, thenStart) else showMessage(text, speak = true)
                }
                "BLOCKED" -> {
                    val authNotReady = isAuthNotReady("BLOCKED", reason)
                    if (!authNotReady && talkingRequested) {
                        // The optional "talking" metric may not be allowed for this account: check again without it.
                        talkingRequested = false
                        applyRequestedMetrics()
                        runReadinessCheck(thenStart)
                    } else {
                        // Starting a measurement signs in to Presage by itself, so the user can still try.
                        val text = getString(
                            if (authNotReady) R.string.readiness_auth else R.string.readiness_blocked,
                            reason,
                        )
                        if (thenStart != null) offerToTryAnyway(text, thenStart) else showMessage(text, speak = true)
                    }
                }
                else -> {
                    val text = getString(R.string.readiness_inconclusive, reason)
                    if (thenStart != null) offerToTryAnyway(text, thenStart) else showMessage(text, speak = true)
                }
            }
        }
    }

    /** "Authentication is not ready": the SDK hasn't finished signing in yet, which is not a phone problem. */
    private fun isAuthNotReady(verdict: String, reason: String): Boolean =
        verdict == "BLOCKED" && reason.contains("authentic", ignoreCase = true)

    private fun cancelReadinessCheck() {
        readinessJob?.cancel()
        readinessJob = null
        phase = Phase.IDLE
        restoreIdleButtons()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { runCatching { sdk.stop() } }
        showMessage(getString(R.string.readiness_cancelled), speak = true)
    }

    private fun saveReadiness(verdict: String) {
        prefs.edit()
            .putString(KEY_READINESS, verdict)
            .putString(KEY_READINESS_SDK, sdk.version)
            .apply()
    }

    private fun offerToTryAnyway(text: String, requested: Mode) {
        showMessage(text + " " + getString(R.string.readiness_try_question), speak = true)
        AlertDialog.Builder(this)
            .setTitle(R.string.readiness_title)
            .setMessage(text + "\n\n" + getString(R.string.readiness_try_question))
            .setPositiveButton(R.string.btn_try_anyway) { _, _ -> beginSession(requested) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- session flow

    private fun beginSession(requested: Mode, prefix: String? = null) {
        log("session start: mode=$requested aiOn=${prefs.getBoolean(KEY_AI, false)} talking=$talkingRequested sdk=${sdk.version}")
        mode = requested
        phase = Phase.POSITIONING
        currentCode = null
        currentHint = null
        candidateCode = null
        lastSpokenCode = null
        okSince = 0L
        measuredMs = 0L
        halfwayAnnounced = false
        lastTalkWarningAt = 0L
        pulse = null
        breathing = null
        hrvMs = null
        exerciseMs = 0L
        nextCueAtMs = 0L
        breathsStarted = 0
        inhaleNext = true
        startPulse = null
        startBreathing = null
        feedbackGiven = false
        notOkSince = 0L
        lastPositionWarnAt = 0L
        pendingInsightId = null
        aiReply = null
        resultsSummary = ""

        pulseText.text = getString(R.string.no_results_yet)
        listOf(breathingText, hrvText, insightText).forEach {
            it.text = ""
            it.visibility = View.GONE
        }
        progressText.text = getString(R.string.progress_positioning)
        setButtonsForSession()
        // Keep the screen on for the whole session, so the phone doesn't lock mid-way.
        screenReleaseJob?.cancel()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val intro = getString(if (mode == Mode.BREATHE) R.string.breathe_intro else R.string.intro)
        showMessage(if (prefix != null) "$prefix $intro" else intro, speak = true)
        sessionStartedAt = SystemClock.elapsedRealtime()
        gotFirstStatus = false
        startSdk()
        startTicker()
    }

    private fun startSdk() {
        lifecycleScope.launch {
            runCatching { sdk.start() }
                .onSuccess { retryInFlight = false }
                .onFailure { e ->
                    retryInFlight = false
                    if (!isSessionPhase()) return@onFailure
                    val known = listOf(
                        "AUTHENTICATION_FAILED", "CREDIT_EXHAUSTED", "NETWORK_ERROR",
                        "SERVER_ERROR", "INPUT_UNAVAILABLE", "CONFIGURATION_FAILED",
                        "SUSTAINED_LOW_FRAME_RATE", "METRIC_NOT_AUTHORIZED",
                    ).firstOrNull { e.toString().contains(it) }
                    onSessionFailure(known ?: "UNKNOWN", e.message ?: e.toString())
                }
        }
    }

    private fun onSessionFailure(code: String, message: String) {
        if (!isSessionPhase()) return
        if (retryInFlight) return

        // The results are already out; a failure while waiting for the AI only loses the explanation.
        if (phase == Phase.EXPLAINING) {
            endSession(getString(R.string.ai_failed))
            return
        }

        // Without internet the SDK can't authenticate; say that plainly instead of a sign-in error.
        if (!isOnline()) {
            endSession(getString(R.string.err_offline))
            return
        }

        // First failure while asking for the optional "talking" metric: try again without it.
        val talkingMayBeTheCause = code == "CONFIGURATION_FAILED" ||
            code == "AUTHENTICATION_FAILED" || code == "METRIC_NOT_AUTHORIZED"
        if (talkingRequested && talkingMayBeTheCause) {
            talkingRequested = false
            retryInFlight = true
            sessionStartedAt = SystemClock.elapsedRealtime()
            lifecycleScope.launch {
                runCatching { sdk.reset() }
                applyRequestedMetrics()
                startSdk()
            }
            return
        }

        val text = when (code) {
            "AUTHENTICATION_FAILED" -> getString(R.string.err_auth)
            "CREDIT_EXHAUSTED" -> getString(R.string.err_credits)
            "NETWORK_ERROR", "SERVER_ERROR" -> getString(R.string.err_network)
            "INPUT_UNAVAILABLE" -> getString(R.string.err_camera)
            "SUSTAINED_LOW_FRAME_RATE" -> getString(R.string.err_low_fps)
            else -> getString(R.string.err_generic, message)
        }
        endSession(text)
    }

    private fun onValidation(code: String, hint: String?) {
        gotFirstStatus = true
        currentCode = code
        currentHint = hint
        if (code != candidateCode) {
            candidateCode = code
            candidateSince = SystemClock.elapsedRealtime()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        lastTickAt = SystemClock.elapsedRealtime()
        tickerJob = lifecycleScope.launch {
            while (isActive && isSessionPhase()) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /** Runs five times a second while a session is active. */
    private fun tick() {
        if (!isSessionPhase()) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickAt
        lastTickAt = now

        // Watchdog: never wait forever if the SDK doesn't start on this phone.
        if (!gotFirstStatus && !retryInFlight && now - sessionStartedAt > START_TIMEOUT_MS) {
            android.util.Log.w("UltraTalkingPulse", "No status from SmartSpectra within $START_TIMEOUT_MS ms")
            endSession(getString(if (isOnline()) R.string.err_start_timeout else R.string.err_offline))
            return
        }

        // Speak a position change only once it has settled, and never over another message.
        // During the breathing exercise the rhythm has priority; position problems are handled there.
        val code = candidateCode
        if ((phase == Phase.POSITIONING || phase == Phase.MEASURING) &&
            code != null &&
            code != lastSpokenCode &&
            now - candidateSince >= SETTLE_MS &&
            now >= announcer.busyUntil + GAP_MS
        ) {
            announceCode(code)
        }

        when (phase) {
            Phase.POSITIONING -> {
                if (currentCode == OK) {
                    if (okSince == 0L) okSince = now
                    if (now - okSince >= OK_HOLD_MS && now >= announcer.busyUntil) {
                        if (mode == Mode.BREATHE) startExercise() else startMeasuring()
                    }
                } else {
                    okSince = 0L
                }
            }

            Phase.MEASURING -> {
                val good = currentCode == OK
                if (good) measuredMs += elapsed
                val percent = (measuredMs * 100 / MEASURE_MS).toInt().coerceIn(0, 100)
                progressText.text = getString(
                    if (good) R.string.progress_measuring else R.string.progress_paused,
                    percent,
                )
                if (!halfwayAnnounced && percent >= 50 && now >= announcer.busyUntil + GAP_MS) {
                    halfwayAnnounced = true
                    showMessage(getString(R.string.halfway), speak = true)
                }
                if (measuredMs >= MEASURE_MS) finishMeasurement()
            }

            Phase.EXERCISING -> exerciseTick(now, elapsed)

            Phase.EXPLAINING -> explainingTick(now)

            Phase.IDLE, Phase.CHECKING -> Unit
        }
    }

    private fun announceCode(code: String) {
        lastSpokenCode = code
        if (code == OK) {
            announcer.cueGood(guidanceText)
            val text = if (phase == Phase.MEASURING) R.string.good_continue else R.string.good
            showMessage(getString(text), speak = true)
            return
        }
        val text = guidanceFor(code) ?: currentHint?.takeIf { it.isNotBlank() } ?: return
        showMessage(text, speak = true)
    }

    private fun guidanceFor(code: String): String? {
        val id = when (code) {
            "NO_FACE_FOUND" -> R.string.g_no_face
            "MULTIPLE_FACES_FOUND" -> R.string.g_multiple_faces
            "FACE_NOT_CENTERED" -> R.string.g_not_centered
            "FACE_TOO_CLOSE" -> R.string.g_too_close
            "FACE_TOO_FAR" -> R.string.g_too_far
            "FACE_SIZE_OUT_OF_RANGE" -> R.string.g_size
            "FACE_TOO_HIGH" -> R.string.g_too_high
            "FACE_TOO_LOW" -> R.string.g_too_low
            "FACE_NOT_FORWARD" -> R.string.g_not_forward
            "TOO_DARK" -> R.string.g_too_dark
            "TOO_BRIGHT" -> R.string.g_too_bright
            "CHEST_NOT_VISIBLE" -> R.string.g_chest
            "CAMERA_TUNING" -> R.string.g_tuning
            "FRAME_RATE_TOO_LOW" -> R.string.g_frame_rate
            "EXCESSIVE_MOTION" -> R.string.g_motion
            else -> return null
        }
        return getString(id)
    }

    // ---------------------------------------------------------------- measurement

    private fun startMeasuring() {
        phase = Phase.MEASURING
        measuredMs = 0L
        halfwayAnnounced = false
        showMessage(getString(R.string.measuring_start, MEASURE_SECONDS), speak = true)
    }

    private fun warnAboutTalking() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTalkWarningAt < TALK_WARNING_INTERVAL_MS) return
        if (now < announcer.busyUntil) return
        lastTalkWarningAt = now
        showMessage(getString(R.string.talking), speak = true)
    }

    private fun finishMeasurement() {
        val pulseLine = pulse?.let {
            getString(R.string.r_pulse, it.value.roundToInt(), confidenceWord(it.confidence))
        } ?: getString(R.string.r_pulse_none)
        val breathingLine = breathing?.let {
            getString(R.string.r_breathing, it.value.roundToInt(), confidenceWord(it.confidence))
        } ?: getString(R.string.r_breathing_none)
        val hrvLine = hrvMs?.let { getString(R.string.r_hrv, it.roundToInt()) }
            ?: getString(R.string.r_hrv_none)

        pulseText.text = pulseLine
        showResult(breathingText, breathingLine)
        showResult(hrvText, hrvLine)

        val weak = pulse == null || breathing == null ||
            (pulse?.confidence ?: 0f) < 40f || (breathing?.confidence ?: 0f) < 40f
        resultsSummary = buildString {
            append(getString(R.string.r_done)).append(' ')
            append(pulseLine).append(' ').append(breathingLine).append(' ').append(hrvLine)
            if (weak) append(' ').append(getString(R.string.r_retry_tip))
        }
        announcer.cueDone(guidanceText)

        // The AI explanation needs the session to stay open, so the camera keeps running a little longer.
        log("measurement done: pulse=$pulse breathing=$breathing hrv=$hrvMs")
        val anyResult = pulse != null || breathing != null || hrvMs != null
        finishWithOptionalAi(resultsSummary, if (anyResult) aiPrompt() else null)
    }

    /**
     * Speaks [summary] and ends the session, or, when the AI explanation is on and there is
     * something to explain ([prompt] not null), asks for it first while the session is still open.
     */
    private fun finishWithOptionalAi(summary: String, prompt: String?) {
        resultsSummary = summary
        val aiOn = prefs.getBoolean(KEY_AI, false)
        log("finish: aiOn=$aiOn hasPrompt=${prompt != null}")
        if (!aiOn) {
            endSession(summary)
            return
        }
        if (prompt == null) {
            endSession(summary + " " + getString(R.string.ai_skipped))
            return
        }
        val request = runCatching { sdk.requestInsight(prompt) }
        log("requestInsight -> id=${request.getOrNull()} error=${request.exceptionOrNull()} prompt=$prompt")
        val id = request.getOrNull()
        if (id == null) {
            endSession(summary + " " + getString(R.string.ai_failed))
            return
        }
        pendingInsightId = id
        aiReply = null
        explainStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.EXPLAINING
        progressText.text = getString(R.string.ai_waiting)
        showMessage(summary + " " + getString(R.string.ai_waiting), speak = true)
    }

    /** Waits for the AI reply; speaks it once the spoken results have finished. */
    private fun explainingTick(now: Long) {
        val reply = aiReply
        if (reply != null) {
            if (now < announcer.busyUntil + GAP_MS) return
            log("AI reply ready, blank=${reply.isBlank()}")
            if (reply.isBlank()) {
                endSession(getString(R.string.ai_failed))
            } else {
                val text = getString(R.string.ai_prefix, reply)
                showResult(insightText, text)
                endSession(text)
            }
            return
        }
        if (now - explainStartedAt > INSIGHT_TIMEOUT_MS && now >= announcer.busyUntil) {
            log("AI explanation timed out after ${INSIGHT_TIMEOUT_MS} ms (waiting for id=$pendingInsightId)")
            endSession(getString(R.string.ai_timeout))
        }
    }

    /** The question sent with the measured numbers. The SDK adds its own buffered vitals as well. */
    private fun aiPrompt(): String {
        val numbers = buildList {
            pulse?.let { add("pulse rate about ${it.value.roundToInt()} beats per minute") }
            breathing?.let { add("breathing rate about ${it.value.roundToInt()} breaths per minute") }
            hrvMs?.let { add("heart rate variability RMSSD about ${it.roundToInt()} ms") }
        }.joinToString("; ")
        return getString(R.string.ai_prompt, numbers)
    }

    /** The question after the breathing exercise: start and end values, as far as they are known. */
    private fun exercisePrompt(sp: Reading?, ep: Reading?, sb: Reading?, eb: Reading?): String? {
        val numbers = buildList {
            if (sb != null && eb != null) {
                add("breathing rate about ${sb.value.roundToInt()} breaths per minute at the start and about ${eb.value.roundToInt()} at the end")
            }
            if (sp != null && ep != null) {
                add("pulse rate about ${sp.value.roundToInt()} beats per minute at the start and about ${ep.value.roundToInt()} at the end")
            }
        }
        if (numbers.isEmpty()) return null
        return getString(R.string.ai_prompt_exercise, numbers.joinToString("; "))
    }

    /** Language models like Markdown; a screen reader should hear plain sentences. */
    private fun cleanForSpeech(text: String): String =
        text.replace(Regex("[*#_`>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun confidenceWord(confidence: Float): String = getString(
        when {
            confidence >= 70f -> R.string.conf_high
            confidence >= 40f -> R.string.conf_medium
            else -> R.string.conf_low
        },
    )

    // ---------------------------------------------------------------- guided breathing

    private fun startExercise() {
        phase = Phase.EXERCISING
        exerciseMs = 0L
        breathsStarted = 0
        inhaleNext = true
        feedbackGiven = false
        startPulse = null
        startBreathing = null
        notOkSince = 0L
        lastPositionWarnAt = SystemClock.elapsedRealtime()
        showMessage(getString(R.string.exercise_start), speak = true)
        // The first "in" comes right after the explanation has been spoken.
        nextCueAtMs = (announcer.busyUntil - SystemClock.elapsedRealtime() + GAP_MS).coerceAtLeast(0L)
    }

    private fun exerciseTick(now: Long, elapsed: Long) {
        exerciseMs += elapsed

        // Keep track of how long the position has been lost, to mention it between breaths.
        if (currentCode != OK) {
            if (notOkSince == 0L) notOkSince = now
        } else {
            notOkSince = 0L
        }

        progressText.text = getString(
            R.string.progress_exercise,
            breathsStarted.coerceIn(1, EXERCISE_BREATHS),
            EXERCISE_BREATHS,
        )

        if (exerciseMs < nextCueAtMs) return

        if (inhaleNext) {
            if (breathsStarted >= EXERCISE_BREATHS) {
                finishExercise()
                return
            }
            breathsStarted++
            announcer.cueInhale(guidanceText)
            showMessage(getString(R.string.inhale), speak = true)
            nextCueAtMs += INHALE_MS
        } else {
            announcer.cueExhale(guidanceText)
            showMessage(exhaleMessage(now), speak = true)
            nextCueAtMs += EXHALE_MS
        }
        inhaleNext = !inhaleNext
    }

    /** "Out", sometimes followed by the live breathing rate or a position reminder (the out-breath is longer). */
    private fun exhaleMessage(now: Long): String {
        val out = getString(R.string.exhale)
        val rate = breathing
        if (!feedbackGiven && exerciseMs >= EXERCISE_FEEDBACK_AT_MS && rate != null) {
            feedbackGiven = true
            return out + " " + getString(R.string.exercise_feedback, rate.value.roundToInt())
        }
        val code = currentCode
        if (code != null && code != OK && notOkSince != 0L &&
            now - notOkSince >= LOST_POSITION_MS &&
            now - lastPositionWarnAt >= LOST_POSITION_REPEAT_MS
        ) {
            val hint = guidanceFor(code) ?: return out
            lastPositionWarnAt = now
            return "$out $hint"
        }
        return out
    }

    private fun finishExercise() {
        val endPulse = pulse
        val endBreathing = breathing
        val sp = startPulse
        val sb = startBreathing

        val breathingLine = if (sb != null && endBreathing != null) {
            getString(R.string.x_breathing, sb.value.roundToInt(), endBreathing.value.roundToInt())
        } else {
            getString(R.string.x_breathing_none)
        }
        val pulseLine = if (sp != null && endPulse != null) {
            getString(R.string.x_pulse, sp.value.roundToInt(), endPulse.value.roundToInt())
        } else {
            getString(R.string.x_pulse_none)
        }
        pulseText.text = pulseLine
        showResult(breathingText, breathingLine)

        announcer.cueDone(guidanceText)
        log("exercise done: pulse $sp -> $endPulse, breathing $sb -> $endBreathing")
        finishWithOptionalAi(
            getString(R.string.exercise_done) + " " + breathingLine + " " + pulseLine,
            exercisePrompt(sp, endPulse, sb, endBreathing),
        )
    }

    // ---------------------------------------------------------------- ending

    /** Stops the camera and returns to the idle state; [message] is spoken if given. */
    private fun endSession(message: String?) {
        log("session end: phase=$phase mode=$mode message=${message?.take(120)}")
        if (phase == Phase.CHECKING) {
            readinessJob?.cancel()
            readinessJob = null
        }
        phase = Phase.IDLE
        retryInFlight = false
        pendingInsightId = null
        tickerJob?.cancel()
        tickerJob = null
        restoreIdleButtons()
        progressText.text = ""
        lifecycleScope.launch { runCatching { sdk.stop() } }
        if (message != null) {
            showMessage(message, speak = true)
            releaseScreenAfterSpeech()
        } else {
            screenReleaseJob?.cancel()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- buttons

    private fun setButtonsForSession() {
        if (mode == Mode.BREATHE) {
            breatheButton.text = getString(R.string.btn_breathe_stop)
            startButton.isEnabled = false
        } else {
            startButton.text = getString(R.string.btn_stop)
            breatheButton.isEnabled = false
        }
        checkButton.isEnabled = false
    }

    private fun setButtonsForChecking() {
        // While checking, the start buttons are off; the check button cancels the check.
        checkButton.text = getString(R.string.btn_check_cancel)
        startButton.isEnabled = false
        breatheButton.isEnabled = false
        checkButton.isEnabled = true
    }

    private fun restoreIdleButtons() {
        startButton.text = getString(R.string.btn_start)
        breatheButton.text = getString(R.string.btn_breathe)
        checkButton.text = getString(R.string.btn_check_phone)
        startButton.isEnabled = true
        breatheButton.isEnabled = true
        checkButton.isEnabled = true
    }

    // ---------------------------------------------------------------- messages

    private fun showMessage(text: String, speak: Boolean) {
        lastMessage = text
        guidanceText.text = text
        if (speak) announcer.say(text, guidanceText)
    }

    private fun showResult(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    private fun repeatLastMessage() {
        val extra = if (phase == Phase.MEASURING || phase == Phase.EXERCISING) " " + progressText.text else ""
        announcer.say(lastMessage + extra, guidanceText)
        if (phase == Phase.IDLE) releaseScreenAfterSpeech()
    }

    /**
     * Keeps the screen on while the last message (results, AI explanation) is being read,
     * plus a margin for slower speech rates, then lets the phone lock normally again.
     */
    private fun releaseScreenAfterSpeech(extraDelay: Boolean = false) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screenReleaseJob?.cancel()
        screenReleaseJob = lifecycleScope.launch {
            // When called before the message is spoken, give the speech a moment to start.
            if (extraDelay) delay(SCREEN_RELEASE_MARGIN_MS)
            while (SystemClock.elapsedRealtime() < announcer.busyUntil) delay(500)
            delay(SCREEN_RELEASE_MARGIN_MS)
            if (phase == Phase.IDLE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun showConsentDialog(firstRun: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setCancelable(!firstRun)
        if (firstRun) {
            builder
                .setPositiveButton(R.string.btn_agree) { _, _ ->
                    prefs.edit().putBoolean(KEY_CONSENT, true).apply()
                }
                .setNegativeButton(R.string.btn_exit) { _, _ -> finish() }
        } else {
            builder
                .setPositiveButton(R.string.btn_close, null)
                .setNegativeButton(R.string.btn_withdraw) { _, _ ->
                    if (phase != Phase.IDLE) endSession(null)
                    prefs.edit().putBoolean(KEY_CONSENT, false).apply()
                    finish()
                }
        }
        builder.show()
    }

    /** Turning the AI explanation on sends numbers off the phone, so it is confirmed first. */
    private fun onAiSwitchChanged(on: Boolean) {
        log("AI switch changed: on=$on (saved=${prefs.getBoolean(KEY_AI, false)})")
        if (!on) {
            prefs.edit().putBoolean(KEY_AI, false).apply()
            return
        }
        if (prefs.getBoolean(KEY_AI, false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_dialog_title)
            .setMessage(R.string.ai_dialog_body)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_enable) { _, _ ->
                prefs.edit().putBoolean(KEY_AI, true).apply()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                aiSwitch.isChecked = false
            }
            .show()
    }

    private fun showAboutDialog() {
        val sr = SupportInfo.isSerbian(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(
                getString(R.string.about_body, BuildConfig.VERSION_NAME) + "\n\n" + SupportInfo.aboutLine(sr),
            )
            .setPositiveButton(R.string.btn_close, null)
            .setNeutralButton(SupportInfo.buttonText(sr)) { _, _ ->
                SupportInfo.show(this, getString(R.string.app_name))
            }
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        content.addView(heading(getString(R.string.app_name), 28f))
        content.addView(body(getString(R.string.tagline), muted = true), topMargin(4))

        // Camera preview: useful for sighted users, hidden from screen readers.
        val preview = PreviewView(this).apply {
            contentDescription = getString(R.string.camera_preview)
            // Hide the preview and the camera surface inside it; otherwise some phones read it as "unlabeled".
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            setBackgroundColor(Color.BLACK)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        sdk.config.previewSurfaceProvider = preview.surfaceProvider
        content.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply {
                topMargin = dp(16)
            },
        )

        startButton = bigButton(getString(R.string.btn_start), primary = true) { onModeButtonPressed(Mode.MEASURE) }
        content.addView(startButton, topMargin(16))
        breatheButton = bigButton(getString(R.string.btn_breathe), primary = false) {
            onModeButtonPressed(Mode.BREATHE)
        }
        content.addView(breatheButton, topMargin(10))

        content.addView(heading(getString(R.string.heading_guidance), 20f), topMargin(20))
        guidanceText = body("", sizeSp = 20f).apply { setTypeface(typeface, Typeface.BOLD) }
        content.addView(guidanceText, topMargin(6))
        progressText = body("", muted = true)
        content.addView(progressText, topMargin(6))
        content.addView(
            bigButton(getString(R.string.btn_repeat), primary = false) { repeatLastMessage() },
            topMargin(10),
        )

        content.addView(heading(getString(R.string.heading_results), 20f), topMargin(20))
        pulseText = body(getString(R.string.no_results_yet), sizeSp = 20f)
        content.addView(pulseText, topMargin(6))
        breathingText = body("", sizeSp = 20f).apply { visibility = View.GONE }
        content.addView(breathingText, topMargin(6))
        hrvText = body("", sizeSp = 20f).apply { visibility = View.GONE }
        content.addView(hrvText, topMargin(6))
        insightText = body("", sizeSp = 18f).apply { visibility = View.GONE }
        content.addView(insightText, topMargin(10))

        content.addView(heading(getString(R.string.heading_settings), 20f), topMargin(20))
        content.addView(
            settingSwitch(getString(R.string.setting_speech), announcer.speechEnabled) { on ->
                announcer.speechEnabled = on
                prefs.edit().putBoolean(KEY_SPEECH, on).apply()
            },
            topMargin(6),
        )
        content.addView(
            settingSwitch(getString(R.string.setting_cues), announcer.cuesEnabled) { on ->
                announcer.cuesEnabled = on
                prefs.edit().putBoolean(KEY_CUES, on).apply()
            },
            topMargin(6),
        )
        aiSwitch = settingSwitch(getString(R.string.setting_ai), prefs.getBoolean(KEY_AI, false)) { on ->
            onAiSwitchChanged(on)
        }
        content.addView(aiSwitch, topMargin(6))
        checkButton = bigButton(getString(R.string.btn_check_phone), primary = false) { onCheckButtonPressed() }
        content.addView(checkButton, topMargin(16))
        content.addView(
            bigButton(getString(R.string.btn_privacy), primary = false) { showConsentDialog(firstRun = false) },
            topMargin(10),
        )
        content.addView(
            bigButton(getString(R.string.btn_about), primary = false) { showAboutDialog() },
            topMargin(10),
        )
        content.addView(
            bigButton(SupportInfo.buttonText(SupportInfo.isSerbian(this)), primary = false) {
                SupportInfo.show(this, getString(R.string.app_name))
            },
            topMargin(10),
        )
        content.addView(body(getString(R.string.disclaimer), muted = true), topMargin(20))

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun heading(text: String, sizeSp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun body(text: String, sizeSp: Float = 16f, muted: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(if (muted) 0xFFC8CDD8.toInt() else Color.WHITE)
            setLineSpacing(0f, 1.15f)
        }

    private fun bigButton(text: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minHeight = dp(56)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (primary) {
                    setColor(0xFF4FCCC4.toInt())
                } else {
                    setColor(0xFF1A2233.toInt())
                    setStroke(dp(2), 0xFF4FCCC4.toInt())
                }
            }
            setOnClickListener { onClick() }
        }

    private fun settingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            isChecked = checked
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun topMargin(valueDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(valueDp) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
