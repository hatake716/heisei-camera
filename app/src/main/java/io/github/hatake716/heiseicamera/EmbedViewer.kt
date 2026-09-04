package io.github.hatake716.heiseicamera

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** PAGE_LOADED confirms the wrapper page loaded, never that Google's panorama is available. */
enum class EmbedPageState { LOADING, PAGE_LOADED, FAILED }

@Composable
fun EmbedViewer(
    request: EmbedRequest,
    frozen: Boolean,
    onPageState: (EmbedPageState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Changing the interaction lock keeps the same iframe and its view.
    key(request) { EmbedViewerContent(request, frozen, onPageState, modifier) }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun EmbedViewerContent(
    request: EmbedRequest,
    frozen: Boolean,
    onPageState: (EmbedPageState) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentCallback = rememberUpdatedState(onPageState)
    val currentFrozen = rememberUpdatedState(frozen)
    val document = remember(request) { EmbedDocument.html(request) }
    val holder = remember(context, request) {
        ViewerHolder(context).apply {
            val owner = this
            webView.apply {
                setBackgroundColor(0xff101815.toInt())
                settings.apply {
                    javaScriptEnabled = true // Required by the official interactive iframe.
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                setOnTouchListener { _, _ -> currentFrozen.value }
                setOnKeyListener { _, _, _ -> currentFrozen.value }
                setOnGenericMotionListener { _, _ -> currentFrozen.value }
                // Long-press image/link context menus must not become a download/capture path.
                setOnLongClickListener { true }
                setDownloadListener { _, _, _, _, _ -> }
                webViewClient = object : WebViewClient() {
                    private var pageFailed = false

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (!owner.disposed && url == EmbedDocument.BASE_URL) {
                            pageFailed = false
                            currentCallback.value(EmbedPageState.LOADING)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!owner.disposed && !pageFailed && url == EmbedDocument.BASE_URL) {
                            currentCallback.value(EmbedPageState.PAGE_LOADED)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) fail()
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) fail()
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        fail()
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        fail()
                        owner.dispose()
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (owner.disposed) return true
                        if (!request.isForMainFrame) return request.url.scheme != "https"
                        if (request.url.toString() == EmbedDocument.BASE_URL) return false
                        if (request.hasGesture() && !currentFrozen.value) openExternal(context, request.url)
                        return true
                    }

                    private fun fail() {
                        pageFailed = true
                        if (!owner.disposed) currentCallback.value(EmbedPageState.FAILED)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean {
                        if (!isUserGesture || currentFrozen.value || owner.disposed) return false
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        // Resolve user-tapped target=_blank links without rendering a second web page.
                        val popup = WebView(context).apply {
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        owner.popups += popup
                        popup.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!owner.disposed && !currentFrozen.value) openExternal(context, request.url)
                                owner.closePopup(popup)
                                return true
                            }
                        }
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }
            }
        }
    }

    DisposableEffect(holder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (!holder.disposed) when (event) {
                Lifecycle.Event.ON_RESUME -> holder.webView.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.webView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.webView.onPause()
        onDispose {
            lifecycle.removeObserver(observer)
            holder.dispose()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            holder.webView.apply {
                currentCallback.value(EmbedPageState.LOADING)
                loadDataWithBaseURL(EmbedDocument.BASE_URL, document, "text/html", "UTF-8", EmbedDocument.BASE_URL)
            }
        },
        update = { view ->
            if (!holder.disposed) {
                view.importantForAccessibility = if (frozen) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                view.isFocusable = !frozen
                view.isFocusableInTouchMode = !frozen
                if (frozen) view.clearFocus()
            }
        },
    )
}

private class ViewerHolder(context: Context) {
    val webView = WebView(context)
    val popups = mutableSetOf<WebView>()
    var disposed = false
        private set

    fun closePopup(popup: WebView) {
        if (popups.remove(popup)) {
            popup.stopLoading()
            popup.destroy()
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        popups.toList().forEach(::closePopup)
        webView.stopLoading()
        webView.onPause()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }
}

private fun openExternal(context: Context, uri: Uri) {
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port !in listOf(-1, 443)) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
}
