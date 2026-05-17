package com.multivideo.player.adapter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
        val btnLoop: ToggleButton = itemView.findViewById(R.id.btnLoop)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val seekBarProgress: SeekBar = itemView.findViewById(R.id.seekBarProgress)
        val btnSubtitle: ImageButton = itemView.findViewById(R.id.btnSubtitle)
        val btnClose: ImageButton = itemView.findViewById(R.id.btnClose)
        val btnFullscreen: ImageButton = itemView.findViewById(R.id.btnFullscreen)
        val controlBar: View = itemView.findViewById(R.id.controlBar)
        val titleBar: View = itemView.findViewById(R.id.titleBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_video_player, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val videoItem = videoItems[position]
        
        holder.tvVideoTitle.text = videoItem.title
        holder.playerView.useController = false
        
        // 创建或获取播放器
        val playerWrapper = players.getOrPut(videoItem.id) {
            VideoPlayerWrapper(context, videoItem.uri).apply {
                playerView = holder.playerView
                isLooping = videoItem.isLooping
                initialize()
            }
        }
        
        // 确保 playerView 绑定正确
        playerWrapper.playerView = holder.playerView
        holder.playerView.player = playerWrapper.player
        
        // 播放/暂停
        holder.btnPlayPause.setOnClickListener {
            playerWrapper.togglePlayPause()
            updatePlayPauseButton(holder, playerWrapper)
        }
        
        // 循环播放
        holder.btnLoop.isChecked = videoItem.isLooping
        holder.btnLoop.setOnCheckedChangeListener { _, isChecked ->
            playerWrapper.isLooping = isChecked
            videoItem.isLooping = isChecked
        }
        
        // 进度条
        holder.seekBarProgress.max = 1000
        holder.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && playerWrapper.duration > 0) {
                    val pos = (progress.toLong() * playerWrapper.duration) / 1000
                    playerWrapper.seekTo(pos)
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
        
        // 视频区域手势
        setupVideoGesture(holder, playerWrapper)
        
        // 启动定时更新
        startTimeUpdate(holder, videoItem.id, playerWrapper)
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
    
    private fun setupVideoGesture(holder: VideoViewHolder, playerWrapper: VideoPlayerWrapper) {
        var seekStartPosition: Long = 0
        var isSeeking = false
        
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
                
                val dx = e2.x - e1.x
                val viewWidth = holder.playerView.width
                
                if (Math.abs(dx) > 30) {
                    if (!isSeeking) {
                        isSeeking = true
                        seekStartPosition = playerWrapper.currentPosition
                        holder.tvSeekIndicator.visibility = View.VISIBLE
                    }
                    
                    val seekDelta = (dx / viewWidth * playerWrapper.duration * 0.3).toLong()
                    val newPosition = (seekStartPosition + seekDelta)
                        .coerceIn(0, playerWrapper.duration)
                    
                    playerWrapper.seekTo(newPosition)
                    
                    val currentStr = formatTime(newPosition)
                    val totalStr = formatTime(playerWrapper.duration)
                    holder.tvSeekIndicator.text = "$currentStr / $totalStr"
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
            }
            true
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
    
    fun releaseAll() {
        // 先停止所有定时器
        val handlerKeys = handlers.keys.toList()
        handlerKeys.forEach { stopTimeUpdate(it) }
        
        // 再释放所有播放器
        val playerKeys = players.keys.toList()
        playerKeys.forEach { key ->
            players[key]?.release()
        }
        players.clear()
    }
    
    override fun getItemCount(): Int = videoItems.size
}