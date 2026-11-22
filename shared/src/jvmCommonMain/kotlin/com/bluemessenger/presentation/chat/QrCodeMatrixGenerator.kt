package com.bluemessenger.presentation.chat

import com.bluemessenger.domain.error.InvalidInputError
import com.bluemessenger.domain.error.asFailure
import com.bluemessenger.presentation.constants.PresentationConstants
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * JVM and Android actual implementation of [QrCodeMatrixGenerator] backed by ZXing Core.
 */
internal actual object QrCodeMatrixGenerator {
    actual fun encode(content: String): Result<QrCodeMatrix> {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return InvalidInputError("QR code content must not be blank.").asFailure()
        }
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to PresentationConstants.QR_MATRIX_MARGIN,
            )
        return try {
            val bitMatrix =
                QRCodeWriter().encode(
                    trimmed,
                    BarcodeFormat.QR_CODE,
                    PresentationConstants.QR_REQUEST_DIMENSION_PX,
                    PresentationConstants.QR_REQUEST_DIMENSION_PX,
                    hints,
                )
            val size = bitMatrix.width
            if (size <= 0 || size != bitMatrix.height) {
                Result.failure(
                    IllegalStateException(
                        "ZXing produced non-square QR matrix ${bitMatrix.width}x${bitMatrix.height}.",
                    ),
                )
            } else {
                val darkModules =
                    List(size * size) { index ->
                        val row = index / size
                        val col = index % size
                        bitMatrix.get(col, row)
                    }
                Result.success(QrCodeMatrix(size = size, darkModules = darkModules))
            }
        } catch (exception: WriterException) {
            Result.failure(IllegalStateException("Failed to encode Safety Number QR code.", exception))
        } catch (exception: IllegalArgumentException) {
            InvalidInputError(exception.message ?: "Invalid QR code content.").asFailure(exception)
        }
    }
}
