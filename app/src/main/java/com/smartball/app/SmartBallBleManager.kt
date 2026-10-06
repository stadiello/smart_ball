package com.smartball.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat


data class EventHeader(
    val version: Int,
    val flags: Int,
    val sampleCount: Int,
    val triggerIndex: Int,
    val sampleRateHz: Int,
    val accelRangeG: Int,
    val gyroRangeDps: Int
)

data class ThrowRecord(
    val id: String,
    val createdAt: Long,
    val sampleCount: Int,
    val sampleRateHz: Int,
    val triggerIndex: Int,
    val distanceMeters: Double?,
    val metrics: ThrowMetrics?,
    val directoryPath: String
)



class SmartBallBleManager(

    private val context: Context
) {

    companion object {
        private const val DEVICE_NAME = "SmartBall-001"

        private val NUS_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

        // Téléphone -> balle
        private val NUS_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

        // Balle -> téléphone
        private val NUS_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        private val CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val HEADER_SIZE = 16
        private const val SAMPLE_SIZE = 16
    }


    var status by mutableStateOf("Déconnecté")
        private set

    var connected by mutableStateOf(false)
        private set

    var ready by mutableStateOf(false)
        private set

    var armed by mutableStateOf(false)
        private set

    var receivedSamples by mutableIntStateOf(0)
        private set

    var expectedSamples by mutableIntStateOf(0)
        private set

    var lastThrow by mutableStateOf<ThrowRecord?>(null)
        private set

    var history by mutableStateOf<List<ThrowRecord>>(emptyList())
        private set


    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter
        get() = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var scanning = false

    // Réception d'un événement complet.
    private val eventBuffer = ByteArrayOutputStream()

    private var currentHeader: EventHeader? = null
    private var expectedEventBytes = 0

    var batteryVoltage by mutableStateOf<Float?>(null)
        private set

    var batteryPercent by mutableStateOf<Int?>(null)
        private set

    private val preferences =
        context.getSharedPreferences(
            "smart_ball_settings",
            Context.MODE_PRIVATE
        )

    var ballMassGrams by mutableStateOf<Double?>(
        preferences
            .getString("ball_mass_grams", null)
            ?.toDoubleOrNull()
    )
        private set


    init {
        history = loadHistory()
    }

    fun saveBallMassGrams(
        grams: Double?
    ) {

        val valid =
            grams
                ?.takeIf {
                    it > 0.0
                }

        ballMassGrams =
            valid

        preferences
            .edit()
            .apply {
                if (valid == null) {
                    remove("ball_mass_grams")
                } else {
                    putString(
                        "ball_mass_grams",
                        valid.toString()
                    )
                }
            }
            .apply()
    }

    // ============================================================
    // BATTERIE
    // ============================================================
    private fun voltageToBatteryPercent(
        voltage: Float
    ): Int {

        /*
         * Approximation courbe LiPo 1S.
         *
         * Ce n'est volontairement PAS une interpolation
         * linéaire 3.0 -> 4.2 V.
         */

        return when {
            voltage >= 4.18f -> 100
            voltage >= 4.10f -> 90
            voltage >= 4.00f -> 80
            voltage >= 3.92f -> 70
            voltage >= 3.87f -> 60
            voltage >= 3.82f -> 50
            voltage >= 3.79f -> 40
            voltage >= 3.75f -> 30
            voltage >= 3.70f -> 20
            voltage >= 3.60f -> 10
            voltage >= 3.50f -> 5
            else -> 0
        }
    }

    @SuppressLint("MissingPermission")
    fun requestBattery() {

        if (!hasBluetoothConnectPermission()) {
            status = "Permission Bluetooth manquante"
            return
        }

        val currentGatt =
            gatt ?: return

        val rx =
            rxCharacteristic ?: return

        val command =
            byteArrayOf(
                'B'.code.toByte()
            )

        try {

            if (Build.VERSION.SDK_INT >= 33) {

                currentGatt.writeCharacteristic(
                    rx,
                    command,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE
                )

            } else {

                @Suppress("DEPRECATION")
                run {
                    rx.value = command

                    rx.writeType =
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE

                    currentGatt
                        .writeCharacteristic(rx)
                }
            }

        } catch (e: SecurityException) {

            status =
                "Permission Bluetooth refusée"
        }
    }


    // ============================================================
    // CONNEXION
    // ============================================================

    @SuppressLint("MissingPermission")
    fun connect() {

        if (!adapter.isEnabled) {
            status = "Bluetooth désactivé"
            return
        }

        if (connected) {
            return
        }

        scanner = adapter.bluetoothLeScanner

        if (scanner == null) {
            status = "Scanner BLE indisponible"
            return
        }

        status = "Recherche de SmartBall-001…"
        scanning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE))
            .setDeviceName(DEVICE_NAME)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(
            listOf(filter),
            settings,
            scanCallback
        )

        // Timeout recherche.
        mainHandler.postDelayed({

            if (scanning && !connected) {

                stopScan()

                status = "Balle introuvable"
            }

        }, 10_000)
    }


    @SuppressLint("MissingPermission")
    private fun stopScan() {

        if (!scanning)
            return

        scanner?.stopScan(scanCallback)

        scanning = false
    }


    private val scanCallback =
        object : ScanCallback() {

            @SuppressLint("MissingPermission")
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                stopScan()

                status = "Connexion…"

                gatt = result.device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }

            override fun onScanFailed(errorCode: Int) {

                scanning = false

                status =
                    "Erreur scan BLE : $errorCode"
            }
        }


    // ============================================================
    // GATT
    // ============================================================

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {
                mainHandler.post {
                    this@SmartBallBleManager.status =
                        "BLE MTU : $mtu"
                }

                if (!hasBluetoothConnectPermission()) {
                    return
                }

                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    mainHandler.post {
                        this@SmartBallBleManager.status =
                            "Permission Bluetooth refusée"
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                statusCode: Int,
                newState: Int
            ) {

                if (
                    statusCode == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {

                    this@SmartBallBleManager.gatt = gatt

                    mainHandler.post {

                        connected = true

                        status =
                            "Découverte des services…"
                    }

                    if (!hasBluetoothConnectPermission()) {
                        mainHandler.post {
                            this@SmartBallBleManager.status =
                                "Permission Bluetooth manquante"
                        }
                        return
                    }

                    try {
                        gatt.requestConnectionPriority(
                            BluetoothGatt.CONNECTION_PRIORITY_HIGH
                        )

                        gatt.requestMtu(247)

                    } catch (e: SecurityException) {

                        mainHandler.post {
                            this@SmartBallBleManager.status =
                                "Permission Bluetooth refusée"
                        }

                        return
                    }

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    mainHandler.post {

                        connected = false
                        ready = false
                        armed = false

                        status = "Déconnecté"
                    }

                    rxCharacteristic = null
                    txCharacteristic = null

                    gatt.close()
                }
            }


            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                statusCode: Int
            ) {

                if (
                    statusCode !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    mainHandler.post {
                        status =
                            "Erreur découverte services"
                    }

                    return
                }

                val service =
                    gatt.getService(NUS_SERVICE)

                if (service == null) {

                    mainHandler.post {
                        status =
                            "Service SmartBall absent"
                    }

                    return
                }

                rxCharacteristic =
                    service.getCharacteristic(NUS_RX)

                txCharacteristic =
                    service.getCharacteristic(NUS_TX)

                if (
                    rxCharacteristic == null ||
                    txCharacteristic == null
                ) {

                    mainHandler.post {
                        status =
                            "Caractéristiques BLE absentes"
                    }

                    return
                }

                enableNotifications(
                    gatt,
                    txCharacteristic!!
                )
            }


            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                statusCode: Int
            ) {

                if (
                    descriptor.uuid == CCCD &&
                    statusCode ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    mainHandler.post {

                        ready = true

                        status =
                            "Connecté à SmartBall-001"

                        // Les notifications sont maintenant actives :
                        // on peut demander la batterie.
                        requestBattery()
                    }
                }
            }


            // Android 13+
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                if (
                    characteristic.uuid ==
                    NUS_TX
                ) {

                    handleNotification(value)
                }
            }


            // Android 12
            @Deprecated(
                "Deprecated in Android 13"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic
            ) {

                if (
                    Build.VERSION.SDK_INT < 33 &&
                    characteristic.uuid ==
                    NUS_TX
                ) {

                    val value =
                        characteristic.value
                            ?: return

                    handleNotification(value)
                }
            }
        }


    // ============================================================
    // ACTIVER LES NOTIFICATIONS
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic:
        BluetoothGattCharacteristic
    ) {

        val result =
            gatt.setCharacteristicNotification(
                characteristic,
                true
            )

        if (!result) {

            mainHandler.post {
                status =
                    "Impossible d'activer les notifications"
            }

            return
        }

        val descriptor =
            characteristic.getDescriptor(CCCD)

        if (descriptor == null) {

            mainHandler.post {
                status =
                    "CCCD absent"
            }

            return
        }

        if (Build.VERSION.SDK_INT >= 33) {

            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE
            )

        } else {

            @Suppress("DEPRECATION")
            descriptor.value =
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE

            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }


    // ============================================================
    // ARMER
    // ============================================================

    @SuppressLint("MissingPermission")
    fun arm() {

        val gatt = gatt ?: return
        val rx = rxCharacteristic ?: return

        eventBuffer.reset()

        currentHeader = null
        expectedEventBytes = 0

        receivedSamples = 0
        expectedSamples = 0

        val command =
            byteArrayOf('A'.code.toByte())

        val started =
            if (Build.VERSION.SDK_INT >= 33) {

                gatt.writeCharacteristic(
                    rx,
                    command,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS

            } else {

                @Suppress("DEPRECATION")
                run {

                    rx.value = command

                    rx.writeType =
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE

                    gatt.writeCharacteristic(rx)
                }
            }

        if (started) {

            armed = true

            status =
                "Armé — lance la balle"

        } else {

            status =
                "Échec commande ARM"
        }
    }


    // ============================================================
    // RECEPTION
    // ============================================================

    private fun handleNotification(
        bytes: ByteArray
    ) {
        // ============================================================
// MESSAGE BATTERIE ASCII
// ============================================================

        if (currentHeader == null) {

            try {

                val text =
                    bytes.toString(
                        Charsets.UTF_8
                    )

                if (text.startsWith("BAT:")) {

                    val mv =
                        text
                            .removePrefix("BAT:")
                            .trim()
                            .toIntOrNull()

                    if (mv != null) {

                        val voltage =
                            mv / 1000.0f

                        mainHandler.post {

                            batteryVoltage =
                                voltage

                            batteryPercent =
                                voltageToBatteryPercent(
                                    voltage
                                )
                        }

                        return
                    }
                }

            } catch (_: Exception) {
                // Ce n'était simplement pas
                // un message batterie.
            }
        }

        eventBuffer.write(bytes)

        val data =
            eventBuffer.toByteArray()


        // ----------------------------
        // HEADER
        // ----------------------------

        if (
            currentHeader == null &&
            data.size >= HEADER_SIZE
        ) {

            val header =
                parseHeader(data)

            if (header == null) {

                eventBuffer.reset()

                mainHandler.post {
                    status =
                        "Header SmartBall invalide"
                }

                return
            }

            currentHeader = header

            expectedEventBytes =
                HEADER_SIZE +
                        header.sampleCount *
                        SAMPLE_SIZE

            mainHandler.post {

                expectedSamples =
                    header.sampleCount

                status =
                    "Réception 0/${header.sampleCount}"
            }
        }


        val header =
            currentHeader ?: return


        val sampleBytes =
            (data.size - HEADER_SIZE)
                .coerceAtLeast(0)

        val sampleCount =
            (sampleBytes / SAMPLE_SIZE)
                .coerceAtMost(
                    header.sampleCount
                )


        mainHandler.post {

            receivedSamples =
                sampleCount

            status =
                "Réception $sampleCount/${header.sampleCount}"
        }


        // ----------------------------
        // EVENEMENT COMPLET
        // ----------------------------

        if (
            expectedEventBytes > 0 &&
            data.size >= expectedEventBytes
        ) {

            val payload =
                data.copyOfRange(
                    0,
                    expectedEventBytes
                )

            val record =
                saveThrow(
                    payload,
                    header
                )

            eventBuffer.reset()

            currentHeader = null
            expectedEventBytes = 0

            mainHandler.post {

                armed = false

                receivedSamples =
                    header.sampleCount

                status =
                    "✓ Lancer reçu"

                lastThrow =
                    record

                history =
                    listOf(record) +
                            history.filter {
                                it.id != record.id
                            }
            }
        }
    }


    // ============================================================
    // HEADER
    // ============================================================

    private fun parseHeader(
        bytes: ByteArray
    ): EventHeader? {

        if (bytes.size < HEADER_SIZE)
            return null

        if (
            bytes[0].toInt() != 'B'.code ||
            bytes[1].toInt() != 'A'.code ||
            bytes[2].toInt() != 'L'.code ||
            bytes[3].toInt() != 'L'.code
        ) {
            return null
        }

        val buffer =
            ByteBuffer.wrap(
                bytes,
                0,
                HEADER_SIZE
            )
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )

        // BALL
        buffer.position(4)

        val version =
            buffer.get().toInt() and 0xFF

        val flags =
            buffer.get().toInt() and 0xFF

        val sampleCount =
            buffer.short.toInt() and 0xFFFF

        val triggerIndex =
            buffer.short.toInt() and 0xFFFF

        val sampleRate =
            buffer.short.toInt() and 0xFFFF

        val accelRange =
            buffer.short.toInt() and 0xFFFF

        val gyroRange =
            buffer.short.toInt() and 0xFFFF


        return EventHeader(
            version = version,
            flags = flags,
            sampleCount = sampleCount,
            triggerIndex = triggerIndex,
            sampleRateHz = sampleRate,
            accelRangeG = accelRange,
            gyroRangeDps = gyroRange
        )
    }


    // ============================================================
    // SAUVEGARDE
    // ============================================================

    private fun saveThrow(
        payload: ByteArray,
        header: EventHeader
    ): ThrowRecord {

        val now = System.currentTimeMillis()

        val id =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            )
                .format(Date(now))


        val root =
            File(
                context.filesDir,
                "throws"
            )

        val directory =
            File(root, id)

        directory.mkdirs()


        // Exactement ce qui vient de la balle :
        //
        // EventHeader 16 octets
        // +
        // N * ImuSample 16 octets

        File(
            directory,
            "raw.bin"
        ).writeBytes(payload)


        val samples =
            ThrowAnalyzer.decodeSamples(
                payload,
                header
            )

        val metrics =
            ThrowAnalyzer.analyze(
                samples = samples,
                header = header,
                ballMassKg =
                    ballMassGrams
                        ?.div(1000.0)
            )

        val metadata =
            JSONObject()
                .put("id", id)
                .put(
                    "createdAtEpochMs",
                    now
                )
                .put(
                    "distanceM",
                    JSONObject.NULL
                )
                .put(
                    "ballMassGrams",
                    ballMassGrams
                        ?: JSONObject.NULL
                )
                .put(
                    "sampleCount",
                    header.sampleCount
                )
                .put(
                    "triggerIndex",
                    header.triggerIndex
                )
                .put(
                    "sampleRateHz",
                    header.sampleRateHz
                )
                .put(
                    "accelRangeG",
                    header.accelRangeG
                )
                .put(
                    "gyroRangeDps",
                    header.gyroRangeDps
                )
                .put(
                    "headerBytes",
                    HEADER_SIZE
                )
                .put(
                    "sampleBytes",
                    SAMPLE_SIZE
                )
                .put(
                    "rawBytes",
                    payload.size
                )
                .put(
                    "format",
                    "EventHeader16 + ImuSample16[]"
                )
                .put(
                    "analysis",
                    metricsToJson(metrics)
                )


        File(
            directory,
            "metadata.json"
        ).writeText(
            metadata.toString(2)
        )


        return ThrowRecord(
            id = id,
            createdAt = now,
            sampleCount =
                header.sampleCount,
            sampleRateHz =
                header.sampleRateHz,
            triggerIndex =
                header.triggerIndex,
            distanceMeters = null,
            metrics = metrics,
            directoryPath =
                directory.absolutePath
        )
    }


    // ============================================================
    // DISTANCE REELLE
    // ============================================================

    fun saveDistance(
        meters: Double
    ) {

        val record =
            lastThrow ?: return

        val directory =
            File(record.directoryPath)

        val metadataFile =
            File(
                directory,
                "metadata.json"
            )

        if (!metadataFile.exists())
            return

        val json =
            JSONObject(
                metadataFile.readText()
            )

        json.put(
            "distanceM",
            meters
        )

        val updatedMetrics =
            record.metrics?.let {
                ThrowAnalyzer.withDistance(
                    it,
                    meters
                )
            }

        if (updatedMetrics != null) {
            json.put(
                "analysis",
                metricsToJson(updatedMetrics)
            )
        }

        metadataFile.writeText(
            json.toString(2)
        )


        val updated =
            record.copy(
                distanceMeters = meters,
                metrics = updatedMetrics
            )

        lastThrow = updated

        history =
            history.map {
                if (it.id == updated.id)
                    updated
                else
                    it
            }

        status =
            "✓ Distance enregistrée : %.2f m"
                .format(
                    Locale.US,
                    meters
                )
    }


    // ============================================================
    // HISTORIQUE
    // ============================================================

    private fun loadHistory():
            List<ThrowRecord> {

        val root =
            File(
                context.filesDir,
                "throws"
            )

        if (!root.exists())
            return emptyList()


        return root.listFiles()
            ?.mapNotNull { directory ->

                try {

                    val metadataFile =
                        File(
                            directory,
                            "metadata.json"
                        )

                    if (!metadataFile.exists())
                        return@mapNotNull null

                    val json =
                        JSONObject(
                            metadataFile.readText()
                        )

                    val distance =
                        if (
                            json.isNull(
                                "distanceM"
                            )
                        ) {
                            null
                        } else {
                            json.getDouble(
                                "distanceM"
                            )
                        }


                    val metrics =
                        loadMetrics(
                            directory = directory,
                            json = json,
                            distanceMeters = distance
                        )

                    ThrowRecord(
                        id =
                            json.getString(
                                "id"
                            ),

                        createdAt =
                            json.getLong(
                                "createdAtEpochMs"
                            ),

                        sampleCount =
                            json.getInt(
                                "sampleCount"
                            ),

                        sampleRateHz =
                            json.getInt(
                                "sampleRateHz"
                            ),

                        triggerIndex =
                            json.getInt(
                                "triggerIndex"
                            ),

                        distanceMeters =
                            distance,

                        metrics =
                            metrics,

                        directoryPath =
                            directory.absolutePath
                    )

                } catch (
                    e: Exception
                ) {

                    null
                }
            }
            ?.sortedByDescending {
                it.createdAt
            }
            ?: emptyList()
    }


    private fun loadMetrics(
        directory: File,
        json: JSONObject,
        distanceMeters: Double?
    ): ThrowMetrics? {

        val analysis =
            json.optJSONObject("analysis")

        if (analysis != null) {
            return metricsFromJson(analysis)
        }

        // Compatibilité avec les lancers enregistrés avant
        // l'ajout de l'analyse : recalcul depuis raw.bin.
        val rawFile =
            File(directory, "raw.bin")

        if (!rawFile.exists()) {
            return null
        }

        return try {

            val header =
                EventHeader(
                    version = 1,
                    flags = 0,
                    sampleCount =
                        json.getInt("sampleCount"),
                    triggerIndex =
                        json.getInt("triggerIndex"),
                    sampleRateHz =
                        json.getInt("sampleRateHz"),
                    accelRangeG =
                        json.optInt("accelRangeG", 16),
                    gyroRangeDps =
                        json.optInt("gyroRangeDps", 2000)
                )

            val payload =
                rawFile.readBytes()

            val massGrams =
                if (
                    json.has("ballMassGrams") &&
                    !json.isNull("ballMassGrams")
                ) {
                    json.getDouble(
                        "ballMassGrams"
                    )
                } else {
                    ballMassGrams
                }

            ThrowAnalyzer.analyze(
                samples =
                    ThrowAnalyzer.decodeSamples(
                        payload,
                        header
                    ),
                header = header,
                distanceMeters =
                    distanceMeters,
                ballMassKg =
                    massGrams
                        ?.div(1000.0)
            )

        } catch (_: Exception) {

            null
        }
    }


    private fun metricsToJson(
        metrics: ThrowMetrics
    ): JSONObject {

        fun JSONObject.putNullable(
            key: String,
            value: Any?
        ): JSONObject =
            put(
                key,
                value ?: JSONObject.NULL
            )

        return JSONObject()
            .putNullable(
                "flightStartIndex",
                metrics.flightStartIndex
            )
            .put(
                "impactIndex",
                metrics.impactIndex
            )
            .putNullable(
                "flightTimeSeconds",
                metrics.flightTimeSeconds
            )
            .putNullable(
                "peakLaunchAccelerationG",
                metrics.peakLaunchAccelerationG
            )
            .putNullable(
                "peakImpactAccelerationG",
                metrics.peakImpactAccelerationG
            )
            .putNullable(
                "peakLaunchForceNewton",
                metrics.peakLaunchForceNewton
            )
            .putNullable(
                "peakImpactForceNewton",
                metrics.peakImpactForceNewton
            )
            .putNullable(
                "launchImpulseNewtonSecond",
                metrics.launchImpulseNewtonSecond
            )
            .putNullable(
                "meanSpinRpm",
                metrics.meanSpinRpm
            )
            .putNullable(
                "peakSpinRpm",
                metrics.peakSpinRpm
            )
            .putNullable(
                "rotationsInFlight",
                metrics.rotationsInFlight
            )
            .putNullable(
                "spinStabilityPercent",
                metrics.spinStabilityPercent
            )
            .putNullable(
                "spinAxisStabilityPercent",
                metrics.spinAxisStabilityPercent
            )
            .putNullable(
                "ballisticHeightMeters",
                metrics.ballisticHeightMeters
            )
            .putNullable(
                "ballisticVerticalSpeedMps",
                metrics.ballisticVerticalSpeedMps
            )
            .putNullable(
                "averageHorizontalSpeedMps",
                metrics.averageHorizontalSpeedMps
            )
            .putNullable(
                "ballisticInitialSpeedMps",
                metrics.ballisticInitialSpeedMps
            )
            .putNullable(
                "ballisticLaunchAngleDeg",
                metrics.ballisticLaunchAngleDeg
            )
            .put(
                "accelSaturated",
                metrics.accelSaturated
            )
            .put(
                "gyroSaturated",
                metrics.gyroSaturated
            )
    }


    private fun metricsFromJson(
        json: JSONObject
    ): ThrowMetrics {

        fun nullableDouble(
            key: String
        ): Double? =
            if (json.isNull(key))
                null
            else
                json.optDouble(key)
                    .takeUnless { it.isNaN() }

        fun nullableInt(
            key: String
        ): Int? =
            if (json.isNull(key))
                null
            else
                json.optInt(key)

        return ThrowMetrics(
            flightStartIndex =
                nullableInt("flightStartIndex"),
            impactIndex =
                json.optInt("impactIndex"),
            flightTimeSeconds =
                nullableDouble("flightTimeSeconds"),
            peakLaunchAccelerationG =
                nullableDouble("peakLaunchAccelerationG"),
            peakImpactAccelerationG =
                nullableDouble("peakImpactAccelerationG"),
            peakLaunchForceNewton =
                nullableDouble("peakLaunchForceNewton"),
            peakImpactForceNewton =
                nullableDouble("peakImpactForceNewton"),
            launchImpulseNewtonSecond =
                nullableDouble("launchImpulseNewtonSecond"),
            meanSpinRpm =
                nullableDouble("meanSpinRpm"),
            peakSpinRpm =
                nullableDouble("peakSpinRpm"),
            rotationsInFlight =
                nullableDouble("rotationsInFlight"),
            spinStabilityPercent =
                nullableDouble("spinStabilityPercent"),
            spinAxisStabilityPercent =
                nullableDouble("spinAxisStabilityPercent"),
            ballisticHeightMeters =
                nullableDouble("ballisticHeightMeters"),
            ballisticVerticalSpeedMps =
                nullableDouble("ballisticVerticalSpeedMps"),
            averageHorizontalSpeedMps =
                nullableDouble("averageHorizontalSpeedMps"),
            ballisticInitialSpeedMps =
                nullableDouble("ballisticInitialSpeedMps"),
            ballisticLaunchAngleDeg =
                nullableDouble("ballisticLaunchAngleDeg"),
            accelSaturated =
                json.optBoolean("accelSaturated"),
            gyroSaturated =
                json.optBoolean("gyroSaturated")
        )
    }


    // ============================================================
    // CLEANUP
    // ============================================================

    @SuppressLint("MissingPermission")
    fun close() {

        stopScan()

        gatt?.disconnect()
        gatt?.close()

        gatt = null

        connected = false
        ready = false
    }
}