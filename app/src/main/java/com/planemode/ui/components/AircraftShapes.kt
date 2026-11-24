package com.planemode.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import com.planemode.model.SilhouetteKind

/**
 * Original, parameterised aircraft silhouettes drawn from simple geometry.
 * Top view: nose points up (−y). Side view: nose points right (+x).
 */
object AircraftShapes {
    private data class Params(val fuselageW: Float, val span: Float, val sweep: Float, val rootChord: Float, val tipChord: Float,
                              val wingY: Float, val engines: Int, val tailEngines: Boolean, val stabSpan: Float)

    private fun params(kind: SilhouetteKind) = when (kind) {
        SilhouetteKind.NARROWBODY -> Params(0.10f, 0.94f, 0.17f, 0.20f, 0.06f, 0.40f, 2, false, 0.36f)
        SilhouetteKind.WIDEBODY -> Params(0.12f, 1.00f, 0.21f, 0.22f, 0.05f, 0.38f, 2, false, 0.42f)
        SilhouetteKind.REGIONAL -> Params(0.09f, 0.86f, 0.14f, 0.18f, 0.06f, 0.42f, 2, false, 0.34f)
    }

    /** Top-view outline in a [size]×[size] box. */
    fun topView(kind: SilhouetteKind, size: Float): Path {
        val p = params(kind); val s = size; val cx = s / 2
        val path = PartBuilder()
        val fw = p.fuselageW * s
        // Fuselage with rounded nose and tapered tail
        path.addRoundRect(RoundRect(Rect(cx - fw / 2, 0.02f * s, cx + fw / 2, 0.92f * s), CornerRadius(fw / 2, fw * 0.9f)))
        path.moveTo(cx - fw / 2, 0.80f * s); path.lineTo(cx, 0.99f * s); path.lineTo(cx + fw / 2, 0.80f * s); path.close()
        // Wings
        val wy = p.wingY * s; val half = p.span * s / 2
        for (dir in listOf(-1f, 1f)) {
            path.moveTo(cx + dir * fw * 0.4f, wy)
            path.lineTo(cx + dir * half, wy + p.sweep * s)
            path.lineTo(cx + dir * half, wy + p.sweep * s + p.tipChord * s)
            path.lineTo(cx + dir * fw * 0.4f, wy + p.rootChord * s)
            path.close()
            // Horizontal stabiliser
            val ty = 0.80f * s; val th = p.stabSpan * s / 2
            path.moveTo(cx + dir * fw * 0.3f, ty)
            path.lineTo(cx + dir * th, ty + 0.09f * s)
            path.lineTo(cx + dir * th, ty + 0.13f * s)
            path.lineTo(cx + dir * fw * 0.3f, ty + 0.11f * s)
            path.close()
            // Underwing engine nacelle
            val ex = cx + dir * half * 0.36f
            val ey = wy + p.sweep * s * 0.36f - 0.05f * s
            val ew = fw * (if (kind == SilhouetteKind.WIDEBODY) 0.75f else 0.6f)
            path.addRoundRect(RoundRect(Rect(ex - ew / 2, ey, ex + ew / 2, ey + 0.13f * s), CornerRadius(ew / 2, ew / 2)))
        }
        return path.union()
    }

    /** Side-view silhouette in a [w]×[h] box. */
    fun sideView(kind: SilhouetteKind, w: Float, h: Float): Path {
        val path = PartBuilder()
        val fh = h * (if (kind == SilhouetteKind.WIDEBODY) 0.30f else 0.26f)
        val cy = h * 0.62f
        path.addRoundRect(RoundRect(Rect(w * 0.06f, cy - fh / 2, w * 0.98f, cy + fh / 2), CornerRadius(fh * 1.1f, fh / 2)))
        // Tail cone and fin
        path.moveTo(w * 0.06f, cy - fh / 2); path.lineTo(w * 0.0f, cy - fh * 0.35f); path.lineTo(w * 0.04f, cy + fh * 0.1f); path.close()
        path.moveTo(w * 0.04f, cy - fh / 2); path.lineTo(w * 0.02f, cy - fh * 2.4f); path.lineTo(w * 0.11f, cy - fh * 2.4f)
        path.lineTo(w * 0.24f, cy - fh / 2); path.close()
        // Wing (seen edge-on) and engine
        path.moveTo(w * 0.38f, cy + fh * 0.25f); path.lineTo(w * 0.62f, cy + fh * 0.1f); path.lineTo(w * 0.60f, cy + fh * 0.45f); path.lineTo(w * 0.36f, cy + fh * 0.45f); path.close()
        path.addRoundRect(RoundRect(Rect(w * 0.50f, cy + fh * 0.35f, w * 0.68f, cy + fh * 0.95f), CornerRadius(fh * 0.3f, fh * 0.3f)))
        // Horizontal stabiliser
        path.moveTo(w * 0.02f, cy - fh * 0.15f); path.lineTo(w * 0.16f, cy - fh * 0.05f); path.lineTo(w * 0.16f, cy + fh * 0.1f); path.lineTo(w * 0.02f, cy + fh * 0.05f); path.close()
        return path.union()
    }

    /** Bitmap of the top view for use as a map symbol (white body, dark outline, cyan glow). */
    fun mapIcon(kind: SilhouetteKind, sizePx: Int, bodyColor: Int, outlineColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = ACanvas(bmp)
        val inset = sizePx * 0.08f
        val path = topView(kind, sizePx - 2 * inset).asAndroidPath().apply { offset(inset, inset) }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5522D3EE; style = Paint.Style.STROKE; strokeWidth = sizePx * 0.07f; strokeJoin = Paint.Join.ROUND })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bodyColor; style = Paint.Style.FILL })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outlineColor; style = Paint.Style.STROKE; strokeWidth = sizePx * 0.018f; strokeJoin = Paint.Join.ROUND })
        return bmp
    }

    fun center(size: Float) = Offset(size / 2, size / 2)

    /** Collects closed sub-shapes and unions them so overlaps never produce holes. */
    private class PartBuilder {
        private val parts = ArrayList<Path>()
        private var cur: Path? = null
        fun moveTo(x: Float, y: Float) { cur = Path().also { it.moveTo(x, y); parts.add(it) } }
        fun lineTo(x: Float, y: Float) { cur!!.lineTo(x, y) }
        fun close() { cur?.close() }
        fun addRoundRect(r: RoundRect) { parts.add(Path().apply { addRoundRect(r) }) }
        fun union(): Path = parts.reduce { a, b -> Path.combine(PathOperation.Union, a, b) }
    }
}
