package com.oneturn.transfer.qr

import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel

object QrCodeGenerator {
    private const val QUIET_ZONE_MODULES = 4

    fun encodeMatrix(content: String): QrMatrix {
        val raw = QRCode(
            data = content,
            errorCorrectionLevel = ErrorCorrectionLevel.HIGH,
        ).rawData
        val dataDim = raw.size
        val qz = QUIET_ZONE_MODULES
        val dimension = dataDim + qz * 2
        val cells = BooleanArray(dimension * dimension)
        raw.forEachIndexed { row, rowData ->
            rowData.forEachIndexed { col, cell ->
                if (cell.dark) {
                    val y = row + qz
                    val x = col + qz
                    cells[y * dimension + x] = true
                }
            }
        }
        return QrMatrix(
            width = dimension,
            height = dimension,
            cells = cells.toList(),
        )
    }
}

data class QrMatrix(
    val width: Int,
    val height: Int,
    val cells: List<Boolean>,
) {
    fun cellAt(x: Int, y: Int): Boolean = cells.getOrNull(y * width + x) ?: false
}
