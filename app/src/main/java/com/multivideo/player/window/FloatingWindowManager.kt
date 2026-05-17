package com.multivideo.player.window

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.*
import com.multivideo.player.R
import com.multivideo.player.model.VideoItem
import com.multivideo.player.model.WindowOrientation
import com.multivideo.player.player.VideoPlayerWrapper

class FloatingWindowManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val windows = mutableMapOf<String, FloatingWindow>()
    private val spacing = 4
    
    private fun getScreenWidth(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val size = Point()
            windowManager.defaultDisplay.getSize(size)
            size.x
        }
    }
    
    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val size = Point()
            windowManager.defaultDisplay.getSize(size)
            size.y
        }
    }
    
    private fun isScreenLandscape(): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    
    fun createWindow(videoItem: VideoItem): FloatingWindow {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.floating_window_player, null)
        
        val playerWrapper = VideoPlayerWrapper(context, videoItem.uri).apply {
            playerView = view.findViewById(R.id.playerView)
            isLooping = videoItem.isLooping
        }
        
        val floatingWindow = FloatingWindow(
            context = context,
            windowManager = windowManager,
            view = view,
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ),
            videoItem = videoItem,
            playerWrapper = playerWrapper
        )
        
        floatingWindow.setupViews()
        
        windows[videoItem.id] = floatingWindow
        
        windowManager.addView(view, floatingWindow.layoutParams)
        playerWrapper.initialize()
        
        rearrangeAllWindows()
        
        return floatingWindow
    }
    
    fun rearrangeAllWindows() {
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        val windowList = windows.values.toList()
        val count = windowList.size
        
        if (count == 0) return
        
        val isLandscape = isScreenLandscape()
        
        windowList.forEachIndexed { index, window ->
            val params = window.layoutParams
            
            if (isLandscape) {
                // 横屏：水平排列，高度100%，宽度均分
                val totalSpacing = spacing * (count + 1)
                val windowWidth = (screenWidth - totalSpacing) / count
                val windowHeight = screenHeight - spacing * 2
                
                params.width = windowWidth
                params.height = windowHeight
                params.x = spacing + index * (windowWidth + spacing)
                params.y = spacing
            } else {
                // 竖屏：垂直堆叠，宽度100%，高度均分
                val totalSpacing = spacing * (count + 1)
                val windowWidth = screenWidth - spacing * 2
                val windowHeight = (screenHeight - totalSpacing) / count
                
                params.width = windowWidth
                params.height = windowHeight
                params.x = spacing
                params.y = spacing + index * (windowHeight + spacing)
            }
            
            params.gravity = Gravity.TOP or Gravity.LEFT
            
            window.videoItem.windowWidth = params.width
            window.videoItem.windowHeight = params.height
            window.videoItem.windowX = params.x
            window.videoItem.windowY = params.y
            
            try {
                windowManager.updateViewLayout(window.view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun removeWindow(id: String) {
        windows[id]?.let { window ->
            window.release()
            try {
                windowManager.removeView(window.view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            windows.remove(id)
        }
        if (windows.isNotEmpty()) {
            rearrangeAllWindows()
        }
    }
    
    fun removeAllWindows() {
        windows.values.forEach { window ->
            window.release()
            try {
                windowManager.removeView(window.view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        windows.clear()
    }
    
    fun getWindow(id: String): FloatingWindow? = windows[id]
    
    fun getAllWindows(): List<FloatingWindow> = windows.values.toList()
}