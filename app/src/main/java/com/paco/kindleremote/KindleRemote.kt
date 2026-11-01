package com.paco.kindleremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Makes the phone look like Amazon's official "Kindle Remote" page turner
 * (BLE HID over GATT, VID 0x0171 / PID 0x0443). Stock Kindle firmware only
 * accepts HID input from devices that present this identity.
 *
 * Protocol details come from the kindle-curie project
 * (github.com/tsuuraifluens/kindle-curie), whose ESP32 build of the same
 * identity was verified on a Paperwhite 12th gen, firmware 5.20.1.
 */
@SuppressLint("MissingPermission")
class KindleRemote(private val context: Context, private val onStatus: (String) -> Unit) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val main = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null
    private lateinit var inputReport: BluetoothGattCharacteristic
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var kindle: BluetoothDevice? = null
    private var notificationsOn = false
    private var lastPress = 0L

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return
        }
        server = manager.openGattServer(context, callback)
        pendingServices.addAll(listOf(deviceInfoService(), batteryService(), hidService()))
        server!!.addService(pendingServices.removeFirst())
    }

    fun stop() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        kindle = null
    }

    fun nextPage() = press(KEY_PAGE_DOWN)
    fun previousPage() = press(KEY_PAGE_UP)

    private fun press(key: Byte) {
        val device = kindle
        val s = server
        if (device == null || s == null || !notificationsOn) {
            onStatus("Not connected to Kindle")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPress < 80) return
        lastPress = now

        // 8-byte keyboard report: modifiers, reserved, key1..key6
        val down = byteArrayOf(0, 0, key, 0, 0, 0, 0, 0)
        s.notifyCharacteristicChanged(device, inputReport, false, down)
        main.postDelayed({
            server?.notifyCharacteristicChanged(device, inputReport, false, ByteArray(8))
        }, 40)
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The Kindle's scan filter matches company 0x0171 followed by PID 0x0443 big-endian.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HID_SERVICE))
            .addManufacturerData(VENDOR_ID, byteArrayOf(0x04, 0x43))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            onStatus("Waiting for Kindle. On the Kindle: Settings → Bluetooth → Pair new device")
        }

        override fun onStartFailure(errorCode: Int) {
            onStatus("Advertising failed (error $errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("Android refused to add service ${service.uuid} (status $status)")
                return
            }
            val next = pendingServices.removeFirstOrNull()
            if (next != null) server?.addService(next) else startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                kindle = device
                onStatus("Connecting to ${device.name ?: device.address}…")
            } else if (device == kindle) {
                kindle = null
                notificationsOn = false
                onStatus("Kindle disconnected. Waiting for it to reconnect…")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val chunk = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            characteristic.value = value
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val value = descriptor.value ?: ByteArray(0)
            val chunk = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            descriptor.value = value
            if (descriptor.uuid == CCCD && descriptor.characteristic == inputReport) {
                notificationsOn = value.isNotEmpty() && (value[0].toInt() and 1) == 1
                if (notificationsOn) {
                    kindle = device
                    onStatus("Connected to Kindle. Volume down = next page, volume up = previous page")
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun deviceInfoService() = BluetoothGattService(DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(readOnly(uuid16(0x2A29), "Amazon".toByteArray()))          // Manufacturer
        addCharacteristic(readOnly(uuid16(0x2A24), "Kindle Remote".toByteArray()))   // Model number
        addCharacteristic(readOnly(uuid16(0x2A26), "1.0.0".toByteArray()))           // Firmware revision
        // PnP ID: source USB-IF, VID 0x0171 (Amazon/Lab126), PID 0x0443, version 0x0100
        addCharacteristic(readOnly(uuid16(0x2A50), byteArrayOf(0x02, 0x71, 0x01, 0x43, 0x04, 0x00, 0x01)))
    }

    private fun batteryService() = BluetoothGattService(uuid16(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val level = BluetoothGattCharacteristic(uuid16(0x2A19), PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ)
        level.value = byteArrayOf(100)
        level.addDescriptor(cccd())
        addCharacteristic(level)
    }

    private fun hidService() = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        // HID Information: bcdHID 1.11, country 0, flags = normally connectable
        addCharacteristic(encryptedRead(uuid16(0x2A4A), byteArrayOf(0x11, 0x01, 0x00, 0x02)))
        addCharacteristic(encryptedRead(uuid16(0x2A4B), REPORT_MAP))

        addCharacteristic(BluetoothGattCharacteristic(
            uuid16(0x2A4C), PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED
        ).apply { value = byteArrayOf(0) })

        addCharacteristic(BluetoothGattCharacteristic(
            uuid16(0x2A4E), PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE,
            PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED
        ).apply { value = byteArrayOf(1) }) // Report protocol mode

        inputReport = BluetoothGattCharacteristic(
            uuid16(0x2A4D), PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED
        ).apply {
            value = ByteArray(8)
            addDescriptor(cccd())
            // Report Reference: report ID 1, input report
            addDescriptor(BluetoothGattDescriptor(uuid16(0x2908), BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
                .apply { value = byteArrayOf(0x01, 0x01) })
        }
        addCharacteristic(inputReport)
    }

    private fun readOnly(uuid: UUID, v: ByteArray) =
        BluetoothGattCharacteristic(uuid, PROPERTY_READ, PERMISSION_READ).apply { value = v }

    private fun encryptedRead(uuid: UUID, v: ByteArray) =
        BluetoothGattCharacteristic(uuid, PROPERTY_READ, PERMISSION_READ_ENCRYPTED).apply { value = v }

    private fun cccd() = BluetoothGattDescriptor(
        CCCD, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
    ).apply { value = byteArrayOf(0, 0) }

    companion object {
        private const val VENDOR_ID = 0x0171
        private const val KEY_PAGE_UP: Byte = 0x4B
        private const val KEY_PAGE_DOWN: Byte = 0x4E

        private fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

        private val HID_SERVICE = uuid16(0x1812)
        private val DIS_SERVICE = uuid16(0x180A)
        private val CCCD = uuid16(0x2902)

        // Standard keyboard descriptor, report ID 1
        private val REPORT_MAP = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
            0x81.toByte(), 0x00,
            0xC0.toByte()
        )
    }
}
