package com.cloudx.databridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth dial: dials a number on a PAIRED button/feature phone over classic
 * Bluetooth SPP (Serial Port Profile) with the standard modem command
 * `ATD<number>;` — the phone dials with its own SIM, this phone only sends.
 *
 * No root, no extra hardware: Android can open an RFCOMM client socket to any
 * bonded device exposing SPP/DUN (most classic button phones do). Pairing
 * itself happens in Android Settings; here the agent just picks which bonded
 * device is the dial phone (Settings → Bluetooth dial phone).
 *
 * Requirements at dial time: Bluetooth ON, button phone paired + in range,
 * BLUETOOTH_CONNECT granted (Android 12+ runtime). Connecting to an already
 * bonded device needs no location permission. All blocking I/O runs on
 * Dispatchers.IO — never call from the main thread.
 */
object BtDialHelper {

    private const val PREFS = "databridge_toggles"
    private const val KEY_MAC = "bt_dial_mac"
    private const val KEY_NAME = "bt_dial_name"

    /** Classic SPP UUID — the AT-command modem channel button phones expose. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val VERDICT_TIMEOUT_MS = 10_000

    sealed class DialResult {
        /** Phone accepted the dial (AT verdict OK). */
        data object Dialed : DialResult()
        /** Anything else — [reason] is user-readable, safe for a toast. */
        data class Failed(val reason: String) : DialResult()
    }

    fun hasPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun configuredMac(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAC, "").orEmpty().trim()

    fun configuredName(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, "").orEmpty().trim()

    fun isConfigured(ctx: Context): Boolean = configuredMac(ctx).isNotBlank()

    fun saveDevice(ctx: Context, mac: String, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAC, mac.trim())
            .putString(KEY_NAME, name.trim())
            .apply()
    }

    fun clearDevice(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_MAC).remove(KEY_NAME).apply()
    }

    /** Bonded classic devices (name + MAC) for the Settings picker. Empty when
     *  permission is missing or Bluetooth is off — never throws. */
    fun bondedDevices(ctx: Context): List<Pair<String, String>> {
        if (!hasPermission(ctx)) return emptyList()
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices
                .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
                .map { d ->
                    val name = try { d.name.orEmpty().trim() } catch (_: SecurityException) { "" }
                    val mac = try { d.address.orEmpty().trim() } catch (_: SecurityException) { "" }
                    (if (name.isBlank()) "Unknown device" else name) to mac
                }
                .filter { it.second.isNotBlank() }
                .sortedBy { it.first.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Dials [phone] on the configured button phone ([mac] override or the
     * saved device). Suspends on IO; returns [DialResult.Dialed] only on an OK
     * verdict from the phone.
     */
    suspend fun dial(
        ctx: Context,
        phone: String,
        mac: String = configuredMac(ctx),
    ): DialResult = withContext(Dispatchers.IO) {
        val number = phone.filter { it.isDigit() || it == '+' }.trim()
        if (number.filter { it.isDigit() }.length < 7) {
            return@withContext DialResult.Failed("Invalid number")
        }
        if (mac.isBlank()) {
            return@withContext DialResult.Failed("No Bluetooth phone set (Settings → Bluetooth dial phone)")
        }
        if (!hasPermission(ctx)) {
            return@withContext DialResult.Failed("Bluetooth permission needed")
        }
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Exception) {
            null
        } ?: return@withContext DialResult.Failed("No Bluetooth on this device")
        if (!adapter.isEnabled) {
            return@withContext DialResult.Failed("Bluetooth is off")
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (_: Exception) {
            return@withContext DialResult.Failed("Bad Bluetooth address")
        }
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                adapter.cancelDiscovery()
            } catch (_: Exception) {
            }
            // Blocking connect — bounded by doing it on a worker thread with a
            // watchdog: socket.connect() itself has no timeout param, so run it
            // in a future and cap the wait.
            val connected = runCatching {
                val t = Thread { try { socket?.connect() } catch (_: Exception) { } }
                t.isDaemon = true
                t.start()
                t.join(CONNECT_TIMEOUT_MS.toLong())
                socket?.isConnected == true
            }.getOrDefault(false)
            if (!connected) {
                return@withContext DialResult.Failed("Couldn't reach ${deviceAlias(ctx, device)} (in range + paired?)")
            }
            val out = socket?.outputStream
                ?: return@withContext DialResult.Failed("No channel to phone")
            val inp = socket?.inputStream
                ?: return@withContext DialResult.Failed("No channel to phone")
            // Voice-call dial — ';' charai phone data-call vabe; '\r' ends the AT line.
            out.write("ATD$number;\r".toByteArray(Charsets.US_ASCII))
            out.flush()
            val deadline = System.currentTimeMillis() + VERDICT_TIMEOUT_MS
            val buf = StringBuilder()
            val chunk = ByteArray(128)
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val n = inp.read(chunk)
                    if (n > 0) buf.append(String(chunk, 0, n, Charsets.US_ASCII))
                    val upper = buf.toString().uppercase()
                    if (upper.contains("\nOK") || upper.startsWith("OK") || upper.contains("\rOK")) {
                        return@withContext DialResult.Dialed
                    }
                    if (upper.contains("ERROR")) {
                        return@withContext DialResult.Failed(
                            "Phone rejected dial (${buf.trim().take(60)})"
                        )
                    }
                } else {
                    Thread.sleep(100)
                }
            }
            DialResult.Failed("No answer from phone (dial may still have started — check it)")
        } catch (e: SecurityException) {
            DialResult.Failed("Bluetooth permission needed")
        } catch (e: IOException) {
            DialResult.Failed("Bluetooth error (${e.message?.take(60) ?: "connection failed"})")
        } catch (e: Exception) {
            DialResult.Failed("Bluetooth error (${e.message?.take(60) ?: "failed"})")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun deviceAlias(ctx: Context, device: BluetoothDevice): String {
        val saved = configuredName(ctx)
        if (saved.isNotBlank()) return saved
        return try {
            device.name?.trim()?.takeIf { it.isNotBlank() } ?: "button phone"
        } catch (_: SecurityException) {
            "button phone"
        }
    }
}
