package com.multivideo.player.window

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import com.google.android.exoplayer2.ui.PlayerView
import com.multivideo.player.R
import com.multivideo.player.model.VideoItem
import com.multivideo.player.player.VideoPlayerWrapper
import java.util.concurrent.TimeUnit

class FloatingWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    val view: View,
    val layoutParams: WindowManager.LayoutParams,
    val videoItem: VideoItem,
    val playerWrapper: VideoPlayerWrapper
) {
    private var isControlVisible = true
    private var seekStartPosition: Long = 0
    private var isSeeking = false
    private lateinit var tvSeekIndicator: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    fun setupViews() {
        val titleBar = view.findViewById<LinearLayout>(R.id.titleBar)
        val tvVideoTitle = view.findViewById<TextView>(R.id.tvVideoTitle)
        tvVideoTitle.text = videoItem.title
        
        val playerView = view.findViewById<PlayerView>(R.id.playerView)
        playerView.useController = false
        
        tvSeekIndicator = view.findViewById<TextView>(R.id.tvSeekIndicator)
        
        // 标题栏拖动
        setupTitleBarDrag(titleBar)
        
        // 视频区域手势
        setupVideoGesture(playerView)
        
        // 关闭按钮
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            onWindowCloseListener?.invoke(videoItem.id)
        }
        
        // 字幕按钮
        view.findViewById<ImageButton>(R.id.btnSubtitle).setOnClickListener {
            onSubtitleClickListener?.invoke(videoItem.id)
        }
        
        // 播放/暂停按钮
        view.findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener {
            playerWrapper.togglePlayPause()
            updatePlayPauseButton()
        }
        
        // 后退10秒按钮
        view.findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            playerWrapper.seekTo((playerWrapper.currentPosition - 10000).coerceAtLeast(0))
        }
        
        // 快进10秒按钮
        view.findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            val duration = playerWrapper.duration
            if (duration > 0) {
                playerWrapper.seekTo((playerWrapper.currentPosition + 10000).coerceAtMost(duration))
            }
        }
        
        // 循环播放按钮
        view.findViewById<ToggleButton>(R.id.btnLoop).apply {
            isChecked = videoItem.isLooping
            setOnCheckedChangeListener { _, isChecked ->
                playerWrapper.isLooping = isChecked
                videoItem.isLooping = isChecked
            }
        }
        
        // 全屏按钮
        view.findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener {
            onFullscreenClickListener?.invoke(videoItem.id)
        }
        
        // 进度条
        view.findViewById<SeekBar>(R.id.seekBarProgress).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val position = (progress.toLong() * playerWrapper.duration) / 1000
                        playerWrapper.seekTo(position)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        
        // 音量控制
        val seekBarVolume = view.findViewById<SeekBar>(R.id.seekBarVolume)
        val ivVolumeIcon = view.findViewById<ImageView>(R.id.ivVolumeIcon)
        seekBarVolume.apply {
            max = 100
            progress = (videoItem.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val volume = progress / 100f
                        playerWrapper.volume = volume
                        videoItem.volume = volume
                        ivVolumeIcon.setImageResource(
                            if (progress == 0) R.drawable.ic_volume else R.drawable.ic_volume_up
                        )
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        ivVolumeIcon.setImageResource(
            if (videoItem.volume == 0f) R.drawable.ic_volume else R.drawable.ic_volume_up
        )
        
        // 启动定时更新
        startTimeUpdate()
    }
    
    private fun setupTitleBarDrag(titleBar: View) {
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, layoutParams)
                        return true
                    }
                }
                return false
            }
        })
    }
    
    private fun setupVideoGesture(playerView: PlayerView) {
        var isVolumeAdjusting = false
        var isBrightnessAdjusting = false
        var startVolume: Float = 0f
        var startBrightness: Float = 0f
        var startY: Float = 0f
        
        val volumeIndicator = view.findViewById<LinearLayout>(R.id.volumeIndicator)
        val ivVolumeIndicatorIcon = view.findViewById<ImageView>(R.id.ivVolumeIndicatorIcon)
        val tvVolumePercent = view.findViewById<TextView>(R.id.tvVolumePercent)
        val brightnessIndicator = view.findViewById<LinearLayout>(R.id.brightnessIndicator)
        val tvBrightnessPercent = view.findViewById<TextView>(R.id.tvBrightnessPercent)
        
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControlVisibility()
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val viewWidth = playerView.width
                val viewHeight = playerView.height
                val duration = playerWrapper.duration
                val startX = e1.x
                
                // 判断是水平滑动还是垂直滑动
                if (Math.abs(dx) > Math.abs(dy)) {
                    // 水平滑动 - 控制进度（降低敏感度）
                    if (Math.abs(dx) > 30 && duration > 0) {
                        if (!isSeeking) {
                            isSeeking = true
                            seekStartPosition = playerWrapper.currentPosition
                            tvSeekIndicator.visibility = View.VISIBLE
                        }
                        
                        // 降低敏感度：从 0.3 改为 0.15
                        val seekDelta = (dx / viewWidth * duration * 0.15).toLong()
                        val newPosition = (seekStartPosition + seekDelta)
                            .coerceIn(0, duration)
                        
                        playerWrapper.seekTo(newPosition)
                        
                        val currentStr = formatTime(newPosition)
                        val totalStr = formatTime(duration)
                        tvSeekIndicator.text = "$currentStr / $totalStr"
                    }
                } else {
                    // 垂直滑动
                    if (startX < viewWidth / 2) {
                        // 左侧 - 控制亮度
                        if (!isBrightnessAdjusting) {
                            isBrightnessAdjusting = true
                            startBrightness = getScreenBrightness()
                            startY = e1.y
                            brightnessIndicator.visibility = View.VISIBLE
                        }
                        
                        val brightnessChange = (startY - e2.y) / viewHeight * 0.7f
                        val newBrightness = (startBrightness + brightnessChange).coerceIn(0.01f, 1f)
                        setScreenBrightness(newBrightness)
                        
                        // 更新亮度指示器
                        val brightnessPercent = (newBrightness * 100).toInt()
                        tvBrightnessPercent.text = "$brightnessPercent%"
                    } else {
                        // 右侧 - 控制音量
                        if (!isVolumeAdjusting) {
                            isVolumeAdjusting = true
                            startVolume = playerWrapper.volume
                            startY = e1.y
                            volumeIndicator.visibility = View.VISIBLE
                        }
                        
                        val volumeChange = (startY - e2.y) / viewHeight * 0.7f
                        val newVolume = (startVolume + volumeChange).coerceIn(0f, 1f)
                        playerWrapper.volume = newVolume
                        videoItem.volume = newVolume
                        
                        // 更新音量指示器
                        val volumePercent = (newVolume * 100).toInt()
                        tvVolumePercent.text = "$volumePercent%"
                        ivVolumeIndicatorIcon.setImageResource(
                            if (volumePercent == 0) R.drawable.ic_volume else R.drawable.ic_volume_up
                        )
                        // 同步更新控制栏的音量条
                        view.findViewById<SeekBar>(R.id.seekBarVolume).progress = volumePercent
                    }
                }
                return true
            }
        })
        
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isSeeking) {
                    isSeeking = false
                    tvSeekIndicator.visibility = View.GONE
                }
                if (isVolumeAdjusting) {
                    isVolumeAdjusting = false
                    volumeIndicator.visibility = View.GONE
                }
                if (isBrightnessAdjusting) {
                    isBrightnessAdjusting = false
                    brightnessIndicator.visibility = View.GONE
                }
            }
            true
        }
    }
    
    private fun getScreenBrightness(): Float {
        return try {
            val activity = context as? Activity
            val layoutParams = activity?.window?.attributes
            layoutParams?.screenBrightness ?: 0.5f
        } catch (e: Exception) {
            0.5f
        }
    }
    
    private fun setScreenBrightness(brightness: Float) {
        try {
            val activity = context as? Activity
            val layoutParams = activity?.window?.attributes
            layoutParams?.screenBrightness = brightness
            activity?.window?.attributes = layoutParams
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun toggleControlVisibility() {
        isControlVisible = !isControlVisible
        val controlBar = view.findViewById<LinearLayout>(R.id.controlBar)
        val titleBar = view.findViewById<LinearLayout>(R.id.titleBar)
        
        if (isControlVisible) {
            controlBar.visibility = View.VISIBLE
            titleBar.visibility = View.VISIBLE
        } else {
            controlBar.visibility = View.GONE
            titleBar.visibility = View.GONE
        }
    }
    
    fun updatePlayPauseButton() {
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btnPlayPause)
        if (playerWrapper.isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
        }
    }
    
    fun updateTimeDisplay() {
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val current = formatTime(playerWrapper.currentPosition)
        val total = formatTime(playerWrapper.duration)
        tvTime.text = "$current / $total"
        
        val seekBar = view.findViewById<SeekBar>(R.id.seekBarProgress)
        if (playerWrapper.duration > 0) {
            seekBar.progress = ((playerWrapper.currentPosition * 1000) / playerWrapper.duration).toInt()
        }
    }
    
    private fun startTimeUpdate() {
        stopTimeUpdate()
        updateRunnable = object : Runnable {
            override fun run() {
                updateTimeDisplay()
                updatePlayPauseButton()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateRunnable!!)
    }
    
    private fun stopTimeUpdate() {
        updateRunnable?.let {
            handler.removeCallbacks(it)
        }
        updateRunnable = null
    }
    
    private fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    fun release() {
        stopTimeUpdate()
        playerWrapper.release()
    }
    
    var onWindowCloseListener: ((String) -> Unit)? = null
    var onFullscreenClickListener: ((String) -> Unit)? = null
    var onSubtitleClickListener: ((String) -> Unit)? = null
}