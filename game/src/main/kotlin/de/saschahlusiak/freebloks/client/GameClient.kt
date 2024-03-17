package de.saschahlusiak.freebloks.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import de.saschahlusiak.freebloks.network.bluetooth.BluetoothServerThread
import de.saschahlusiak.freebloks.model.GameConfig
import de.saschahlusiak.freebloks.model.Game
import de.saschahlusiak.freebloks.model.GameMode
import de.saschahlusiak.freebloks.model.Turn
import de.saschahlusiak.freebloks.network.Message
import de.saschahlusiak.freebloks.network.MessageReader
import de.saschahlusiak.freebloks.network.MessageWriter
import de.saschahlusiak.freebloks.network.message.*
import de.saschahlusiak.freebloks.utils.CrashReporter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

// Extend Object so we can override finalize()
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class GameClient constructor(
    val game: Game,
    val config: GameConfig,
    val crashReporter: CrashReporter
): Object() {

    private var clientSocket: Closeable? = null
    private val gameClientMessageHandler = GameClientMessageHandler(game)

    private val sendQueue = Channel<Message>(Channel.UNLIMITED)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main) + supervisorJob

    /**
     * The last status message received by our handler
     */
    val lastStatus get() = gameClientMessageHandler.lastStatus

    @Throws(Throwable::class)
    override fun finalize() {
        disconnect()
        super.finalize()
    }

    fun isConnected(): Boolean {
        return when (val socket = clientSocket) {
            null -> false

            is Socket -> !socket.isClosed
            is BluetoothSocket -> socket.isConnected

            else -> true
        }
    }

    fun addObserver(sci: GameEventObserver) = gameClientMessageHandler.addObserver(sci)
    fun removeObserver(sci: GameEventObserver) = gameClientMessageHandler.removeObserver(sci)

    /**
     * Try to establish a TCP connection to the given host and port.
     * On success will call [.connected]
     *
     * @param host target hostname or null for localhost
     * @param port port
     *
     * @throws IOException on connection refused
     */
    @UiThread
    suspend fun connect(host: String?, port: Int): Boolean {
        val socket = Socket()
        val error = runInterruptible(Dispatchers.IO) {
            val address = host?.let { InetSocketAddress(it, port) }
                ?: InetSocketAddress(null as InetAddress?, port)

            try {
                socket.connect(address, CONNECT_TIMEOUT)
                null
            } catch (e: IOException) {
                e.printStackTrace()
                e
            }
        }

        if (error != null) {
            gameClientMessageHandler.notifyConnectionFailed(this, error)
            return false
        }

        val (input, output) = runInterruptible(Dispatchers.IO) {
            socket.getInputStream() to socket.getOutputStream()
        }

        connected(socket, input, output)
        return true
    }

    @UiThread
    suspend fun connect(endpoint: String): Boolean {
        val socket = LocalSocket()
        val address = LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT)

        val error = runInterruptible(Dispatchers.IO) {
            try {
                socket.connect(address)
                null
            } catch (e: IOException) {
                e.printStackTrace()
                e
            }
        }

        if (error != null) {
            gameClientMessageHandler.notifyConnectionFailed(this, error)
            return false
        }

        val (input, output) = runInterruptible(Dispatchers.IO) {
            socket.getInputStream() to socket.getOutputStream()
        }

        connected(socket, input, output)
        return true
    }

    /**
     * Try to connect to the given remote bluetooth device
     *
     * On success will call [.connected]
     */
    suspend fun connect(remote: BluetoothDevice): Boolean {
        val (socket, error) = runInterruptible(Dispatchers.IO) {
            try {
                remote.createInsecureRfcommSocketToServiceRecord(BluetoothServerThread.SERVICE_UUID).also {
                    it.connect()
                } to null
            } catch (e: IOException) {
                e.printStackTrace()

                // translate any IOException to "Connection refused"
                null to e
            }
        }
        if (error != null) {
            gameClientMessageHandler.notifyConnectionFailed(this, error)
            return false
        }
        socket ?: return false

        connected(socket, socket.inputStream, socket.outputStream)
        return true
    }

    /**
     * Connection is successful, set up message readers and writers.
     * Make sure you have observers registered before calling this method.
     *
     * @param socket a closeable socket, for disconnecting
     * @param input the InputStream from the socket
     * @param output the OutputStream to the socket
     */
    @Synchronized
    @UiThread
    fun connected(socket: Closeable, input: InputStream, output: OutputStream) {
        clientSocket = socket

        // first we set up writing to the server
        val writer = MessageWriter(output)
        val reader = MessageReader(input)

        sendQueue.receiveAsFlow()
            .onEach { writer.write(it) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

        // we start reading from the server, we will likely begin receiving and processing
        // messages and sending events to the observers.
        // To allow for other observers to be registered before we consume messages, we inform the
        // observers so far about it.
        gameClientMessageHandler.notifyConnected(this)

        reader.asFlow()
            .buffer(2)
            .onEach { gameClientMessageHandler.handleMessage(it) }
            .catch { disconnect(it) }
            .launchIn(scope)
    }

    @Synchronized
    @UiThread
    fun disconnect(error: Throwable? = null) {
        val socket = clientSocket ?: return

        try {
            crashReporter.log("Disconnecting from ${config.server}")

            sendQueue.close()
            supervisorJob.cancel()

            if (socket is Socket && socket.isConnected) {
                socket.shutdownInput()
            }
            socket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        clientSocket = null

        gameClientMessageHandler.notifyDisconnected(this, error)
    }

    /**
     * Request a new player from the server. This can be called before the client is connected.
     *
     * @param player player to request or -1 for random
     * @param name name for the player
     */
    fun requestPlayer(player: Int, name: String?) {
        // be aware that the name may be capped at length 16
        send(MessageRequestPlayer(player, name))
    }

    /**
     * Request to revoke the given player
     * @param player the local player to revoke
     */
    fun revokePlayer(player: Int) {
        if (!game.isLocalPlayer(player)) return
        send(MessageRevokePlayer(player))
    }

    /**
     * Request a new game mode with new board sizes from the server.
     *
     * @param width new width to request
     * @param height new height to request
     * @param gameMode new game mode to request
     * @param stones availability of the 21 stones
     */
    fun requestGameMode(width: Int, height: Int, gameMode: GameMode, stones: IntArray) {
        send(MessageRequestGameMode(width, height, gameMode, stones))
    }

    /**
     * Request a hint for the current local player.
     */
    fun requestHint() {
        if (!isConnected()) return
        if (!game.isLocalPlayer()) return
        send(MessageRequestHint(game.currentPlayer))
    }

    /**
     * Send a chat message to the server, which will relay it back
     * @param message the message
     */
    fun sendChat(message: String) { // the client does not matter, it will be filled in by the server then broadcasted to all clients
        send(MessageChat(0, message))
    }

    /**
     * Called by the UI for the local player to place the stone.
     * The request is sent to the server, the stone will not be placed locally.
     */
    fun setStone(turn: Turn) {
        // locally set no player as the current player
        // on success the server will send us the new current player
        game.currentPlayer = -1

        send(MessageSetStone(turn))
    }

    /**
     * Request server to start the game. This can be called before the client is connected.
     */
    fun requestGameStart() {
        send(MessageStartGame())
    }

    /**
     * Request server to undo the last move
     */
    fun requestUndo() {
        if (!isConnected()) return
        if (!game.isLocalPlayer()) return
        send(MessageRequestUndo())
    }

    /**
     * Queue the given message to be sent via the [sendQueue]. This can be done even when the [sender] is not running yet.
     *
     * Write errors will be silently ignored.
     *
     * @param msg the message to send
     */
    @AnyThread
    private fun send(msg: Message) {
        try {
            sendQueue.trySendBlocking(msg)
        }
        catch (e: ClosedSendChannelException) {
            // ignore send failures
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT = 5000
        const val DEFAULT_PORT = 59995
    }
}