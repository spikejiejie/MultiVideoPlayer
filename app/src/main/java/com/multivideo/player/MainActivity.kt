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
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addVideoFromUri(uri)
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
            val videoId = result.data?.getStringExtra("video_id") ?: return@registerForActivityResult
            // 恢复视频位置
            if (isFloatingMode) {
                floatingWindowManager.getWindow(videoId)?.playerWrapper?.seekTo(position)
            } else {
                videoPlayerAdapter?.getPlayer(videoId)?.seekTo(position)
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
            videoPickerLauncher.launch(arrayOf("video/*"))
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
        
        // 如果有悬浮窗，关闭它们
        if (::floatingWindowManager.isInitialized) {
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
        
        // 释放应用内播放器
        videoPlayerAdapter?.releaseAll()
        
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
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                checkOverlayPermission()
            }
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED -> {
                checkOverlayPermission()
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
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
    
    private fun addVideoFromUri(uri: Uri) {
        val videoItem = VideoItem(
            id = UUID.randomUUID().toString(),
            uri = uri,
            title = getVideoTitle(uri)
        )
        
        videoItems.add(videoItem)
        
        if (isFloatingMode) {
            createFloatingWindow(videoItem)
        } else {
            videoPlayerAdapter?.notifyItemInserted(videoItems.size - 1)
        }
        
        updateEmptyState()
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
            // 切换到应用内模式
            switchToInAppMode()
            // 返回视频位置
            videoPlayerAdapter?.getPlayer(videoId)?.seekTo(position)
            videoPlayerAdapter?.getPlayer(videoId)
        } else {
            videoPlayerAdapter?.getPlayer(videoId)
        }
        
        // 暂停所有视频
        pauseAllVideos()
        
        val intent = Intent(this, FullscreenPlayerActivity::class.java).apply {
            putExtra("video_uri", videoItem.uri.toString())
            putExtra("video_title", videoItem.title)
            putExtra("video_position", player?.currentPosition ?: 0)
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
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(orientationReceiver)
        if (::floatingWindowManager.isInitialized) {
            floatingWindowManager.removeAllWindows()
        }
        videoPlayerAdapter?.releaseAll()
    }
}