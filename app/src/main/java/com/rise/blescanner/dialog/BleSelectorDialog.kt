package com.rise.blescanner.dialog

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import androidx.annotation.RequiresPermission
import androidx.core.graphics.drawable.toDrawable
import androidx.core.util.isNotEmpty
import androidx.recyclerview.widget.LinearLayoutManager
import com.rise.blescanner.adapters.BleDeviceAdapter
import com.rise.blescanner.databinding.DialogBleSelectorBinding
import com.rise.blescanner.models.BleDeviceUiInfo
import com.rise.blescanner.store.BleDeviceConfig

class BleSelectorDialog(
    context: Context,
    private val callback: BleSelectorCallback
) : Dialog(context, com.rise.blescanner.R.style.Theme_FullScreenDialog) {

    interface BleSelectorCallback {
        fun onDeviceSelected(config: BleDeviceConfig)
        fun onCancelled()
    }

    private lateinit var binding: DialogBleSelectorBinding
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var adapter: BleDeviceAdapter? = null

    private val discoveredDevicesMap = mutableMapOf<String, BleDeviceUiInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var refreshAnimator: ObjectAnimator? = null

    private val scanRunnable = Runnable { stopScanning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        binding = DialogBleSelectorBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothLeScanner = bluetoothManager.adapter?.bluetoothLeScanner

        setupRecyclerView()
        setupClickListeners()
        startScanning()
    }

    private fun setupRecyclerView() {
        adapter = BleDeviceAdapter { uiInfo ->
            stopScanning()
            callback.onDeviceSelected(BleDeviceConfig(uiInfo.name, uiInfo.macAddress))
            dismiss()
        }
        binding.rvDiscoveredDevices.layoutManager = LinearLayoutManager(context)
        binding.rvDiscoveredDevices.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnRefreshDialog.setOnClickListener {
            if (!isScanning) {
                startScanning()
            }
        }

        binding.btnCancelDialog.setOnClickListener {
            stopScanning()
            callback.onCancelled()
            dismiss()
        }
    }

    private fun getDeviceName(result: ScanResult): String {
        try {
            val name = result.device.name
            if (!name.isNullOrBlank()) return name
        } catch (_: SecurityException) {
        }

        val recordName = result.scanRecord?.deviceName
        if (!recordName.isNullOrBlank()) return recordName

        val manufacturerData = result.scanRecord?.manufacturerSpecificData
        if (manufacturerData != null && manufacturerData.isNotEmpty()) {
            val mfgId = manufacturerData.keyAt(0)
            return when (mfgId) {
                0x004C -> "Apple Device"
                0x0006 -> "Microsoft Device"
                0x0075 -> "Samsung Device"
                0x0059 -> "Nordic Device"
                0x00E0 -> "Google Device"
                0x008F -> "Xiaomi Device"
                0x0057 -> "Huawei Device"
                0x001D -> "Sony Device"
                else -> "Device (Mfg 0x${Integer.toHexString(mfgId).uppercase()})"
            }
        }

        val serviceUuids = result.scanRecord?.serviceUuids
        if (!serviceUuids.isNullOrEmpty()) {
            val firstUuid = serviceUuids[0].uuid.toString().substring(0, 8).uppercase()
            return "Device (UUID $firstUuid)"
        }

        return "Unknown Device"
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            try {
                val device = result.device
                val address = device.address
                val name = getDeviceName(result)
                val rssi = result.rssi
                val txPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                } else {
                    null
                }

                val uiInfo = BleDeviceUiInfo(name, address, rssi, txPower)
                discoveredDevicesMap[address] = uiInfo

                handler.post {
                    adapter?.updateDevice(uiInfo)
                }
            } catch (e: Exception) {
                android.util.Log.e("BleSelectorDialog", "Error in onScanResult: ${e.message}", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            android.util.Log.e("BleSelectorDialog", "Scan failed with error code: $errorCode")
            handler.post {
                stopScanning()
                android.widget.Toast.makeText(context, "Scan failed: error $errorCode", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        return locationManager?.let {
            it.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    it.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (bluetoothLeScanner == null) {
            android.widget.Toast.makeText(context, "Bluetooth LE Scanner is null", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (!isLocationEnabled()) {
            android.widget.Toast.makeText(context, "Please enable Location Services (GPS) to find BLE devices", android.widget.Toast.LENGTH_LONG).show()
        }

        discoveredDevicesMap.clear()
        adapter?.clear()

        isScanning = true
        binding.progressScanning.visibility = View.VISIBLE
        binding.btnRefreshDialog.isEnabled = false

        if (refreshAnimator == null) {
            refreshAnimator = ObjectAnimator.ofFloat(binding.btnRefreshDialog, View.ROTATION, 0f, 360f).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }
        }
        refreshAnimator?.start()

        bluetoothLeScanner?.startScan(scanCallback)
        handler.postDelayed(scanRunnable, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return

        isScanning = false
        binding.progressScanning.visibility = View.GONE
        binding.btnRefreshDialog.isEnabled = true

        refreshAnimator?.cancel()
        binding.btnRefreshDialog.rotation = 0f

        handler.removeCallbacks(scanRunnable)
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            android.util.Log.e("BleSelectorDialog", "Error stopping scan: ${e.message}", e)
        }
    }

    override fun onStop() {
        stopScanning()
        super.onStop()
    }
}