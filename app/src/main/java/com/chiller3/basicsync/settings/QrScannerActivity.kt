/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.LifecycleCameraController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.R
import com.chiller3.basicsync.databinding.QrScannerActivityBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity(), QrScannerAnalyzer.Listener {
    companion object {
        const val EXTRA_DATA = "data"
    }

    private lateinit var binding: QrScannerActivityBinding
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                startActivity(Permissions.getAppInfoIntent(this))
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = QrScannerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                topMargin = insets.top
                rightMargin = insets.right
            }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.preview) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                        or WindowInsetsCompat.Type.ime()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                bottomMargin = insets.bottom
            }

            WindowInsetsCompat.CONSUMED
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        setTitle(R.string.web_ui_scan_qr_code)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (Permissions.have(this, arrayOf(Manifest.permission.CAMERA))) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        cameraExecutor.shutdown()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startCamera() {
        val cameraController = LifecycleCameraController(baseContext)
        val previewView = binding.preview

        cameraController.setImageAnalysisAnalyzer(mainExecutor, QrScannerAnalyzer(this))

        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }

    override fun onQrScanned(data: String) {
        setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_DATA, data) })
        finish()
    }
}
