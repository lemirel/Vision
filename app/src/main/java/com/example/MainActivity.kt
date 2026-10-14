package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.VideoRepository
import com.example.ml.MediaPipeObjectDetectorHelper
import com.example.ui.camera.CameraPreviewScreen
import com.example.ui.gallery.GalleryAnalysisScreen
import com.example.ui.recordings.RecordingsScreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RecCrimson
import com.example.ui.theme.SurfaceCardDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import com.example.video.TrackingVideoRecorder

enum class AppTab(val title: String) {
    CAMERA("Live Camera"),
    GALLERY("Gallery"),
    RECORDINGS("Recordings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(AppTab.CAMERA) }

    // BackHandler for secondary tabs
    BackHandler(enabled = currentTab != AppTab.CAMERA) {
        currentTab = AppTab.CAMERA
    }

    // Permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    val videoRecorder = remember { TrackingVideoRecorder(context) }
    val videoRepository = remember { VideoRepository(context) }
    val staticMediaPipeHelper = remember {
        MediaPipeObjectDetectorHelper(
            context = context,
            threshold = 0.35f,
            maxResults = 10,
            useGpu = false
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SurfaceDark,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark.copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = currentTab == AppTab.CAMERA,
                    onClick = { currentTab = AppTab.CAMERA },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppTab.CAMERA) Icons.Filled.CenterFocusStrong else Icons.Outlined.CenterFocusStrong,
                            contentDescription = "Live Camera & Tracking",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Live Track",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == AppTab.CAMERA) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = TargetAmber,
                        selectedTextColor = TargetAmber,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = TargetAmber.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("nav_camera")
                )

                NavigationBarItem(
                    selected = currentTab == AppTab.GALLERY,
                    onClick = { currentTab = AppTab.GALLERY },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppTab.GALLERY) Icons.Filled.Collections else Icons.Outlined.Collections,
                            contentDescription = "Gallery Detection",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Gallery",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == AppTab.GALLERY) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberCyan,
                        selectedTextColor = CyberCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = CyberCyan.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("nav_gallery")
                )

                NavigationBarItem(
                    selected = currentTab == AppTab.RECORDINGS,
                    onClick = { currentTab = AppTab.RECORDINGS },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == AppTab.RECORDINGS) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary,
                            contentDescription = "Saved Recordings",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Videos",
                            fontSize = 11.sp,
                            fontWeight = if (currentTab == AppTab.RECORDINGS) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RecCrimson,
                        selectedTextColor = RecCrimson,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = RecCrimson.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("nav_recordings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (currentTab) {
                AppTab.CAMERA -> {
                    if (hasCameraPermission) {
                        CameraPreviewScreen(
                            videoRecorder = videoRecorder,
                            onVideoSaved = {
                                currentTab = AppTab.RECORDINGS
                            }
                        )
                    } else {
                        PermissionRationaleScreen(
                            onRequestPermission = {
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                                )
                            }
                        )
                    }
                }
                AppTab.GALLERY -> {
                    GalleryAnalysisScreen(mediaPipeHelper = staticMediaPipeHelper)
                }
                AppTab.RECORDINGS -> {
                    RecordingsScreen(
                        videoRepository = videoRepository,
                        onNavigateToCamera = { currentTab = AppTab.CAMERA }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRationaleScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = TargetAmber,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "VisionTrack needs camera access to detect and track objects in real time using local MediaPipe hardware acceleration, and record video of prominent objects.",
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = TargetAmber),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.testTag("grant_permission_button")
            ) {
                Text(
                    text = "Grant Camera Permission",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
