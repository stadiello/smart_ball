package com.smartball.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {

    private lateinit var ble:
            SmartBallBleManager


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        ble =
            SmartBallBleManager(
                applicationContext
            )

        setContent {

            MaterialTheme {

                SmartBallScreen(ble)
            }
        }
    }


    override fun onDestroy() {

        ble.close()

        super.onDestroy()
    }
}


@Composable
fun SmartBallScreen(
    ble: SmartBallBleManager
) {

    val context =
        LocalContext.current

    var distanceText
            by remember {
                mutableStateOf("")
            }


    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()
        ) { permissions ->

            val scan =
                permissions[
                    Manifest.permission
                        .BLUETOOTH_SCAN
                ] == true

            val connect =
                permissions[
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                ] == true

            if (scan && connect) {

                ble.connect()
            }
        }


    fun connect() {

        val scanGranted =
            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_SCAN
                ) ==
                    PackageManager
                        .PERMISSION_GRANTED

        val connectGranted =
            ContextCompat
                .checkSelfPermission(
                    context,
                    Manifest.permission
                        .BLUETOOTH_CONNECT
                ) ==
                    PackageManager
                        .PERMISSION_GRANTED


        if (
            scanGranted &&
            connectGranted
        ) {

            ble.connect()

        } else {

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission
                        .BLUETOOTH_SCAN,

                    Manifest.permission
                        .BLUETOOTH_CONNECT
                )
            )
        }
    }


    Scaffold {

        Column(
            modifier =
                Modifier
                    .padding(it)
                    .padding(20.dp)
                    .fillMaxSize()
                    .verticalScroll(
                        rememberScrollState()
                    ),
            verticalArrangement =
                Arrangement.spacedBy(
                    16.dp
                )
        ) {


            // ========================
            // TITRE
            // ========================

            Text(
                text = "SmartBall",
                style =
                    MaterialTheme
                        .typography
                        .headlineLarge,
                fontWeight =
                    FontWeight.Bold
            )


            // ========================
            // ETAT
            // ========================

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Column(
                    modifier =
                        Modifier.padding(
                            16.dp
                        )
                ) {

                    Text(
                        "État",
                        style =
                            MaterialTheme
                                .typography
                                .labelLarge
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        ble.status,
                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )
                }
            }

            // ========================
            // BATTERIE
            // ========================

            if (ble.connected) {

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),

                        horizontalArrangement =
                            Arrangement.SpaceBetween,

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Column {

                            Text(
                                "Batterie",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelLarge
                            )

                            val voltage =
                                ble.batteryVoltage

                            val percent =
                                ble.batteryPercent

                            if (
                                voltage != null &&
                                percent != null
                            ) {

                                Text(
                                    "%.2f V — %d %%"
                                        .format(
                                            Locale.FRANCE,
                                            voltage,
                                            percent
                                        ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium
                                )

                            } else {

                                Text("Mesure…")
                            }
                        }


                        TextButton(
                            enabled =
                                !ble.armed,

                            onClick = {
                                ble.requestBattery()
                            }
                        ) {

                            Text("↻")
                        }
                    }
                }
            }


            // ========================
            // CONNECT
            // ========================

            Button(
                modifier =
                    Modifier.fillMaxWidth(),

                enabled =
                    !ble.connected,

                onClick = {
                    connect()
                }
            ) {

                Text(
                    if (ble.connected)
                        "Balle connectée"
                    else
                        "Connecter la balle"
                )
            }


            // ========================
            // ARMER
            // ========================

            Button(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp),

                enabled =
                    ble.ready &&
                            !ble.armed,

                onClick = {
                    ble.arm()
                }
            ) {

                Text(
                    if (ble.armed)
                        "ARMÉE"
                    else
                        "ARMER POUR UN LANCER"
                )
            }


            // ========================
            // PROGRESSION
            // ========================

            if (
                ble.expectedSamples > 0
            ) {

                val progress =
                    (
                            ble.receivedSamples
                                .toFloat() /
                                    ble.expectedSamples
                                        .toFloat()
                            )
                        .coerceIn(
                            0f,
                            1f
                        )


                LinearProgressIndicator(
                    progress = {
                        progress
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                )


                Text(
                    "${ble.receivedSamples} / " +
                            "${ble.expectedSamples} échantillons"
                )
            }


            // ========================
            // DERNIER LANCER
            // ========================

            ble.lastThrow?.let {
                    throwRecord ->

                HorizontalDivider()

                Text(
                    "Dernier lancer",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall
                )


                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Column(
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                6.dp
                            )
                    ) {

                        Text(
                            "${throwRecord.sampleCount} échantillons"
                        )

                        Text(
                            "${throwRecord.sampleRateHz} Hz"
                        )

                        Text(
                            "Impact index : " +
                                    "${throwRecord.triggerIndex}"
                        )
                    }
                }


                // ------------------------
                // DISTANCE TERRAIN
                // ------------------------

                OutlinedTextField(
                    value =
                        distanceText,

                    onValueChange = {
                        distanceText = it
                    },

                    label = {
                        Text(
                            "Distance réelle (m)"
                        )
                    },

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        ),

                    modifier =
                        Modifier.fillMaxWidth()
                )


                Button(
                    modifier =
                        Modifier.fillMaxWidth(),

                    onClick = {

                        val distance =
                            distanceText
                                .replace(
                                    ",",
                                    "."
                                )
                                .toDoubleOrNull()

                        if (
                            distance != null &&
                            distance > 0
                        ) {

                            ble.saveDistance(
                                distance
                            )

                            distanceText =
                                ""
                        }
                    }
                ) {

                    Text(
                        "Enregistrer la distance"
                    )
                }
            }


            // ========================
            // HISTORIQUE
            // ========================

            if (
                ble.history.isNotEmpty()
            ) {

                HorizontalDivider()

                Text(
                    "Historique",
                    style =
                        MaterialTheme
                            .typography
                            .headlineSmall
                )


                ble.history
                    .take(20)
                    .forEach {
                            record ->

                        ThrowHistoryCard(
                            record
                        )
                    }
            }
        }
    }
}


@Composable
fun ThrowHistoryCard(
    record: ThrowRecord
) {

    val formatter =
        remember {

            SimpleDateFormat(
                "dd/MM HH:mm:ss",
                Locale.FRANCE
            )
        }


    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            horizontalArrangement =
                Arrangement
                    .SpaceBetween,

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column {

                Text(
                    formatter.format(
                        Date(
                            record.createdAt
                        )
                    ),
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "${record.sampleCount} samples • " +
                            "${record.sampleRateHz} Hz"
                )
            }


            Text(
                if (
                    record.distanceMeters
                    != null
                ) {

                    "%.2f m".format(
                        Locale.FRANCE,
                        record.distanceMeters
                    )

                } else {

                    "Distance ?"
                },

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )
        }
    }
}