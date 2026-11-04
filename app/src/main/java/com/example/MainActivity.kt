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
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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

enum class AppTab {
    CAMERA,
    GALLERY,
    RECORDINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var currentTab by remember { mutableStateOf(AppTab.CAMERA) }

    val videoRecorder = remember { TrackingVideoRecorder(context) }
    val videoRepository = remember { VideoRepository(context) }
    val staticDetectorHelper = remember {
        MediaPipeObjectDetectorHelper(
            context = context,
            threshold = 0.40f,
            maxResults = 8,
            useGpu = false
        )
    }

    BackHandler(enabled = currentTab != AppTab.CAMERA) {
        currentTab = AppTab.CAMERA
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = currentTab == AppTab.CAMERA,
                    onClick = { currentTab = AppTab.CAMERA },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Camera"
                        )
                    },
                    label = { Text("Live Track") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = CyberCyan,
                        indicatorColor = CyberCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_tab_camera")
                )

                NavigationBarItem(
                    selected = currentTab == AppTab.GALLERY,
                    onClick = { currentTab = AppTab.GALLERY },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Gallery"
                        )
                    },
                    label = { Text("Gallery") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = TargetAmber,
                        indicatorColor = TargetAmber,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_tab_gallery")
                )

                NavigationBarItem(
                    selected = currentTab == AppTab.RECORDINGS,
                    onClick = { currentTab = AppTab.RECORDINGS },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = "Recordings"
                        )
                    },
                    label = { Text("Recordings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = RecCrimson,
                        indicatorColor = RecCrimson,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    ),
                    modifier = Modifier.testTag("nav_tab_recordings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            Crossfade(targetState = currentTab, label = "TabCrossfade") { tab ->
                when (tab) {
                    AppTab.CAMERA -> {
                        if (hasCameraPermission) {
                            CameraPreviewScreen(
                                videoRecorder = videoRecorder,
                                onVideoSaved = {
                                    currentTab = AppTab.RECORDINGS
                                }
                            )
                        } else {
                            CameraPermissionRequired(
                                onRequestPermission = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            )
                        }
                    }
                    AppTab.GALLERY -> {
                        GalleryAnalysisScreen(mediaPipeHelper = staticDetectorHelper)
                    }
                    AppTab.RECORDINGS -> {
                        RecordingsScreen(
                            videoRepository = videoRepository,
                            onNavigateToCamera = {
                                currentTab = AppTab.CAMERA
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPermissionRequired(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCardDark, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera Permission Needed",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "VisionTrack requires camera access to perform real-time MediaPipe object detection and video recording.",
                color = Color.Gray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Grant Camera Permission", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
