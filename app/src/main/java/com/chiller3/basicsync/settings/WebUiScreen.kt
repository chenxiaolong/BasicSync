/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.chiller3.basicsync.BuildConfig
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.R
import com.chiller3.basicsync.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.ui.AppScreen
import com.chiller3.basicsync.ui.PreferenceDefaults
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
fun WebUiScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val edgeToEdge = remember {
        !isTv && run {
            val majorVersion = WebView.getCurrentWebViewPackage()
                ?.versionName
                ?.substringBefore('.')
                ?.toIntOrNull()
            Log.d(TAG, "WebView major version: $majorVersion")

            // https://developer.android.com/develop/ui/views/layout/webapps/understand-window-insets#feature-compatibility
            if (majorVersion != null) majorVersion >= 144 else true
        }
    }

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

    var guiUri by remember { mutableStateOf<Uri?>(null) }
    var guiCert by remember { mutableStateOf<X509Certificate?>(null) }
    rememberServiceEventWatcher(
        listener = object : SyncthingService.ServiceListener {
            override fun onMissingStoragePermissions(internal: Boolean, external: List<Uri>) {}

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

    fun onFolderSelected(type: String, path: String) {
        webView.evaluateJavascript("onFolderSelected(\"${jsEscape(type)}\", \"${jsEscape(path)}\");") {}
    }

    fun onDeviceIdScanned(deviceId: String) {
        webView.evaluateJavascript("onDeviceIdScanned(\"${jsEscape(deviceId)}\");") {}
    }

    fun bridgeInit(isTv: Boolean, edgeToEdge: Boolean) {
        webView.evaluateJavascript("bridgeInit($isTv, $edgeToEdge);") {}
    }

    fun closeAllDropdowns() {
        webView.evaluateJavascript("closeAllDropdowns();") {}
    }

    fun closeTopModal() {
        webView.evaluateJavascript("closeTopModal();") {}
    }

    var dropdownsOpen by remember { mutableIntStateOf(0) }
    var modalsOpen by remember { mutableIntStateOf(0) }
    var hasBrowserHistory by remember { mutableStateOf(false) }
    BackHandler(
        enabled = dropdownsOpen > 0 || modalsOpen > 0 || hasBrowserHistory,
        onBack = {
            if (dropdownsOpen > 0) {
                closeAllDropdowns()
            } else if (modalsOpen > 0) {
                closeTopModal()
            } else if (hasBrowserHistory) {
                webView.goBack()
            }
        },
    )

    val requestQrScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            onDeviceIdScanned(it.data?.getStringExtra(QrScannerActivity.EXTRA_DATA)!!)
        }
    }

    // The dialogs intentionally do not use rememberSaveable because the WebView would reload anyway
    // if the Activity was recreated.
    var showStorageTypeDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf<FolderPickerLocation?>(null) }
    var existingFolderPath by remember { mutableStateOf<String?>(null) }

    fun showFolderPicker(alwaysClearExisting: Boolean): Boolean {
        val havePermissions = Permissions.haveLocalStorage(context)

        if (havePermissions) {
            showFolderPickerDialog = existingFolderPath
                ?.let(FolderPickerLocation::Path)
                ?: FolderPickerLocation.Default
        }

        if (havePermissions || alwaysClearExisting) {
            existingFolderPath = null
        }

        return havePermissions
    }

    val requestPermissionActivity = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showFolderPicker(true)
    }

    val requestPermissionsRequired = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.all { it.value }) {
            showFolderPicker(true)
        } else {
            requestPermissionActivity.launch(Permissions.getAppInfoIntent(context))
        }
    }

    val requestSafExternalStorage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            SyncthingService.persistExternalStoragePermissions(context, it)

            onFolderSelected("saf", SyncthingService.encodeSafUri(it))
        }

        existingFolderPath = null
    }

    if (showStorageTypeDialog) {
        StorageChoiceDialog(
            onSelect = { type ->
                when (type) {
                    StorageChoice.INTERNAL -> {
                        if (showFolderPicker(false)) {
                            // Already have permissions.
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                "package:${BuildConfig.APPLICATION_ID}".toUri(),
                            )

                            requestPermissionActivity.launch(intent)
                        } else {
                            requestPermissionsRequired.launch(Permissions.LEGACY_STORAGE)
                        }
                    }
                    StorageChoice.EXTERNAL -> {
                        requestSafExternalStorage.launch(existingFolderPath?.toUri())
                        existingFolderPath = null
                    }
                }

                showStorageTypeDialog = false
            },
            onDismiss = {
                showStorageTypeDialog = false
                existingFolderPath = null
            },
        )
    }

    showFolderPickerDialog?.let { location ->
        FolderPickerDialog(
            initialLocation = location,
            onSelect = { shortPath ->
                onFolderSelected("basic", shortPath)
                showFolderPickerDialog = null
            },
            onDismiss = {
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
            fun openFolderPicker(filesystemType: String, path: String) {
                showStorageTypeDialog = true

                if (path.isNotEmpty()) {
                    when (filesystemType) {
                        "basic" -> existingFolderPath = path
                        "saf" -> {
                            // DocumentsUI does not support tree URIs for EXTRA_INITIAL_URI.
                            val treeUri = SyncthingService.decodeSafUri(path).first
                            existingFolderPath = DocumentsContract.buildDocumentUri(
                                treeUri.authority,
                                DocumentsContract.getTreeDocumentId(treeUri),
                            ).toString()
                        }
                        else -> Log.w(TAG, "Ignoring unrecognized filesystem type: $filesystemType")
                    }
                }
            }

            @JavascriptInterface
            fun scanQrCode() {
                requestQrScanner.launch(Intent(context, QrScannerActivity::class.java))
            }

            @JavascriptInterface
            fun onDropdownsOpenChanged(count: Int) {
                dropdownsOpen = count
            }

            @JavascriptInterface
            fun onModalsOpenChanged(count: Int) {
                modalsOpen = count
            }
        }
    }

    var showAlert by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }

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
                        showAlert = R.string.alert_browser_not_found
                    }
                    return true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                val script = resources.openRawResource(R.raw.webview_bridge).reader().use {
                    it.readText()
                }

                view.evaluateJavascript(script) {}

                Log.d(TAG, "Initializing bridge: isTv=$isTv, edgeToEdge=$edgeToEdge")
                bridgeInit(isTv, edgeToEdge)

                loading = false
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                val uri = Uri.parse(url)
                val guiUri = guiUri!!

                if (uri.scheme == guiUri.scheme
                    && uri.host == guiUri.host
                    && uri.port == guiUri.port
                    && uri.path == "/rest/debug/support") {
                    // We don't support downloading. DownloadListener is a terrible API that doesn't
                    // support reading the response to the current request. Using that to feed the
                    // download URL to download manager would cause two support bundles to be
                    // created. Instead, we'll just open the directory containing the support
                    // bundles. Since AOSP's FileSystemProvider uses inotify to notify clients to
                    // refresh, it doesn't matter that we open the file manager before the HTTP
                    // request completes.
                    try {
                        val externalDir = Environment.getExternalStorageDirectory()
                        val filesDir = context.getExternalFilesDir(null)!!
                        val relPath = filesDir.relativeTo(externalDir)
                        val uri = DocumentsContract.buildDocumentUri(
                            DOCUMENTSUI_AUTHORITY, "primary:$relPath")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "vnd.android.document/directory")
                        }

                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        showAlert = R.string.alert_documentsui_not_found
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                hasBrowserHistory = view.canGoBack()
            }
        }
    }

    AppScreen(fullScreenContent = true) { params ->
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
            // We don't use AnimatedVisibility nor AnimatedContent because the WebView needs to load
            // immediately and stay loaded. Instead, the loading spinner will just be a full screen
            // overlay.
            modifier = if (edgeToEdge) {
                Modifier
            } else {
                Modifier.padding(paddingValues = params.contentPadding)
            },
        )

        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(PreferenceDefaults.containerColor),
            ) {
                CircularWavyProgressIndicator()
            }
        }

        if (showAlert != 0) {
            val message = stringResource(showAlert)

            LaunchedEffect(Unit) {
                params.snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                )
                showAlert = 0
            }
        }
    }
}
