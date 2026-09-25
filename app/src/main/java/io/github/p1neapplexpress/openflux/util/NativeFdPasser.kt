package io.github.p1neapplexpress.openflux.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.FileDescriptor

/**
 * IPC-01: Passes the TUN FileDescriptor directly to the native Go daemon via a
 * Linux Abstract Domain Socket using SCM_RIGHTS.
 *
 * Eliminates intermediate disk files (sock_path), ancil_send_fd JNI calls (libsystem.so),
 * and the badvpn-tun2socks proxy bridge.
 */
object NativeFdPasser {
    private const val TAG = "NativeFdPasser"

    fun sendFd(
        socketName: String,
        fd: FileDescriptor,
        maxRetries: Int = 50,
        delayMs: Long = 200L,
    ): Boolean {
        if (!fd.valid()) {
            Logx.e(TAG, "Cannot pass invalid FileDescriptor")
            return false
        }
        val abstractName = socketName.removePrefix("@")
        // Initial grace period to allow the newly spawned daemon to start listening on abstract socket
        try {
            Thread.sleep(60L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        for (attempt in 1..maxRetries) {
            var socket: LocalSocket? = null
            try {
                socket = LocalSocket()
                socket.connect(LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.setFileDescriptorsForSend(arrayOf(fd))
                val os = socket.outputStream
                // Write 1 byte trigger to deliver SCM_RIGHTS ancillary data
                os.write(0x01)
                os.flush()

                // Read 1-byte ACK from Go daemon
                socket.soTimeout = 5000
                val ack = socket.inputStream.read()
                if (ack == 0x01) {
                    Logx.i(TAG, "TUN FD passed successfully to @$abstractName on attempt $attempt")
                    return true
                } else {
                    Logx.w(TAG, "Unexpected ack byte from daemon: $ack on attempt $attempt")
                }
            } catch (e: Exception) {
                if (attempt % 5 == 0 || attempt == 1) {
                    Logx.d(TAG, "Attempt $attempt connecting to @$abstractName: ${e.message}")
                }
            } finally {
                runCatching { socket?.close() }
            }

            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        Logx.e(TAG, "Failed to pass TUN FD to @$abstractName after $maxRetries attempts")
        return false
    }
}
