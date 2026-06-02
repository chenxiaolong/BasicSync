/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.chiller3.basicsync.R
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.ui.AppScreen
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private const val TAG = "WebUiScreen"

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

@Composable
fun WebUiScreen(
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    // We intentionally do not use WebView.saveState() and WebView.restoreState(). It only supports
    // saving the back/forward history, which is pointless for the Syncthing web UI because it is an
    // SPA that is always at the same URL.
    val webView = remember { WebView(context) }
    LifecycleResumeEffect(Unit) {
        webView.onResume()
        webView.resumeTimers()

        onPauseOrDispose {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(
        enabled = canGoBack,
        onBack = { webView.goBack() },
    )

    var guiUri by remember { mutableStateOf<Uri?>(null) }
    var guiCert by remember { mutableStateOf<X509Certificate?>(null) }
    rememberServiceEventWatcher(
        listener = object : SyncthingService.ServiceListener {
            override fun onExitRequested() = onExit()

            override fun onRunStateChanged(
                state: SyncthingService.ServiceState,
                guiInfo: SyncthingService.GuiInfo?,
            ) {
                if (!state.runState.webUiAvailable) {
                    onExit()
                }

                if (guiInfo != null && webView.url == null) {
                    guiUri = guiInfo.address.toUri()
                    guiCert = loadDer(guiInfo.cert)

                    // Use basic auth because Android's WebView has no sane way to pass in
                    // "X-Api-Key" nor "Authorization: Bearer" for each request. Basic auth is the
                    // only method that'll persist throughout the session, so stbridge forcibly sets
                    // the password to the API key.
                    val authorization = Base64.encodeToString(
                        "${guiInfo.user}:${guiInfo.apiKey}".encodeToByteArray(),
                        Base64.NO_WRAP,
                    )
                    val headers = mapOf("Authorization" to "Basic $authorization")

                    webView.loadUrl(guiInfo.address, headers)
                }
            }

            override fun onPreRunActionResult(
                preRunAction: SyncthingService.PreRunAction,
                exception: Exception?,
            ) {}

            override fun onConflictsUpdated(conflicts: List<String>) {}
        },
    )

    fun onFolderSelected(path: String) {
        webView.evaluateJavascript("onFolderSelected(\"${jsEscape(path)}\");") {}
    }

    fun onDeviceIdScanned(deviceId: String) {
        webView.evaluateJavascript("onDeviceIdScanned(\"${jsEscape(deviceId)}\");") {}
    }

    val requestQrScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            onDeviceIdScanned(it.data?.getStringExtra(QrScannerActivity.EXTRA_DATA)!!)
        }
    }

    // This intentionally does not use rememberSaveable because the WebView would reload anyway if
    // the Activity was recreated.
    var showFolderPickerDialog by remember { mutableStateOf<FolderPickerLocation?>(null) }
    showFolderPickerDialog?.let { location ->
        FolderPickerDialog(
            initialLocation = location,
            onSelect = { shortPath ->
                onFolderSelected(shortPath)
                @Suppress("AssignedValueIsNeverRead")
                showFolderPickerDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showFolderPickerDialog = null
            },
        )
    }

    @Suppress("unused")
    val webViewInterface = remember {
        object {
            @JavascriptInterface
            fun getTranslation(id: String) = when (id) {
                "select_folder" -> resources.getString(R.string.web_ui_select_folder)
                "scan_qr_code" -> resources.getString(R.string.web_ui_scan_qr_code)
                else -> throw IllegalArgumentException("Unknown string ID: $id")
            }

            @JavascriptInterface
            fun openFolderPicker(path: String) {
                @Suppress("AssignedValueIsNeverRead")
                showFolderPickerDialog = if (path.isNotEmpty()) {
                    FolderPickerLocation.Path(path)
                } else {
                    FolderPickerLocation.Default
                }
            }

            @JavascriptInterface
            fun scanQrCode() {
                requestQrScanner.launch(Intent(context, QrScannerActivity::class.java))
            }
        }
    }

    var showBrowserAlert by remember { mutableStateOf(false) }

    val webViewClient = remember {
        object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val guiCert = guiCert!!

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
                val guiUri = guiUri!!

                if (uri.scheme == guiUri.scheme && uri.host == guiUri.host && uri.port == guiUri.port) {
                    return false
                } else {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        showBrowserAlert = true
                    }
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
                @Suppress("AssignedValueIsNeverRead")
                canGoBack = view.canGoBack()
            }
        }
    }

    AppScreen(
        title = { Text(text = stringResource(R.string.app_name_upstream)) },
        onBack = onBack,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
    ) { params ->
        AndroidView(
            factory = {
                webView.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    settings.apply {
                        // All required assets are served locally from the daemon.
                        allowContentAccess = false
                        allowFileAccess = false
                        cacheMode = WebSettings.LOAD_NO_CACHE

                        // The web UI does not work at all without JavaScript.
                        @SuppressLint("SetJavaScriptEnabled")
                        javaScriptEnabled = true

                        // The web UI uses localStorage for a few things.
                        domStorageEnabled = true

                        // The NARROW_COLUMNS default is deprecated since API 29.
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    }

                    // Android Studio's lint is broken when using remember { ... }.
                    @SuppressLint("JavascriptInterface")
                    addJavascriptInterface(webViewInterface, "BasicSync")

                    this.webViewClient = webViewClient
                }
            },
            onRelease = {
                it.destroy()
            },
            modifier = Modifier.fillMaxSize().padding(params.contentPadding),
        )

        if (showBrowserAlert) {
            val message = stringResource(R.string.alert_browser_not_found)

            LaunchedEffect(Unit) {
                params.snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                )
                showBrowserAlert = false
            }
        }
    }
}
