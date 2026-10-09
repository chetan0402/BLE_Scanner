package com.rise.blescanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rise.blescanner.databinding.ActivityMainBinding
import com.rise.blescanner.models.RecordingInfo
import com.rise.blescanner.store.ConfigStore
import com.rise.blescanner.store.RecordStore
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private lateinit var configStore: ConfigStore
    private lateinit var sensorManager: SensorManager

    private var isRecording = false
    private var dataPointsCount = 0
    private var startTimeMillis = 0L

    private val currentBleRssis = ConcurrentHashMap<String, Int>()
    private val trackedBleMacs = mutableSetOf<String>()

    private var jsonFileWriter: BufferedWriter? = null
    private var currentJsonFile: File? = null
    private var writeExecutor = Executors.newSingleThreadExecutor()
    private var lastFlushTime = 0L
    private var lastUiUpdateTime = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isRecording) return
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    writeJsonTelemetry(true)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - startTimeMillis
                val hours = (elapsed / 3600000)
                val minutes = (elapsed % 3600000) / 60000
                val seconds = (elapsed % 60000) / 1000
                binding.txtRecordingDuration.text =
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            checkBluetoothHardwareAndState()
        } else {
            showFatalDialog(
                "Permissions Required",
                "This app requires Bluetooth and Location permissions to record telemetry data."
            ) {
                finish()
            }
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkLocationHardwareAndState()
        } else {
            showFatalDialog(
                "Bluetooth Required",
                "Bluetooth must be enabled to execute scanning operations."
            ) {
                checkBluetoothHardwareAndState()
            }
        }
    }

    private val locationEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isLocationEnabled()) {
            initializeApplicationLogic()
        } else {
            showFatalDialog(
                "Location Required",
                "Location Services (GPS) must be enabled to execute scanning operations."
            ) {
                checkLocationHardwareAndState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configStore = ConfigStore(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        checkAndRequestPermissions()
        setupUI()
    }

    private fun setupUI() {
        updateRecordingUiState()
        binding.btnToggleRecording.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        binding.btnSettings.setOnClickListener {
            showSettings()
        }
        binding.viewRecordings.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Stop recording before viewing history", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            startActivity(Intent(this, RecordManager::class.java))
        }
    }

    private fun updateRecordingUiState() {
        if (isRecording) {
            binding.btnToggleRecording.text = "STOP RECORDING"
            binding.btnToggleRecording.setIconResource(R.drawable.ic_stop)
            binding.btnToggleRecording.backgroundTintList =
                ColorStateList.valueOf("#F44336".toColorInt())
            binding.txtRecordingStatus.text = "RECORDING"
            binding.txtRecordingStatus.setTextColor("#4CAF50".toColorInt())
        } else {
            binding.btnToggleRecording.text = "START RECORDING"
            binding.btnToggleRecording.setIconResource(R.drawable.ic_play_arrow)
            binding.btnToggleRecording.backgroundTintList =
                ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.primary_green
                    )
                )
            binding.txtRecordingStatus.text = "IDLE"
            binding.txtRecordingStatus.setTextColor("#6E726E".toColorInt())
        }
    }

    private fun showSettings() {
        if (isRecording) {
            Toast.makeText(this, "Stop recording before changing settings", Toast.LENGTH_SHORT)
                .show()
            return
        }
        startActivity(Intent(this, SettingActivity::class.java))
    }

    private fun startRecording() {
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth scanner initialization failed", Toast.LENGTH_SHORT)
                .show()
            return
        }

        try {
            val dir = File(filesDir, "recordings")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentJsonFile = File(dir, "BLE_Telemetry_$timestamp.json")
            if (writeExecutor.isShutdown) {
                writeExecutor = Executors.newSingleThreadExecutor()
            }
            jsonFileWriter = BufferedWriter(FileWriter(currentJsonFile, true))
            lastFlushTime = System.currentTimeMillis()
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to create JSON file: ${e.message}", Toast.LENGTH_LONG)
                .show()
            return
        }

        isRecording = true
        dataPointsCount = 0
        startTimeMillis = System.currentTimeMillis()

        binding.txtDataPointsCount.text = "0"
        binding.txtRecordingDuration.text = "00:00:00"

        currentBleRssis.clear()
        trackedBleMacs.clear()
        val macs = configStore.getTrackedMacs()
        if (macs.isNotEmpty()) {
            macs.split(",").forEach { 
                val trimmed = it.trim()
                if (trimmed.isNotEmpty()) {
                    trackedBleMacs.add(trimmed.uppercase())
                }
            }
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sensor ->
            sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        updateRecordingUiState()
        handler.post(timerRunnable)

        startBleScan()
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        updateRecordingUiState()
        handler.removeCallbacks(timerRunnable)

        stopBleScan()
        sensorManager.unregisterListener(sensorListener)

        binding.txtDataPointsCount.text = dataPointsCount.toString()

        try {
            writeExecutor.submit {
                try {
                    jsonFileWriter?.flush()
                    jsonFileWriter?.close()
                    jsonFileWriter = null
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }.get()

            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val durationStr = binding.txtRecordingDuration.text.toString()
            val fileLocation = currentJsonFile?.absolutePath ?: ""
            val entryCount = dataPointsCount

            val recordStore = RecordStore(this)
            recordStore.saveRecording(RecordingInfo(dateStr, durationStr, fileLocation, entryCount))

            Toast.makeText(this, "Saved Recording", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error closing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBleScan() {
        val permissionsOk = requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsOk) return

        Log.d("BLE", "Starting BLE scan")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (!isRecording) return

                val address = result.device.address.uppercase()
                val rssi = result.rssi

                if (trackedBleMacs.contains(address)) {
                    currentBleRssis[address] = rssi
                    writeJsonTelemetry(false)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BLE", "Scan failed with error: $errorCode")
            }
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            scanner?.startScan(scanCallback)
        }
    }

    private fun stopBleScan() {
        Log.d("BLE", "Stopping BLE scan")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                scanCallback?.let { scanner?.stopScan(it) }
            }
        } else {
            scanCallback?.let { scanner?.stopScan(it) }
        }
        scanCallback = null
    }

    private fun writeJsonTelemetry(stepTriggered: Boolean) {
        val currentTime = System.currentTimeMillis()
        
        val bleData = JSONObject()
        for ((mac, rssi) in currentBleRssis) {
            bleData.put(mac, rssi)
        }

        val jsonRow = JSONObject().apply {
            put("timestamp", currentTime)
            put("ble_rssis", bleData)
            put("step_triggered", stepTriggered)
        }

        val jsonString = jsonRow.toString() + "\n"

        dataPointsCount++

        if (currentTime - lastUiUpdateTime > 300) {
            binding.txtDataPointsCount.text = dataPointsCount.toString()
            lastUiUpdateTime = currentTime
        }

        writeExecutor.execute {
            try {
                jsonFileWriter?.write(jsonString)
                val now = System.currentTimeMillis()
                if (now - lastFlushTime > 5000) {
                    jsonFileWriter?.flush()
                    lastFlushTime = now
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            checkBluetoothHardwareAndState()
        }
    }

    private fun checkBluetoothHardwareAndState() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            showFatalDialog(
                "Hardware Unsupported",
                "This device does not possess the required Bluetooth hardware configurations."
            ) {
                finish()
            }
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            checkLocationHardwareAndState()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager =
            getSystemService(LOCATION_SERVICE) as? LocationManager
        return locationManager?.let {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    private fun checkLocationHardwareAndState() {
        if (!isLocationEnabled()) {
            showFatalDialog(
                "Location Disabled",
                "This app requires Location Services (GPS) to scan for BLE beacons. Please enable it in Settings."
            ) {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                locationEnableLauncher.launch(intent)
            }
        } else {
            initializeApplicationLogic()
        }
    }

    private fun initializeApplicationLogic() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun showFatalDialog(title: String, message: String, onDismiss: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss()
            }
            .show()
    }

    override fun onDestroy() {
        stopRecording()
        writeExecutor.shutdown()
        super.onDestroy()
    }
}