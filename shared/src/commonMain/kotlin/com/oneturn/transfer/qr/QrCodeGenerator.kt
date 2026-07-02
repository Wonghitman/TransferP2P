package com.oneturn.transfer.qr

import qrcode.QRCode

object QrCodeGenerator {
    fun encodeMatrix(content: String): QrMatrix {
        val raw = QRCode(content).rawData
        val dimension = raw.size
        val cells = BooleanArray(dimension * dimension)
        var index = 0
        raw.forEach { row ->
            row.forEach { cell ->
                cells[index++] = cell.dark
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
