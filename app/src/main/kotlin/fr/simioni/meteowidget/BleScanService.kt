package fr.simioni.meteowidget

import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class BleScanService : Service() {
    companion object {
        const val TAG = "BleScanService"
        const val MANUFACTURER_ID = 0x0702
        const val SCAN_DURATION_MS = 5_000L
        const val ACTION_RESULT = "fr.simioni.meteowidget.BLE_RESULT"
        const val EXTRA_TEMPERATURE = "temperature"
    }

    private val bluetoothLeScanner by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter.bluetoothLeScanner
    }

    private var resultSent = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (resultSent) return
            val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return

            Log.d(TAG, "Raw BLE (${data.size} bytes): ${AranetDecoder.toHex(data)}")

            val reading = AranetDecoder.decode(data) ?: run {
                Log.w(TAG, "Incomplete frame (${data.size} bytes) — Smart Home enabled?")
                return
            }

            Log.d(TAG, "Aranet: ${reading.temperatureC}°C | CO2=${reading.co2Ppm}ppm | " +
                    "Hum=${reading.humidity}% | Bat=${reading.battery}% | Age=${reading.ageSec}s")

            NotificationHelper.showDebug(
                this@BleScanService,
                "Indoor: %.1f°C | CO2: %dppm | Age: %ds | Bat: %d%%".format(
                    reading.temperatureC, reading.co2Ppm, reading.ageSec, reading.battery
                )
            )

            resultSent = true
            sendBroadcast(Intent(ACTION_RESULT).putExtra(EXTRA_TEMPERATURE, reading.temperatureC))
            stopSelf()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.NOTIF_SCAN_ID, NotificationHelper.buildScanNotification(this))
        startBleScan()
        return START_NOT_STICKY
    }

    private fun startBleScan() {
        val filter = ScanFilter.Builder()
            .setManufacturerData(MANUFACTURER_ID, null)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
            Handler(Looper.getMainLooper()).postDelayed({
                bluetoothLeScanner.stopScan(scanCallback)
                stopSelf()
            }, SCAN_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { bluetoothLeScanner.stopScan(scanCallback) } catch (_: Exception) {}
    }
}
