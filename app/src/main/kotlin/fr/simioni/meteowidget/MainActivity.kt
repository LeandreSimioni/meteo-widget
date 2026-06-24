package fr.simioni.meteowidget

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val temp = intent.getFloatExtra(BleScanService.EXTRA_TEMPERATURE, Float.NaN)
            if (!temp.isNaN()) {
                statusText.text = "Dernière mesure indoor : %.1f°C\nSurveillance active".format(temp)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            startMonitoring()
        } else {
            statusText.text = "Permissions refusées — impossible de démarrer"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createChannels(this)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, IntentFilter(BleScanService.ACTION_RESULT), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(bleReceiver, IntentFilter(BleScanService.ACTION_RESULT))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bleReceiver)
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
        if (missing.isEmpty()) startMonitoring() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMonitoring() {
        WorkScheduler.schedule(this)
        statusText.text = "Surveillance active\n(vérification toutes les ~15 min)"
        startForegroundService(Intent(this, BleScanService::class.java))
    }
}
