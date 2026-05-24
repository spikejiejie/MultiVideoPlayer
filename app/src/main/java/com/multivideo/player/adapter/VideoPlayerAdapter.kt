package com.multivideo.player.adapter

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ui.PlayerView
import com.multivideo.player.R
import com.multivideo.player.model.VideoItem
import com.multivideo.player.player.VideoPlayerWrapper
import java.util.concurrent.TimeUnit

class VideoPlayerAdapter(
    private val context: Context,
    private val videoItems: MutableList<VideoItem>
) : RecyclerView.Adapter<VideoPlayerAdapter.VideoViewHolder>() {

    private val players = mutableMapOf<String, VideoPlayerWrapper>()
    private val handlers = mutableMapOf<String, Handler>()
    private val updateRunnables = mutableMapOf<String, Runnable>()
    var onVideoCloseListener: ((String) -> Unit)? = null
    var onSubtitleClickListener: ((String) -> Unit)? = null
    var onFullscreenClickListener: ((String) -> Unit)? = null

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvVideoTitle: TextView = itemView.findViewById(R.id.tvVideoTitle)
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val tvSeekIndicator: TextView = itemView.findViewById(R.id.tvSeekIndicator)
        val btnPlayPause: ImageButton = itemView.findViewById(R.id.btnPlayPause)
        val btnRewind: ImageButton = itemView.findViewById(R.id.btnRewind)
        val btnForward: ImageButton = itemView.findViewById(R.id.btnForward)
        val btnLoop: ToggleButton = itemView.findViewById(R.id.btnLoop)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val seekBarProgress: SeekBar = itemView.findViewById(R.id.seekBarProgress)
        val btnSubtitle: ImageButton = itemView.findViewById(R.id.btnSubtitle)
        val btnClose: ImageButton = itemView.findViewById(R.id.btnClose)
        val btnFullscreen: ImageButton = itemView.findViewById(R.id.btnFullscreen)
        val controlBar: View = itemView.findViewById(R.id.controlBar)
        val titleBar: View = itemView.findViewById(R.id.titleBar)
        val seekBarVolume: SeekBar = itemView.findViewById(R.id.seekBarVolume)
        val ivVolumeIcon: ImageView = itemView.findViewById(R.id.ivVolumeIcon)
        val volumeIndicator: LinearLayout = itemView.findViewById(R.id.volumeIndicator)
        val ivVolumeIndicatorIcon: ImageView = itemView.findViewById(R.id.ivVolumeIndicatorIcon)
        val tvVolumePercent: TextView = itemView.findViewById(R.id.tvVolumePercent)
        val brightnessIndicator: LinearLayout = itemView.findViewById(R.id.brightnessIndicator)
        val ivBrightnessIcon: ImageView = itemView.findViewById(R.id.ivBrightnessIcon)
        val tvBrightnessPercent: TextView = itemView.findViewById(R.id.tvBrightnessPercent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_video_player, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val videoItem = videoItems[position]
        
        holder.tvVideoTitle.text = videoItem.title
        holder.playerView.useController = false
        
        // 播放器懒加载：只在 onViewAttachedToWindow 中初始化
        // 这里只绑定已存在的播放器
        val playerWrapper = players[videoItem.id]
        
        if (playerWrapper != null) {
            playerWrapper.playerView = holder.playerView
            holder.playerView.player = playerWrapper.player
            startTimeUpdate(holder, videoItem.id, playerWrapper)
        } else {
            // 清空 PlayerView，等待 attach 时初始化
            holder.playerView.player = null
        }
        
        // 播放/暂停
        holder.btnPlayPause.setOnClickListener {
            players[videoItem.id]?.let { pw ->
                pw.togglePlayPause()
                updatePlayPauseButton(holder, pw)
            }
        }
        
        // 后退10秒
        holder.btnRewind.setOnClickListener {
            players[videoItem.id]?.let { pw ->
                pw.seekTo((pw.currentPosition - 10000).coerceAtLeast(0))
            }
        }
        
        // 快进10秒
        holder.btnForward.setOnClickListener {
            players[videoItem.id]?.let { pw ->
                val duration = pw.duration
                if (duration > 0) {
                    pw.seekTo((pw.currentPosition + 10000).coerceAtMost(duration))
                }
            }
        }
        
        // 循环播放
        holder.btnLoop.isChecked = videoItem.isLooping
        holder.btnLoop.setOnCheckedChangeListener { _, isChecked ->
            players[videoItem.id]?.let { pw ->
                pw.isLooping = isChecked
            }
            videoItem.isLooping = isChecked
        }
        
        // 音量控制
        holder.seekBarVolume.apply {
            max = 100
            progress = (videoItem.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val volume = progress / 100f
                        players[videoItem.id]?.let { pw ->
                            pw.volume = volume
                        }
                        videoItem.volume = volume
                        holder.ivVolumeIcon.setImageResource(
                            if (progress == 0) R.drawable.ic_volume else R.drawable.ic_volume_up
                        )
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        holder.ivVolumeIcon.setImageResource(
            if (videoItem.volume == 0f) R.drawable.ic_volume else R.drawable.ic_volume_up
        )
        
        // 进度条
        holder.seekBarProgress.max = 1000
        holder.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    players[videoItem.id]?.let { pw ->
                        if (pw.duration > 0) {
                            val pos = (progress.toLong() * pw.duration) / 1000
                            pw.seekTo(pos)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 字幕按钮
        holder.btnSubtitle.setOnClickListener {
            onSubtitleClickListener?.invoke(videoItem.id)
        }
        
        // 关闭按钮
        holder.btnClose.setOnClickListener {
            onVideoCloseListener?.invoke(videoItem.id)
        }
        
        // 全屏按钮
        holder.btnFullscreen.setOnClickListener {
            onFullscreenClickListener?.invoke(videoItem.id)
        }
        
        // 视频区域手势（即使播放器未初始化也设置，等待播放器可用）
        setupVideoGesture(holder, playerWrapper)
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position >= videoItems.size) return
        
        val videoItem = videoItems[position]
        
        // 懒加载：仅在 PlayerView 可见时初始化播放器
        if (players[videoItem.id] == null) {
            val playerWrapper = VideoPlayerWrapper(context, videoItem.uri).apply {
                playerView = holder.playerView
                isLooping = videoItem.isLooping
                initialize(videoItem.volume)
            }
            
            players[videoItem.id] = playerWrapper
            
            // 自动加载字幕
            if (videoItem.subtitleUri != null) {
                playerWrapper.loadSubtitle(videoItem.subtitleUri!!)
            }
            
            // 恢复到之前保存的播放位置
            if (videoItem.currentPosition > 0) {
                playerWrapper.seekTo(videoItem.currentPosition)
            }
            
            holder.playerView.player = playerWrapper.player
            setupVideoGesture(holder, playerWrapper)
            startTimeUpdate(holder, videoItem.id, playerWrapper)
        }
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position >= videoItems.size) return
        
        val videoItem = videoItems[position]
        
        // 保存当前播放位置和音量，释放不可见的播放器以节省内存
        players[videoItem.id]?.let { pw ->
            videoItem.currentPosition = pw.currentPosition
            videoItem.volume = pw.volume
        }
        stopTimeUpdate(videoItem.id)
        players[videoItem.id]?.release()
        players.remove(videoItem.id)
        holder.playerView.player = null
    }
    
    private fun startTimeUpdate(holder: VideoViewHolder, videoId: String, playerWrapper: VideoPlayerWrapper) {
        // 停止之前的更新
        stopTimeUpdate(videoId)
        
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                updateTimeDisplay(holder, playerWrapper)
                updatePlayPauseButton(holder, playerWrapper)
                handler.postDelayed(this, 500)
            }
        }
        
        handlers[videoId] = handler
        updateRunnables[videoId] = runnable
        handler.post(runnable)
    }
    
    private fun stopTimeUpdate(videoId: String) {
        updateRunnables[videoId]?.let { runnable ->
            handlers[videoId]?.removeCallbacks(runnable)
        }
        handlers.remove(videoId)
        updateRunnables.remove(videoId)
    }
    
    private fun setupVideoGesture(holder: VideoViewHolder, playerWrapper: VideoPlayerWrapper?) {
        var seekStartPosition: Long = 0
        var isSeeking = false
        var isVolumeAdjusting = false
        var isBrightnessAdjusting = false
        var startVolume: Float = 0f
        var startBrightness: Float = 0f
        var startY: Float = 0f
        
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControlVisibility(holder)
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                
                // 获取当前播放器（从 map 中获取，确保是最新的）
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= videoItems.size) return false
                val videoItem = videoItems[position]
                val currentPlayer = players[videoItem.id] ?: return false
                
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val viewWidth = holder.playerView.width
                val viewHeight = holder.playerView.height
                val duration = currentPlayer.duration
                val startX = e1.x
                
                // 判断是水平滑动还是垂直滑动
                if (Math.abs(dx) > Math.abs(dy)) {
                    // 水平滑动 - 控制进度（降低敏感度）
                    if (Math.abs(dx) > 30 && duration > 0) {
                        if (!isSeeking) {
                            isSeeking = true
                            seekStartPosition = currentPlayer.currentPosition
                            holder.tvSeekIndicator.visibility = View.VISIBLE
                        }
                        
                        // 降低敏感度：从 0.3 改为 0.15
                        val seekDelta = (dx / viewWidth * duration * 0.15).toLong()
                        val newPosition = (seekStartPosition + seekDelta)
                            .coerceIn(0, duration)
                        
                        currentPlayer.seekTo(newPosition)
                        
                        val currentStr = formatTime(newPosition)
                        val totalStr = formatTime(duration)
                        holder.tvSeekIndicator.text = "$currentStr / $totalStr"
                    }
                } else {
                    // 垂直滑动
                    if (startX < viewWidth / 2) {
                        // 左侧 - 控制亮度
                        if (!isBrightnessAdjusting) {
                            isBrightnessAdjusting = true
                            startBrightness = getScreenBrightness()
                            startY = e1.y
                            holder.brightnessIndicator.visibility = View.VISIBLE
                        }
                        
                        val brightnessChange = (startY - e2.y) / viewHeight * 0.7f
                        val newBrightness = (startBrightness + brightnessChange).coerceIn(0.01f, 1f)
                        setScreenBrightness(newBrightness)
                        
                        // 更新亮度指示器
                        val brightnessPercent = (newBrightness * 100).toInt()
                        holder.tvBrightnessPercent.text = "$brightnessPercent%"
                    } else {
                        // 右侧 - 控制音量
                        if (!isVolumeAdjusting) {
                            isVolumeAdjusting = true
                            startVolume = currentPlayer.volume
                            startY = e1.y
                            holder.volumeIndicator.visibility = View.VISIBLE
                        }
                        
                        val volumeChange = (startY - e2.y) / viewHeight * 0.7f
                        val newVolume = (startVolume + volumeChange).coerceIn(0f, 1f)
                        currentPlayer.volume = newVolume
                        videoItem.volume = newVolume
                        
                        // 更新音量指示器
                        val volumePercent = (newVolume * 100).toInt()
                        holder.tvVolumePercent.text = "$volumePercent%"
                        holder.ivVolumeIndicatorIcon.setImageResource(
                            if (volumePercent == 0) R.drawable.ic_volume else R.drawable.ic_volume_up
                        )
                        // 同步更新控制栏的音量条
                        holder.seekBarVolume.progress = volumePercent
                        holder.ivVolumeIcon.setImageResource(
                            if (volumePercent == 0) R.drawable.ic_volume else R.drawable.ic_volume_up
                        )
                    }
                }
                return true
            }
        })
        
        holder.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isSeeking) {
                    isSeeking = false
                    holder.tvSeekIndicator.visibility = View.GONE
                }
                if (isVolumeAdjusting) {
                    isVolumeAdjusting = false
                    holder.volumeIndicator.visibility = View.GONE
                }
                if (isBrightnessAdjusting) {
                    isBrightnessAdjusting = false
                    holder.brightnessIndicator.visibility = View.GONE
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
    
    private fun toggleControlVisibility(holder: VideoViewHolder) {
        if (holder.controlBar.visibility == View.VISIBLE) {
            holder.controlBar.visibility = View.GONE
            holder.titleBar.visibility = View.GONE
        } else {
            holder.controlBar.visibility = View.VISIBLE
            holder.titleBar.visibility = View.VISIBLE
        }
    }
    
    private fun updatePlayPauseButton(holder: VideoViewHolder, playerWrapper: VideoPlayerWrapper) {
        if (playerWrapper.isPlaying) {
            holder.btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            holder.btnPlayPause.setImageResource(R.drawable.ic_play)
        }
    }
    
    private fun updateTimeDisplay(holder: VideoViewHolder, playerWrapper: VideoPlayerWrapper) {
        val current = formatTime(playerWrapper.currentPosition)
        val total = formatTime(playerWrapper.duration)
        holder.tvTime.text = "$current / $total"
        
        if (playerWrapper.duration > 0) {
            holder.seekBarProgress.progress = 
                ((playerWrapper.currentPosition * 1000) / playerWrapper.duration).toInt()
        }
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
    
    fun getPlayer(videoId: String): VideoPlayerWrapper? = players[videoId]
    
    fun removePlayer(videoId: String) {
        stopTimeUpdate(videoId)
        players[videoId]?.release()
        players.remove(videoId)
    }
    
    fun releaseAll(videoItems: List<VideoItem>? = null) {
        // 先停止所有定时器
        val handlerKeys = handlers.keys.toList()
        handlerKeys.forEach { stopTimeUpdate(it) }
        
        // 保存所有活跃播放器的位置和音量到 videoItem
        videoItems?.forEach { item ->
            players[item.id]?.let { pw ->
                item.currentPosition = pw.currentPosition
                item.volume = pw.volume
            }
        }
        
        // 再释放所有播放器
        val playerKeys = players.keys.toList()
        playerKeys.forEach { key ->
            players[key]?.release()
        }
        players.clear()
    }
    
    override fun getItemCount(): Int = videoItems.size
}