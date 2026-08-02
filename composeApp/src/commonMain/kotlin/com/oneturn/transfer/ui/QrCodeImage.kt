package com.oneturn.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.qr.QrMatrix

@Composable
fun QrCodeImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    darkColor: Color = Color(0xFF000000),
    lightColor: Color = Color(0xFFFFFFFF),
) {
    Canvas(
        modifier = modifier
            .size(size)
            .background(lightColor),
    ) {
        val cellSize = size.toPx() / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.cellAt(x, y)) {
                    drawRect(
                        color = darkColor,
                        topLeft = Offset(x * cellSize, y * cellSize),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
        }
    }
}
