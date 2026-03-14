package io.heckel.ntfy.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.content.ActivityNotFoundException
import android.provider.Settings
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log

class WebViewActivity : AppCompatActivity() {

    private val repository by lazy { (application as Application).repository }
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var siteUrl: String = ""
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_view)

        val prefs = getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE)
        siteUrl = prefs.getString(SetupActivity.PREF_SITE_URL, "") ?: ""

        Log.d(TAG, "Opening site: $siteUrl")

        val toolbar = findViewById<Toolbar>(R.id.web_view_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = siteUrl

        progressBar = findViewById(R.id.web_view_progress)
        webView = findViewById(R.id.web_view)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) ProgressBar.VISIBLE else ProgressBar.GONE
                progressBar.progress = newProgress
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                supportActionBar?.title = title ?: siteUrl
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        // KEY: observe instant subscriptions — triggers SubscriberService refresh whenever list changes
        // This is the same pattern as MainActivity and ensures the service stays alive
        viewModel.listIdsWithInstantStatus().observe(this) {
            Log.d(TAG, "Instant subscription list changed, refreshing subscriber service")
            SubscriberServiceManager.refresh(this)
        }

        // Request battery optimization exemption — critical for background notifications
        maybeRequestBatteryOptimizationExemption()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(siteUrl)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            fileChooserCallback?.onReceiveValue(
                if (resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            )
            fileChooserCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_web_view, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.web_view_menu_reload -> { webView.reload(); true }
            R.id.web_view_menu_settings -> {
                getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, SetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
            }
        }
    }

    companion object {
        const val TAG = "NtfyWebViewActivity"
        const val FILE_CHOOSER_REQUEST = 1001
    }
}
