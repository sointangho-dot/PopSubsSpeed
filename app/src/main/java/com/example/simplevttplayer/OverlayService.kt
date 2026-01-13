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

class OverlayService : Service() {

    companion object {
        // These constants MUST match the ones used in MainActivity
        const val ACTION_UPDATE_SUBTITLE = "com.example.simplevttplayer.UPDATE_SUBTITLE"
        const val EXTRA_SUBTITLE_TEXT = "subtitle_text"
        const val ACTION_PAUSE_PLAY = "com.example.simplevttplayer.PAUSE_PLAY"
        val TAG: String = OverlayService::class.java.simpleName
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var textViewOverlaySubtitle: TextView
    private lateinit var params: WindowManager.LayoutParams    

        // ⬇️ 添加這 2 行
    private var lastUpdateTime = 0L
    private val UPDATE_THROTTLE_MS = 100L  // 100毫秒最小間隔
    private var isPaused = false  // Track pause state


    // Listens for broadcasts from MainActivity containing subtitle text
    private val subtitleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_SUBTITLE -> {
                    val subtitleText = intent.getStringExtra(EXTRA_SUBTITLE_TEXT) ?: ""
                    Log.d(TAG, "Received subtitle broadcast: '$subtitleText'")
                    updateSubtitleText(subtitleText)
                }
                ACTION_PAUSE_PLAY -> {
                    isPaused = intent.getBooleanExtra("is_paused", false)
                    updateSubtitlePauseState()
                    Log.d(TAG, "Received pause/play state: isPaused=$isPaused")
                }
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

        // Set click listener on subtitle to toggle pause
        textViewOverlaySubtitle.setOnClickListener {
            Log.d(TAG, "Subtitle clicked - toggle pause!")
            togglePauseFromOverlay()
        }
                    val buttonMoveUp: View? = overlayView.findViewById(R.id.buttonMoveUp)
        if (buttonMoveUp != null) {
            buttonMoveUp.setOnClickListener {
                Log.d(TAG, "Move up button clicked!")
                moveOverlayUpByButtonClick()
            }
            Log.d(TAG, "Move up button listener set successfully")
        } else {
            Log.w(TAG, "buttonMoveUp is null! Check overlay_layout.xml IDs")
        }

            }


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
                // Flags: Not focusable, but ALLOW touchable
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT // Allow background transparency
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL // Position: Bottom Center
                y = 0 // Offset from bottom edge (adjust as needed)
            }


            // Add the view to the window manager
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Overlay view added successfully.")

            // Register the broadcast receiver using LocalBroadcastManager
            val filter = IntentFilter().apply {
                addAction(ACTION_UPDATE_SUBTITLE)
                addAction(ACTION_PAUSE_PLAY)
            }
            LocalBroadcastManager.getInstance(this).registerReceiver(subtitleUpdateReceiver, filter)
            Log.d(TAG, "BroadcastReceiver registered for all actions.")


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
                // ⬇️ 在函數最開始添加這 5 行
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < UPDATE_THROTTLE_MS) {
            Log.d(TAG, "Update throttled (too frequent)")
            return  // 跳過這次更新
        }
        lastUpdateTime = currentTime
        // ⬆️ 到這裡結束
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
                //if (overlayView.visibility != View.VISIBLE) {
                    Log.d(TAG, "Showing overlay view.")
                    overlayView.visibility = View.VISIBLE
                //}
                textViewOverlaySubtitle.text = text
                // ✅ 修復：無論如何都要設為 VISIBLE（重新顯示）
                //overlayView.visibility = View.VISIBLE
                //textViewOverlaySubtitle.text = text
                //Log.d(TAG, "Updating subtitle: $text")
            }
        } else {
            Log.w(TAG, "Overlay views not initialized when trying to update text ('$text').")
        }
    }

    // Toggle pause state from overlay button
    private fun togglePauseFromOverlay() {
        isPaused = !isPaused
        
        // Broadcast to MainActivity
        val intent = Intent(ACTION_PAUSE_PLAY).apply {
            putExtra("is_paused", isPaused)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // Update button color immediately
        updateSubtitlePauseState()
        
        Toast.makeText(this, if (isPaused) "Paused" else "Resumed", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Pause toggled from overlay: isPaused=$isPaused")
    }
    
    // Update pause button color based on state
    // Update subtitle background color based on pause state
    private fun updateSubtitlePauseState() {
        val textColor = if (isPaused) "#FF5252" else "#FFFFFF"  // Red=paused, White=playing        textViewOverlaySubtitle.setBackgroundColor(android.graphics.Color.parseColor(bgColor))
        textViewOverlaySubtitle.setTextColor(android.graphics.Color.parseColor(textColor))    }
    private fun moveOverlayUpByButtonClick() {
    if (::params.isInitialized && ::overlayView.isInitialized) {
        val moveDistance = 18
        Log.d(TAG, "Moving overlay up by $moveDistance pixels")
        params.y += moveDistance
        try {
            windowManager.updateViewLayout(overlayView, params)
            Log.d(TAG, "Overlay moved up. New Y: ${params.y}")
            } catch (e: Exception) {
            Log.e(TAG, "Error moving overlay up", e)
            }
        } else {
        Log.w(TAG, "Cannot move overlay: views not initialized")
        }
    }

}
