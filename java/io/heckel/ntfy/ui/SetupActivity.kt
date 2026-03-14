package io.heckel.ntfy.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.randomSubscriptionId
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.DeleteWorker
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

class SetupActivity : AppCompatActivity() {

    private val repository by lazy { (application as Application).repository }
    private val messenger = FirebaseMessenger()
    private var dispatcher: NotificationDispatcher? = null
    private var workManager: WorkManager? = null

    private lateinit var siteUrlLayout: TextInputLayout
    private lateinit var siteUrlInput: TextInputEditText
    private lateinit var ntfyServerLayout: TextInputLayout
    private lateinit var ntfyServerInput: TextInputEditText
    private lateinit var ntfyTopicLayout: TextInputLayout
    private lateinit var ntfyTopicInput: TextInputEditText
    private lateinit var continueButton: Button
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)

        dispatcher = NotificationDispatcher(this, repository)
        workManager = WorkManager.getInstance(this)

        // Always init channels and schedule workers on every launch
        dispatcher?.init()
        scheduleWorkers()
        maybeRequestNotificationPermission()

        // Check if already configured — start service and go straight to WebView
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedSiteUrl = prefs.getString(PREF_SITE_URL, null)
        val savedServer  = prefs.getString(PREF_NTFY_SERVER, null)
        val savedTopic   = prefs.getString(PREF_NTFY_TOPIC, null)

        if (savedSiteUrl != null && savedServer != null && savedTopic != null) {
            // Ensure service is running every time app opens
            SubscriberServiceManager.refresh(this)
            openWebView()
            return
        }

        setContentView(R.layout.activity_setup)

        siteUrlLayout    = findViewById(R.id.setup_site_url_layout)
        siteUrlInput     = findViewById(R.id.setup_site_url_input)
        ntfyServerLayout = findViewById(R.id.setup_ntfy_server_layout)
        ntfyServerInput  = findViewById(R.id.setup_ntfy_server_input)
        ntfyTopicLayout  = findViewById(R.id.setup_ntfy_topic_layout)
        ntfyTopicInput   = findViewById(R.id.setup_ntfy_topic_input)
        continueButton   = findViewById(R.id.setup_continue_button)
        errorText        = findViewById(R.id.setup_error_text)

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateInputs() }
        }
        siteUrlInput.addTextChangedListener(textWatcher)
        ntfyServerInput.addTextChangedListener(textWatcher)
        ntfyTopicInput.addTextChangedListener(textWatcher)
        continueButton.setOnClickListener { onContinueClick() }
    }

    private fun validateInputs() {
        val siteUrl = siteUrlInput.text.toString().trim()
        val server  = ntfyServerInput.text.toString().trim()
        val topic   = ntfyTopicInput.text.toString().trim()
        continueButton.isEnabled = siteUrl.isNotEmpty() && server.isNotEmpty() && topic.isNotEmpty()
    }

    private fun onContinueClick() {
        val siteUrl = siteUrlInput.text.toString().trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        val server = ntfyServerInput.text.toString().trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        val topic = ntfyTopicInput.text.toString().trim()

        continueButton.isEnabled = false
        errorText.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Add ntfy subscription if not exists
                val existing = repository.getSubscription(server, topic)
                if (existing == null) {
                    val subscription = Subscription(
                        id = randomSubscriptionId(),
                        baseUrl = server,
                        topic = topic,
                        instant = true,
                        dedicatedChannels = false,
                        mutedUntil = 0,
                        minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
                        autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                        insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                        lastNotificationId = null,
                        icon = null,
                        upAppId = null,
                        upConnectorToken = null,
                        displayName = null,
                        totalCount = 0,
                        newCount = 0,
                        lastActive = Date().time / 1000
                    )
                    repository.addSubscription(subscription)
                    Log.d(TAG, "Added subscription for ${topicShortUrl(server, topic)}")
                }

                // Subscribe Firebase topic for ntfy.sh
                val appBaseUrl = getString(R.string.app_base_url)
                if (server == appBaseUrl) {
                    messenger.subscribe(topic)
                }

                // Save settings
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_SITE_URL, siteUrl)
                    .putString(PREF_NTFY_SERVER, server)
                    .putString(PREF_NTFY_TOPIC, topic)
                    .apply()

                // Start subscriber service
                SubscriberServiceManager.refresh(this@SetupActivity)

                runOnUiThread { openWebView() }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up subscription: ${e.message}", e)
                runOnUiThread {
                    errorText.text = getString(R.string.setup_error_text, e.message ?: "Unknown error")
                    errorText.visibility = View.VISIBLE
                    continueButton.isEnabled = true
                }
            }
        }
    }

    private fun openWebView() {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // Exact copy of MainActivity's worker scheduling with version-based REPLACE policy
    private fun scheduleWorkers() {
        val wm = workManager ?: return

        val pollWorkerVersion = repository.getPollWorkerVersion()
        val pollWorkPolicy = if (pollWorkerVersion == PollWorker.VERSION) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            repository.setPollWorkerVersion(PollWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val pollWork = PeriodicWorkRequestBuilder<PollWorker>(POLL_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(PollWorker.TAG)
            .addTag(PollWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        wm.enqueueUniquePeriodicWork(PollWorker.WORK_NAME_PERIODIC_ALL, pollWorkPolicy, pollWork)

        val deleteWorkerVersion = repository.getDeleteWorkerVersion()
        val deleteWorkPolicy = if (deleteWorkerVersion == DeleteWorker.VERSION) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            repository.setDeleteWorkerVersion(DeleteWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val deleteWork = PeriodicWorkRequestBuilder<DeleteWorker>(DELETE_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(DeleteWorker.TAG)
            .addTag(DeleteWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        wm.enqueueUniquePeriodicWork(DeleteWorker.WORK_NAME_PERIODIC_ALL, deleteWorkPolicy, deleteWork)

        val restartWorkerVersion = repository.getAutoRestartWorkerVersion()
        val restartWorkPolicy = if (restartWorkerVersion == SubscriberService.SERVICE_START_WORKER_VERSION) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            repository.setAutoRestartWorkerVersion(SubscriberService.SERVICE_START_WORKER_VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val restartWork = PeriodicWorkRequestBuilder<SubscriberServiceManager.ServiceStartWorker>(SERVICE_START_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(SubscriberService.TAG)
            .addTag(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC)
            .build()
        wm.enqueueUniquePeriodicWork(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC, restartWorkPolicy, restartWork)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    companion object {
        const val TAG = "NtfySetupActivity"
        const val PREFS_NAME = "SetupPreferences"
        const val PREF_SITE_URL = "CustomSiteUrl"
        const val PREF_NTFY_SERVER = "CustomNtfyServer"
        const val PREF_NTFY_TOPIC = "CustomNtfyTopic"
        const val POLL_WORKER_INTERVAL_MINUTES = 60L
        const val DELETE_WORKER_INTERVAL_MINUTES = 8 * 60L
        const val SERVICE_START_WORKER_INTERVAL_MINUTES = 3 * 60L
    }
}
