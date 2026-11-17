package com.empatica.e4companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.empatica.e4companion.ui.theme.E4Colors

@Composable
fun BvpSparkline(
    data: FloatArray,
    modifier: Modifier = Modifier
) {
    val lineColor = E4Colors.ChartLine
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val textSecondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(modifier = modifier) {
        Text(
            "BVP Waveform (64 Hz)",
            color = textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            if (data.size < 2) return@Canvas

            val w = size.width
            val h = size.height

            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
            }

            drawLine(
                gridColor.copy(alpha = 0.5f),
                Offset(0f, h / 2f),
                Offset(w, h / 2f),
                strokeWidth = 1f
            )

            val min = data.min()
            val max = data.max()
            val range = (max - min).coerceAtLeast(0.001f)
            val padding = range * 0.1f
            val yMin = min - padding
            val yRange = range + 2 * padding

            val path = Path()
            val stepX = w / (data.size - 1).toFloat()

            for (i in data.indices) {
                val x = i * stepX
                val y = h - ((data[i] - yMin) / yRange) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(path, lineColor, style = Stroke(width = 2f))
        }
    }
}