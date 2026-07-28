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
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var locationButton: Button
    private lateinit var stationCodeInput: EditText
    private lateinit var stationCodeRow: android.view.View
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
            startForegroundService(Intent(this, PhoneTempMonitorService::class.java))
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

        // targetSdk 35 force l'affichage edge-to-edge : sans ça, le contenu se dessine
        // sous la barre de statut système et le haut de l'écran (statusText, champ station)
        // devient invisible.
        val root = findViewById<android.widget.LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        title = "${getString(R.string.app_name)} v$versionName"

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        locationButton = findViewById(R.id.btnLocation)
        locationButton.setOnClickListener { showLocationPicker() }
        refreshLocationButton()
        title = "Meteo Widget ${appVersion()}"

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
        // Appui long : oublier l'Aranet mémorisé (changement de capteur, mauvaise
        // adresse retenue). Le prochain scan réussi en mémorisera un nouveau.
        findViewById<Button>(R.id.btnScan).setOnLongClickListener {
            val known = Prefs.getAranetAddress(this)
            if (known == null) {
                Toast.makeText(this, "Aucun Aranet mémorisé", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.forgetAranetAddress(this)
                appendLog("Aranet $known oublié — le prochain trouvé sera mémorisé")
                Toast.makeText(this, "Aranet oublié", Toast.LENGTH_SHORT).show()
            }
            true
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("logs", logBuffer.toString()))
            Toast.makeText(this, "Copié !", Toast.LENGTH_SHORT).show()
        }

        stationCodeInput = findViewById(R.id.stationCodeInput)
        stationCodeRow = findViewById(R.id.stationCodeRow)
        stationCodeInput.setText(Prefs.getStationCode(this))
        findViewById<Button>(R.id.btnSaveStation).setOnClickListener {
            val code = stationCodeInput.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "Code de station vide", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setStationCode(this, code)
                appendLog("Station changée manuellement → $code")
                Toast.makeText(this, "Station enregistrée : $code", Toast.LENGTH_SHORT).show()
                refreshLocationButton()
                showTemps()
                if (hasPermissions()) WorkScheduler.runNow(this)
            }
        }
        refreshStationRow()

        // Démarrage automatique — pas besoin que l'utilisateur appuie sur quoi que ce soit
        if (hasPermissions()) {
            WorkScheduler.schedule(this)
            startForegroundService(Intent(this, PhoneTempMonitorService::class.java))
            requestBatteryOptimizationExemption()
        } else {
            setStatus("Autorisation BLE requise", "#F57F17")
            permLauncher.launch(requiredPerms().toTypedArray())
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
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
        val indoor = Prefs.getIndoor(this)
        val outdoor = Prefs.getOutdoor(this)
        val state = prefs.getString(Prefs.KEY_LAST_STATE, Prefs.STATE_NONE)
        val indoorStr = describe(indoor, Reading.MAX_AGE_INDOOR_MS)
        val outdoorStr = describe(outdoor, Reading.MAX_AGE_OUTDOOR_MS)
        val advice = when (state) {
            Prefs.STATE_OPEN  -> " · ↑ Ouvrir"
            Prefs.STATE_CLOSE -> " · ↓ Fermer"
            else -> ""
        }
        val stale = listOfNotNull(
            indoor?.isFresh(Reading.MAX_AGE_INDOOR_MS),
            outdoor?.isFresh(Reading.MAX_AGE_OUTDOOR_MS),
        ).any { !it }
        setStatus(
            "$indoorStr dedans · $outdoorStr ${Prefs.getLocation(this).label}$advice",
            if (stale) "#616161" else "#1565C0",
        )
    }

    /** "21.4°C" si la mesure est d'actualité, "21.4°C (il y a 4 h 10)" sinon. */
    private fun describe(reading: Reading?, maxAgeMs: Long): String = when {
        reading == null -> "--"
        reading.isFresh(maxAgeMs) -> "%.1f°C".format(reading.value)
        else -> "%.1f°C (%s)".format(reading.value, Reading.formatAge(reading.ageMs()))
    }

    private fun refreshLocationButton() {
        val loc = Prefs.getLocation(this)
        locationButton.text = "Lieu : ${loc.label}  (${loc.sourceLabel(this)})"
    }

    /** Le code de station ne concerne que Meteociel : inutile de l'afficher pour Cordovado. */
    private fun refreshStationRow() {
        stationCodeRow.visibility =
            if (Prefs.getLocation(this).usesStationCode) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    /** Sans ça, impossible de savoir quelle build tourne sur le téléphone. */
    private fun appVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName}"
    } catch (_: Exception) {
        ""
    }

    private fun showLocationPicker() {
        val options = WeatherLocation.entries
        val current = Prefs.getLocation(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Température extérieure")
            .setSingleChoiceItems(
                options.map { "${it.label}\n${it.sourceLabel(this)}" }.toTypedArray(),
                options.indexOf(current)
            ) { dialog, which ->
                dialog.dismiss()
                val chosen = options[which]
                if (chosen != current) {
                    Prefs.setLocation(this, chosen)
                    appendLog("Lieu → ${chosen.label} (${chosen.sourceLabel(this)})")
                    refreshLocationButton()
                    refreshStationRow()
                    showTemps()
                    TemperatureWidgetProvider.updateAll(this)
                    if (hasPermissions()) WorkScheduler.runNow(this)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
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
