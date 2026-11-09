package com.demirajvazi.ultratalkingpulse

import android.Manifest
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private companion object {
        const val PREFS = "ultra_talking_pulse"
        const val KEY_CONSENT = "consent_v1"
        const val KEY_SPEECH = "speech"
        const val KEY_CUES = "cues"

        /** How long a validation code must stay the same before it is spoken. */
        const val SETTLE_MS = 800L
        /** Minimum pause after one message finishes before the next one starts. */
        const val GAP_MS = 700L
        /** How long the position must be good before measuring starts. */
        const val OK_HOLD_MS = 1500L
        /** Measuring time, counted only while the position is good. */
        const val MEASURE_SECONDS = 40
        const val MEASURE_MS = MEASURE_SECONDS * 1000L
        const val TALK_WARNING_INTERVAL_MS = 12_000L
        const val TICK_MS = 200L

        const val OK = "OK"
    }

    private enum class Phase { IDLE, POSITIONING, MEASURING }

    private data class Reading(val value: Double, val confidence: Float)

    private val sdk by lazy { SmartSpectraSdk.shared }
    private lateinit var prefs: SharedPreferences
    private lateinit var announcer: Announcer

    // Views
    private lateinit var guidanceText: TextView
    private lateinit var progressText: TextView
    private lateinit var startButton: Button
    private lateinit var pulseText: TextView
    private lateinit var breathingText: TextView

    // Session state
    private var phase = Phase.IDLE
    private var tickerJob: Job? = null
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
    private var pulse: Reading? = null
    private var breathing: Reading? = null

    // "Talking" detection is a nice extra. If the API key is not allowed to use it,
    // the session is restarted once without it.
    private var talkingRequested = true
    private var retryInFlight = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginSession() else showMessage(getString(R.string.camera_denied), speak = true)
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

        if (!prefs.getBoolean(KEY_CONSENT, false)) showConsentDialog(firstRun = true)
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
        // Anonymous SDK usage statistics are opt-out; Ultra Talking Pulse opts out.
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
            if (error == null || phase == Phase.IDLE) return@observe
            onSessionFailure(error.code.name, error.message)
        }

        sdk.metrics.observe(this) { metrics ->
            if (metrics == null || phase == Phase.IDLE) return@observe

            if (metrics.hasCardio()) {
                metrics.cardio.pulseRateList.lastOrNull { it.timestamp > 0 }?.let {
                    pulse = Reading(it.value.toDouble(), it.confidence)
                }
            }
            if (metrics.hasBreathing()) {
                metrics.breathing.rateList.lastOrNull { it.timestamp > 0 }?.let {
                    breathing = Reading(it.value.toDouble(), it.confidence)
                }
            }
            if (talkingRequested && phase == Phase.MEASURING && metrics.hasFace()) {
                if (metrics.face.talkingList.lastOrNull()?.detected == true) warnAboutTalking()
            }
        }
    }

    // ---------------------------------------------------------------- session flow

    private fun onStartStopPressed() {
        if (phase != Phase.IDLE) {
            endSession(getString(R.string.stopped))
            return
        }
        if (!prefs.getBoolean(KEY_CONSENT, false)) {
            showMessage(getString(R.string.consent_needed), speak = true)
            showConsentDialog(firstRun = true)
            return
        }
        if (BuildConfig.PRESAGE_API_KEY.isBlank() && !hasOAuthConfig()) {
            showMessage(getString(R.string.no_api_key), speak = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        beginSession()
    }

    @Suppress("DiscouragedApi") // Looked up by name because the file is optional and not committed.
    private fun hasOAuthConfig(): Boolean =
        resources.getIdentifier("presage_services", "xml", packageName) != 0

    private fun beginSession() {
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
        pulseText.text = getString(R.string.no_results_yet)
        breathingText.text = ""
        breathingText.visibility = View.GONE
        progressText.text = getString(R.string.progress_positioning)
        startButton.text = getString(R.string.btn_stop)
        // Keep the screen on for the whole measurement, so the phone doesn't lock mid-way.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showMessage(getString(R.string.intro), speak = true)
        startSdk()
        startTicker()
    }

    private fun startSdk() {
        lifecycleScope.launch {
            runCatching { sdk.start() }
                .onSuccess { retryInFlight = false }
                .onFailure { e ->
                    retryInFlight = false
                    if (phase == Phase.IDLE) return@onFailure
                    val known = listOf(
                        "AUTHENTICATION_FAILED", "CREDIT_EXHAUSTED", "NETWORK_ERROR",
                        "SERVER_ERROR", "INPUT_UNAVAILABLE", "CONFIGURATION_FAILED",
                    ).firstOrNull { e.toString().contains(it) }
                    onSessionFailure(known ?: "UNKNOWN", e.message ?: e.toString())
                }
        }
    }

    private fun onSessionFailure(code: String, message: String) {
        if (phase == Phase.IDLE) return
        if (retryInFlight) return

        // First failure while asking for the optional "talking" metric: try again without it.
        if (talkingRequested && (code == "CONFIGURATION_FAILED" || code == "AUTHENTICATION_FAILED")) {
            talkingRequested = false
            retryInFlight = true
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
            else -> getString(R.string.err_generic, message)
        }
        endSession(text)
    }

    private fun onValidation(code: String, hint: String?) {
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
            while (isActive && phase != Phase.IDLE) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /** Runs five times a second while a session is active. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickAt
        lastTickAt = now

        // Speak a position change only once it has settled, and never over another message.
        val code = candidateCode
        if (code != null &&
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
                    if (now - okSince >= OK_HOLD_MS && now >= announcer.busyUntil) startMeasuring()
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

            Phase.IDLE -> Unit
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

        pulseText.text = pulseLine
        breathingText.text = breathingLine
        breathingText.visibility = View.VISIBLE

        val weak = pulse == null || breathing == null ||
            (pulse?.confidence ?: 0f) < 40f || (breathing?.confidence ?: 0f) < 40f
        val summary = buildString {
            append(getString(R.string.r_done)).append(' ')
            append(pulseLine).append(' ').append(breathingLine)
            if (weak) append(' ').append(getString(R.string.r_retry_tip))
        }
        announcer.cueDone(guidanceText)
        endSession(summary)
    }

    private fun confidenceWord(confidence: Float): String = getString(
        when {
            confidence >= 70f -> R.string.conf_high
            confidence >= 40f -> R.string.conf_medium
            else -> R.string.conf_low
        },
    )

    /** Stops the camera and returns to the idle state; [message] is spoken if given. */
    private fun endSession(message: String?) {
        phase = Phase.IDLE
        retryInFlight = false
        tickerJob?.cancel()
        tickerJob = null
        startButton.text = getString(R.string.btn_start)
        progressText.text = ""
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { runCatching { sdk.stop() } }
        if (message != null) showMessage(message, speak = true)
    }

    // ---------------------------------------------------------------- messages

    private fun showMessage(text: String, speak: Boolean) {
        lastMessage = text
        guidanceText.text = text
        if (speak) announcer.say(text, guidanceText)
    }

    private fun repeatLastMessage() {
        val extra = if (phase == Phase.MEASURING) " " + progressText.text else ""
        announcer.say(lastMessage + extra, guidanceText)
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
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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

        startButton = bigButton(getString(R.string.btn_start), primary = true) { onStartStopPressed() }
        content.addView(startButton, topMargin(16))

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
        content.addView(
            bigButton(getString(R.string.btn_privacy), primary = false) { showConsentDialog(firstRun = false) },
            topMargin(16),
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
