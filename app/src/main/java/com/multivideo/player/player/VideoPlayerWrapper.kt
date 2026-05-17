package com.multivideo.player.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.google.android.exoplayer2.C
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
    
    fun initialize() {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .build()
        
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItem)
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
            
            // 获取父目录
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            
            // 尝试通过构建同名字幕URI
            // 由于content URI限制，我们尝试直接在initialize后加载
            // 用户可以通过按钮手动加载字幕
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
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