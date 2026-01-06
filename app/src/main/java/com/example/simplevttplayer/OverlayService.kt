package com.example.simplevttplayer // **IMPORTANT: Adjust package name if needed!**

import android.app.Service
import android.widget.Toast
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Use LocalBroadcastManager

/**
 * OverlayService.kt
 *
 * (主要功能)
 * 視窗覆蓋服務：顯示懸浮字幕視窗
 * - 建立可拖動的懸浮視窗
 * - 接收广播來更新字幕內容
 * - 使用 START_STICKY 確保服務自動重啟
 *
 * 主要功能：
 * 1. 在系統上層顯示字幕懸浮視窗
 * 2. 支援觸摸拖動視窗移動
 * 3. 透過广播接收字幕更新
 * 4. 自動調整文字大小與垂直位置
 */

class OverlayService : Service() {

    companion object { // 常數定義區
        // These constants MUST match the ones used in MainActivity // 這些常數必須與 MainActivity 中的定義一致
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE" // 广播動作：更新字幕文字
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text" // 額外資料鍵：字幕文字內容
        val TAG: String = OverlayService::class.java.simpleName // 服務標籤：用於日誌
    }

    private lateinit var windowManager: WindowManager // 私有變數區
    private lateinit var overlayView: View // 視窗管理器：用於建立懸浮視窗
    private lateinit var textViewOverlaySubtitle: TextView // 覆蓋層視圖：懸浮視窗的根視圖
    private lateinit var params: WindowManager.LayoutParams // 字幕文字 TextView：顯示字幕內容
 // 視窗布局參數：控制懸浮視窗屬性
    // Listens for broadcasts from MainActivity containing subtitle text
    private val subtitleUpdateReceiver = object : BroadcastReceiver() { // 广播接收器區
        override fun onReceive(context: Context?, intent: Intent?) { // 广播接收器：接收 MainActivity 的字幕更新广播 // 广播接收：接收 MainActivity 的字幕更新广播
            if (intent?.action == ACTION_UPDATE_SUBTITLE) { // 判斷是否為更新字幕的動作
                // Extract text, default to empty string if null
                val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: "" // 提取字幕文字，如果為空則使用 ""
                Log.d(TAG, "Received subtitle broadcast: '$subtitleText'") // 記錄日誌：接收到字幕广播
                // Call the function to update the UI based on the text
                updateSubtitleText(subtitleText) // 呼叫函數更新 UI 上的字幕文字
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Not using binding, so return null
        return null
    }
 // 服務生命週期：服務綁定時觸發，返回 null 以不支持綁定
    override fun onCreate() { // 返回 null，不提供綁定功能
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        try {
            // Inflate the overlay layout
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle) // 服務生命週期：服務建立時觸發 // 獲取字幕文字 TextView

            // Get WindowManager service // 獲取視窗管理服務
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager // 建立覆蓋層布局區
 // 從 XML 充填視圖
            // Define layout parameters for the overlay window
            val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE // Requires different permission handling potentially
            }

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, // Width
                WindowManager.LayoutParams.WRAP_CONTENT, // Height
                layoutFlag, // Type based on Android version
                // Flags: Not focusable, not touchable (for now), stays within screen bounds
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT // Allow background transparency // 允許背景透明
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL // Position: Bottom Center // 位置：底部中間
                y = 100 // Offset from bottom edge (adjust as needed) // y 偏移：離底部 100 像素（可調整）
            }

            // Add the view to the window manager // 將視圖加入視窗管理器
            windowManager.addView(overlayView, params) // 記錄日誌：視圖成功加入
            Log.d(TAG, "Overlay view added successfully.")

            // Register the broadcast receiver using LocalBroadcastManager // 設定广播接收器的過濾器
            val filter = IntentFilter(ACTION_UPDATE_SUBTITLE) // 註冊广播接收器
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter) // 記錄日誌：接收器已註冊
            Log.d(TAG, "SubtitleUpdateReceiver registered.")
 // 捕捉異常：視圖充填或加入 WindowManager 時的錯誤
        } catch (e: Exception) {
            // Catch potential errors during view inflation or adding to WindowManager // 記錄錯誤日誌
            Log.e(TAG, "Error during OverlayService onCreate", e) // 顯示錯誤提示
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show() // 停止服務，如果初始化失敗
            stopSelf() // Stop the service if initialization fails
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { // 服務生命週期：服務啟動時觸發
        Log.d(TAG, "OverlayService onStartCommand Received") // 記錄日誌：接收 onStartCommand
        // Service is started, view is managed in onCreate/onDestroy
        // START_STICKY: If the service is killed, restart it automatically      
        return START_STICKY // 返回 START_STICKY：服務被殺死後自動重啟
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")

        // Remove the overlay view from the window
        try {
            // Check if overlayView has been initialized before trying to remove it
            if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            } else {
                Log.d(TAG, "Overlay view not attached or not initialized, no removal needed.")
            }
        } catch (e: Exception) {
            // Catch errors during view removal (e.g., view already removed)
            Log.e(TAG, "Error removing overlay view", e)
        }

        // Unregister the broadcast receiver
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(subtitleUpdateReceiver)
            Log.d(TAG, "SubtitleUpdateReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            // Can happen if receiver wasn't registered successfully or already unregistered
            Log.w(TAG, "Receiver possibly already unregistered or not registered.", e)
        }
    }

    // *** THIS FUNCTION CONTAINS THE LOGIC TO HIDE/SHOW BASED ON TEXT ***
    // Function to update the text view in the overlay
    private fun updateSubtitleText(text: String) {
        // Check if views are initialized before accessing them
        // This prevents crashes if update is called before onCreate finishes or after onDestroy starts
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) {
            if (text.isBlank()) {
                // Hide the entire overlay view if the text is blank/empty
                // Check current visibility to avoid redundant calls
                if (overlayView.visibility != View.GONE) {
                    Log.d(TAG, "Hiding overlay view (blank text received).")
                    overlayView.visibility = View.GONE
                }
            } else {
                // Show the overlay view and set the text if text is not blank
                // Check current visibility to avoid redundant calls
                if (overlayView.visibility != View.VISIBLE) {
                    Log.d(TAG, "Showing overlay view.")
                    overlayView.visibility = View.VISIBLE
                }
                textViewOverlaySubtitle.text = text
            }
        } else {
            Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').")
        }
    }
}
