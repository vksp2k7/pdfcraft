package ai.storyteller.pdfcraft

import android.app.Activity
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
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
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
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "https" && url.host == ASSET_HOST && url.path?.startsWith(ASSET_PATH) == true) {
                        return false
                    }
                    if (url.scheme == "http" || url.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(DOWNLOAD_BRIDGE_SCRIPT, null)
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
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

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
