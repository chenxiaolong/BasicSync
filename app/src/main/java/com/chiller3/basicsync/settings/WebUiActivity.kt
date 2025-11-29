/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chiller3.basicsync.R
import com.chiller3.basicsync.databinding.WebUiActivityBinding
import com.chiller3.basicsync.dialog.FolderPickerDialogFragment
import com.chiller3.basicsync.syncthing.SyncthingService
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import androidx.core.net.toUri

class WebUiActivity : AppCompatActivity() {
    companion object {
        private val TAG = WebUiActivity::class.java.simpleName

        private fun loadDer(data: ByteArray): X509Certificate {
            val cf = CertificateFactory.getInstance("X.509")

            ByteArrayInputStream(data).use { stream ->
                return cf.generateCertificate(stream) as X509Certificate
            }
        }

        private fun jsEscape(s: String) = buildString {
            // Our strings are small enough where we can just go the ultra-safe route and use
            // Unicode escaping for every code point.
            for (c in s.chars()) {
                append("\\u{")
                append(c.toHexString())
                append('}')
            }
        }
    }

    private val viewModel: WebUiViewModel by viewModels()

    private lateinit var binding: WebUiActivityBinding

    private lateinit var guiUri: Uri
    private lateinit var guiCert: X509Certificate

    private val webViewGoBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.webview.goBack()
        }
    }

    private val webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            try {
                val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    error.certificate.x509Certificate
                } else {
                    val bundle = SslCertificate.saveState(error.certificate)
                    loadDer(bundle.getByteArray("x509-certificate")!!)
                }
                val guiCert = guiCert

                if (cert != null) {
                    cert.verify(guiCert.publicKey)
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    handler.proceed()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to validate GUI certificate", e)
            }

            super.onReceivedSslError(view, handler, error)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val guiUri = guiUri

            if (uri.scheme == guiUri.scheme && uri.host == guiUri.host && uri.port == guiUri.port) {
                return false
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val script = resources.openRawResource(R.raw.webview_bridge).reader().use {
                it.readText()
            }

            view.evaluateJavascript(script) {}
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            webViewGoBack.isEnabled = view.canGoBack()
        }
    }

    private val requestQrScanner =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                onDeviceIdScanned(it.data?.getStringExtra(QrScannerActivity.EXTRA_DATA)!!)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = WebUiActivityBinding.inflate(layoutInflater)
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.webview) { v, windowInsets ->
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

        binding.webview.webViewClient = webViewClient
        onBackPressedDispatcher.addCallback(webViewGoBack)

        if (savedInstanceState != null) {
            binding.webview.restoreState(savedInstanceState)
        }

        binding.webview.settings.apply {
            // All required assets are served locally from the daemon.
            allowContentAccess = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE

            // The web UI does not work at all without Javascript.
            @SuppressLint("SetJavaScriptEnabled")
            javaScriptEnabled = true

            // The web UI uses localStorage for a few things.
            domStorageEnabled = true

            // The NARROW_COLUMNS default is deprecated since API 29.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        }

        binding.webview.addJavascriptInterface(WebViewInterface(), "BasicSync")

        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        setTitle(R.string.app_name_upstream)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.runState.collect {
                    when (it) {
                        SyncthingService.RunState.NOT_RUNNING,
                        SyncthingService.RunState.STOPPING -> finish()
                        else -> {}
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.guiInfo.collect {
                    if (it != null && binding.webview.url == null) {
                        guiUri = it.address.toUri()
                        guiCert = loadDer(it.cert)

                        // Use basic auth because Android's WebView has no sane way to pass in
                        // "X-Api-Key" nor "Authorization: Bearer" for each request. Basic auth is
                        // the only method that'll persist throughout the session, so stbridge
                        // forcibly sets the password to the API key.
                        val authorization = Base64.encodeToString(
                            "${it.user}:${it.apiKey}".encodeToByteArray(),
                            Base64.NO_WRAP,
                        )
                        val headers = mapOf("Authorization" to "Basic $authorization")

                        binding.webview.loadUrl(it.address, headers)
                    }
                }
            }
        }

        supportFragmentManager.setFragmentResultListener(FolderPickerDialogFragment.TAG, this) { _, bundle ->
            if (bundle.getBoolean(FolderPickerDialogFragment.RESULT_SUCCESS)) {
                val path = bundle.getString(FolderPickerDialogFragment.RESULT_PATH)!!

                onFolderSelected(path)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        (binding.webview.parent as ViewGroup).removeView(binding.webview)
        binding.webview.destroy()
    }

    override fun onResume() {
        super.onResume()

        binding.webview.onResume()
        binding.webview.resumeTimers()
    }

    override fun onPause() {
        super.onPause()

        binding.webview.onPause()
        binding.webview.pauseTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        binding.webview.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        binding.webview.invalidate()
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

    private fun onFolderSelected(path: String) {
        binding.webview.evaluateJavascript("onFolderSelected(\"${jsEscape(path)}\");") {}
    }

    private fun onDeviceIdScanned(deviceId: String) {
        binding.webview.evaluateJavascript("onDeviceIdScanned(\"${jsEscape(deviceId)}\");") {}
    }

    @Suppress("unused")
    private inner class WebViewInterface {
        @JavascriptInterface
        fun getTranslation(id: String) = when (id) {
            "select_folder" -> getString(R.string.web_ui_select_folder)
            "scan_qr_code" -> getString(R.string.web_ui_scan_qr_code)
            else -> throw IllegalArgumentException("Unknown string ID: $id")
        }

        @JavascriptInterface
        fun openFolderPicker(path: String) {
            runOnUiThread {
                FolderPickerDialogFragment.newInstance(path.ifEmpty { null })
                    .show(supportFragmentManager.beginTransaction(),
                        FolderPickerDialogFragment.TAG)
            }
        }

        @JavascriptInterface
        fun scanQrCode() {
            runOnUiThread {
                requestQrScanner.launch(Intent(this@WebUiActivity, QrScannerActivity::class.java))
            }
        }
    }
}
