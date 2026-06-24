package fr.simioni.meteowidget

import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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
        const val SCAN_DURATION_MS = 8_000L
        const val ACTION_RESULT = "fr.simioni.meteowidget.BLE_RESULT"
        const val ACTION_LOG = "fr.simioni.meteowidget.BLE_LOG"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_LOG_MSG = "log_msg"
    }

    private val seenAddresses = mutableSetOf<String>()
    private var resultSent = false
    private var scanStarted = false

    private val adapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        sendBroadcast(Intent(ACTION_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MSG, msg)
        })
    }

    private fun sendResult(temp: Float? = null) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            setPackage(packageName)
            if (temp != null) putExtra(EXTRA_TEMPERATURE, temp)
        })
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (!seenAddresses.add(address)) return

            val name = result.device.name ?: result.scanRecord?.deviceName ?: "(sans nom)"
            val rssi = result.rssi
            val mfData = result.scanRecord?.manufacturerSpecificData
            val mfStr = if (mfData != null && mfData.size() > 0)
                (0 until mfData.size()).joinToString { "0x%04X".format(mfData.keyAt(it)) }
            else "(aucun)"

            log("[$rssi dBm] $name | MfID: $mfStr")

            if (resultSent) return
            val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return

            log("→ ARANET détecté! ${data.size} bytes: ${AranetDecoder.toHex(data)}")

            val reading = AranetDecoder.decode(data) ?: run {
                log("→ Trame trop courte (${data.size} < 21) — Smart Home activé dans Aranet?")
                return
            }

            log("→ Temp=%.1f°C CO2=%dppm Hum=%d%% Bat=%d%% Age=%ds".format(
                reading.temperatureC, reading.co2Ppm, reading.humidity, reading.battery, reading.ageSec))

            resultSent = true
            NotificationHelper.showDebug(this@BleScanService,
                "Indoor: %.1f°C | CO2: %dppm | Age: %ds".format(
                    reading.temperatureC, reading.co2Ppm, reading.ageSec))
            sendResult(reading.temperatureC)
            stopSelf()
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "REGISTRATION_FAILED"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                else -> "code=$errorCode"
            }
            log("ERREUR scan: $reason")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.NOTIF_SCAN_ID, NotificationHelper.buildScanNotification(this))
        if (!scanStarted) {
            scanStarted = true
            startBleScan()
        }
        return START_NOT_STICKY
    }

    private fun startBleScan() {
        if (!adapter.isEnabled) {
            log("Bluetooth désactivé — scan ignoré")
            sendResult()
            stopSelf()
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            adapter.bluetoothLeScanner.startScan(null, settings, scanCallback)
            log("Scan démarré (${SCAN_DURATION_MS / 1000}s)...")
            Handler(Looper.getMainLooper()).postDelayed({
                try { adapter.bluetoothLeScanner.stopScan(scanCallback) } catch (_: Exception) {}
                log("Fin scan — ${seenAddresses.size} appareils vus")
                if (!resultSent) {
                    log("Aranet4 (MfID 0x0702) NON trouvé")
                    sendResult()
                }
                stopSelf()
            }, SCAN_DURATION_MS)
        } catch (e: Exception) {
            log("Exception: ${e.message}")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { adapter.bluetoothLeScanner.stopScan(scanCallback) } catch (_: Exception) {}
    }
}
