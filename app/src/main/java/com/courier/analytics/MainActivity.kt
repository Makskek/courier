package com.courier.analytics

import android.Manifest
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Оболочка-WebView для Courier Analytics Pro.
 * Страница лежит в assets/www/index.html и открывается по https://appassets.androidplatform.net/,
 * поэтому localStorage, IndexedDB и fetch (Яндекс.Диск, погода) работают как в обычном браузере.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: ByteArray? = null
    private var injectOnPageStarted = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val bytes = pendingSave
            pendingSave = null
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    toast("Файл сохранён")
                } catch (e: Exception) {
                    toast("Не удалось сохранить файл")
                }
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resolveNotifPermission(if (granted) "granted" else "denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val bg = ContextCompat.getColor(this, R.color.app_bg)
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        webView = WebView(this).apply { setBackgroundColor(bg) }
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl(START_URL)
    }

    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            textZoom = 100
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, POLYFILL_JS, setOf(ORIGIN))
        } else {
            injectOnPageStarted = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = request?.let { assetLoader.shouldInterceptRequest(it.url) }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.host == HOST) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (injectOnPageStarted) view?.evaluateJavascript(POLYFILL_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // alert / confirm / prompt: без этого WebView молча возвращает false и ничего не показывает
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Отмена") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    setSingleLine()
                }
                val holder = FrameLayout(this@MainActivity).apply {
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setView(holder)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Отмена") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            // <input type="file"> (импорт JSON, свой фон)
            override fun onShowFileChooser(
                wv: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(params!!.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }

            // window.open: ссылки уходят в браузер, окно отчёта открывается в диалоге
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                val hit = view?.hitTestResult
                val extra = hit?.extra
                if (hit != null && extra != null &&
                    (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                ) {
                    openExternal(Uri.parse(extra))
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = createPopup()
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /** Окно отчёта (window.open) в полноэкранном диалоге с кнопками «Печать / PDF» и «Закрыть». */
    private fun createPopup(): WebView {
        val popup = WebView(this)
        popup.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    openExternal(uri)
                    return true
                }
                return false
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                dialog.dismiss()
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#18181B"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val printBtn = Button(this).apply {
            text = "Печать / PDF"
            setOnClickListener {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(
                    "Courier Analytics — отчёт",
                    popup.createPrintDocumentAdapter("Отчёт"),
                    PrintAttributes.Builder().build()
                )
            }
        }
        val closeBtn = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }
        bar.addView(printBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(closeBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(popup, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        dialog.setContentView(container)
        dialog.setOnDismissListener {
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
        }
        dialog.show()
        return popup
    }

    /** Мост JS → Android. Вызывается из фоновых потоков WebView, UI-операции — через runOnUiThread. */
    inner class Bridge {

        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return
            }
            val mimeType = mime.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            runOnUiThread {
                pendingSave = bytes
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mimeType
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try {
                    saveLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    pendingSave = null
                    toast("Нет приложения для сохранения файлов")
                }
            }
        }

        @JavascriptInterface
        fun postNotification(title: String, body: String) {
            runOnUiThread { showNotification(title, body) }
        }

        @JavascriptInterface
        fun notifPermission(): String = if (hasNotifPermission()) "granted" else "default"

        @JavascriptInterface
        fun requestNotifPermission() {
            runOnUiThread {
                if (hasNotifPermission()) {
                    resolveNotifPermission("granted")
                } else if (Build.VERSION.SDK_INT >= 33) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    resolveNotifPermission("denied")
                }
            }
        }
    }

    private fun hasNotifPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }

    private fun resolveNotifPermission(value: String) {
        webView.evaluateJavascript(
            "window.__cpNotifResolve && window.__cpNotifResolve('$value')", null
        )
    }

    private fun showNotification(title: String, body: String) {
        if (!hasNotifPermission()) {
            toast(body)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this)
                .notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            toast(body)
        }
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme ?: return
        if (scheme !in listOf("http", "https", "mailto", "tel")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast("Не удалось открыть ссылку")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val ORIGIN = "https://$HOST"
        private const val START_URL = "$ORIGIN/assets/www/index.html"
        private const val CHANNEL_ID = "reminders"

        /**
         * 1) Notification API (в WebView его нет) → нативные уведомления Android.
         * 2) Скачивание blob-файлов (CSV и т.п.) → системный диалог «Сохранить как».
         */
        private val POLYFILL_JS = """
            (function () {
              if (window.__cpPatched) return;
              window.__cpPatched = true;
              function bridge() { return window.AndroidBridge; }

              var N = function (title, opts) {
                opts = opts || {};
                var b = bridge();
                if (b) { try { b.postNotification(String(title), String(opts.body || '')); } catch (e) {} }
              };
              Object.defineProperty(N, 'permission', {
                get: function () { var b = bridge(); return b ? b.notifPermission() : 'default'; }
              });
              N.requestPermission = function (cb) {
                return new Promise(function (resolve) {
                  window.__cpNotifResolve = function (v) { resolve(v); if (typeof cb === 'function') cb(v); };
                  var b = bridge();
                  if (b) { b.requestNotifPermission(); } else { window.__cpNotifResolve('denied'); }
                });
              };
              try {
                Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true });
              } catch (e) { window.Notification = N; }

              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                var a = this;
                var href = a.href || '';
                if (a.hasAttribute('download') && href.indexOf('blob:') === 0 && bridge()) {
                  fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
                    var fr = new FileReader();
                    fr.onload = function () {
                      var s = String(fr.result);
                      bridge().saveFile(a.download || 'file', blob.type || 'application/octet-stream',
                                        s.substring(s.indexOf(',') + 1));
                    };
                    fr.readAsDataURL(blob);
                  });
                  return;
                }
                return origClick.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
