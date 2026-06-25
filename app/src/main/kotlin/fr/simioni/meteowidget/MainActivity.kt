package fr.simioni.meteowidget

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private val logBuffer = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var pendingMonitor = false
    private var isFirstResume = true

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleScanService.ACTION_LOG -> {
                    val msg = intent.getStringExtra(BleScanService.EXTRA_LOG_MSG) ?: return
                    // LogStore already persisted the line — just update the UI
                    appendLogToUi(msg)
                }
                BleScanService.ACTION_RESULT -> {
                    val temp = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
                    if (!temp.isNaN()) setStatus("Indoor: %.1f°C".format(temp), "#2E7D32")
                }
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            appendLog("Permissions accordées OK")
            if (pendingMonitor) startMonitoring() else launchScan()
        } else {
            appendLog("REFUSÉES: ${denied.map { it.substringAfterLast('.') }}")
            // Si refus définitif, proposer d'ouvrir les paramètres
            val permanent = denied.any { !shouldShowRequestPermissionRationale(it) }
            if (permanent) {
                setStatus("Permissions refusées — appuyer sur Démarrer pour ouvrir les Paramètres", "#B71C1C")
            } else {
                setStatus("Permissions nécessaires — appuyer sur Démarrer", "#F57F17")
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appendLog("Retour des paramètres — permissions: ${permissionStatus()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NotificationHelper.createChannels(this)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            pendingMonitor = true
            checkAndGo()
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            pendingMonitor = false
            appendLog("--- Scan manuel ---")
            checkAndGo()
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("logs", logBuffer.toString()))
            Toast.makeText(this, "Copié !", Toast.LENGTH_SHORT).show()
        }

        val filter = IntentFilter().apply {
            addAction(BleScanService.ACTION_LOG)
            addAction(BleScanService.ACTION_RESULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scanReceiver, filter)
        }

        appendLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        if (hasPermissions()) {
            appendLog("Permissions OK")
        } else {
            appendLog("Permissions manquantes — demande en cours...")
            setStatus("Autorisation BLE requise", "#F57F17")
            pendingMonitor = false
            permLauncher.launch(requiredPerms().toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            val stored = LogStore.getLogs(this)
            if (stored.isNotEmpty()) {
                // Prepend persisted background logs before current-session messages
                val sessionLines = logBuffer.toString()
                logBuffer.clear()
                stored.forEach { logBuffer.append("$it\n") }
                logBuffer.append(sessionLines)
                logText.text = logBuffer.toString()
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scanReceiver)
    }

    private fun checkAndGo() {
        if (hasPermissions()) {
            if (pendingMonitor) startMonitoring() else launchScan()
            return
        }
        val anyPermanent = requiredPerms()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .any { !shouldShowRequestPermissionRationale(it) }

        if (anyPermanent) {
            appendLog("Permissions refusées définitivement → ouverture Paramètres")
            settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            )
        } else {
            permLauncher.launch(requiredPerms().toTypedArray())
        }
    }

    private fun launchScan() {
        setStatus("Scan BLE en cours...", "#1565C0")
        startForegroundService(Intent(this, BleScanService::class.java))
    }

    private fun startMonitoring() {
        WorkScheduler.schedule(this)
        setStatus("Pipeline lancé (BLE + Météo + comparaison)...", "#1565C0")
        appendLog("WorkManager planifié — 1ère exécution immédiate")
    }

    private fun setStatus(msg: String, colorHex: String) {
        runOnUiThread {
            statusText.text = msg
            statusText.setBackgroundColor(android.graphics.Color.parseColor(colorHex))
        }
    }

    private fun appendLog(msg: String) = appendLogToUi(msg)

    private fun appendLogToUi(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        logBuffer.append(line)
        runOnUiThread {
            logText.append(line)
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun requiredPerms() = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermissions() = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionStatus(): String {
        fun s(p: String) = if (ContextCompat.checkSelfPermission(this, p) ==
            PackageManager.PERMISSION_GRANTED) "OK" else "MANQUANT"
        return "SCAN=${s(Manifest.permission.BLUETOOTH_SCAN)} CONNECT=${s(Manifest.permission.BLUETOOTH_CONNECT)}"
    }
}
