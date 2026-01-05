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
import android.widget.ArrayAdapter

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
                startTimeNanos = System.nanoTime() - ((pausedElapsedTimeMillis / playbackSpeed).toLong() * 1_000_000)
                                    // ✅ FIX v1.2: Compensate for playback speed to prevent timebar jump
                if (wasPlayingBeforeSeek) { startPlayback() }
                else { setPlayButtonState(false) } // Ensure icon is Play if not resuming
            }
        })
    }

    // --- Speed Spinner Setup ---
    private fun setupSpeedSpinner() {
    val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinnerSpeed.adapter = adapter
    spinnerSpeed.setSelection(2)  // Default to 1.0x
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
    private fun parseTimeAndTextVtt(tL: String, r: BufferedReader, c: MutableList<SubtitleCue>) { try { val t=tL.split("-->"); if(t.size<2){Log.w("VTTParser","Bad time: $tL"); return}; val s=timeToMillis(t[0].trim()); val eS=t[1].trim().split(Regex("\\s+"))[0]; val e=timeToMillis(eS); val b=StringBuilder(); var x:String?=r.readLine(); while(x!=null&&x.isNotBlank()){if(b.isNotEmpty())b.append("\n"); b.append(x); x=r.readLine()}; if(s!=null&&e!=null&&b.isNotEmpty()){if(e>s){c.add(SubtitleCue(s,e,b.toString()))}else{Log.w("VTTParser","End<=Start: $tL")}}else{Log.w("VTTParser","Bad cue: $tL")}}catch(e:Exception){Log.e("VTTParser","Parse cue error: $tL", e)}}

    // --- SRT Parsing Logic ---
    private fun parseSrt(inputStream: InputStream): List<SubtitleCue> { val c=mutableListOf<SubtitleCue>(); val r=inputStream.bufferedReader(Charsets.UTF_8); try { var l: String?; while (r.readLine().also { l = it } != null) { val tL = l?.trim(); if (tL.isNullOrEmpty()) continue; if (tL.toIntOrNull() != null) { val timeL = r.readLine()?.trim(); if (timeL != null && timeL.contains("-->")) { val ts = timeL.split("-->"); if (ts.size >= 2) { val s = timeToMillis(ts[0].trim().replace(',', '.')); val eS = ts[1].trim().split(Regex("\\s+"))[0]; val e = timeToMillis(eS.replace(',', '.')); val b = StringBuilder(); var txtL: String? = r.readLine(); while (txtL != null && !txtL.isBlank()) { if (b.isNotEmpty()) b.append("\n"); b.append(txtL); txtL = r.readLine() }; if (txtL == null && b.isEmpty() && ts[0].trim().isNotEmpty()) { Log.w("SRTParser", "Malformed SRT end: $timeL") }; if (s != null && e != null && b.isNotEmpty() && e > s) { c.add(SubtitleCue(s, e, b.toString())) } else { Log.w("SRTParser", "Skip invalid SRT cue: $tL / $timeL") } } } } } } catch (e: Exception) { Log.e("SRTParser", "Parse SRT error", e); runOnUiThread { Toast.makeText(this, "SRT Parse Error", Toast.LENGTH_SHORT).show() } } finally { try { r.close() } catch (ioe: Exception) {} }; return c.sortedBy { it.startTimeMs } }

    // --- Time Parsing Helper ---
    private fun timeToMillis(t: String): Long? { try { val p=t.split(":"); val msS: String; val sS: String; val lP=p.last(); val dI=lP.indexOf('.'); if(dI!=-1){sS=lP.substring(0, dI); msS=lP.substring(dI+1)}else{ sS=lP; msS="0" }; val msDigits=msS.filter{it.isDigit()}; if(msDigits.isEmpty()){Log.w("TimeParser","Bad ms: $t"); return null}; val ms=msDigits.padEnd(3,'0').take(3).toLong(); if(sS.isEmpty()||sS.any{!it.isDigit()}){Log.w("TimeParser","Bad secs: $t"); return null}; val s=sS.toLong(); if(s<0||s>59){Log.w("TimeParser","Bad secs val: $t"); return null}; return when(p.size){3->{if(p[1].isEmpty()||p[1].any{!it.isDigit()}||p[0].isEmpty()||p[0].any{!it.isDigit()}){Log.w("TimeParser","Bad H/M: $t"); return null}; val m=p[1].toLong(); val h=p[0].toLong(); if(m<0||m>59){Log.w("TimeParser","Bad min val: $t"); return null}; (h*3600000+m*60000+s*1000+ms)}2->{if(p[0].isEmpty()||p[0].any{!it.isDigit()}){Log.w("TimeParser","Bad M: $t"); return null}; val m=p[0].toLong(); if(m<0||m>59){Log.w("TimeParser","Bad min val: $t"); return null}; (m*60000+s*1000+ms)}else->{Log.w("TimeParser","Bad colon#: $t"); null}}}catch(e:Exception){Log.e("TimeParser","Time parse err: $t",e); return null}}

    // --- Overlay Permission and Service Handling ---
    private fun handleLaunchOverlayClick() { Log.d(TAG, "Ensuring overlay service started."); if (checkOverlayPermission()) startOverlayService() else requestOverlayPermission() }
    private fun checkOverlayPermission(): Boolean { val has = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true; Log.d(TAG, "Overlay perm status: $has"); return has }
    private fun requestOverlayPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { Log.d(TAG, "Requesting overlay perm."); Toast.makeText(this, "Need 'Draw over apps' permission.", Toast.LENGTH_LONG).show(); val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")); try { overlayPermissionLauncher.launch(i) } catch (e: Exception) { Log.e(TAG, "Can't launch overlay settings", e); Toast.makeText(this, "Can't open perm settings.", Toast.LENGTH_SHORT).show() } } }
    private fun startOverlayService() { if (!checkOverlayPermission()) { Log.w(TAG, "Start service denied (no perm)"); requestOverlayPermission(); return }; Log.d(TAG, "Starting OverlayService..."); val i = Intent(this, OverlayService::class.java); try { startService(i) } catch (e: Exception) { Log.e(TAG, "Can't start OverlayService", e); Toast.makeText(this, "Failed to start overlay.", Toast.LENGTH_SHORT).show() } }
    private fun stopOverlayService() { Log.d(TAG, "Stopping OverlayService..."); val i = Intent(this, OverlayService::class.java); stopService(i) }
    private fun sendSubtitleUpdate(text: String) { val textToSend = if (isOverlayUIShown) text else ""; if (textToSend.isNotBlank()) { Log.d(TAG, "Broadcasting update: '$textToSend'") } else { Log.d(TAG, "Broadcasting empty subtitle update.") }; val i = Intent(ACTION_UPDATE_SUBTITLE_LOCAL).apply { putExtra(EXTRA_SUBTITLE_TEXT_LOCAL, textToSend) }; LocalBroadcastManager.getInstance(this).sendBroadcast(i) }


    // --- Playback Control ---
    private fun togglePlayPause() { if (isPlaying) pausePlayback() else startPlayback() }

    // *** ADDED Keep Screen On logic & Icon change ***
    private fun startPlayback() {
        if (subtitleCues.isEmpty()) return
        isPlaying = true
        setPlayButtonState(true) // Set icon to Pause
        // *** Keep screen on ***
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen kept on.")

        startTimeNanos = System.nanoTime() - (pausedElapsedTimeMillis * 1_000_000)
        if (isOverlayUIShown) { val c = findCueForTime(pausedElapsedTimeMillis); sendSubtitleUpdate(c?.text ?: "") } else { sendSubtitleUpdate("") }
        handler.post(updateRunnable)
    }

    // *** ADDED Keep Screen On logic & Icon change ***
    private fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false
        setPlayButtonState(false) // Set icon to Play
        // *** Allow screen to turn off ***
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Screen allowed to turn off.")

        // Update paused time only when actually pausing
        pausedElapsedTimeMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
        handler.removeCallbacks(updateRunnable)
    }

    // *** UPDATED for Slider & Keep Screen On flag ***
    private fun resetPlayback() {
        if (isPlaying) { handler.removeCallbacks(updateRunnable); isPlaying = false }
        pausedElapsedTimeMillis = 0L
        startTimeNanos = 0L
        currentCueIndex = -1
        isOverlayUIShown = true // Reset overlay visibility state
        textViewSubtitle.text = "[Select VTT / SRT File]" // Updated default text
        textViewCurrentTime.text = formatTime(0)
        setPlayButtonState(false) // Set icon to Play
        val cuesLoaded = subtitleCues.isNotEmpty()
        buttonPlayPause.isEnabled = cuesLoaded
        buttonReset.isEnabled = cuesLoaded
        buttonLaunchOverlay.isEnabled = cuesLoaded
        sliderPlayback.value = 0.0f // Reset Slider value
        sliderPlayback.isEnabled = cuesLoaded
        sendSubtitleUpdate("") // Clear the overlay text

        // ---> ADDED Clear flag on reset <---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Playback reset, screen allowed to turn off.")
    }

    // *** UPDATED for Slider & Keep Screen On flag ***
    private fun resetPlaybackStateOnError() {
        subtitleCues = emptyList(); selectedFileUri = null
        buttonPlayPause.isEnabled = false; buttonReset.isEnabled = false; buttonLaunchOverlay.isEnabled = false
        sliderPlayback.value = 0.0f // Reset Slider value
        sliderPlayback.isEnabled = false
        isOverlayUIShown = true; textViewSubtitle.text = "[Error loading file]"; textViewCurrentTime.text = formatTime(0)
        if (!textViewFilePath.text.startsWith("File:")) { textViewFilePath.text = "No file or error" }
        sendSubtitleUpdate("")

        // ---> ADDED Clear flag on error <---
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "Error state reset, screen allowed to turn off.")
    }

    // Helper function to change Play/Pause button icon and text
    private fun setPlayButtonState(playing: Boolean) {
        if (playing) {
            buttonPlayPause.text = "Pause" // Keep text for accessibility readers
            // Ensure you have ic_pause in res/drawable
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause)
        } else {
            buttonPlayPause.text = "Play"
            // Ensure you have ic_play_arrow in res/drawable
            buttonPlayPause.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
        }
    }

    // --- Subtitle Display Update Logic ---
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val rawElapsedMillis = (System.nanoTime() - startTimeNanos) / 1_000_000
            val elapsedMillis = (rawElapsedMillis * playbackSpeed).toLong()  // 應用速度
            textViewCurrentTime.text = formatTime(elapsedMillis)

            // Update Slider only if user isn't dragging it
            // Also check bounds to prevent crash if time slightly exceeds max due to timing
            if (!sliderPlayback.isPressed) {
                if (elapsedMillis.toFloat() >= sliderPlayback.valueFrom && elapsedMillis.toFloat() <= sliderPlayback.valueTo) {
                    sliderPlayback.value = elapsedMillis.toFloat()
                } else if (elapsedMillis.toFloat() > sliderPlayback.valueTo) {
                    // If time exceeded max, clamp slider value to max
                    sliderPlayback.value = sliderPlayback.valueTo
                }
            }

            val activeCue = findCueForTime(elapsedMillis)
            val newText = activeCue?.text ?: ""
            var textChanged = false
            if (textViewSubtitle.text != newText) { textViewSubtitle.text = newText; textChanged = true }
            if (textChanged || (activeCue == null && newText == "")) { sendSubtitleUpdate(newText) }

            if (subtitleCues.isNotEmpty()) {
                val lastCueEndTime = subtitleCues.last().endTimeMs
                if (elapsedMillis >= lastCueEndTime) {
                    // Call pausePlayback first to handle flags/state/button icon
                    pausePlayback()
                    // Set final UI state after pausing
                    textViewCurrentTime.text = formatTime(lastCueEndTime)
                    if (!sliderPlayback.isPressed) { sliderPlayback.value = sliderPlayback.valueTo }
                    textViewSubtitle.text = "[Playback Finished]"
                    sendSubtitleUpdate("[Playback Finished]")
                    return // Stop runnable
                }
            } else { pausePlayback(); sendSubtitleUpdate(""); return } // Safety check
            handler.postDelayed(this, 50) // Update ~20 times per second
        }
    }

    // --- Find Cue Logic ---
    private fun findCueForTime(elapsedMillis: Long): SubtitleCue? { return subtitleCues.find { c -> elapsedMillis >= c.startTimeMs && elapsedMillis < c.endTimeMs } }

    // --- Format Time Logic ---
    private fun formatTime(millis: Long): String { if (millis < 0) return "00:00.000"; val sT = millis / 1000; val m = sT / 60; val s = sT % 60; val ms = millis % 1000; return String.format("%02d:%02d.%03d", m, s, ms) }

} // End of MainActivity class
