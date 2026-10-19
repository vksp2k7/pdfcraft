package ai.storyteller.pdfcraft

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingIncomingPdf: Uri? = null
    private val pendingDownloads = mutableMapOf<String, PendingDownload>()

    private data class PendingDownload(val uri: Uri, val name: String, val stream: OutputStream)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(241, 241, 243)
        window.navigationBarColor = Color.rgb(241, 241, 243)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        pendingIncomingPdf = incomingPdf(intent)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.rgb(241, 241, 243))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            addJavascriptInterface(AndroidPdfCraftBridge(), "AndroidPdfCraft")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val url = request.url
                    val isBundledAppAsset = url.scheme == "https" && url.host == ASSET_HOST && url.path?.startsWith(ASSET_PATH) == true
                    if (isBundledAppAsset) {
                        // addJavascriptInterface is visible to every frame; do not let untrusted
                        // remote frames/scripts share the clipboard and file-export bridge.
                        return assetLoader.shouldInterceptRequest(url) ?: blockedWebResource()
                    }
                    return blockedWebResource()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "https" && url.host == ASSET_HOST && url.path?.startsWith(ASSET_PATH) == true) {
                        return false
                    }
                    if (!request.isForMainFrame) return true
                    if (url.scheme == "http" || url.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(DOWNLOAD_BRIDGE_SCRIPT, null)
                    view.evaluateJavascript(INPUT_BRIDGE_SCRIPT, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = callback
                    val incoming = pendingIncomingPdf
                    if (incoming != null) {
                        pendingIncomingPdf = null
                        callback.onReceiveValue(arrayOf(incoming))
                        fileChooserCallback = null
                        return true
                    }
                    return try {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            // Some DocumentsProviders report PDFs with a generic or vendor MIME
                            // type. Do not pass WebView's accept filter to SAF; it can gray out
                            // otherwise valid PDF files. PdfCraft validates the selected file.
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        }
                        startActivityForResult(intent, REQUEST_FILE_PICKER)
                        true
                    } catch (_: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        false
                    }
                }
            }
        }
        // Run before eframe initializes its hidden IME text agent. The later page-finished
        // evaluation remains an idempotent fallback for WebViews without this feature.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    INPUT_BRIDGE_SCRIPT,
                    setOf("https://$ASSET_HOST"),
                )
            }
        }
        setContentView(webView)
        webView.loadUrl(APP_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIncomingPdf = incomingPdf(intent)
        if (pendingIncomingPdf != null) showToast("PDF received. Tap Open in PdfCraft to import it.")
    }

    @Suppress("DEPRECATION")
    private fun incomingPdf(source: Intent?): Uri? = when (source?.action) {
        Intent.ACTION_VIEW -> source.data
        Intent.ACTION_SEND -> source.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    @Deprecated("The WebView file chooser API uses the activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_PICKER) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        val results = if (resultCode == Activity.RESULT_OK && data != null) {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data) ?: data.data?.let { arrayOf(it) }
        } else {
            null
        }
        callback.onReceiveValue(results)
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        synchronized(pendingDownloads) {
            pendingDownloads.values.forEach { pending ->
                runCatching { pending.stream.close() }
                runCatching { contentResolver.delete(pending.uri, null, null) }
            }
            pendingDownloads.clear()
        }
        webView.destroy()
        super.onDestroy()
    }

    private inner class AndroidPdfCraftBridge {
        @JavascriptInterface
        fun copyText(text: String): Boolean = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PdfCraft", text))
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun getClipboardText(): String = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        }.getOrDefault("")

        @JavascriptInterface
        fun showKeyboard() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                webView.requestFocusFromTouch()
                fun requestWhenFocused(attempt: Int) {
                    if (isFinishing || isDestroyed) return
                    if (!webView.hasWindowFocus()) {
                        if (attempt < 10) webView.postDelayed({ requestWhenFocused(attempt + 1) }, 50)
                        return
                    }
                    // JavascriptInterface methods run off the UI thread. Posting gives WebView
                    // time to install the focused HTML input's InputConnection before the IME call.
                    WindowCompat.getInsetsController(window, webView).show(WindowInsetsCompat.Type.ime())
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                }
                webView.post { requestWhenFocused(0) }
            }
        }

        @JavascriptInterface
        fun beginFile(name: String): String {
            return synchronized(pendingDownloads) {
                try {
                    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                        .filter { it.code >= 32 && it !in ":*?\"<>|" }
                        .take(180)
                        .ifBlank { "PdfCraft-export.pdf" }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(safeName))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PdfCraft")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@synchronized ""
                    val stream = contentResolver.openOutputStream(uri, "w")
                    if (stream == null) {
                        contentResolver.delete(uri, null, null)
                        return@synchronized ""
                    }
                    val id = UUID.randomUUID().toString()
                    pendingDownloads[id] = PendingDownload(uri, safeName, stream)
                    id
                } catch (_: Exception) {
                    ""
                }
            }
        }

        @JavascriptInterface
        fun writeChunk(id: String, encoded: String): Boolean {
            return synchronized(pendingDownloads) {
                val pending = pendingDownloads[id] ?: return@synchronized false
                try {
                    pending.stream.write(Base64.decode(encoded, Base64.DEFAULT))
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        @JavascriptInterface
        fun finishFile(id: String): Boolean {
            return synchronized(pendingDownloads) {
                val pending = pendingDownloads.remove(id) ?: return@synchronized false
                try {
                    pending.stream.flush()
                    pending.stream.close()
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    val updated = contentResolver.update(pending.uri, values, null, null) > 0
                    if (updated) showToast("Saved to Downloads/PdfCraft: ${pending.name}")
                    else contentResolver.delete(pending.uri, null, null)
                    updated
                } catch (_: Exception) {
                    runCatching { pending.stream.close() }
                    runCatching { contentResolver.delete(pending.uri, null, null) }
                    false
                }
            }
        }

        @JavascriptInterface
        fun abortFile(id: String) {
            synchronized(pendingDownloads) {
                pendingDownloads.remove(id)?.let { pending ->
                    runCatching { pending.stream.close() }
                    runCatching { contentResolver.delete(pending.uri, null, null) }
                }
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            showToast("Couldn't save export: $message")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun blockedWebResource() = android.webkit.WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked by PdfCraft",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "svg" -> "image/svg+xml"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    companion object {
        private const val REQUEST_FILE_PICKER = 7402
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val ASSET_PATH = "/assets/pdfcraft-web/"
        private const val APP_URL = "https://appassets.androidplatform.net/assets/pdfcraft-web/index.html"
        private const val INPUT_BRIDGE_SCRIPT = """
            (() => {
              if (window.__pdfcraftAndroidInputBridge || !window.AndroidPdfCraft) return;
              window.__pdfcraftAndroidInputBridge = true;
              const bridge = window.AndroidPdfCraft;

              // Route eframe's Clipboard API to Android's clipboard service rather than relying
              // on WebView's activation-gated implementation.
              const androidClipboard = {
                writeText: text => {
                  try {
                    return bridge.copyText(String(text))
                      ? Promise.resolve()
                      : Promise.reject(new Error('Android clipboard write failed'));
                  } catch (error) {
                    return Promise.reject(error);
                  }
                },
                readText: () => Promise.resolve(String(bridge.getClipboardText() || ''))
              };
              try {
                Object.defineProperty(navigator, 'clipboard', {
                  configurable: true,
                  value: androidClipboard
                });
              } catch (navigatorError) {
                try {
                  Object.defineProperties(navigator.clipboard, {
                    writeText: { configurable: true, value: androidClipboard.writeText },
                    readText: { configurable: true, value: androidClipboard.readText }
                  });
                } catch (clipboardError) {
                  console.warn('PdfCraft Android clipboard bridge unavailable', clipboardError);
                }
              }

              const isTextAgent = target => target instanceof HTMLInputElement && target.type === 'text';
              const findTextAgent = () => {
                if (isTextAgent(document.activeElement)) return document.activeElement;
                return Array.from(document.querySelectorAll('input[type="text"]'))
                  .find(input => input.style.position === 'absolute' && input.style.width === '1px' && input.style.height === '1px') || null;
              };
              let activeTextAgent = null;
              let controls = null;
              let keyboardButton = null;
              let pasteButton = null;

              const dispatchPaste = text => {
                if (!text) return;
                const data = new DataTransfer();
                data.setData('text/plain', text);
                document.dispatchEvent(new ClipboardEvent('paste', {
                  clipboardData: data, bubbles: true, cancelable: true
                }));
              };

              const ensureControls = () => {
                if (!document.body) {
                  document.addEventListener('DOMContentLoaded', ensureControls, { once: true });
                  return;
                }
                if (!controls) {
                  controls = document.createElement('div');
                  controls.style.cssText = 'position:fixed;z-index:2147483647;right:12px;bottom:12px;display:flex;flex-direction:column;align-items:flex-end;gap:8px';
                  keyboardButton = document.createElement('button');
                  keyboardButton.type = 'button';
                  keyboardButton.textContent = 'Keyboard';
                  keyboardButton.setAttribute('aria-label', 'Show on-screen keyboard');
                  keyboardButton.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:11px 16px;border:0;border-radius:999px;background:#2563eb;color:white;font:600 14px system-ui,sans-serif;box-shadow:0 2px 8px #0004;touch-action:manipulation;user-select:none;-webkit-user-select:none';
                  keyboardButton.addEventListener('pointerdown', event => {
                    event.preventDefault();
                    event.stopPropagation();
                    const agent = findTextAgent();
                    if (agent) {
                      activeTextAgent = agent;
                      if (document.activeElement !== agent) agent.focus({ preventScroll: true });
                    }
                    // The explicit tap is the user gesture; don't depend on eframe firing a new
                    // DOM focus event when its single hidden text agent was focused at startup.
                    bridge.showKeyboard();
                  }, { passive: false });
                  controls.appendChild(keyboardButton);

                  pasteButton = document.createElement('button');
                  pasteButton.type = 'button';
                  pasteButton.textContent = 'Paste';
                  pasteButton.setAttribute('aria-label', 'Paste from Android clipboard');
                  pasteButton.style.cssText = 'display:none;align-items:center;justify-content:center;padding:10px 16px;border:0;border-radius:999px;background:#374151;color:white;font:600 14px system-ui,sans-serif;box-shadow:0 2px 8px #0004;touch-action:manipulation;user-select:none;-webkit-user-select:none';
                  pasteButton.addEventListener('pointerdown', event => {
                    // Keep egui's hidden input focused: it is an IME composition buffer, not a DOM
                    // text field containing the full egui value.
                    event.preventDefault();
                    event.stopPropagation();
                    const agent = activeTextAgent || findTextAgent();
                    if (agent) {
                      activeTextAgent = agent;
                      if (document.activeElement !== agent) agent.focus({ preventScroll: true });
                    }
                    const text = bridge.getClipboardText();
                    if (agent && text) {
                      dispatchPaste(String(text));
                      bridge.showKeyboard();
                    }
                  }, { passive: false });
                  controls.appendChild(pasteButton);
                  document.body.appendChild(controls);
                }
                pasteButton.style.display = activeTextAgent ? 'inline-flex' : 'none';
              };

              // If the fallback bridge is installed after eframe's startup focus event, recover
              // the already-focused hidden input so Paste remains available too.
              if (isTextAgent(document.activeElement)) activeTextAgent = document.activeElement;
              ensureControls();
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', () => {
                  if (isTextAgent(document.activeElement)) activeTextAgent = document.activeElement;
                  ensureControls();
                }, { once: true });
              }
              document.addEventListener('focusin', event => {
                if (!isTextAgent(event.target)) return;
                activeTextAgent = event.target;
                ensureControls();
              }, true);
              document.addEventListener('focusout', event => {
                if (!isTextAgent(event.target)) return;
                setTimeout(() => {
                  if (document.activeElement === event.target) return;
                  if (activeTextAgent === event.target) activeTextAgent = null;
                  ensureControls();
                }, 0);
              }, true);

              // Native Android paste menus do not always expose clipboardData in WebView.
              document.addEventListener('paste', event => {
                if (!event.isTrusted || event.clipboardData?.getData('text')) return;
                const text = bridge.getClipboardText();
                if (!text) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                dispatchPaste(text);
              }, true);
              // Hardware keyboards commonly deliver Ctrl/Meta+V without a DOM paste event.
              document.addEventListener('keydown', event => {
                if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
                  const text = bridge.getClipboardText();
                  if (text) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    dispatchPaste(text);
                  }
                }
              }, true);
            })();
        """
        private const val DOWNLOAD_BRIDGE_SCRIPT = """
            (() => {
              if (window.__pdfcraftAndroidDownloadBridge) return;
              window.__pdfcraftAndroidDownloadBridge = true;
              const originalClick = HTMLAnchorElement.prototype.click;
              const originalRevoke = URL.revokeObjectURL.bind(URL);
              const pendingUrls = new Set();
              URL.revokeObjectURL = function(url) {
                if (!pendingUrls.has(url)) originalRevoke(url);
              };
              HTMLAnchorElement.prototype.click = function() {
                const anchor = this;
                if (anchor.hasAttribute('download') && anchor.href.startsWith('blob:') && window.AndroidPdfCraft) {
                  const filename = anchor.download || 'PdfCraft-export.pdf';
                  const blobUrl = anchor.href;
                  pendingUrls.add(blobUrl);
                  let id = '';
                  fetch(blobUrl).then(response => response.arrayBuffer()).then(buffer => {
                    id = AndroidPdfCraft.beginFile(filename);
                    if (!id) throw new Error('Could not create the Downloads file');
                    const bytes = new Uint8Array(buffer);
                    for (let offset = 0; offset < bytes.length; offset += 16384) {
                      const slice = bytes.subarray(offset, Math.min(offset + 16384, bytes.length));
                      let binary = '';
                      for (let i = 0; i < slice.length; i++) binary += String.fromCharCode(slice[i]);
                      if (!AndroidPdfCraft.writeChunk(id, btoa(binary))) throw new Error('Could not write the Downloads file');
                    }
                    if (!AndroidPdfCraft.finishFile(id)) throw new Error('Could not finish the Downloads file');
                    id = '';
                  }).catch(error => {
                    if (id) AndroidPdfCraft.abortFile(id);
                    AndroidPdfCraft.reportError(String(error));
                  }).finally(() => {
                    if (pendingUrls.delete(blobUrl)) originalRevoke(blobUrl);
                  });
                  return;
                }
                return originalClick.call(anchor);
              };
            })();
        """
    }
}
