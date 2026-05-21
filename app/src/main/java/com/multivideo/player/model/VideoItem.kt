package com.multivideo.player.model

import android.net.Uri

enum class WindowOrientation {
    PORTRAIT,
    LANDSCAPE
}

data class VideoItem(
    val id: String,
    val uri: Uri,
    val title: String,
    var subtitleUri: Uri? = null,
    var isLooping: Boolean = false,
    var currentPosition: Long = 0,
    var windowX: Int = 0,
    var windowY: Int = 0,
    var windowWidth: Int = 300,
    var windowHeight: Int = 250,
    var orientation: WindowOrientation = WindowOrientation.PORTRAIT
)