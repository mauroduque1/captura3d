package co.northing.n3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Radar de cobertura: 24 direcciones × 3 alturas (anillo interior = mirando abajo,
 * medio = al frente, exterior = arriba). Gris = falta, ámbar = 1 foto, verde = cubierto.
 */
class Cobertura(ctx: Context) : View(ctx) {
    private var datos = IntArray(Captura.SECTORES * 3)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }

    fun actualizar(c: IntArray) { datos = c; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
        canvas.drawCircle(cx, cy, r, fondo)
        val ancho = r * 0.26f
        p.strokeWidth = ancho * 0.85f
        val paso = 360f / Captura.SECTORES
        for (anillo in 0..2) {
            val rad = r * 0.28f + ancho * anillo + ancho / 2
            val caja = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
            for (s in 0 until Captura.SECTORES) {
                val n = datos[anillo * Captura.SECTORES + s]
                p.color = when { n == 0 -> 0x55FFFFFF; n == 1 -> 0xFFE0A030.toInt(); else -> 0xFF2FB37A.toInt() }
                canvas.drawArc(caja, -90f + s * paso + 1f, paso - 2f, false, p)
            }
        }
    }
}
