package com.multivideo.player.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.util.MimeTypes

class VideoPlayerWrapper(
    private val context: Context,
    private val uri: Uri
) {
    var player: ExoPlayer? = null
        private set
    
    var playerView: PlayerView? = null
    
    var isLooping: Boolean = false
        set(value) {
            field = value
            player?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player?.volume = field
        }
    
    var currentPosition: Long
        get() = player?.currentPosition ?: 0
        set(value) {
            player?.seekTo(value)
        }
    
    val duration: Long
        get() = player?.duration ?: 0
    
    val isPlaying: Boolean
        get() = player?.isPlaying ?: false
    
    private val subtitleExtensions = listOf(".srt", ".vtt", ".ass", ".ssa", ".ttml")
    
    fun initialize(initialVolume: Float = 1.0f) {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .build()
        
        volume = initialVolume
        
        // 使用较小的缓冲区以减少内存占用（本地视频场景）
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10000,  // 最小缓冲 10秒
                30000,  // 最大缓冲 30秒
                1500,   // 播放前缓冲 1.5秒
                2000    // 重新缓冲 2秒
            )
            .build()
        
        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(mediaItem)
                volume = initialVolume
                prepare()
                playWhenReady = true
                repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        
        playerView?.player = player
        
        // 尝试自动加载同名字幕
        tryLoadSubtitleAuto()
    }
    
    private fun tryLoadSubtitleAuto() {
        try {
            val videoDoc = DocumentFile.fromSingleUri(context, uri) ?: return
            val videoName = videoDoc.name ?: return
            
            // 获取不带扩展名的视频文件名
            val baseName = videoName.substringBeforeLast(".")
            
            // 尝试多种方式获取父目录并查找字幕
            val subtitleUri = findSubtitleFile(baseName)
            if (subtitleUri != null) {
                loadSubtitle(subtitleUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun findSubtitleFile(baseName: String): Uri? {
        try {
            val videoDoc = DocumentFile.fromSingleUri(context, uri) ?: return null
            val parentDoc = videoDoc.parentFile
            if (parentDoc != null) {
                val subtitle = searchSubtitleInDirectory(parentDoc, baseName)
                if (subtitle != null) return subtitle
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun searchSubtitleInDirectory(directory: DocumentFile, baseName: String): Uri? {
        try {
            for (file in directory.listFiles()) {
                val fileName = file.name ?: continue
                val fileNameWithoutExt = fileName.substringBeforeLast(".")
                
                // 检查是否是同名字幕文件
                if (fileNameWithoutExt.equals(baseName, ignoreCase = true)) {
                    val ext = fileName.substringAfterLast(".", "").lowercase()
                    if (subtitleExtensions.any { it == ".$ext" }) {
                        return file.uri
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    fun loadSubtitle(subtitleUri: Uri, language: String = "zh") {
        val currentPlayer = player ?: return
        
        val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(getSubtitleMimeType(subtitleUri))
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        
        val currentMediaItem = currentPlayer.currentMediaItem ?: return
        val newMediaItem = currentMediaItem.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()
        
        val position = currentPlayer.currentPosition
        currentPlayer.setMediaItem(newMediaItem, false)
        currentPlayer.seekTo(position)
        currentPlayer.prepare()
    }
    
    private fun getSubtitleMimeType(uri: Uri): String {
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
            path.endsWith(".ssa") || path.endsWith(".ass") -> "text/x-ssa"
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
    
    fun play() {
        player?.play()
    }
    
    fun pause() {
        player?.pause()
    }
    
    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }
    
    fun seekTo(position: Long) {
        player?.seekTo(position)
    }
    
    fun release() {
        player?.release()
        player = null
        playerView?.player = null
    }
}