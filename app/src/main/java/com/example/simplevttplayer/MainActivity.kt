/**
 * MainActivity.kt
 * 
 * 【主要活動類別】
 * 這是應用程式的主要 Activity，負責：
 * - 顯示字幕播放的主界面
 * - 處理 VTT/SRT 字幕檔案的選擇與解析
 * - 控制字幕播放的時間軸和速度
 * - 管理浮動視窗 Overlay Service
 * - 處理應用程式生命週期（前台/後台切換）
 * 
 * 主要功能：
 * 1. 檔案選擇：選擇 .vtt 或 .srt 字幕檔案
 * 2. 播放控制：播放、暫停、重置
 * 3. 速度控制：0.5x ~ 2.0x 播放速度
 * 4. 時間軸拖曳：使用 Slider 快速跳轉
 * 5. 浮動視窗：在其他 app 上方顯示字幕
 */

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

    // --- Constants --- // 常數定義區
    companion object {
        private const val ACTION_UPDATE_SUBTITLE_LOCAL = OverlayService.ACTION_UPDATE_SUBTITLE // 廣播動作：更新字幕文字
        private const val EXTRA_SUBTITLE_TEXT_LOCAL = OverlayService.EXTRA_SUBTITLE_TEXT // 額外資料鍵：字幕文字內容
        private val TAG: String = MainActivity::class.java.simpleName // 活動標籤：用於日誌
    }

    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
 // 權限請求啟動器：用於請求螢幕覆蓋權限
    // --- UI Elements ---
    private lateinit var buttonSelectFile: MaterialButton
    private lateinit var textViewFilePath: TextView // UI 元件定義區
    private lateinit var textViewCurrentTime: TextView // 按鈕：選擇字幕檔案
    private lateinit var textViewSubtitle: TextView // 文字視圖：顯示檔案路徑
    private lateinit var buttonPlayPause: MaterialButton // 文字視圖：顯示當前播放時間
    private lateinit var buttonReset: MaterialButton // 文字視圖：顯示字幕文字
    private lateinit var buttonLaunchOverlay: MaterialButton // 按鈕：播放/暫停檔案播放
    private lateinit var sliderPlayback: Slider // 按鈕：重設時間軸到 0:00
    private lateinit var spinnerSpeed: Spinner // Slider：時間軸控制（備用）
 // 按鈕：切換覆蓋層顯示/隱藏
    // --- Subtitle Data --- // Spinner：播放速度選擇器（1.0x - 2.0x）
    data class SubtitleCue( val startTimeMs: Long, val endTimeMs: Long, val text: String )
 // 字幕檔案 URI：儲存當前選擇的字幕檔
    private var subtitleCues: List<SubtitleCue> = emptyList() // 播放線程：用於在背景更新時間
    private var selectedFileUri: Uri? = null // 字幕資料列表：儲存已解析的 SRT 內容
 // 啟動時間戳：記錄播放開始的系統時間
    // --- Playback & UI State --- // 播放狀態：true=播放中，false=暫停
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var startTimeNanos: Long = 0L
    private var pausedElapsedTimeMillis: Long = 0L
    private var currentCueIndex: Int = -1 // 當前字幕索引：追蹤已顯示的字幕
    private var wasPlayingBeforeSeek = false // Seek 前播放標誌：防止 Seek 操作影響當前播放
    private var isOverlayUIShown = true // 覆蓋層顯示狀態：true=顯示，false=隱藏
    private var playbackSpeed: Float = 1.0f // 播放速度：預設 1.0 倍速
        private var playbackEndTimeMs: Long = 0L // 播放結束時間：用於自動停止

    // --- File Selection Launcher ---
    private val selectSubtitleFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> // 檔案選擇啟動器區
        if (result.resultCode == Activity.RESULT_OK) { // 註冊 Activity 結果啟動器：用於檔案選擇器
            result.data?.data?.also { uri -> // 檢查結果是否為 RESULT_OK
                selectedFileUri = uri // 取得檔案 URI
                val fileName = getFileName(uri) // 取得檔案名稱
                resetPlayback() // Reset first // 重設播放狀態
                if (fileName != null) { // 如果檔名不為空
                    textViewFilePath.text = "File: $fileName" // 更新 UI 顯示檔案路徑
                    when {
                        fileName.lowercase().endsWith(".vtt") -> loadAndParseSubtitleFile(uri, "vtt") // 判斷檔案是否為 .vtt 格式
                        fileName.lowercase().endsWith(".srt") -> loadAndParseSubtitleFile(uri, "srt") // 判斷檔案是否為 .srt 格式
                        else -> { // 如果不是 VTT/SRT 格式
                            Toast.makeText(this, "Not VTT/SRT ($fileName)", Toast.LENGTH_LONG).show() // 顯示錯誤提示
                            resetPlaybackStateOnError() // 重設播放狀態為錯誤狀態
                            textViewFilePath.text = "File: $fileName (Not VTT/SRT?)" // 更新 UI 顯示錯誤訊息
                        }
                    }
                } else {
                    Toast.makeText(this, "No filename.", Toast.LENGTH_SHORT).show() // 如果沒有檔名，顯示提示
                    resetPlaybackStateOnError() // 重設播放狀態為錯誤狀態
                    textViewFilePath.text = "File: (Unknown)" // 更新 UI 顯示未知檔名
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

        // Init UI Elements // 綁定 UI 元件區
        buttonSelectFile = findViewById(R.id.buttonSelectFile) // 綁定選擇檔案按鈕
        textViewFilePath = findViewById(R.id.textViewFilePath) // 綁定檔案路徑文字視圖
        textViewCurrentTime = findViewById(R.id.textViewCurrentTime) // 綁定當前時間文字視圖
        textViewSubtitle = findViewById(R.id.textViewSubtitle) // 綁定字幕內容文字視圖
        buttonPlayPause = findViewById(R.id.buttonPlayPause) // 綁定播放/暫停按鈕
        buttonReset = findViewById(R.id.buttonReset) // 綁定重設按鈕
        buttonLaunchOverlay = findViewById(R.id.buttonLaunchOverlay) // 綁定啟動覆蓋層按鈕
        sliderPlayback = findViewById(R.id.sliderPlayback) // Use Slider ID // 綁定 Slider （備用）
        spinnerSpeed = findViewById(R.id.spinnerSpeed) // 綁定速度選擇 Spinner

        // Set Listeners // 設定按鈕點擊事件區
        buttonSelectFile.setOnClickListener { openFilePicker() } // 選擇檔案按鈕：打開檔案選擇器
        buttonPlayPause.setOnClickListener { togglePlayPause() } // 播放/暫停按鈕：切換播放狀態
        buttonReset.setOnClickListener { resetPlayback() } // 重設按鈕：重設時間軸到 0:00
        buttonLaunchOverlay.setOnClickListener { // 啟動覆蓋層按鈕：切換覆蓋層狀態
            isOverlayUIShown = !isOverlayUIShown // 切換覆蓋層顯示狀態
            if (isOverlayUIShown) { Log.d(TAG,"Overlay ON"); Toast.makeText(this,"Overlay Shown",Toast.LENGTH_SHORT).show(); handleLaunchOverlayClick(); sendSubtitleUpdate(textViewSubtitle.text.toString()) // 如果覆蓋層開啟，記錄日誌並顯示提示
            } else { Log.d(TAG,"Overlay OFF"); Toast.makeText(this,"Overlay Hidden",Toast.LENGTH_SHORT).show(); sendSubtitleUpdate("") } // 如果覆蓋層關閉，記錄日誌並顯示提示，發送空字幕
        }

        setupSliderListener() // Setup listener for the Slider // 設定 Slider 監聽器（備用）
        setupSpeedSpinner() // Setup speed control // 設定速度 Spinner

        // Initial state // 初始化狀態區
        buttonLaunchOverlay.isEnabled = false // 覆蓋層預設關閉
        sliderPlayback.isEnabled = false // Slider 預設禁用
        setPlayButtonState(false) // Ensure correct initial icon
    }


        // *** ADDED to handle app going to background (HOME button) ***
    override fun onPause() {
        super.onPause()
        // If playing, pause when app goes to background
        if (isPlaying) {
            pausePlayback()
            Log.d(TAG, "onPause: Paused playback (app to background)")
        }
    }

    override fun onResume() { // 生命週期：回到前台時觸發
        super.onResume() // 呼叫父類 onResume
        // App returned to foreground. Playback remains paused. // App 回到前台，但播放保持暫停狀態
        Log.d(TAG, "onResume: App returned to foreground. Playback remains paused.") // 記錄日誌：App 回到前台
    }
    // *** ADDED Keep Screen On flag clearing ***
    override fun onDestroy() { // 生命週期：活動銷毀時觸發
        super.onDestroy() // 呼叫父類 onDestroy
        handler.removeCallbacks(updateRunnable) // 移除所有 Handler 回呼
        stopOverlayService() // 停止覆蓋層服務
        // Ensure screen on flag is cleared if activity is destroyed // 確保 Activity 銷毀時清除螢幕常亮標誌
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 清除螢幕常亮標誌
        Log.d(TAG, "onDestroy: Stopped service & cleared keep screen on flag.") // 記錄日誌：停止服務並清除旗標
    }

    // --- Slider Setup ---
    private fun setupSliderListener() { // 設定 Slider 監聽器（備用功能）
        sliderPlayback.addOnChangeListener { _, value, fromUser -> // Underscore for unused 'slider' param // 監聽 Slider 值變化（用戶不操作時）
            if (fromUser) { textViewCurrentTime.text = formatTime(value.toLong()) } // 如果是用戶操作，更新時間顯示
        }

        sliderPlayback.addOnSliderTouchListener(object : OnSliderTouchListener { // 監聽 Slider 觸摸事件
            @SuppressLint("RestrictedApi")
            override fun onStartTrackingTouch(slider: Slider) { // 當用戶開始拖拉 Slider
                wasPlayingBeforeSeek = isPlaying // 記錄操作前的播放狀態
                if (isPlaying) { pausePlayback() } // 如果正在播放，暫停播放
                Log.d(TAG, "Slider touch started.") // 記錄日誌：Slider 開始觸摸
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
                else { setPlayButtonState(false) } // Ensure icon is Play if not resuming // 如果不恢復播放，確保按鈕為播放圖示
            }
        })
    }
 // 速度 Spinner 設定區
    // --- Speed Spinner Setup --- // 設定速度 Spinner
    private fun setupSpeedSpinner() { // 定義速度選項陣列：0.5x - 2.0x
    val speedOptions = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x") // 建立 ArrayAdapter 來顯示速度選項
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speedOptions) // 設定下拉式清單資源
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) // 將 adapter 綁定到 Spinner
    spinnerSpeed.adapter = adapter // 預設選擇第 2 個（1.0x）
    spinnerSpeed.setSelection(2)  // Default to 1.0x // Spinner 選擇監聽器
    spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // 當用戶選擇新速度
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { // 根據 position 設定播放速度
            playbackSpeed = when (position) { // 0 -> 0.5x
                0 -> 0.5f // 1 -> 0.75x
                1 -> 0.75f // 2 -> 1.0x
                2 -> 1.0f // 3 -> 1.25x
                3 -> 1.25f // 4 -> 1.5x
                4 -> 1.5f // 5 -> 2.0x
                5 -> 2.0f // else -> 1.0x （預設）
                else -> 1.0f
                } // 記錄日誌：播放速度已變更
            Log.d(TAG, "Playback speed changed to ${playbackSpeed}x")
            } // 當用戶未選擇任何項目時的回呼
        override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
 // 檔案處理與解析區
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
                                        val ADD_MILLISECONDS = 90 * 60 * 1000L  // 90 minutes
                    val duration = (subtitleCues.lastOrNull()?.endTimeMs ?: 0L) + ADD_MILLISECONDS
                                            playbackEndTimeMs = duration
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
                if (elapsedMillis >= playbackEndTimeMs) {
                    // Call pausePlayback first to handle flags/state/button icon
                    pausePlayback()
                    textViewCurrentTime.text = formatTime(playbackEndTimeMs)
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
