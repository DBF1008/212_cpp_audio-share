/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.service

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.util.Log
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.pb.Client.AudioFormat
import io.github.mkckr0.audio_share_app.pb.Client.FormatConstraints
import io.github.mkckr0.audio_share_app.pb.Client.NegotiateResponse
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.util.network.address
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NetClient(val context: Context) {

    private val tag = NetClient::class.simpleName

    private fun defaultScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("NetClientCoroutine") + CoroutineExceptionHandler { _, cause ->
            Log.d(tag, cause.stackTraceToString())
            _callback?.launch {
                onError(cause.message, cause)
            }
        }
    )

    private var _callback: Callback? = null
    private var _scope: CoroutineScope? = null
    private val scope: CoroutineScope get() = _scope!!

    private var _selectorManager: SelectorManager? = null
    private val selectorManager get() = _selectorManager!!
    private var _tcpSocket: Socket? = null
    private val tcpSocket get() = _tcpSocket!!
//    private var _udpSocket: ConnectedDatagramSocket? = null
    private var _udpSocket: BoundDatagramSocket? = null
    private val udpSocket get() = _udpSocket!!

    private var _heartbeatLastTick = TimeSource.Monotonic.markNow()
    private var _supportsNegotiate = true

    enum class CMD {
        CMD_NONE,
        CMD_GET_FORMAT,
        CMD_START_PLAY,
        CMD_HEARTBEAT,
        CMD_NEGOTIATE_FORMAT,
    }

    interface Callback {
        val scope: CoroutineScope
        suspend fun log(message: String)
        suspend fun onReceiveAudioFormat(format: AudioFormat)
        suspend fun onPlaybackStarted()
        suspend fun onReceiveAudioData(audioData: ByteBuffer)
        suspend fun onError(message: String?, cause: Throwable?)

        fun launch(block: suspend Callback.() -> Unit): Job {
            return scope.launch {
                block()
            }
        }
    }

    fun start(host: String, port: Int, callback: Callback) {
        Log.d(tag, "$host:$port")
        _scope = defaultScope()
        scope.launch {
            _callback = callback

            if (_selectorManager != null) {
                throw Exception("Repeat start")
            }

            _callback?.launch {
                log("${context.getString(R.string.label_connecting)} $host:$port")
            }
            _selectorManager = SelectorManager(Dispatchers.IO)

            try {
                _tcpSocket = withTimeout(3.seconds) {
                    aSocket(selectorManager).tcp().connect(host, port)
                }
            } catch (e: TimeoutCancellationException) {
                throw Exception(context.getString(R.string.label_timeout))
            } catch (e: UnresolvedAddressException) {
                throw Exception(context.getString(R.string.label_unresolved_address))
            }

            _callback?.launch {
                log("TCP connected")
            }

            val tcpReadChannel = tcpSocket.openReadChannel()
            val tcpWriteChannel = tcpSocket.openWriteChannel()

            // get format
            tcpWriteChannel.writeCMD(CMD.CMD_GET_FORMAT)
            var cmd = tcpReadChannel.readCMD()
            if (cmd != CMD.CMD_GET_FORMAT) {
                return@launch
            }
            var audioFormat = tcpReadChannel.readAudioFormat() ?: return@launch
            _callback?.launch {
                log("get format: ${audioFormat.encoding} ${audioFormat.channels}ch ${audioFormat.sampleRate}Hz")
            }

            // negotiate format for Android compatibility
            if (_supportsNegotiate) {
                try {
                    val constraints = getDeviceConstraints()
                    Log.d(tag, "negotiate constraints: $constraints")
                    tcpWriteChannel.writeCMD(CMD.CMD_NEGOTIATE_FORMAT)
                    tcpWriteChannel.writeFormatConstraints(constraints)
                    cmd = tcpReadChannel.readCMD()
                    if (cmd == CMD.CMD_NEGOTIATE_FORMAT) {
                        val response = tcpReadChannel.readNegotiateResponse()
                        if (response != null && response.status == NegotiateResponse.Status.SUCCESS) {
                            audioFormat = response.format
                            Log.d(tag, "negotiated format: $audioFormat")
                            _callback?.launch {
                                log("negotiated: ${audioFormat.encoding} ${audioFormat.channels}ch ${audioFormat.sampleRate}Hz")
                            }
                        } else {
                            Log.w(tag, "negotiation returned FAILED, using original format")
                            _callback?.launch {
                                log("negotiation failed, using original format")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Old server doesn't support CMD_NEGOTIATE_FORMAT.
                    // The server may have closed the connection.
                    Log.w(tag, "negotiate failed (old server?), disabling: ${e.message}")
                    _supportsNegotiate = false
                    throw e   // propagate to trigger retry
                }
            }

            _callback?.launch {
                onReceiveAudioFormat(audioFormat)
            }?.join()   // wait AudioTrack created

            _callback?.launch {
                log("get format success")
            }

            // start play
            tcpWriteChannel.writeCMD(CMD.CMD_START_PLAY)
            cmd = tcpReadChannel.readCMD()
            if (cmd != CMD.CMD_START_PLAY) {
                return@launch
            }
            val id = tcpReadChannel.readIntLE()
            if (id <= 0) {
                return@launch
            }

            _callback?.launch {
                onPlaybackStarted()
            }

//            _udpSocket = aSocket(selectorManager).udp()
//                .connect(InetSocketAddress(host, port))
            _udpSocket = aSocket(selectorManager).udp()
                .bind(InetSocketAddress(tcpSocket.localAddress.toJavaAddress().address, 0))

            // heartbeat loop
            scope.launch {
                _heartbeatLastTick = TimeSource.Monotonic.markNow()
                while (true) {
                    Log.d(tag, "check heartbeat")
                    if (TimeSource.Monotonic.markNow() - _heartbeatLastTick > 5.seconds) {
                        throw Exception("heartbeat timeout")
                    }
                    delay(3.seconds)
                }
            }
            scope.launch {
                while (true) {
                    cmd = tcpReadChannel.readCMD()
                    if (cmd == CMD.CMD_HEARTBEAT) {
                        Log.d(tag, "receive heartbeat")
                        _heartbeatLastTick = TimeSource.Monotonic.markNow()
                        tcpWriteChannel.writeCMD(CMD.CMD_HEARTBEAT)
                    }
                }
            }

            // audio data read loop
            scope.launch {
                udpSocket.writeIntLE(id, InetSocketAddress(host, port))
//                udpSocket.writeIntLE(id)
                while (true) {
                    val buf = udpSocket.readByteBuffer()
                    _callback?.launch {
                        onReceiveAudioData(buf.order(ByteOrder.LITTLE_ENDIAN))
                    }
                }
            }
        }
    }

    fun stop() {
        Log.d(tag, "stop")
        _callback?.scope?.cancel()
        _callback = null
        _scope?.cancel()
        _scope = null
        _selectorManager?.close()
        _selectorManager = null
        _udpSocket?.close()
        _tcpSocket?.close()
    }

    companion object {
        /**
         * Build FormatConstraints based on the current device's playback capabilities.
         * Encodings are ordered by preference (best quality first).
         */
        fun getDeviceConstraints(): FormatConstraints {
            val builder = FormatConstraints.newBuilder()

            // Preferred encodings ordered by quality.
            // 24/32-bit integer PCM require API 31 (S).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_FLOAT)
                builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_32BIT)
                builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_24BIT)
            } else {
                builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_FLOAT)
            }
            // 16-bit and 8-bit are universally supported on all API levels.
            builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_16BIT)
            builder.addPreferredEncodings(AudioFormat.Encoding.ENCODING_PCM_8BIT)

            // Most phones have stereo output; cap at 2 channels.
            builder.maxChannels = 2

            builder.apiLevel = Build.VERSION.SDK_INT

            return builder.build()
        }

        /**
         * Given a server format and device constraints, return a format that the
         * device can actually play. This mirrors the server-side logic and is
         * used as a local fallback when negotiation is unavailable.
         */
        fun pickCompatibleFormat(
            serverFormat: AudioFormat,
            constraints: FormatConstraints
        ): AudioFormat {
            // If the device already supports the server's encoding, keep it.
            if (constraints.preferredEncodingsList.contains(serverFormat.encoding)) {
                val builder = serverFormat.toBuilder()
                // Still clamp channels if needed.
                if (constraints.hasMaxChannels() && serverFormat.channels > constraints.maxChannels) {
                    builder.channels = constraints.maxChannels
                }
                return builder.build()
            }
            // Fall back to the device's first preferred encoding.
            val fallbackEncoding = if (constraints.preferredEncodingsCount > 0) {
                constraints.getPreferredEncodings(0)
            } else {
                AudioFormat.Encoding.ENCODING_PCM_16BIT
            }
            val maxChannels = if (constraints.hasMaxChannels() && constraints.maxChannels > 0) {
                minOf(serverFormat.channels, constraints.maxChannels)
            } else {
                serverFormat.channels
            }
            return serverFormat.toBuilder()
                .setEncoding(fallbackEncoding)
                .setChannels(maxChannels)
                .build()
        }
    }
}