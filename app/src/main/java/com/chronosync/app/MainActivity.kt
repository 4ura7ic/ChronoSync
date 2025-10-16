package com.chronosync.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "NtpAppSettings"
        const val KEY_GTS_PIPS_ENABLED = "gts_pips_enabled"
        const val KEY_SELECTED_SERVER_POSITION = "selected_server_position"
        const val KEY_CUSTOM_SERVER = "custom_server"
        const val KEY_TIMEZONE_MODE = "timezone_mode"
        const val KEY_UTC_TIMEZONE = "utc_timezone"
        const val KEY_CONFIDENCE_LEVEL = "confidence_level"
        const val KEY_SECOND_HAND_STYLE = "second_hand_style"
        const val KEY_DEFAULTS_SET = "defaults_set"
        val SECOND_HAND_STYLES = listOf(
            "Smooth Sweep",
            "Quartz (1 tick/sec)",
            "18,000 bph (5 ticks/sec)",
            "21,600 bph (6 ticks/sec)",
            "25,200 bph (7 ticks/sec)",
            "28,800 bph (8 ticks/sec)",
            "36,000 bph (10 ticks/sec)"
        )
        val PRESET_SERVERS = listOf("pool.ntp.org", "time.google.com", "time.cloudflare.com", "time.windows.com", "Custom...")
    }

    private var latestNtpOffset: Long = 0L
    private var lastPipSecond = -1
    private lateinit var analogClockView: AnalogClockView
    private var isGtsPipsEnabled = true
    private var timerJob: Job? = null
    private var lastSyncWasSuccessful = false

    private val timeZoneDisplayList = (-12..12).map {
        when { it > 0 -> "UTC+$it"; it < 0 -> "UTC$it"; else -> "UTC+0" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        analogClockView = findViewById(R.id.analogClockView)
        val syncButton: Button = findViewById(R.id.buttonSync)
        val gtsSwitch: SwitchMaterial = findViewById(R.id.gtsSwitch)
        val timeZoneChipGroup: ChipGroup = findViewById(R.id.timeZoneChipGroup)
        val timeZoneSpinner: Spinner = findViewById(R.id.timeZoneSpinner)
        val gtsInfoButton: ImageButton = findViewById(R.id.gtsInfoButton)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_DEFAULTS_SET, false)) {
            prefs.edit {
                putBoolean(KEY_DEFAULTS_SET, true)
                putString(KEY_TIMEZONE_MODE, "LOCAL")
                putString(KEY_UTC_TIMEZONE, "UTC")
                putBoolean(KEY_GTS_PIPS_ENABLED, true)
                putInt(KEY_SECOND_HAND_STYLE, 0)
                putInt(KEY_SELECTED_SERVER_POSITION, 0)
                putString(KEY_CUSTOM_SERVER, "")
            }
        }

        val savedConfidence = prefs.getInt(KEY_CONFIDENCE_LEVEL, 0)
        analogClockView.setConfidenceLevel(savedConfidence)

        val timeZoneAdapter = ArrayAdapter(this, R.layout.custom_spinner_item, timeZoneDisplayList)
        timeZoneAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item)
        timeZoneSpinner.adapter = timeZoneAdapter

        val savedTimezoneMode = prefs.getString(KEY_TIMEZONE_MODE, "UTC")
        val savedUtcTimezone = prefs.getString(KEY_UTC_TIMEZONE, "UTC") ?: "UTC"

        if (savedTimezoneMode == "LOCAL") {
            timeZoneChipGroup.check(R.id.chipLocal)
            timeZoneSpinner.isEnabled = false
            analogClockView.setDisplayTimeZone("LOCAL")
        } else {
            timeZoneChipGroup.check(R.id.chipUTC)
            timeZoneSpinner.isEnabled = true
            analogClockView.setDisplayTimeZone(savedUtcTimezone)
        }

        val customTzIndex = timeZoneDisplayList.indexOf(convertSystemTzToDisplayTz(savedUtcTimezone))
        if (customTzIndex != -1) {
            timeZoneSpinner.setSelection(customTzIndex)
        }

        analogClockView.setOnStatusTextClickListener {
            val messageResId = if (lastSyncWasSuccessful) R.string.explanation_offset else R.string.explanation_internal_clock
            Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show()
        }

        syncButton.setOnClickListener { performNtpSync() }
        gtsSwitch.setOnCheckedChangeListener { _, isChecked -> isGtsPipsEnabled = isChecked; prefs.edit { putBoolean(KEY_GTS_PIPS_ENABLED, isChecked) } }
        gtsInfoButton.setOnClickListener {
            val url = "https://en.wikipedia.org/wiki/Greenwich_Time_Signal"
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }
        timeZoneChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            prefs.edit {
                if (checkedIds.first() == R.id.chipLocal) {
                    timeZoneSpinner.isEnabled = false
                    analogClockView.setDisplayTimeZone("LOCAL")
                    putString(KEY_TIMEZONE_MODE, "LOCAL")
                } else {
                    timeZoneSpinner.isEnabled = true
                    val lastUtcTz = prefs.getString(KEY_UTC_TIMEZONE, "UTC") ?: "UTC"
                    analogClockView.setDisplayTimeZone(lastUtcTz)
                    putString(KEY_TIMEZONE_MODE, "UTC")
                }
            }
        }
        timeZoneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val systemTz = convertDisplayTzToSystemTz(timeZoneDisplayList[position])
                prefs.edit { putString(KEY_UTC_TIMEZONE, systemTz) }
                if (timeZoneChipGroup.checkedChipId == R.id.chipUTC) {
                    analogClockView.setDisplayTimeZone(systemTz)
                }
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        performNtpSync()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        stopTimer()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isGtsPipsEnabled = prefs.getBoolean(KEY_GTS_PIPS_ENABLED, true)
        findViewById<SwitchMaterial>(R.id.gtsSwitch).isChecked = isGtsPipsEnabled

        var savedStylePosition = prefs.getInt(KEY_SECOND_HAND_STYLE, 0)
        if (savedStylePosition < 0 || savedStylePosition >= SECOND_HAND_STYLES.size) {
            savedStylePosition = 0
            prefs.edit { putInt(KEY_SECOND_HAND_STYLE, savedStylePosition) }
        }
        analogClockView.setSecondHandStyle(savedStylePosition)
        startTimer()
    }

    private fun startTimer() { timerJob?.cancel(); timerJob = lifecycleScope.launch(Dispatchers.Default) { while (isActive) { val correctedTimeMillis = System.currentTimeMillis() + latestNtpOffset; launch(Dispatchers.Main) { analogClockView.updateTime(correctedTimeMillis) }; if (isGtsPipsEnabled) { val calendar = Calendar.getInstance(); calendar.timeInMillis = correctedTimeMillis; val seconds = calendar.get(Calendar.SECOND); if (seconds != lastPipSecond) { when (seconds) { 55, 56, 57, 58, 59 -> { playPip(false); lastPipSecond = seconds }; 0 -> { playPip(true); vibrate(); lastPipSecond = seconds }; 1 -> { lastPipSecond = -1 } } } }; delay(50) } } }
    private fun stopTimer() { timerJob?.cancel(); timerJob = null }

    private fun convertDisplayTzToSystemTz(displayTz: String): String {
        if (displayTz == "UTC+0") return "UTC"
        val offset = displayTz.removePrefix("UTC").toIntOrNull() ?: 0
        val gmtOffset = -offset
        return if (gmtOffset >= 0) "Etc/GMT+$gmtOffset" else "Etc/GMT$gmtOffset"
    }

    private fun convertSystemTzToDisplayTz(systemTz: String): String {
        if (systemTz == "UTC") return "UTC+0"
        val offset = systemTz.removePrefix("Etc/GMT").toIntOrNull() ?: 0
        val displayOffset = -offset
        return if (displayOffset >= 0) "UTC+$displayOffset" else "UTC$displayOffset"
    }

    private fun performNtpSync() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var serverPosition = prefs.getInt(KEY_SELECTED_SERVER_POSITION, 0)
        if (serverPosition < 0 || serverPosition >= PRESET_SERVERS.size) {
            serverPosition = 0
            prefs.edit { putInt(KEY_SELECTED_SERVER_POSITION, serverPosition) }
        }

        val serverUrl = PRESET_SERVERS[serverPosition]
        val finalServerUrl = if (serverUrl == "Custom...") {
            prefs.getString(KEY_CUSTOM_SERVER, "") ?: ""
        } else {
            serverUrl
        }

        if (finalServerUrl.isBlank()) {
            Toast.makeText(this, "NTP server not set. Go to settings.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = fetchNtpTime(finalServerUrl)
            var confidenceLevel = 0

            if (result.wasSuccessful && result.offset != null) {
                lastSyncWasSuccessful = true
                val offsetValue = result.offset
                latestNtpOffset = offsetValue
                analogClockView.setNtpOffset(offsetValue)
                confidenceLevel = when {
                    abs(offsetValue) < 50 -> 1
                    abs(offsetValue) < 500 -> 2
                    else -> 3
                }
                val successMsg = "Sync successful ($finalServerUrl). Offset: $offsetValue ms"
                Toast.makeText(this@MainActivity, successMsg, Toast.LENGTH_SHORT).show()
            } else {
                lastSyncWasSuccessful = false
                analogClockView.setNtpOffset(null)
                val errorMsg = "NTP sync failed ($finalServerUrl).\nPlease check address and internet."
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
            prefs.edit { putInt(KEY_CONFIDENCE_LEVEL, confidenceLevel) }
            analogClockView.setConfidenceLevel(confidenceLevel)
        }
    }

    private suspend fun fetchNtpTime(serverUrl: String): NtpResult {
        return withContext(Dispatchers.IO) {
            val client = NTPUDPClient()
            try {
                client.open()
                client.soTimeout = 5000
                val inetAddress = InetAddress.getByName(serverUrl)
                val timeInfo = client.getTime(inetAddress)
                if (timeInfo == null) {
                    return@withContext NtpResult(false, null, null, "Error: Timeout")
                }
                timeInfo.computeDetails()
                val offset = timeInfo.offset
                val roundTripTime = timeInfo.delay
                return@withContext NtpResult(true, offset, roundTripTime, "")
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext NtpResult(false, null, null, "Error: ${e.message}")
            } finally {
                if (client.isOpen) {
                    client.close()
                }
            }
        }
    }

    private fun playPip(isLongPip: Boolean) {
        val duration = if (isLongPip) 500 else 100
        playTone(1000.0, duration)
    }

    private fun playTone(frequency: Double, durationMs: Int) {
        lifecycleScope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val numSamples = durationMs * sampleRate / 1000
            val samples = DoubleArray(numSamples)
            val generatedSnd = ByteArray(2 * numSamples)
            for (i in 0 until numSamples) { samples[i] = sin(2 * Math.PI * i / (sampleRate / frequency)) }
            var idx = 0
            for (dVal in samples) {
                val `val` = (dVal * 32767).toInt().toShort()
                generatedSnd[idx++] = (`val`.toInt() and 0x00ff).toByte()
                generatedSnd[idx++] = ((`val`.toInt() and 0xff00) ushr 8).toByte()
            }
            try {
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(generatedSnd.size)
                    .build()
                audioTrack.write(generatedSnd, 0, generatedSnd.size)
                audioTrack.play()
                delay(durationMs.toLong() + 50)
                audioTrack.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }
}

data class NtpResult(val wasSuccessful: Boolean, val offset: Long?, val roundTripTime: Long?, val displayString: String)
