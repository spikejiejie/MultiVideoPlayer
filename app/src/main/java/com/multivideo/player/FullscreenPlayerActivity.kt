package com.multivideo.player

import android.app.Activity
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.util.concurrent.TimeUnit

class FullscreenPlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBarProgress: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var topControls: View
    private lateinit var bottomControls: View
    private lateinit var brightnessIndicator: View
    private lateinit var volumeIndicator: View
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvVolumeValue: TextView
    
    private var videoUri: Uri? = null
    private var videoTitle: String = ""
    private var videoPosition: Long = 0
    
    private var currentBrightness: Float = 0.5f
    private var currentVolume: Int = 0
    private var maxVolume: Int = 0
    
    private var isControlsVisible = true
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全屏沉浸模式
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        
        setContentView(R.layout.activity_fullscreen_player)
        
        // 获取传入的视频信息
        videoUri = intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        videoTitle = intent.getStringExtra("video_title") ?: ""
        videoPosition = intent.getLongExtra("video_position", 0)
        
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // 获取当前亮度
        try {
            val layoutParams = window.attributes
            currentBrightness = layoutParams.screenBrightness
            if (currentBrightness < 0) {
                currentBrightness = Settings.System.getFloat(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255f
                ) / 255f
            }
        } catch (e: Exception) {
            currentBrightness = 0.5f
        }
        
        initViews()
        initPlayer()
        setupGestures()
        startTimeUpdate()
    }
    
    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        tvTitle = findViewById(R.id.tvTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBarProgress = findViewById(R.id.seekBarProgress)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        topControls = findViewById(R.id.topControls)
        bottomControls = findViewById(R.id.bottomControls)
        brightnessIndicator = findViewById(R.id.brightnessIndicator)
        volumeIndicator = findViewById(R.id.volumeIndicator)
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue)
        tvVolumeValue = findViewById(R.id.tvVolumeValue)
        
        tvTitle.text = videoTitle
        
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
        
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            updatePlayPauseButton()
        }
        
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
        }
        
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
        }
        
        seekBarProgress.max = 1000
        seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    val pos = (progress.toLong() * player.duration) / 1000
                    player.seekTo(pos)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            playerView.player = this
            playerView.useController = false
            
            videoUri?.let { setMediaItem(MediaItem.fromUri(it)) }
            prepare()
            seekTo(videoPosition)
            play()
        }
        
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    updatePlayPauseButton()
                }
            }
        })
    }
    
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = playerView.width
                val tapX = e.x
                
                if (tapX < screenWidth / 2) {
                    // 左侧双击：快退10秒
                    player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                } else {
                    // 右侧双击：快进10秒
                    player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
                }
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                
                val screenWidth = playerView.width
                val startY = e1.y
                val endY = e2.y
                val deltaY = startY - endY
                val viewHeight = playerView.height
                
                if (e1.x < screenWidth / 2) {
                    // 左侧：调节亮度
                    val deltaBrightness = deltaY / viewHeight
                    currentBrightness = (currentBrightness + deltaBrightness).coerceIn(0.01f, 1f)
                    
                    val layoutParams = window.attributes
                    layoutParams.screenBrightness = currentBrightness
                    window.attributes = layoutParams
                    
                    // 显示亮度提示
                    showBrightnessIndicator()
                } else {
                    // 右侧：调节音量
                    val deltaVolume = (deltaY / viewHeight * maxVolume).toInt()
                    currentVolume = (currentVolume + deltaVolume).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    
                    // 显示音量提示
                    showVolumeIndicator()
                }
                return true
            }
        })
        
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                handler.postDelayed({
                    brightnessIndicator.visibility = View.GONE
                    volumeIndicator.visibility = View.GONE
                }, 1000)
            }
            true
        }
    }
    
    private fun showBrightnessIndicator() {
        brightnessIndicator.visibility = View.VISIBLE
        volumeIndicator.visibility = View.GONE
        val percent = (currentBrightness * 100).toInt()
        tvBrightnessValue.text = "$percent%"
    }
    
    private fun showVolumeIndicator() {
        volumeIndicator.visibility = View.VISIBLE
        brightnessIndicator.visibility = View.GONE
        val percent = (currentVolume * 100 / maxVolume)
        tvVolumeValue.text = "$percent%"
    }
    
    private fun toggleControls() {
        isControlsVisible = !isControlsVisible
        if (isControlsVisible) {
            topControls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
        } else {
            topControls.visibility = View.GONE
            bottomControls.visibility = View.GONE
        }
    }
    
    private fun updatePlayPauseButton() {
        if (player.isPlaying) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            btnPlayPause.setImageResource(R.drawable.ic_play)
        }
    }
    
    private fun startTimeUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                updateTimeDisplay()
                handler.postDelayed(this, 500)
            }
        })
    }
    
    private fun updateTimeDisplay() {
        val current = formatTime(player.currentPosition)
        val total = formatTime(player.duration)
        tvCurrentTime.text = current
        tvTotalTime.text = total
        
        if (player.duration > 0) {
            seekBarProgress.progress = ((player.currentPosition * 1000) / player.duration).toInt()
        }
        
        updatePlayPauseButton()
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
    
    override fun finish() {
        // 返回视频位置给调用方
        intent.putExtra("video_position", player.currentPosition)
        setResult(RESULT_OK, intent)
        super.finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}