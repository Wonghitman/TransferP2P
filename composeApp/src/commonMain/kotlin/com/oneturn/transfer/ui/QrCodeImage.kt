package com.oneturn.transfer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneturn.transfer.qr.QrMatrix

@Composable
fun QrCodeImage(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White,
) {
    Canvas(modifier = modifier.size(200.dp)) {
        val cellSize = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                drawRect(
                    color = if (matrix.cellAt(x, y)) darkColor else lightColor,
                    topLeft = Offset(x * cellSize, y * cellSize),
                    size = Size(cellSize, cellSize),
                )
            }
        }
    }
}
