/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.daemon

import android.net.LocalSocket
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

private fun InputStream.readFully(buf: ByteArray, offset: Int, length: Int) {
    var curOffset = offset
    var curLength = length

    while (curLength > 0) {
        val n = read(buf, curOffset, curLength)
        if (n < 0) {
            throw EOFException()
        }

        curOffset += n
        curLength -= n
    }
}

private fun InputStream.readByte(): Byte {
    val result = read()
    if (result < 0) {
        throw EOFException()
    }

    return result.toByte()
}

private fun InputStream.readShortLe(): Short {
    val buf = ByteArray(2)
    readFully(buf, 0, 2)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short
}

private fun InputStream.readData(): ByteArray {
    val size = readShortLe().toInt()
    val buf = ByteArray(size)
    readFully(buf, 0, size)
    return buf
}

private fun OutputStream.writeByte(value: Byte) {
    write(value.toInt())
}

private fun OutputStream.writeShortLe(value: Short) {
    write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array())
}

private fun OutputStream.writeData(buf: ByteArray) {
    if (buf.size > Short.MAX_VALUE) {
        throw IllegalArgumentException("Data length exceeds u16 bounds")
    }

    writeShortLe(buf.size.toShort())
    write(buf)
}

private fun LocalSocket.receiveFds(size: Int): Array<FileDescriptor> {
    inputStream.readByte()

    val fds = ancillaryFileDescriptors
    if (fds.size != size) {
        throw IOException("Expected $size fds, but received ${fds.size}")
    }

    return fds
}

private fun LocalSocket.sendFds(fds: Array<FileDescriptor>) {
    setFileDescriptorsForSend(fds)
    outputStream.writeByte(0)
}

private const val PROTOCOL_VERSION: Byte = 1

fun negotiateProtocol(stream: LocalSocket) {
    stream.outputStream.writeByte(PROTOCOL_VERSION)

    when (val ack = stream.inputStream.readByte()) {
        1.toByte() -> {}
        0.toByte() -> throw IOException("Daemon does not support protocol version: $PROTOCOL_VERSION")
        else -> throw IOException("Invalid protocol version acknowledgement: $ack")
    }
}

interface MessageId {
    val id: Byte
}

interface FromSocket<T> {
    fun fromSocket(stream: LocalSocket): T
}

interface ToSocket {
    fun toSocket(stream: LocalSocket)
}

sealed interface RequestMessage

sealed interface ResponseMessage

data class ErrorResponse(val message: String) : ResponseMessage, ToSocket {
    companion object : MessageId, FromSocket<ErrorResponse> {
        override val id: Byte = 1

        override fun fromSocket(stream: LocalSocket): ErrorResponse {
            val data = stream.inputStream.readData()

            return ErrorResponse(String(data))
        }
    }

    override fun toSocket(stream: LocalSocket) {
        stream.outputStream.writeData(message.toByteArray())
    }
}

object GetFunctionsRequest : RequestMessage, MessageId, FromSocket<GetFunctionsRequest>, ToSocket {
    override val id: Byte = 2

    override fun fromSocket(stream: LocalSocket): GetFunctionsRequest = this

    override fun toSocket(stream: LocalSocket) {}
}

data class GetFunctionsResponse(val functions: Map<String, String>) : ResponseMessage, ToSocket {
    companion object : MessageId, FromSocket<GetFunctionsResponse> {
        override val id: Byte = 3

        override fun fromSocket(stream: LocalSocket): GetFunctionsResponse {
            val numFunctions = stream.inputStream.readByte().toInt()
            val functions = TreeMap<String, String>()

            for (i in 0 until numFunctions) {
                val config = stream.inputStream.readData()
                val function = stream.inputStream.readData()
                functions[String(config)] = String(function)
            }

            return GetFunctionsResponse(functions)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        if (functions.size > Byte.MAX_VALUE) {
            throw IllegalArgumentException("Number of functions exceeds u8 bounds")
        }

        stream.outputStream.writeByte(functions.size.toByte())
        for ((config, function) in functions) {
            stream.outputStream.writeData(config.toByteArray())
            stream.outputStream.writeData(function.toByteArray())
        }
    }
}

data class MassStorageDevice(val fd: FileDescriptor, val cdrom: Boolean, val ro: Boolean) :
    ToSocket {
    companion object : FromSocket<MassStorageDevice> {
        override fun fromSocket(stream: LocalSocket): MassStorageDevice {
            val fd = stream.receiveFds(1)[0]
            val cdrom = stream.inputStream.readByte().toInt() != 0
            val ro = stream.inputStream.readByte().toInt() != 0

            return MassStorageDevice(fd, cdrom, ro)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        stream.sendFds(arrayOf(fd))
        stream.outputStream.writeByte(if (cdrom) { 1 } else { 0 })
        stream.outputStream.writeByte(if (ro) { 1 } else { 0 })
    }
}

data class SetMassStorageRequest(val devices: List<MassStorageDevice>) : RequestMessage, ToSocket {
    companion object : MessageId, FromSocket<SetMassStorageRequest> {
        override val id: Byte = 4

        override fun fromSocket(stream: LocalSocket): SetMassStorageRequest {
            val numDevices = stream.inputStream.readByte().toInt()
            val devices = mutableListOf<MassStorageDevice>()

            for (i in 0 until numDevices) {
                val device = MassStorageDevice.fromSocket(stream)
                devices.add(device)
            }

            return SetMassStorageRequest(devices)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        if (devices.size > Byte.MAX_VALUE) {
            throw IllegalArgumentException("Number of devices exceeds u8 bounds")
        }

        stream.outputStream.writeByte(devices.size.toByte())
        for (device in devices) {
            device.toSocket(stream)
        }
    }
}

object SetMassStorageResponse : ResponseMessage, MessageId, FromSocket<SetMassStorageResponse>,
    ToSocket {
    override val id: Byte = 5

    override fun fromSocket(stream: LocalSocket): SetMassStorageResponse = this

    override fun toSocket(stream: LocalSocket) {}
}

data class ActiveMassStorageDevice(val file: String, val cdrom: Boolean, val ro: Boolean) :
    ToSocket {
    companion object : FromSocket<ActiveMassStorageDevice> {
        override fun fromSocket(stream: LocalSocket): ActiveMassStorageDevice {
            val file = stream.inputStream.readData()
            val cdrom = stream.inputStream.readByte().toInt() != 0
            val ro = stream.inputStream.readByte().toInt() != 0

            return ActiveMassStorageDevice(String(file), cdrom, ro)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        stream.outputStream.writeData(file.toByteArray())
        stream.outputStream.writeByte(if (cdrom) { 1 } else { 0 })
        stream.outputStream.writeByte(if (ro) { 1 } else { 0 })
    }
}

object GetMassStorageRequest : RequestMessage, MessageId, FromSocket<GetMassStorageRequest>,
    ToSocket {
    override val id: Byte = 6

    override fun fromSocket(stream: LocalSocket): GetMassStorageRequest = this

    override fun toSocket(stream: LocalSocket) {}
}

data class GetMassStorageResponse(val devices: List<ActiveMassStorageDevice>) : ResponseMessage,
    ToSocket {
    companion object : MessageId, FromSocket<GetMassStorageResponse> {
        override val id: Byte = 7

        override fun fromSocket(stream: LocalSocket): GetMassStorageResponse {
            val numDevices = stream.inputStream.readByte().toInt()
            val devices = mutableListOf<ActiveMassStorageDevice>()

            for (i in 0 until numDevices) {
                val device = ActiveMassStorageDevice.fromSocket(stream)
                devices.add(device)
            }

            return GetMassStorageResponse(devices)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        if (devices.size > Byte.MAX_VALUE) {
            throw IllegalArgumentException("Number of devices exceeds u8 bounds")
        }

        stream.outputStream.writeByte(devices.size.toByte())
        for (device in devices) {
            device.toSocket(stream)
        }
    }
}

data class Request(val message: RequestMessage) : ToSocket {
    companion object : FromSocket<Request> {
        override fun fromSocket(stream: LocalSocket): Request {
            val message = when (val id = stream.inputStream.readByte()) {
                GetFunctionsRequest.id -> GetFunctionsRequest.fromSocket(stream)
                SetMassStorageRequest.id -> SetMassStorageRequest.fromSocket(stream)
                GetMassStorageRequest.id -> GetMassStorageRequest.fromSocket(stream)
                else -> throw IOException("Invalid message ID: $id")
            }

            return Request(message)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        val id = when (message) {
            is GetFunctionsRequest -> GetFunctionsRequest.id
            is SetMassStorageRequest -> SetMassStorageRequest.id
            is GetMassStorageRequest -> GetMassStorageRequest.id
        }

        stream.outputStream.writeByte(id)

        when (message) {
            is GetFunctionsRequest -> message.toSocket(stream)
            is SetMassStorageRequest -> message.toSocket(stream)
            is GetMassStorageRequest -> message.toSocket(stream)
        }
    }
}

data class Response(val message: ResponseMessage) : ToSocket {
    companion object : FromSocket<Response> {
        override fun fromSocket(stream: LocalSocket): Response {
            val message = when (val id = stream.inputStream.readByte()) {
                ErrorResponse.id -> ErrorResponse.fromSocket(stream)
                GetFunctionsResponse.id -> GetFunctionsResponse.fromSocket(stream)
                SetMassStorageResponse.id -> SetMassStorageResponse.fromSocket(stream)
                GetMassStorageResponse.id -> GetMassStorageResponse.fromSocket(stream)
                else -> throw IOException("Invalid message ID: $id")
            }

            return Response(message)
        }
    }

    override fun toSocket(stream: LocalSocket) {
        val id = when (message) {
            is ErrorResponse -> ErrorResponse.id
            is GetFunctionsResponse -> GetFunctionsResponse.id
            is SetMassStorageResponse -> SetMassStorageResponse.id
            is GetMassStorageResponse -> GetMassStorageResponse.id
        }

        stream.outputStream.writeByte(id)

        when (message) {
            is ErrorResponse -> message.toSocket(stream)
            is GetFunctionsResponse -> message.toSocket(stream)
            is SetMassStorageResponse -> message.toSocket(stream)
            is GetMassStorageResponse -> message.toSocket(stream)
        }
    }
}
