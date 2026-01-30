package com.example.simplevttplayer // **IMPORTANT: Adjust package name if needed!** //【重要】必須調整套件名稱如需要！

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
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Use LocalBroadcastManager //使用實時廣播管理

class OverlayService : Service() {

    companion object {
        // These constants MUST match the ones used in MainActivity //這些常數子必須與MainActivity中使用的常數子一致
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        val TAG: String = OverlayService::class.java.simpleName
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams

    // Listens for broadcasts from MainActivity containing subtitle text //皁值 MainActivity 從需逗接收互關字幕的馨播
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SUBTITLE) {
                // Extract text, default to empty string if null
                val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: ""
                Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                // Call the function to update the UI based on the text
                updateSubtitleText(subtitleText)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Not using binding, so return null
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")

        try {
            // Inflate the overlay layout
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            textViewOverlaySubtitle = overlayView.findViewById(R.id.textViewOverlaySubtitle)

            // Get WindowManager service
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
                PixelFormat.TRANSLUCENT // Allow background transparency
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL // Position: Bottom Center
                y = 100 // Offset from bottom edge (adjust as needed)
            }

            // Add the view to the window manager
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully.")

            // Register the broadcast receiver using LocalBroadcastManager
            val filter = IntentFilter(ACTION_UPDATE_SUBTITLE)
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "SubtitleUpdateReceiver registered.")

        } catch (e: Exception) {
            // Catch potential errors during view inflation or adding to WindowManager
            Log.e(TAG, "Error during OverlayService onCreate", e)
            Toast.makeText(this, "Failed to create overlay.", Toast.LENGTH_SHORT).show()
            stopSelf() // Stop the service if initialization fails
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OverlayService onStartCommand Received")
        // Service is started, view is managed in onCreate/onDestroy
        // START_NOT_STICKY: If the service is killed, don't restart it automatically
        return START_NOT_STICKY
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
            // Catch errors during view removal (e.g., view already removed) //得捕在加驗休沐時可能的錯誤
            Log.e(TAG, "Error removing overlay view", e)
        }

        // Unregister the broadcast receiver //取消註冊広播接收者
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(subtitleUpdateReceiver)
            Log.d(TAG, "SubtitleUpdateReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            // Can happen if receiver wasn't registered successfully or already unregistered //如果接收者沒有成功註冊或已經取消註冊，就會發生
            Log.w(TAG, "Receiver possibly already unregistered or not registered.", e)
        }
    }

    // *** THIS FUNCTION CONTAINS THE LOGIC TO HIDE/SHOW BASED ON TEXT *** //此函數含有根據文本通与顯示或隱藏的那輯輫
    // Function to update the text view in the overlay //用於更新覆蓋層中文字檔案的函数
    private fun updateSubtitleText(text: String) {
        // Check if views are initialized before accessing them //在存取親不之前棃查查看是否已初始化
        // This prevents crashes if update is called before onCreate finishes or after onDestroy starts //這止是在onCreate後或onDestroy前榨丢更新時的捵掩
        if (::textViewOverlaySubtitle.isInitialized && ::overlayView.isInitialized) {
             //Always show the overlay, keep it visible even if no subtitle //永遠顯示覆蓋層上文字即使沒有字幕
            overlayView.visibility = View.VISIBLE
            // Only update text if it's not blank //僅有一不是空白時才更新文字
            if (text.isNotBlank()) {
                textViewOverlaySubtitle.text = text
            }
        } else {
            Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').")
        }
    }
}
