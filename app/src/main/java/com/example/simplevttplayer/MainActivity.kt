package com.example.simplevttplayer // **<<< CHECK THIS LINE CAREFULLY!**

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
// No longer using standard Button/SeekBar directly in code
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStream
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.slider.Slider // Import Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.slider.Slider.OnSliderTouchListener
import android.view.View
import android.view.WindowManager // *** Import for Keep Screen On ***
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import android.widget.Spinner
import android.widget.AdapterView

class MainActivity : AppCompatActivity() {

    // --- Constants ---
    companion object {
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT
        private val TAG: String = MainActivity::class.java.simpleName
    }

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    // --- UI Elements ---
    private lateinit var buttonSelectFile: MaterialButton
    private lateinit var textViewFilePath: TextView
    private lateinit var textViewCurrentTime: TextView
    private lateinit var textViewSubtitle: TextView
    private lateinit var buttonPlayPause: MaterialButton
    private lateinit var buttonReset: MaterialButton
    private lateinit var buttonLaunchOverlay: MaterialButton
    private lateinit var sliderPlayback: Slider
    private lateinit var spinnerSpeed: Spinner

    // --- Subtitle Data ---
    data class SubtitleCue( val startTimeMs: Long, val endTimeMs: Long, val text: String )

    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var selectedFileUri: Uri? = null

    // --- Playback & UI State ---
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var startTimeNanos: Long = 0L
    private var pausedElapsedTimeMillis: Long = 0L
    private var currentCueIndex: Int = -1
    private var wasPlayingBeforeSeek = false
    private var isOverlayUIShown = true
    private var playbackSpeed: Float = 1.0f

    // --- File Selection Launcher ---
    private val selectSubtitleFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.also { uri ->
                selectedFileUri = uri
                val fileName = getFileName(uri)
                resetPlayback() // Reset first
                if (fileName != null) {
                    textViewFilePath.text = "File: $fileName"
                    when {
                        fileName.lowercase().endsWith(".vtt") -> loadAndParseSubtitleFile(uri, "vtt")
                        fileName.lowercase().endsWith(".srt") -> loadAndParseSubtitleFile(uri, "srt")
                        else -> {
                            Toast.makeText(this, "Not VTT/SRT ($fileName)", Toast.LENGTH_LONG).show()
                            resetPlaybackStateOnError()
                            textViewFilePath.text = "File: $fileName (Not VTT/SRT?)"
                        }
                    }
                } else {
                    Toast.makeText(this, "No filename.", Toast.LENGTH_SHORT).show()
                    resetPlaybackStateOnError()
                    textViewFilePath.text = "File: (Unknown)"
                }
            }
        } else {
            Toast.makeText(this, "File selection cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Uses layout with Material components

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (checkOverlayPermission()) { Log.d(TAG, "Overlay perm granted post-settings."); startOverlayService()
            } else { Log.w(TAG, "Overlay perm not granted post-settings."); Toast.makeText(this, "Overlay permission required.", Toast.LENGTH_SHORT).show() }
        }

        // Init UI Elements
        buttonSelectFile = findViewById(R.id.buttonSelectFile)
        textViewFilePath = findViewById(R.id.textViewFilePath)
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime)
        textViewSubtitle = findViewById(R.id.textViewSubtitle)
        buttonPlayPause = findViewById(R.id.buttonPlayPause)
        buttonReset = findViewById(R.id.buttonReset)
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay)
        sliderPlayback = findViewById(R.id.sliderPlayback) // Use Slider ID
        spinnerSpeed = findViewById(R.id.spinnerSpeed)

        // Set Listeners
        buttonSelectFile.setOnClickListener { openFilePicker() }
        buttonPlayPause.setOnClickListener { togglePlayPause() }
        buttonReset.setOnClickListener { resetPlayback() }
        buttonLaunchOverlay.setOnClickListener {
            isOverlayUIShown = !isOverlayUIShown
            if (isOverlayUIShown) { Log.d(TAG,"Overlay ON"); Toast.makeText(this,"Overlay Shown",Toast.LENGTH_SHORT).show(); handleLaunchOverlayClick(); sendSubtitleUpdate(textViewSubtitle.text.toString())
            } else { Log.d(TAG,"Overlay OFF"); Toast.makeText(this,"Overlay Hidden",Toast.LENGTH_SHORT).show(); sendSubtitleUpdate("") }
        }

        setupSliderListener() // Setup listener for the Slider
        setupSpeedSpinner() // Setup speed control

        // Initial state
        buttonLaunchOverlay.isEnabled = false
        sliderPlayback.isEnabled = false
        setPlayButtonState(false) // Ensure correct initial icon
    }

    // *** ADDED Keep Screen On flag clearing ***
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        stopOverlayService()
        // Ensure screen on flag is cleared if activity is destroyed
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "onDestroy: Stopped service & cleared keep screen on flag.")
    }

    // --- Slider Setup ---
    private fun setupSliderListener() {
        sliderPlayback.addOnChangeListener { _, value, fromUser -> // Underscore for unused 'slider' param
            if (fromUser) { textViewCurrentTime.text = formatTime(value.toLong()) }
        }

        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener {
            @SuppressLint("RestrictedApi")
            override fun onStartTrackingTouch(slider: Slider) {
                wasPlayingBeforeSeek = isPlaying
                if (isPlaying) { pausePlayback() }
                Log.d(TAG, "Slider touch started.")
            }

            @SuppressLint("RestrictedApi")
            override fun onStopTrackingTouch(slider: Slider) {
                val seekToMillis = slider.value.toLong()
                Log.d(TAG, "Seek finished via Slider at: $seekToMillis ms")
                pausedElapsedTimeMillis = seekToMillis
                textViewCurrentTime.text = formatTime(pausedElapsedTimeMillis)
                val currentCue = findCueForTime(pausedElapsedTimeMillis)
                val currentText = currentCue?.text ?: ""
                textViewSubtitle.text = currentText
                sendSubtitleUpdate(currentText)
                startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
                if (wasPlayingBeforeSeek) { startPlayback() }
                else { setPlayButtonState(false) } // Ensure icon is Play if not resuming
            }
        })
    }

    // --- Speed Spinner Setup ---
    private fun setupSpeedSpinner() {
        spinnerSpeed.setSelection(2) // Default to 1.0x (index 2)
        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                playbackSpeed = when (position) {
                    0 -> 0.5f
                    1 -> 0.75f
                    2 -> 1.0f
                    3 -> 1.25f
                    4 -> 1.5f
                    5 -> 2.0f
                    else -> 1.0f
                }
                Log.d(TAG, "Playback speed changed to ${playbackSpeed}x")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // --- File Handling & Parsing ---
    @SuppressLint("Range") private fun getFileName(uri: Uri): String? { var f: String? = null; try { contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i != -1) f = c.getString(i) } } } catch (e: Exception) { Log.e(TAG, "getFileName error: $uri", e) }; if (f == null) { f = uri.path; val cut = f?.lastIndexOf('/'); if (cut != -1 && cut != null) { f = f?.substring(cut + 1) } }; return f }

    private fun openFilePicker() { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; selectSubtitleFileLauncher.launch(i) }

    private fun loadAndParseSubtitleFile(uri: Uri, format: String) {
        Log.d(TAG, "Attempting to load $format file: $uri")
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                subtitleCues = if (format == "vtt") parseVtt(inputStream) else parseSrt(inputStream)
                if (subtitleCues.isNotEmpty()) {
                    Toast.makeText(this, "${format.uppercase()} loaded: ${subtitleCues.size} cues", Toast.LENGTH_SHORT).show()
                    buttonPlayPause.isEnabled = true; buttonReset.isEnabled = true; buttonLaunchOverlay.isEnabled = true
                    val duration = subtitleCues.lastOrNull()?.endTimeMs ?: 0L
                    sliderPlayback.valueFrom = 0.0f; sliderPlayback.valueTo = duration.toFloat(); sliderPlayback.value = 0.0f; sliderPlayback.isEnabled = true
                    textViewSubtitle.text = "[Ready to play]"; textViewCurrentTime.text = formatTime(0)
                    isOverlayUIShown = true; setPlayButtonState(false); sendSubtitleUpdate("")
                } else { Toast.makeText(this, "No cues parsed.", Toast.LENGTH_LONG).show(); resetPlaybackStateOnError() }
            } ?: run { Toast.makeText(this, "Failed file stream.", Toast.LENGTH_LONG).show(); Log.w(TAG, "Null InputStream: $uri"); resetPlaybackStateOnError() }
        } catch (e: Exception) { Log.e(TAG, "load/parse $format error", e); Toast.makeText(this, "Load ${format.uppercase()} error: ${e.message}", Toast.LENGTH_LONG).show(); resetPlaybackStateOnError() }
    }

    // --- VTT Parsing Logic ---
    private fun parseVtt(inputStream: InputStream): List<SubtitleCue> { val c=mutableListOf<SubtitleCue>(); val r=inputStream.bufferedReader(Charsets.UTF_8); try { var h=r.readLine(); if(h?.startsWith("\uFEFF")==true){h=h.substring(1)}; if(h==null||!h.trim().startsWith("WEBVTT")){Log.e("VTTParser","Bad Header: '$h'"); runOnUiThread { Toast.makeText(this,"Bad VTT Header",Toast.LENGTH_LONG).show() }; return emptyList()}; var l:String?; while(r.readLine().also{l=it}!=null){ val t=l?.trim()?:""; if(t.isEmpty()||t.startsWith("NOTE")){continue}; if(t.contains("-->")){parseTimeAndTextVtt(t,r,c)}else{Log.w("VTTParser","Skip line: $t")}} }catch(e:Exception){Log.e("VTTParser","Parse VTT Error",e); runOnUiThread { Toast.makeText(this,"VTT Process Error",Toast.LENGTH_SHORT).show() }}finally{try{r.close()}catch(e:Exception){}}; return c.sortedBy{it.startTimeMs}}

    private fun parseTimeAndTextVtt(tL: String, r: BufferedReader, c: MutableList<SubtitleCue>) { try { val t=tL.split("-->"); if(t.size<2){Log.w("VTTParser","Bad time: $tL"); return}; val s=timeToMillis(t[0].trim()); val eS=t[1].trim
