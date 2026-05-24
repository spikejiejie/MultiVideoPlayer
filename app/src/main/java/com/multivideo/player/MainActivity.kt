package com.multivideo.player

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.multivideo.player.adapter.VideoPlayerAdapter
import com.multivideo.player.model.VideoItem
import com.multivideo.player.window.FloatingWindowManager
import java.util.*

class MainActivity : AppCompatActivity() {
    
    private lateinit var floatingWindowManager: FloatingWindowManager
    private lateinit var recyclerViewVideos: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var inAppContainer: FrameLayout
    private lateinit var floatContainer: FrameLayout
    private lateinit var btnAddVideo: Button
    private lateinit var btnCloseAll: Button
    private lateinit var btnModeInApp: Button
    private lateinit var btnModeFloat: Button
    private lateinit var btnToggleToolbar: ImageButton
    private lateinit var toolbarActions: LinearLayout
    
    private val videoItems = mutableListOf<VideoItem>()
    private var videoPlayerAdapter: VideoPlayerAdapter? = null
    private var isFloatingMode = false
    private var isToolbarExpanded = false
    private var currentSubtitleWindowId: String? = null
    private var isReceiverRegistered = false
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            // 分离视频和字幕文件
            val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp")
            val subtitleExtensions = listOf(".srt", ".vtt", ".ass", ".ssa", ".ttml")
            
            val videos = mutableListOf<Uri>()
            val subtitles = mutableListOf<Uri>()
            
            uris.forEach { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = getFileName(uri).lowercase()
                when {
                    subtitleExtensions.any { name.endsWith(it) } -> subtitles.add(uri)
                    else -> videos.add(uri)
                }
            }
            
            // 添加视频
            videos.forEach { uri ->
                addVideoFromUri(uri, subtitles)
            }
        }
    }
    
    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadSubtitleToWindow(it)
        }
    }
    
    private val fullscreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val position = result.data?.getLongExtra("video_position", 0) ?: 0
            val volume = result.data?.getFloatExtra("video_volume", 1.0f) ?: 1.0f
            val videoId = result.data?.getStringExtra("video_id") ?: return@registerForActivityResult
            val videoItem = videoItems.find { it.id == videoId }
            
            // 保存位置和音量到 videoItem
            videoItem?.currentPosition = position
            videoItem?.volume = volume
            
            // 恢复视频位置和音量
            if (isFloatingMode) {
                floatingWindowManager.getWindow(videoId)?.let { window ->
                    window.playerWrapper.seekTo(position)
                    window.playerWrapper.volume = volume
                }
            } else {
                videoPlayerAdapter?.getPlayer(videoId)?.let { player ->
                    player.seekTo(position)
                    player.volume = volume
                }
            }
        }
        // 恢复所有视频播放
        resumeAllVideos()
    }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkOverlayPermission()
        } else {
            showPermissionDeniedDialog("存储")
        }
    }
    
    private val manageStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()) {
            checkOverlayPermission()
        } else {
            showPermissionDeniedDialog("存储")
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            initializeWindowManager()
        } else {
            showPermissionDeniedDialog("悬浮窗")
        }
    }
    
    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                if (::floatingWindowManager.isInitialized && isFloatingMode) {
                    floatingWindowManager.rearrangeAllWindows()
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        checkPermissions()
        registerOrientationReceiver()
    }
    
    private fun registerOrientationReceiver() {
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        registerReceiver(orientationReceiver, filter)
        isReceiverRegistered = true
    }
    
    private fun initViews() {
        recyclerViewVideos = findViewById(R.id.recyclerViewVideos)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        inAppContainer = findViewById(R.id.inAppContainer)
        floatContainer = findViewById(R.id.floatContainer)
        btnAddVideo = findViewById(R.id.btnAddVideo)
        btnCloseAll = findViewById(R.id.btnCloseAll)
        btnModeInApp = findViewById(R.id.btnModeInApp)
        btnModeFloat = findViewById(R.id.btnModeFloat)
        btnToggleToolbar = findViewById(R.id.btnToggleToolbar)
        toolbarActions = findViewById(R.id.toolbarActions)
        
        // 初始化 RecyclerView
        videoPlayerAdapter = VideoPlayerAdapter(this, videoItems)
        recyclerViewVideos.layoutManager = LinearLayoutManager(this)
        recyclerViewVideos.adapter = videoPlayerAdapter
        
        videoPlayerAdapter?.onVideoCloseListener = { id ->
            closeVideo(id)
        }
        
        videoPlayerAdapter?.onSubtitleClickListener = { id ->
            currentSubtitleWindowId = id
            subtitlePickerLauncher.launch(arrayOf(
                "application/x-subrip",
                "text/vtt",
                "application/ttml+xml",
                "*/*"
            ))
        }
        
        videoPlayerAdapter?.onFullscreenClickListener = { id ->
            enterFullscreen(id)
        }
        
        // 工具栏折叠/展开
        btnToggleToolbar.setOnClickListener {
            toggleToolbar()
        }
        
        btnAddVideo.setOnClickListener {
            videoPickerLauncher.launch(arrayOf("*/*"))
        }
        
        btnCloseAll.setOnClickListener {
            closeAllVideos()
        }
        
        btnModeInApp.setOnClickListener {
            switchToInAppMode()
        }
        
        btnModeFloat.setOnClickListener {
            switchToFloatMode()
        }
        
        // 默认应用内模式
        updateModeButtons()
        
        // 默认展开工具栏
        isToolbarExpanded = true
        toolbarActions.visibility = View.VISIBLE
        btnToggleToolbar.setImageResource(R.drawable.ic_expand_less)
    }
    
    private fun toggleToolbar() {
        isToolbarExpanded = !isToolbarExpanded
        
        if (isToolbarExpanded) {
            toolbarActions.visibility = View.VISIBLE
            btnToggleToolbar.setImageResource(R.drawable.ic_expand_less)
        } else {
            toolbarActions.visibility = View.GONE
            btnToggleToolbar.setImageResource(R.drawable.ic_expand_more)
        }
    }
    
    private fun switchToInAppMode() {
        isFloatingMode = false
        inAppContainer.visibility = View.VISIBLE
        floatContainer.visibility = View.GONE
        updateModeButtons()
        
        // 保存悬浮窗的当前位置和音量
        if (::floatingWindowManager.isInitialized) {
            floatingWindowManager.getAllWindows().forEach { window ->
                val videoItem = videoItems.find { it.id == window.videoItem.id }
                videoItem?.let {
                    it.currentPosition = window.playerWrapper.currentPosition
                    it.volume = window.playerWrapper.volume
                }
            }
            floatingWindowManager.removeAllWindows()
        }
        
        // 刷新列表
        videoPlayerAdapter?.notifyDataSetChanged()
        updateEmptyState()
    }
    
    private fun switchToFloatMode() {
        isFloatingMode = true
        inAppContainer.visibility = View.GONE
        floatContainer.visibility = View.VISIBLE
        updateModeButtons()
        
        // 释放应用内播放器（会自动保存位置和音量到 videoItem）
        videoPlayerAdapter?.releaseAll(videoItems)
        
        // 创建悬浮窗
        if (Settings.canDrawOverlays(this)) {
            initializeWindowManager()
            videoItems.forEach { videoItem ->
                createFloatingWindow(videoItem)
            }
        } else {
            requestOverlayPermission()
        }
    }
    
    private fun updateModeButtons() {
        if (isFloatingMode) {
            btnModeInApp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF666666.toInt())
            btnModeFloat.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9C27B0.toInt())
        } else {
            btnModeInApp.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
            btnModeFloat.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF666666.toInt())
        }
    }
    
    private fun checkPermissions() {
        // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE 权限来访问所有文件
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                checkOverlayPermission()
            } else {
                requestManageStoragePermission()
            }
        } else {
            // Android 10 及以下
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    checkOverlayPermission()
                }
                else -> {
                    storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
    
    private fun requestManageStoragePermission() {
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage("应用需要访问存储权限来自动加载同名字幕文件")
            .setPositiveButton("去授权") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    manageStoragePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStoragePermissionLauncher.launch(intent)
                }
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                checkOverlayPermission()
            }
            .show()
    }
    
    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            initializeWindowManager()
        }
    }
    
    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("悬浮窗模式需要悬浮窗权限来显示多个视频窗口")
            .setPositiveButton("去授权") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                switchToInAppMode()
            }
            .show()
    }
    
    private fun showPermissionDeniedDialog(permissionName: String) {
        AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("$permissionName 权限被拒绝，无法正常使用应用")
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun initializeWindowManager() {
        if (!::floatingWindowManager.isInitialized) {
            floatingWindowManager = FloatingWindowManager(this, windowManager)
        }
    }
    
    private fun addVideoFromUri(uri: Uri, subtitleUris: List<Uri> = emptyList()) {
        val videoTitle = getFileName(uri)
        val baseName = videoTitle.substringBeforeLast(".")
        
        // 查找匹配的字幕文件 - 先从用户选择的，再自动查找
        var matchedSubtitle = subtitleUris.firstOrNull { subtitleUri ->
            val subtitleName = getFileName(subtitleUri)
            val subtitleBaseName = subtitleName.substringBeforeLast(".")
            subtitleBaseName.equals(baseName, ignoreCase = true)
        }
        
        // 如果用户没有选择字幕，尝试自动查找同名字幕
        if (matchedSubtitle == null) {
            matchedSubtitle = findSubtitleByVideoUri(uri, baseName)
        }
        
        val videoItem = VideoItem(
            id = UUID.randomUUID().toString(),
            uri = uri,
            title = videoTitle,
            subtitleUri = matchedSubtitle
        )
        
        videoItems.add(videoItem)
        
        if (isFloatingMode) {
            createFloatingWindow(videoItem)
        } else {
            videoPlayerAdapter?.notifyItemInserted(videoItems.size - 1)
        }
        
        updateEmptyState()
    }
    
    private fun findSubtitleByVideoUri(videoUri: Uri, baseName: String): Uri? {
        val subtitleExtensions = listOf(".srt", ".vtt", ".ass", ".ssa", ".ttml")
        
        try {
            // 首先尝试通过文件路径直接查找
            val videoPath = getPathFromUri(videoUri)
            if (videoPath != null) {
                val videoFile = java.io.File(videoPath)
                val parentDir = videoFile.parentFile
                
                if (parentDir != null && parentDir.exists()) {
                    for (ext in subtitleExtensions) {
                        val subtitleFile = java.io.File(parentDir, "$baseName$ext")
                        if (subtitleFile.exists()) {
                            return copySubtitleToPrivate(subtitleFile)
                        }
                    }
                }
            }
            
            // 如果文件路径方式失败，查询 MediaStore
            val collection = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: continue
                    
                    val isSubtitle = subtitleExtensions.any { name.lowercase().endsWith(it) }
                    if (!isSubtitle) continue
                    
                    val fileNameWithoutExt = name.substringBeforeLast(".")
                    if (fileNameWithoutExt.equals(baseName, ignoreCase = true)) {
                        return android.content.ContentUris.withAppendedId(collection, id)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
    
    private fun getPathFromUri(uri: Uri): String? {
        try {
            val uriStr = uri.toString()
            
            // 对于小米文件管理器: content://com.android.fileexplorer.myprovider/external_files/path
            if (uriStr.contains("com.android.fileexplorer.myprovider")) {
                val encodedPath = uriStr.substringAfter("myprovider")
                val decodedPath = Uri.decode(encodedPath)
                if (decodedPath.startsWith("/external_files/")) {
                    val filePath = decodedPath.removePrefix("/external_files/")
                    val fullPath = "/storage/emulated/0/$filePath"
                    val file = java.io.File(fullPath)
                    if (file.exists()) {
                        return fullPath
                    }
                }
            }
            
            // 对于 externalstorage URI
            if (uriStr.contains("com.android.externalstorage.documents")) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val storageType = parts[0]
                    val path = parts[1]
                    
                    val storagePath = when (storageType) {
                        "primary" -> "/storage/emulated/0"
                        else -> "/storage/$storageType"
                    }
                    
                    val fullPath = "$storagePath/$path"
                    val file = java.io.File(fullPath)
                    if (file.exists()) {
                        return fullPath
                    }
                }
            }
            
            // 对于 media URI
            if (uriStr.contains("com.android.providers.media.documents")) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val mediaId = parts[1]
                    
                    val cursor = contentResolver.query(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                        "${android.provider.MediaStore.MediaColumns._ID} = ?",
                        arrayOf(mediaId),
                        null
                    )
                    
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val dataIndex = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                            if (dataIndex >= 0) {
                                return it.getString(dataIndex)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
    
    private fun copySubtitleToPrivate(subtitleFile: java.io.File): Uri? {
        try {
            val privateDir = java.io.File(filesDir, "subtitles")
            if (!privateDir.exists()) {
                privateDir.mkdirs()
            }
            
            val privateFile = java.io.File(privateDir, subtitleFile.name)
            subtitleFile.copyTo(privateFile, overwrite = true)
            
            return androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                privateFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun getVideoTitle(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val titleIndex = it.getColumnIndex(android.provider.MediaStore.Video.Media.TITLE)
                if (titleIndex >= 0) {
                    it.getString(titleIndex)
                } else {
                    uri.lastPathSegment ?: "未知视频"
                }
            } else {
                uri.lastPathSegment ?: "未知视频"
            }
        } ?: uri.lastPathSegment ?: "未知视频"
    }
    
    private fun getFileName(uri: Uri): String {
        var name = ""
        
        // 方法1: 通过 ContentResolver 查询
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        name = it.getString(nameIndex) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        
        // 方法2: 通过 URI path 获取
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: ""
            if (name.contains("/")) {
                name = name.substringAfterLast("/")
            }
            if (name.contains("%")) {
                try {
                    name = java.net.URLDecoder.decode(name, "UTF-8")
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        
        return name
    }
    
    private fun createFloatingWindow(videoItem: VideoItem) {
        if (!::floatingWindowManager.isInitialized) return
        
        val floatingWindow = floatingWindowManager.createWindow(videoItem)
        
        floatingWindow.onWindowCloseListener = { id ->
            closeVideo(id)
        }
        
        floatingWindow.onFullscreenClickListener = { id ->
            enterFullscreen(id)
        }
        
        floatingWindow.onSubtitleClickListener = { id ->
            currentSubtitleWindowId = id
            subtitlePickerLauncher.launch(arrayOf(
                "application/x-subrip",
                "text/vtt",
                "application/ttml+xml",
                "*/*"
            ))
        }
    }
    
    private fun closeVideo(id: String) {
        val index = videoItems.indexOfFirst { it.id == id }
        if (index != -1) {
            videoItems.removeAt(index)
            
            if (isFloatingMode) {
                floatingWindowManager.removeWindow(id)
            } else {
                videoPlayerAdapter?.removePlayer(id)
                videoPlayerAdapter?.notifyItemRemoved(index)
            }
            
            updateEmptyState()
        }
    }
    
    private fun loadSubtitleToWindow(subtitleUri: Uri) {
        val videoId = currentSubtitleWindowId ?: return
        
        if (isFloatingMode) {
            val window = floatingWindowManager.getWindow(videoId) ?: return
            window.playerWrapper.loadSubtitle(subtitleUri)
        } else {
            val player = videoPlayerAdapter?.getPlayer(videoId) ?: return
            player.loadSubtitle(subtitleUri)
        }
        
        Toast.makeText(this, "字幕已加载", Toast.LENGTH_SHORT).show()
    }
    
    private fun closeAllVideos() {
        videoItems.clear()
        
        if (isFloatingMode) {
            if (::floatingWindowManager.isInitialized) {
                floatingWindowManager.removeAllWindows()
            }
        } else {
            videoPlayerAdapter?.releaseAll()
            videoPlayerAdapter?.notifyDataSetChanged()
        }
        
        updateEmptyState()
    }
    
    private fun enterFullscreen(videoId: String) {
        val videoItem = videoItems.find { it.id == videoId } ?: return
        val player = if (isFloatingMode) {
            // 悬浮窗模式下，先获取播放位置，然后切换到应用内模式
            val window = floatingWindowManager.getWindow(videoId)
            val position = window?.playerWrapper?.currentPosition ?: 0
            videoItem.currentPosition = position
            // 切换到应用内模式
            switchToInAppMode()
            // 返回视频位置
            videoPlayerAdapter?.getPlayer(videoId)?.seekTo(position)
            videoPlayerAdapter?.getPlayer(videoId)
        } else {
            videoPlayerAdapter?.getPlayer(videoId)
        }
        
        // 获取播放位置（优先从播放器获取，其次从保存的位置获取）
        val currentPosition = player?.currentPosition ?: videoItem.currentPosition
        
        // 暂停所有视频
        pauseAllVideos()
        
        val intent = Intent(this, FullscreenPlayerActivity::class.java).apply {
            putExtra("video_uri", videoItem.uri.toString())
            putExtra("video_title", videoItem.title)
            putExtra("video_position", currentPosition)
            putExtra("video_id", videoId)
        }
        fullscreenLauncher.launch(intent)
    }
    
    private fun pauseAllVideos() {
        if (isFloatingMode) {
            floatingWindowManager.getAllWindows().forEach { window ->
                window.playerWrapper.pause()
            }
        } else {
            videoItems.forEach { item ->
                videoPlayerAdapter?.getPlayer(item.id)?.pause()
            }
        }
    }
    
    private fun resumeAllVideos() {
        // 可以选择恢复播放或保持暂停状态
        // 这里保持暂停状态，让用户手动播放
    }
    
    private fun updateEmptyState() {
        emptyStateLayout.visibility = if (videoItems.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 如果有视频正在播放，将应用移到后台而不是关闭所有视频
        if (videoItems.isNotEmpty()) {
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isReceiverRegistered = false
        }
        if (::floatingWindowManager.isInitialized) {
            floatingWindowManager.removeAllWindows()
        }
        videoPlayerAdapter?.releaseAll()
    }
}