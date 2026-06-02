/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.R
import com.chiller3.basicsync.ui.AppScreen
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(
    onScan: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current

    var reloadPerms by remember { mutableIntStateOf(0) }
    val cameraGranted = remember(reloadPerms) {
        Permissions.have(context, arrayOf(Manifest.permission.CAMERA))
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            reloadPerms++
        } else {
            context.startActivity(Permissions.getAppInfoIntent(context))
            onBack()
        }
    }

    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!cameraGranted && !requestedOnce) {
            requestedOnce = true
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val latestOnScan by rememberUpdatedState(onScan)

    if (cameraGranted) {
        // Reload when the window size changes or else the preview sometimes becomes stretched when
        // adjusting the size in split screen mode.
        LaunchedEffect(windowInfo.containerSize) {
            val cameraProvider = ProcessCameraProvider.awaitInstance(context.applicationContext)

            val preview = Preview.Builder()
                .build()
                .apply { surfaceProvider = Preview.SurfaceProvider { surfaceRequest = it } }

            val imageAnalysisExecutor = Executors.newSingleThreadExecutor()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(imageAnalysisExecutor, QrScannerAnalyzer(latestOnScan)) }

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )

            try {
                awaitCancellation()
            } finally {
                cameraProvider.unbindAll()
                imageAnalysisExecutor.shutdown()
            }
        }
    }

    AppScreen(
        title = { Text(text = stringResource(R.string.web_ui_scan_qr_code)) },
        onBack = onBack,
        fullScreenContent = true,
    ) {
        surfaceRequest?.let { surfaceRequest ->
            CameraXViewfinder(
                surfaceRequest = surfaceRequest,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
