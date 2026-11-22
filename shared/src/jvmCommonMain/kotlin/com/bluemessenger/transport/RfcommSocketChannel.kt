package com.bluemessenger.transport

import com.bluemessenger.domain.concurrency.CoroutineDispatchers
import com.bluemessenger.domain.error.DomainException
import com.bluemessenger.domain.error.TransportDisconnectedError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.domain.ports.logging.AppLogger
import com.bluemessenger.domain.ports.transport.model.Packet
import com.bluemessenger.transport.constants.RfcommChannelConstants
import com.bluemessenger.transport.protocol.FrameDecoder
import com.bluemessenger.transport.protocol.FrameEncoder
import com.bluemessenger.transport.protocol.PacketCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional RFCOMM stream channel wrapping raw [InputStream], [OutputStream], and [Closeable]
 * socket descriptors with length-prefixed CRC32 framing and binary [Packet] serialization.
 *
 * Thread safety: Outbound [send] calls are serialized by an internal [Mutex] and executed on
 * [CoroutineDispatchers.io]. Inbound reads via [incomingPackets] must be collected sequentially by
 * a single reader coroutine on [CoroutineDispatchers.io]. [close] is idempotent and thread-safe.
 *
 * Failure behavior: Oversized outbound packets fail fast before I/O. Corrupted inbound frames or
 * malformed packets are logged with `logger.warn` and dropped without terminating a healthy stream.
 * Socket I/O errors or coroutine cancellations deterministically close all underlying descriptors
 * and rethrow [CancellationException] when cancelled.
 */
class RfcommSocketChannel(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val closeable: Closeable,
    private val dispatchers: CoroutineDispatchers,
    private val logger: AppLogger,
    private val readBufferSize: Int = RfcommChannelConstants.RFCOMM_READ_BUFFER_SIZE_BYTES,
) : Closeable {
    private val writeMutex: Mutex = Mutex()
    private val closed: AtomicBoolean = AtomicBoolean(false)

    init {
        require(readBufferSize > 0) {
            "RFCOMM read buffer size must be positive, got $readBufferSize."
        }
    }

    /**
     * Returns whether this channel and its underlying descriptors have been closed.
     */
    val isClosed: Boolean
        get() = closed.get()

    /**
     * Serializes [packet], frames it with CRC32 integrity metadata, and writes it to the RFCOMM
     * output stream on [CoroutineDispatchers.io].
     */
    suspend fun send(packet: Packet): Result<Unit> {
        if (closed.get()) {
            return TransportDisconnectedError("RFCOMM socket channel is closed.").asFailure()
        }
        val packetBytes = PacketCodec.encode(packet)
        return FrameEncoder.encode(packetBytes).fold(
            onSuccess = { frameBytes -> writeEncodedFrame(frameBytes) },
            onFailure = { error -> Result.failure(error) },
        )
    }

    private suspend fun writeEncodedFrame(frameBytes: ByteArray): Result<Unit> =
        writeMutex.withLock {
            withContext(dispatchers.io) {
                if (closed.get()) {
                    TransportDisconnectedError("RFCOMM socket channel is closed.").asFailure()
                } else {
                    try {
                        outputStream.write(frameBytes)
                        outputStream.flush()
                        Result.success(Unit)
                    } catch (cancellation: CancellationException) {
                        close()
                        throw cancellation
                    } catch (exception: IOException) {
                        logger.warn(
                            tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                            message = "Failed to write frame to RFCOMM stream; closing channel.",
                            throwable = exception,
                            context = mapOf("frameBytes" to frameBytes.size.toString()),
                        )
                        close()
                        TransportDisconnectedError(
                            "RFCOMM stream write failed: ${exception.message ?: "connection closed"}.",
                        ).asFailure(exception)
                    }
                }
            }
        }

    /**
     * Streams decoded [Packet] values read from the RFCOMM input stream on
     * [CoroutineDispatchers.io], closing the channel deterministically on completion, error, or
     * cancellation.
     */
    fun incomingPackets(): Flow<Packet> =
        flow {
            coroutineScope {
                val closeWatcher =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            close()
                        }
                    }
                val readBuffer = ByteArray(readBufferSize)
                val frameDecoder = FrameDecoder()
                try {
                    readStreamLoop(readBuffer = readBuffer, frameDecoder = frameDecoder)
                    currentCoroutineContext().ensureActive()
                } catch (cancellation: CancellationException) {
                    close()
                    throw cancellation
                } finally {
                    closeWatcher.cancel()
                    frameDecoder.reset()
                    close()
                }
            }
        }.flowOn(dispatchers.io)

    /**
     * Deterministically closes the input stream, output stream, and underlying socket descriptor.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        closeQuietly(resourceName = "inputStream", resource = inputStream)
        closeQuietly(resourceName = "outputStream", resource = outputStream)
        closeQuietly(resourceName = "socket", resource = closeable)
    }

    private suspend fun FlowCollector<Packet>.readStreamLoop(
        readBuffer: ByteArray,
        frameDecoder: FrameDecoder,
    ) {
        var streamOpen = true
        while (streamOpen && currentCoroutineContext().isActive && !closed.get()) {
            val bytesRead = readChunkOrNull(readBuffer)
            when {
                bytesRead == null -> streamOpen = false
                bytesRead == RfcommChannelConstants.EOF_MARKER -> {
                    logger.info(
                        tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                        message = "RFCOMM peer closed stream (EOF).",
                    )
                    streamOpen = false
                }
                bytesRead > 0 ->
                    decodeAndEmitChunk(
                        chunk = readBuffer.copyOf(bytesRead),
                        frameDecoder = frameDecoder,
                    )
            }
        }
    }

    private suspend fun readChunkOrNull(readBuffer: ByteArray): Int? =
        try {
            runInterruptible {
                inputStream.read(readBuffer)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: IOException) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) {
                null
            } else {
                logger.warn(
                    tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                    message = "RFCOMM stream read failed; closing channel.",
                    throwable = exception,
                )
                close()
                throw DomainException(
                    TransportDisconnectedError(
                        "RFCOMM stream read failed: ${exception.message ?: "connection closed"}.",
                    ),
                    exception,
                )
            }
        }

    private suspend fun FlowCollector<Packet>.decodeAndEmitChunk(
        chunk: ByteArray,
        frameDecoder: FrameDecoder,
    ) {
        frameDecoder.feed(chunk).fold(
            onSuccess = { payloads ->
                for (payload in payloads) {
                    decodeAndEmitPacket(payload)
                }
            },
            onFailure = { error ->
                logger.warn(
                    tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                    message = "Discarded corrupted RFCOMM frame while keeping stream active.",
                    throwable = error,
                    context = mapOf("chunkBytes" to chunk.size.toString()),
                )
            },
        )
    }

    private suspend fun FlowCollector<Packet>.decodeAndEmitPacket(payload: ByteArray) {
        PacketCodec.decode(payload).fold(
            onSuccess = { packet -> emit(packet) },
            onFailure = { error ->
                logger.warn(
                    tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                    message = "Discarded malformed RFCOMM packet while keeping stream active.",
                    throwable = error,
                    context = mapOf("payloadBytes" to payload.size.toString()),
                )
            },
        )
    }

    private fun closeQuietly(
        resourceName: String,
        resource: Closeable,
    ) {
        try {
            resource.close()
        } catch (exception: IOException) {
            logger.debug(
                tag = RfcommChannelConstants.TAG_RFCOMM_CHANNEL,
                message = "Ignored IOException while closing RFCOMM resource.",
                context =
                    mapOf(
                        "resource" to resourceName,
                        "reason" to (exception.message ?: "unknown"),
                    ),
            )
        }
    }
}
