package org.kvj.habtproxy

import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.SparseArray
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.util.forEach
import androidx.core.util.keyIterator
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

private val TAG = "ScanWorker"

fun <K> Map<K, ByteArray>.contentMapEquals(other: Map<K, ByteArray>): Boolean {
    if (keys == other.keys) {
        if (keys.any { !get(it).contentEquals(other[it]) }) {
            return false;
        }
    } else {
        return false;
    }
    return true;
}

fun SparseArray<ByteArray>.contentMapEquals(other: SparseArray<ByteArray>): Boolean {
    val list = keyIterator().asSequence().toList()
    if (list == other.keyIterator().asSequence().toList()) {
        if (list.any { !get(it).contentEquals(other[it]) }) {
            return false;
        }
    } else {
        return false;
    }
    return true;
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val FG_NOTIFICATION_ID = 1
    private var theCallback: ScanCallback? = null
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(applicationContext, BluetoothManager::class.java) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
    }

    private val powerManager by lazy {
        getSystemService(applicationContext, PowerManager::class.java)!!
    }

    private val discoveryResults by lazy {
        DiscoveryResults.instance
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeScan(): Boolean {
        if (!applicationContext.hasRequiredRuntimePermissions()) {
            Log.w(TAG, "No runtime permissions")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter disabled")
            return false
        }
        var rssiTresh = preferences.getInt(applicationContext, R.string.settings_rssi_threshold, R.string.settings_rssi_threshold_def)
        var dontOverwriteEvents = preferences.getBool(applicationContext, R.string.settings_dont_overwrite_events, R.string.settings_dont_overwrite_events_def)

        val devicesFound = mutableSetOf<String>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, outerResult: ScanResult?) {
                Log.d(TAG, "onScanResult(): $outerResult / $callbackType")
                outerResult?.let { result ->
                    result.scanRecord?.let { record ->
                        val address = result.device.address.uppercase()
                        devicesFound.add(address)
                        val isButton = record.deviceName?.contains("090615.remote.btsw1") == true
                        if (!isButton) {
                            Log.d(TAG, "onScanResult(): Skip $address, not a button")
                            return
                        }
                        val idx = if (dontOverwriteEvents || isButton) discoveryResults.discoveredRecords.keys.count().toString() else address
                        if (!discoveryResults.discoveredRecords.containsKey(idx)) {
                            discoveryResults.discoveredRecords[idx] = DiscoveredDevice(address, record, result.rssi, result.txPower, rssiTresh, record.deviceName)
                            Log.d(TAG, "onScanResult(): New entry[$idx]: ${discoveryResults.discoveredRecords[idx]}")
                        } else {
                            if (dontOverwriteEvents) {
                                discoveryResults.discoveredRecords[idx] = DiscoveredDevice(address, record, result.rssi, result.txPower, rssiTresh, record.deviceName)
                                Log.d(TAG, "onScanResult(): New entry instead update[$idx]: ${discoveryResults.discoveredRecords[idx]}")
                            } else {
                                if (discoveryResults.discoveredRecords[idx]?.updateMaybe(record, result.rssi, result.txPower, record.deviceName) == true) {
                                    Log.d(TAG, "onScanResult(): Updated entry[$idx]: ${discoveryResults.discoveredRecords[idx]}")
                                } else {
                                    Log.d(TAG, "onScanResult(): Didnt Updated entry[$idx]: ${discoveryResults.discoveredRecords[idx]}")
                                }
                            }
                        }
                        record
                    }
                }
                Log.d(TAG, "onScanResult(): New cache: $discoveryResults")
            }
        }
        val scanDuration = preferences.getInt(applicationContext, R.string.settings_scan_duration, R.string.settings_scan_duration_def)
        val startNextScanRightAway = preferences.getBool(applicationContext, R.string.settings_use_ongoing_scan, R.string.settings_use_ongoing_scan_def)
        if (startNextScanRightAway) {
            if (theCallback == null) {
                updateNotification("No callback...")
                bleScanner.startScan(emptyList(), settings, callback)
                updateNotification("Started first scan...")
                Log.d(TAG, "Scan has started")
                theCallback = callback
            }
            Log.d(TAG, "Scan has started for $scanDuration s")
            withContext(Dispatchers.IO) {
                Thread.sleep(1000L * scanDuration)
            }
            updateNotification("Stopping current scan...")

            bleScanner.stopScan(theCallback)
            theCallback = callback

            updateNotification("Starting current scan...")
            bleScanner.startScan(emptyList(), settings, callback)
        } else {
            if (theCallback != null) {
                bleScanner.stopScan(theCallback)
                theCallback = null
            }
            bleScanner.startScan(emptyList(), settings, callback)
            val scanDuration = preferences.getInt(applicationContext, R.string.settings_scan_duration, R.string.settings_scan_duration_def)
            Log.d(TAG, "Scan has started for $scanDuration s")
            withContext(Dispatchers.IO) {
                Thread.sleep(1000L * scanDuration)
            }
            bleScanner.stopScan(callback)
            Log.d(TAG, "Scan has finished")
        }
        Log.d(TAG, "Scan has finished")
        if (devicesFound.isNotEmpty()) {
            updateNotification("Devices discovered: ${devicesFound.size}")
        }
        return true
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        while (true) {
            val enabled = preferences.getBool(applicationContext, R.string.settings_enabled, R.string.settings_enabled_def)
            val optimizeBackground = preferences.getBool(applicationContext, R.string.settings_optimize_background, R.string.settings_optimize_background_def)
            Log.d(TAG, "doWork(): Next scan: $enabled / ${powerManager.isInteractive} / ${optimizeBackground}")
            if (!enabled) {
                Log.d(TAG, "doWork(): Stopping background scan as not enabled")
                break
            }

            executeScan()
            uploadData()
            var dontOverwriteEvents = preferences.getBool(applicationContext, R.string.settings_dont_overwrite_events, R.string.settings_dont_overwrite_events_def)
            if (dontOverwriteEvents) {
                discoveryResults.discoveredRecords = mutableMapOf<String, DiscoveredDevice>()
            }
            if (optimizeBackground && !powerManager.isInteractive) {
                Log.d(TAG, "doWork(): Stopping background scan for optimization")
                break
            }
            val scanInterval = preferences.getInt(applicationContext, R.string.settings_scan_interval, R.string.settings_scan_interval_def)
            Log.d(TAG, "doWork(): Next scan sleep: $scanInterval s")
            withContext(Dispatchers.IO) {
                Thread.sleep(1000L * scanInterval)
            }
        }
        return Result.success()
    }


    private suspend fun uploadData(): Boolean {
        val webhook = preferences.getString(applicationContext, R.string.settings_webhook, 0)
        var dontOmitSendingWebhook = preferences.getBool(applicationContext, R.string.settings_dont_omit_sending_webhook, R.string.settings_dont_omit_sending_webhook_def)

        if (TextUtils.isEmpty(webhook)) {
            Log.w(TAG, "uploadData(): No webhook set")
            return false
        }
        val now = Date().time
        val uploadInterval = preferences.getInt(applicationContext, R.string.settings_upload_inteval, R.string.settings_upload_inteval_def)
        if ((now - discoveryResults.lastUploadTimestamp < uploadInterval) && !dontOmitSendingWebhook) {
            Log.d(TAG, "uploadData(): Skip upload due to the uploadInterval $uploadInterval s")
            return true
        }
        val arr = JSONArray()
        discoveryResults.discoveredRecords.forEach {
            if (it.value.timestamp >= discoveryResults.lastUploadTimestamp) {
                val obj = JSONObject()
                obj.put("address", it.value.address)
                obj.put("name", it.value.name)
                obj.put("rssi", it.value.rssi)
                obj.put("tx_power", it.value.txPower)
                obj.put("timestamp", it.value.timestamp)
                it.value.record.serviceUuids?.let {
                    val uuids = JSONArray()
                    it.forEach { uuids.put(it.uuid.toString()) }
                    obj.put("service_uuids", uuids)
                }
                it.value.record.serviceData?.let {
                    val serviceData = JSONObject()
                    it.forEach {
                        serviceData.put(it.key.uuid.toString(), Base64.encodeToString(it.value, Base64.DEFAULT))
                    }
                    obj.put("service_data", serviceData)
                }
                it.value.record.manufacturerSpecificData?.let {
                    val mData = JSONObject()
                    it.forEach { key, value ->
                        mData.put(key.toString(), Base64.encodeToString(value, Base64.DEFAULT))
                    }
                    obj.put("manufacturer_data", mData)
                }
                arr.put(obj)
            }
        }
        if (arr.length() == 0 && !dontOmitSendingWebhook) {
            Log.d(TAG, "uploadData(): Skip upload, no new devices")
            return true
        }
        val result = withContext(Dispatchers.IO) {
            val conn = URL(webhook).openConnection() as HttpURLConnection
            try {
                conn.doOutput = true
                conn.setChunkedStreamingMode(0)
                conn.requestMethod = "POST"
                conn.addRequestProperty("content-type", "application/json")
                conn.outputStream.bufferedWriter().let {
                    it.write(arr.toString())
                    it.close()
                }
                conn.inputStream.bufferedReader().let {
                    val text = it.readText()
                    it.close()
                    text
                }
                discoveryResults.lastUploadTimestamp = now
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data via webhook", e)
                null
            } finally {
                conn.disconnect()
            }
        }
        Log.d(TAG, "Webhook send result: $result")
        return result == null
    }

    private fun createNotification(text: String) : Notification {
        return NotificationCompat.Builder(applicationContext, "foreground")
            .setContentTitle("Bluetooth Proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_foreground_scan)
            .setOngoing(true)
//            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(applicationContext).notify(FG_NOTIFICATION_ID, createNotification(text))
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channel = NotificationChannelCompat.Builder("foreground", NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Foreground tasks")
            .build()
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(channel)
        val notification = createNotification("Application starting")
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                ForegroundInfo(FG_NOTIFICATION_ID, notification)
            }
            else -> {
                ForegroundInfo(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        }
    }
}