/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.daemon

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.chiller3.msd.settings.DeviceInfo
import com.chiller3.msd.settings.DeviceType
import java.io.Closeable
import java.io.File
import java.io.IOException

class ClientException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class Client : Closeable {
    private val socket = LocalSocket()

    init {
        try {
            socket.connect(LocalSocketAddress("msdd", LocalSocketAddress.Namespace.ABSTRACT))

            negotiateProtocol(socket)
        } catch (e: IOException) {
            socket.close()
            throw IOException("Failed to connect to MSD daemon", e)
        }
    }

    override fun close() {
        socket.close()
    }

    fun getFunctions(): Map<String, String> {
        val request = Request(GetFunctionsRequest)
        request.toSocket(socket)

        val response = Response.fromSocket(socket)
        when (response.message) {
            is ErrorResponse -> throw ClientException(response.message.message)
            is GetFunctionsResponse -> return response.message.functions
            else -> throw IOException("Invalid response: ${response.message}")
        }
    }

    fun setMassStorage(context: Context, devices: List<DeviceInfo>) {
        val openFds = mutableListOf<ParcelFileDescriptor>()

        try {
            for (device in devices) {
                try {
                    val fd = context.contentResolver.openFileDescriptor(device.uri, "r")
                        ?: throw IOException("File provider recently crashed for ${device.uri}")
                    openFds.add(fd)
                } catch (e: Exception) {
                    throw IOException("Failed to open image: ${device.uri}", e)
                }
            }

            val request = Request(SetMassStorageRequest(devices.zip(openFds) { device, fd ->
                MassStorageDevice(
                    fd.fileDescriptor,
                    device.type == DeviceType.CDROM,
                    device.type != DeviceType.DISK_RW,
                )
            }))
            request.toSocket(socket)

            val response = Response.fromSocket(socket)
            when (response.message) {
                is ErrorResponse -> throw ClientException(response.message.message)
                is SetMassStorageResponse -> {}
                else -> throw IOException("Invalid response: ${response.message}")
            }
        } finally {
            for (fd in openFds) {
                fd.close()
            }
        }
    }

    fun getMassStorage(): List<DeviceInfo> {
        val request = Request(GetMassStorageRequest)
        request.toSocket(socket)

        val response = Response.fromSocket(socket)
        when (response.message) {
            is ErrorResponse -> throw ClientException(response.message.message)
            is GetMassStorageResponse -> return response.message.devices.map {
                val type = if (it.cdrom) {
                    DeviceType.CDROM
                } else if (it.ro) {
                    DeviceType.DISK_RO
                } else {
                    DeviceType.DISK_RW
                }

                DeviceInfo(Uri.fromFile(File(it.file)), type)
            }
            else -> throw IOException("Invalid response: ${response.message}")
        }
    }
}
