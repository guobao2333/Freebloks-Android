package de.saschahlusiak.freebloks.network.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.UiThread
import de.saschahlusiak.freebloks.client.GameClient
import de.saschahlusiak.freebloks.client.GameEventObserver
import de.saschahlusiak.freebloks.utils.CrashReporter
import java.io.IOException
import java.util.*

/**
 * Opens a bluetooth socket and listens for incoming connections on a separate Thread.
 *
 * Will inform the given [listener] about connected clients.
 *
 * Register this to a [GameClient] to automatically shut it down once the game is started.
 */
class BluetoothServerThread(
    private val crashReporter: CrashReporter,
    private val listener: OnBluetoothConnectedListener
) : Thread("BluetoothServerBridge"), GameEventObserver {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private val handler = Handler(Looper.getMainLooper())

    interface OnBluetoothConnectedListener {
        @UiThread
        fun onBluetoothClientConnected(socket: BluetoothSocket)
    }

    init {
        if (bluetoothAdapter != null) {
            try {
                Log.i(tag, "name " + bluetoothAdapter.name)
                Log.i(tag, "enabled " + bluetoothAdapter.isEnabled)
            } catch (e: SecurityException) { // doesn't matter, but is interesting
                e.printStackTrace()
                crashReporter.logException(e)
            }
        }
    }

    override fun run() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(tag, "Bluetooth disabled, not starting bridge")
            return
        }
        Log.i(tag, "Starting Bluetooth server")

        serverSocket = try {
            bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord("freebloks", SERVICE_UUID)
        } catch (e: IOException) {
            e.printStackTrace()
            crashReporter.logException(e)
            return
        } catch (e: SecurityException) {
            e.printStackTrace()
            crashReporter.logException(e)
            return
        }

        if (serverSocket == null) {
            Log.e(tag, "Failed to create server socket")
            return
        }

        try {
            while (true) {
                val socket = serverSocket ?: break
                val clientSocket = socket.accept()
                Log.i(tag, "client connected: " + clientSocket.remoteDevice.name)
                if (serverSocket == null || isInterrupted) {
                    clientSocket.close()
                    break
                }
                handler.post { listener.onBluetoothClientConnected(clientSocket) }
            }
        } catch (e: IOException) { // nop
        }

        shutdown()
        Log.i(tag, "Stopping Bluetooth server")
    }

    @Synchronized
    fun shutdown() {
        serverSocket ?: return
        try {
            serverSocket?.close()
            interrupt()
            serverSocket = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @UiThread
    override fun onDisconnected(client: GameClient, error: Throwable?) {
        shutdown()
    }

    override fun gameStarted() {
        shutdown()
    }

    companion object {
        private val tag = BluetoothServerThread::class.java.simpleName

        val SERVICE_UUID: UUID = UUID.fromString("B4C72729-2E7F-48B2-B15C-BDD73CED0D13")
    }
}