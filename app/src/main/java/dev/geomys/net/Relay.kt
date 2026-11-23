package dev.geomys.net

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TcpRelay(
    private val scope: CoroutineScope,
    private val pfd: ParcelFileDescriptor,
    private val socket: Socket,
    private val onToPeer: (Int) -> Unit,
    private val onFromPeer: (Int) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val pfdIn = FileInputStream(pfd.fileDescriptor)
                val pfdOut = FileOutputStream(pfd.fileDescriptor)
                val socketIn = socket.getInputStream()
                val socketOut = socket.getOutputStream()
                coroutineScope {
                    // Half-close propagation:
                    //  - EOF from the TCP socket means the app/target finished
                    //    sending. Shut down the socketpair's write side so the
                    //    peer relay sees EOF and forwards a FIN to its TCP peer.
                    //  - EOF from the socketpair means the peer relay forwarded
                    //    a FIN. Shut down the TCP socket's write side.
                    // The reverse direction stays open until it reaches EOF.
                    launch {
                        copy(socketIn, pfdOut, onToPeer) { shutdownPfdWrite() }
                    }
                    launch {
                        copy(pfdIn, socketOut, onFromPeer) { shutdownSocketWrite() }
                    }
                }
            } catch (_: IOException) {
                // Normal close path.
            } finally {
                close()
            }
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        onBytes: (Int) -> Unit,
        onEof: () -> Unit,
    ) {
        val buffer = ByteArray(16 * 1024)
        while (!closed.get()) {
            val read = input.read(buffer)
            if (read < 0) {
                if (!closed.get()) onEof()
                return
            }
            output.write(buffer, 0, read)
            output.flush()
            onBytes(read)
        }
    }

    private fun shutdownPfdWrite() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_WR) }
    }

    private fun shutdownSocketWrite() {
        runCatching { socket.shutdownOutput() }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            runCatching { pfd.close() }
            job?.cancel()
        }
        if (notified.compareAndSet(false, true)) onClosed()
    }
}

class UdpServerRelay(
    private val scope: CoroutineScope,
    private val pfd: ParcelFileDescriptor,
    private val target: InetSocketAddress,
    private val onToPeer: (Int) -> Unit,
    private val onFromPeer: (Int) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val socket = DatagramSocket().apply {
        connect(target)
        soTimeout = 1000
    }
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val pfdIn = FileInputStream(pfd.fileDescriptor)
                val pfdOut = FileOutputStream(pfd.fileDescriptor)
                coroutineScope {
                    val toTarget = launch {
                        while (!closed.get()) {
                            val payload = UdpFrameCodec.decode(pfdIn) ?: break
                            socket.send(DatagramPacket(payload, payload.size))
                            onFromPeer(payload.size)
                        }
                    }
                    val fromTarget = launch {
                        val buffer = ByteArray(UdpFrameCodec.MAX_PAYLOAD)
                        while (!closed.get()) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                continue
                            } catch (_: IOException) {
                                break
                            }
                            val payload = packet.data.copyOf(packet.length)
                            UdpFrameCodec.encode(payload, pfdOut)
                            onToPeer(payload.size)
                        }
                    }
                    toTarget.invokeOnCompletion { close() }
                    fromTarget.invokeOnCompletion { close() }
                }
            } catch (_: IOException) {
                // Normal close path.
            } finally {
                close()
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            runCatching { pfd.close() }
            job?.cancel()
        }
        if (notified.compareAndSet(false, true)) onClosed()
    }
}

class UdpClientMapping(
    private val scope: CoroutineScope,
    private val listener: DatagramSocket,
    private val source: SocketAddress,
    private val pfd: ParcelFileDescriptor,
    private val timeoutMillis: Long,
    private val onToPeer: (Int) -> Unit,
    private val onFromPeer: (Int) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                coroutineScope {
                    val writer = launch {
                        for (payload in outgoing) {
                            UdpFrameCodec.encode(payload, output)
                            onToPeer(payload.size)
                        }
                    }
                    val reader = launch {
                        while (!closed.get()) {
                            val payload = UdpFrameCodec.decode(input) ?: break
                            listener.send(DatagramPacket(payload, payload.size, source))
                            lastActivity.set(System.currentTimeMillis())
                            onFromPeer(payload.size)
                        }
                    }
                    writer.invokeOnCompletion { close() }
                    reader.invokeOnCompletion { close() }
                }
            } catch (_: IOException) {
                // Normal close path.
            } finally {
                close()
            }
        }
    }

    fun send(payload: ByteArray): Boolean {
        if (closed.get()) return false
        lastActivity.set(System.currentTimeMillis())
        val result = outgoing.trySend(payload)
        if (result.isFailure) {
            close()
            return false
        }
        return true
    }

    fun isIdle(now: Long): Boolean = now - lastActivity.get() >= timeoutMillis

    fun close() {
        if (closed.compareAndSet(false, true)) {
            outgoing.close()
            runCatching { pfd.close() }
            job?.cancel()
        }
        if (notified.compareAndSet(false, true)) onClosed()
    }
}
