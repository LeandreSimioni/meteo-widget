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

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleScanService.ACTION_LOG ->
                    appendLogToUi(intent.getStringExtra(BleScanService.EXTRA_LOG_MSG) ?: return)
                BleScanService.ACTION_RESULT -> {
                    val temp = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
                    if (!temp.isNaN()) showTemps()
                }
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            appendLog("Permissions accordées — démarrage du pipeline")
            WorkScheduler.schedule(this)
            WorkScheduler.runNow(this)
        } else {
            val permanent = denied.any { !shouldShowRequestPermissionRationale(it) }
            if (permanent) {
                setStatus("Permissions refusées — ouvrir Paramètres et les accorder", "#B71C1C")
            } else {
                appendLog("Permissions refusées: ${denied.map { it.substringAfterLast('.') }}")
                setStatus("Permissions BLE requises", "#F57F17")
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasPermissions()) {
            appendLog("Permissions OK après paramètres")
            WorkScheduler.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NotificationHelper.createChannels(this)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

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

        // "Forcer un cycle maintenant" — remplace toute tâche en attente
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (hasPermissions()) {
                appendLog("--- Cycle forcé ---")
                WorkScheduler.runNow(this)
            } else {
                requestPermsOrSettings()
            }
        }
        // Scan BLE seul (diagnostic)
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            if (hasPermissions()) {
                appendLog("--- Scan BLE manuel ---")
                startForegroundService(Intent(this, BleScanService::class.java))
            } else {
                requestPermsOrSettings()
            }
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("logs", logBuffer.toString()))
            Toast.makeText(this, "Copié !", Toast.LENGTH_SHORT).show()
        }

        // Démarrage automatique — pas besoin que l'utilisateur appuie sur quoi que ce soit
        if (hasPermissions()) {
            WorkScheduler.schedule(this)
        } else {
            setStatus("Autorisation BLE requise", "#F57F17")
            permLauncher.launch(requiredPerms().toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        // Toujours recharger l'historique complet depuis le store persistant
        val stored = LogStore.getLogs(this)
        logBuffer.clear()
        stored.forEach { logBuffer.append("$it\n") }
        logText.text = logBuffer.toString()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        showTemps()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scanReceiver)
    }

    private fun showTemps() {
        val prefs = Prefs.get(this)
        val indoor = prefs.getFloat(Prefs.KEY_INDOOR, Float.NaN)
        val outdoor = prefs.getFloat(Prefs.KEY_OUTDOOR, Float.NaN)
        val state = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE)
        val indoorStr = if (indoor.isNaN()) "--" else "%.1f°C".format(indoor)
        val outdoorStr = if (outdoor.isNaN()) "--" else "%.1f°C".format(outdoor)
        val advice = when (state) {
            Prefs.STATE_OPEN  -> " · ↑ Ouvrir"
            Prefs.STATE_CLOSE -> " · ↓ Fermer"
            else -> ""
        }
        setStatus("$indoorStr dedans · $outdoorStr dehors$advice", "#1565C0")
    }

    private fun requestPermsOrSettings() {
        val anyPermanent = requiredPerms()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .any { !shouldShowRequestPermissionRationale(it) }
        if (anyPermanent) {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            )
        } else {
            permLauncher.launch(requiredPerms().toTypedArray())
        }
    }

    private fun setStatus(msg: String, colorHex: String) {
        runOnUiThread {
            statusText.text = msg
            statusText.setBackgroundColor(android.graphics.Color.parseColor(colorHex))
        }
    }

    private fun appendLog(msg: String) {
        LogStore.append(this, msg)
        appendLogToUi(msg)
    }

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
}
