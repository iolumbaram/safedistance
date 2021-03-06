package org.openexposuretrace.oextrace.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import org.openexposuretrace.oextrace.MainActivity
import org.openexposuretrace.oextrace.data.ADV_TAG
import org.openexposuretrace.oextrace.data.Enums
import org.openexposuretrace.oextrace.data.SCAN_TAG
import org.openexposuretrace.oextrace.di.api.ApiClientProvider
import org.openexposuretrace.oextrace.ext.data.insertLogs
import org.openexposuretrace.oextrace.storage.BtContactsManager
import org.openexposuretrace.oextrace.storage.BtEncounter
import org.openexposuretrace.oextrace.distance.DistanceManager
import org.openexposuretrace.oextrace.location.LocationUpdateManager
import org.openexposuretrace.oextrace.service.TrackingService
import org.openexposuretrace.oextrace.storage.TrackingManager
import org.openexposuretrace.oextrace.storage.TrackingPoint
import org.openexposuretrace.oextrace.utils.CryptoUtil
import org.openexposuretrace.oextrace.utils.CryptoUtil.base64EncodedString
import java.util.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DeviceManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f")
        val MAIN_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    }

    private var bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // To provide bluetooth communication
    private var bluetoothGatt: BluetoothGatt? = null

    private var scanCallback: ScanCallback? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var scanActive = false
    private var advertisingActive = false

    private var deviceStatusListener: DeviceStatusListener? = null
    private val apiClient by ApiClientProvider()

    /**
     * Check is Bluetooth LE is available and is it turned on
     *
     * @return current state of Bluetooth scanner
     * @see Enums
     */
    fun checkBluetooth(): Enums {
        val hasSupportLe = context.packageManager
            ?.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            ?: false

        return if (bluetoothAdapter == null || !hasSupportLe) {
            Enums.NOT_FOUND
        } else if (!bluetoothAdapter?.isEnabled!!) {
            Enums.DISABLED
        } else {
            Enums.ENABLED
        }
    }

    /**
     * Start searching Bluetooth LE devices according to the selected device type
     * and return one by one found devices via devicesCallback
     *
     * @param devicesCallback a callback for found devices
     *
     */
    fun startSearchDevices(devicesCallback: (ScanResult) -> Unit) {
        if (scanActive) {
            return
        }

        val deviceFilter = ScanFilter.Builder()
            .apply { setServiceUuid(ParcelUuid(SERVICE_UUID)) }
            .build()

        val bluetoothSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val scanRecord = result.scanRecord
                if (scanRecord != null) {
                    devicesCallback(result)
                }
            }
        }

        bluetoothAdapter?.bluetoothLeScanner?.startScan(
            mutableListOf(deviceFilter),
            bluetoothSettings,
            scanCallback
        )
        scanActive = true
        insertLogs(SCAN_TAG, "Start scan")
    }

    /**
     * Stop Bluetooth LE scanning process
     */
    fun stopSearchDevices() {
        scanActive = false
        bluetoothAdapter?.isDiscovering?.let {
            bluetoothAdapter?.cancelDiscovery()
        }
        scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
        insertLogs(SCAN_TAG, "Stop scan")
    }

    /**
     * Arrange connection to the selected device, and read characteristics of the identified device type
     */
    fun connectDevice(
        scanResult: ScanResult,
        deviceConnectCallback: (BluetoothDevice, Boolean) -> Unit
    ): Boolean {
        val device = scanResult.device
        if (isDeviceConnected()) {
            return false
        }

        bluetoothGatt = device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    super.onMtuChanged(gatt, mtu, status)
                    Log.d(SCAN_TAG, "Mtu Changed $mtu status $status")
                    gatt?.discoverServices()
                }

                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(SCAN_TAG, "Device Connected ${device.address}")
                            deviceConnectCallback(device, true)
                            val mtu = 32 + 3 // Maximum allowed 517 - 3 bytes do BLE
                            bluetoothGatt?.requestMtu(mtu)

                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(SCAN_TAG, "Disconnected ${device.address}")
                            deviceConnectCallback(device, false)
                            closeConnection()
                        }

                    }
                    when (status) {
                        BluetoothGatt.GATT_FAILURE -> {
                            insertLogs(SCAN_TAG, "Failed to connect to ${device.address}")
                            deviceConnectCallback(device, false)
                            closeConnection()
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    insertLogs(SCAN_TAG, "Device discovered ${device.address}")
                    var hasServiceAndCharacteristic = false
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        val characteristic =
                            service.getCharacteristic(MAIN_CHARACTERISTIC_UUID)
                        characteristic?.let {
                            bluetoothGatt?.readCharacteristic(it)
                            hasServiceAndCharacteristic = true
                        }

                    }
                    if (!hasServiceAndCharacteristic) {
                        deviceStatusListener?.onServiceNotFound(device)
                        closeConnection()
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        handleCharacteristics(scanResult, characteristic)
                    }
                }

            })

        return true
    }

    /**
     * Close connection with earlier connected device
     */
    fun closeConnection() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun isDeviceConnected() = bluetoothGatt != null

    private fun handleCharacteristics(
        scanResult: ScanResult,
        characteristic: BluetoothGattCharacteristic
    ) {
        val data = characteristic.value

        if (data.size != CryptoUtil.KEY_LENGTH * 2) {
            insertLogs(SCAN_TAG, "Received unexpected data length: ${data.size}")

            return
        }

        val rollingId = data.sliceArray(0 until CryptoUtil.KEY_LENGTH).base64EncodedString()
        val meta = data.sliceArray(CryptoUtil.KEY_LENGTH until CryptoUtil.KEY_LENGTH * 2)
            .base64EncodedString()
        deviceStatusListener?.onDataReceived(scanResult.device, characteristic.value)

        val day = CryptoUtil.currentDayNumber()
        BtContactsManager.addContact(rollingId, day, BtEncounter(scanResult.rssi, meta))

        var distance = DistanceManager.calculateDistance(scanResult.rssi)

        if (distance != null) {
            if(distance < 2.0) {
                var lat = LocationUpdateManager.getLastLocation()?.latitude
                var lon = LocationUpdateManager.getLastLocation()?.longitude
                Log.d("debug", "Latitude ${lat} Longitude ${lon}")
                val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 120)
                toneG.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 200)
//                var mainActivity = MainActivity()
//                mainActivity.popAlertNotification()
                MainActivity.popAlertNotification()

                // to update database via api
                val location = Location(LocationUpdateManager.getLastLocation())
                val gson = Gson()
                println("Buzzer location:" + gson.toJson(location))
                //apiClient.sendTracks(TrackingPoint(location))
                apiClient.sendTracks(TrackingPoint(location) )
                    .enqueue(object : Callback<String> {

                        override fun onResponse(call: Call<String>, response: Response<String>) {
                            println("violation uploaded!")
                        }

                        override fun onFailure(call: Call<String>, t: Throwable) {
                            println("ERROR: ${t.message}")
                        }

                    })
            }
        }

        insertLogs(
            SCAN_TAG,
            "Recorded a contact with ${scanResult.device.address} RSSI ${scanResult.rssi} DISTANCE ${distance} Latitude${LocationUpdateManager.getLastLocation()?.latitude} Longitude ${LocationUpdateManager.getLastLocation()?.longitude}"
        )

        closeConnection()
    }


    interface DeviceStatusListener {
        fun onDataReceived(device: BluetoothDevice, bytes: ByteArray)
        fun onServiceNotFound(device: BluetoothDevice)
    }


    /********************************************
     ******************SERVICE*******************
     ********************************************/

    /**
     * Begin advertising over Bluetooth that this device is connectable
     */
    fun startAdvertising(): Boolean {
        if (advertisingActive)
            return true

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            insertLogs(ADV_TAG, "Multiple advertisement is not supported")
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            insertLogs(ADV_TAG, "Bluetooth LE is not supported")

            return false
        }

        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
            bluetoothManager.adapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            insertLogs(ADV_TAG, "Bluetooth LE advertiser is unavailable")

            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
        advertisingActive = true

        return true
    }

    /**
     * Stop Bluetooth advertisements.
     */
    fun stopAdvertising() {
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
            bluetoothManager.adapter.bluetoothLeAdvertiser
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertisingActive = false
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            insertLogs(ADV_TAG, "Advertising has started")

            if (!startBleServer()) {
                insertLogs(ADV_TAG, "Unable to create GATT server")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            insertLogs(ADV_TAG, "Failed to start advertising: errorCode $errorCode")
        }
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     */
    private fun startBleServer(): Boolean {
        bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        return bluetoothGattServer?.addService(createService()) ?: false
    }

    private fun createService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            MAIN_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(characteristic)

        return service
    }

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(ADV_TAG, "Connected to ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(ADV_TAG, "Disconnected from ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                MAIN_CHARACTERISTIC_UUID -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        CryptoUtil.getCurrentRpi()
                    )

                    insertLogs(
                        ADV_TAG,
                        "Sent RPI to ${device.address}"
                    )
                }
                else -> {
                    // Invalid characteristic
                    insertLogs(
                        ADV_TAG,
                        "Invalid Characteristic Read ${characteristic.uuid}"
                    )
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            super.onExecuteWrite(device, requestId, execute)
            insertLogs(ADV_TAG, "Execute Write ${device?.address ?: ""}")
        }
    }

    fun stopServer() {
        insertLogs(ADV_TAG, "Stop gatt server")
        bluetoothGattServer?.close()
    }

}
