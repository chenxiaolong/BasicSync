/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

class QrScannerAnalyzer(private val listener: Listener) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.ALSO_INVERTED to true,
        ))
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            if (image.format != ImageFormat.YUV_420_888) {
                return
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rawData = ByteArray(buffer.remaining())
            buffer.get(rawData)
            buffer.rewind()

            val source = PlanarYUVLuminanceSource(
                rawData,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            reader.reset()
            try {
                reader.decodeWithState(binaryBitmap).text?.let {
                    listener.onQrScanned(it)
                }
            } catch (_: ReaderException) {}
        }
    }

    interface Listener {
        fun onQrScanned(data: String)
    }
}
