package fr.simioni.meteowidget

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
                BleScanService.ACTION_LOG -> {
                    val msg = intent.getStringExtra(BleScanService.EXTRA_LOG_MSG) ?: return
                    appendLog(msg)
                }
                BleScanService.ACTION_RESULT -> {
                    val temp = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
                    if (!temp.isNaN()) {
                        statusText.text = "Indoor: %.1f°C".format(temp)
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            startMonitoring()
        } else {
            val denied = results.filterValues { !it }.keys.joinToString()
            appendLog("PERMISSIONS REFUSÉES: $denied")
            statusText.text = "Permissions refusées"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createChannels(this)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            appendLog("--- Scan manuel ---")
            startForegroundService(Intent(this, BleScanService::class.java))
        }

        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("meteo-widget logs", logBuffer.toString()))
            Toast.makeText(this, "Logs copiés dans le presse-papier", Toast.LENGTH_SHORT).show()
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

        appendLog("App démarrée — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("Permissions BLE: ${checkBlePermissions()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scanReceiver)
    }

    private fun appendLog(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        logBuffer.append(line)
        runOnUiThread {
            logText.append(line)
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun checkBlePermissions(): String {
        val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
        val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
        return "SCAN=${if (scan == PackageManager.PERMISSION_GRANTED) "OK" else "MANQUANT"} " +
                "CONNECT=${if (connect == PackageManager.PERMISSION_GRANTED) "OK" else "MANQUANT"}"
    }

    private fun checkPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitoring()
        else {
            appendLog("Demande permissions: ${missing.map { it.substringAfterLast('.') }}")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMonitoring() {
        WorkScheduler.schedule(this)
        statusText.text = "Surveillance active (~15 min)"
        appendLog("WorkManager planifié — scan immédiat lancé")
        startForegroundService(Intent(this, BleScanService::class.java))
    }
}
