package co.northing.n3d

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Lógica de captura (corre en el hilo de OpenGL, una vez por cuadro):
 * decide cuándo tomar foto según cuánto te moviste, la velocidad y la nitidez,
 * guarda JPEG + pose de ARCore y lleva el mapa de cobertura.
 */
class Captura(val dir: File, private val avisar: (Aviso) -> Unit) {

    data class Toma(val nombre: String, val matriz: FloatArray)
    data class Aviso(val texto: String, val color: Int, val fotos: Int, val cobertura: IntArray, val flash: Boolean = false)

    val tomas = mutableListOf<Toma>()
    val cobertura = IntArray(SECTORES * 3)
    var intrinsecos: FloatArray? = null   // fx, fy, cx, cy, w, h
        private set
    @Volatile var auto = true
    @Volatile var bloqueada = false
    @Volatile var pedirFoto = false
    val pendientes = AtomicInteger(0)

    private val io = Executors.newSingleThreadExecutor()
    private val nitideces = ArrayDeque<Double>()
    private var ultima: Pose? = null
    private var previa: Pose? = null
    private var previaT = 0L
    private var ultimoAviso = 0L

    fun reiniciar() {
        synchronized(tomas) { tomas.clear() }
        cobertura.fill(0); nitideces.clear(); ultima = null; intrinsecos = null
        dir.listFiles()?.forEach { it.delete() }
    }

    fun procesar(frame: Frame) {
        if (bloqueada) return
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) {
            previa = null
            aviso(motivo(cam.trackingFailureReason), ROJO); return
        }
        val pose = cam.pose
        val t = frame.timestamp
        var vel = 0f; var velAng = 0f
        previa?.let { p ->
            val dt = (t - previaT) / 1e9f
            if (dt > 0f) { vel = distancia(p, pose) / dt; velAng = angulo(p, pose) / dt }
        }
        previa = pose; previaT = t

        val u = ultima
        val d = if (u == null) 99f else distancia(u, pose)
        val a = if (u == null) 99f else angulo(u, pose)
        val manual = pedirFoto
        pedirFoto = false
        when {
            tomas.size >= MAX_FOTOS -> aviso("Máximo de fotos: toca Enviar", VERDE)
            vel > 0.6f || velAng > 70f -> aviso("Más despacio", ROJO)
            manual -> tomar(frame, pose)
            d < MIN_PASO && a < MIN_GIRO -> aviso("Da un paso lateral (20–30 cm)", GRIS)
            auto && pendientes.get() < 3 -> tomar(frame, pose)
            else -> aviso("¡Ahora! Toma la foto", VERDE)
        }
    }

    private fun tomar(frame: Frame, pose: Pose) {
        val img: Image = try { frame.acquireCameraImage() } catch (e: Exception) { return }
        try {
            val n = nitidez(img)
            if (nitideces.size >= 5 && n < 0.55 * nitideces.sorted()[nitideces.size / 2]) {
                aviso("Imagen movida: quédate quieto un instante", ROJO); return
            }
            nitideces.addLast(n); if (nitideces.size > 25) nitideces.removeFirst()
            if (intrinsecos == null) {
                val ci = frame.camera.imageIntrinsics
                val f = ci.focalLength; val pp = ci.principalPoint; val dim = ci.imageDimensions
                intrinsecos = floatArrayOf(f[0], f[1], pp[0], pp[1], dim[0].toFloat(), dim[1].toFloat())
            }
            val w = img.width; val h = img.height
            val nv21 = aNv21(img)
            val m = FloatArray(16); pose.toMatrix(m, 0)
            val nombre = "img_%04d.jpg".format(tomas.size)
            synchronized(tomas) { tomas.add(Toma(nombre, m)) }
            ultima = pose
            marcarCobertura(pose)
            pendientes.incrementAndGet()
            io.execute {
                try {
                    FileOutputStream(File(dir, nombre)).use {
                        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, it)
                    }
                } finally { pendientes.decrementAndGet() }
            }
            aviso("Foto ${tomas.size}", VERDE, flash = true, forzar = true)
        } finally { img.close() }
    }

    /** transforms.json estilo nerfstudio (convención OpenGL, igual que ARCore). Unidades: metros. */
    fun transforms(): String {
        val i = intrinsecos ?: return "{}"
        val sb = StringBuilder()
        sb.append("{\"fuente\":\"arcore\",\"camera_model\":\"OPENCV\",")
            .append("\"fl_x\":${i[0]},\"fl_y\":${i[1]},\"cx\":${i[2]},\"cy\":${i[3]},")
            .append("\"w\":${i[4].toInt()},\"h\":${i[5].toInt()},\"k1\":0,\"k2\":0,\"p1\":0,\"p2\":0,\"frames\":[")
        synchronized(tomas) {
            tomas.forEachIndexed { k, t ->
                if (k > 0) sb.append(',')
                sb.append("{\"file_path\":\"images/${t.nombre}\",\"transform_matrix\":[")
                for (r in 0..3) {
                    if (r > 0) sb.append(',')
                    sb.append('[').append((0..3).joinToString(",") { c -> t.matriz[c * 4 + r].toString() }).append(']')
                }
                sb.append("]}")
            }
        }
        return sb.append("]}").toString()
    }

    fun porcentajeCobertura(): Int {
        val medio = (SECTORES until 2 * SECTORES).count { cobertura[it] > 0 }
        return medio * 100 / SECTORES
    }

    private fun marcarCobertura(p: Pose) {
        val z = p.zAxis   // la cámara mira hacia -Z
        val fx = -z[0]; val fy = -z[1]; val fz = -z[2]
        var yaw = Math.toDegrees(atan2(fx.toDouble(), -fz.toDouble()))
        if (yaw < 0) yaw += 360.0
        val pitch = Math.toDegrees(asin(fy.coerceIn(-1f, 1f).toDouble()))
        val anillo = when { pitch < -20 -> 0; pitch > 20 -> 2; else -> 1 }
        cobertura[anillo * SECTORES + (yaw / (360.0 / SECTORES)).toInt().coerceIn(0, SECTORES - 1)]++
    }

    private fun aviso(texto: String, color: Int, flash: Boolean = false, forzar: Boolean = false) {
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - ultimoAviso < 120) return
        ultimoAviso = ahora
        avisar(Aviso(texto, color, tomas.size, cobertura.copyOf(), flash))
    }

    companion object {
        const val SECTORES = 24
        const val MAX_FOTOS = 300
        const val MIN_PASO = 0.12f      // metros
        const val MIN_GIRO = 12f        // grados
        const val ROJO = 0xFFE05050.toInt()
        const val VERDE = 0xFF2FB37A.toInt()
        const val GRIS = 0xAA000000.toInt()

        fun motivo(r: TrackingFailureReason) = when (r) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Muy oscuro: prende las luces"
            TrackingFailureReason.EXCESSIVE_MOTION -> "Muy rápido: muévete despacio"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Apunta a muebles u objetos con textura"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Cámara ocupada por otra app"
            else -> "Mueve el celular despacio para ubicarte…"
        }

        fun distancia(a: Pose, b: Pose): Float {
            val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun angulo(a: Pose, b: Pose): Float {
            val za = a.zAxis; val zb = b.zAxis
            val d = (za[0] * zb[0] + za[1] * zb[1] + za[2] * zb[2]).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(d.toDouble())).toFloat()
        }

        /** Varianza del laplaciano sobre el plano Y (muestreado cada 4 píxeles). */
        fun nitidez(img: Image): Double {
            val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            val w = img.width; val h = img.height; val s = 4
            var suma = 0.0; var suma2 = 0.0; var n = 0
            fun y(x: Int, yy: Int) = (b.get(yy * rs + x * ps).toInt() and 0xFF)
            var yy = h / 8
            while (yy < h - h / 8) {
                var x = w / 8
                while (x < w - w / 8) {
                    val l = 4 * y(x, yy) - y(x - s, yy) - y(x + s, yy) - y(x, yy - s) - y(x, yy + s)
                    suma += l; suma2 += l.toDouble() * l; n++
                    x += s
                }
                yy += s
            }
            if (n == 0) return 0.0
            val m = suma / n
            return abs(suma2 / n - m * m)
        }

        fun aNv21(img: Image): ByteArray {
            val w = img.width; val h = img.height
            val out = ByteArray(w * h * 3 / 2)
            val yP = img.planes[0]; val yb = yP.buffer; val yrs = yP.rowStride; val yps = yP.pixelStride
            var o = 0
            if (yps == 1) {
                for (r in 0 until h) { yb.position(r * yrs); yb.get(out, o, w); o += w }
                yb.rewind()
            } else {
                for (r in 0 until h) for (c in 0 until w) out[o++] = yb.get(r * yrs + c * yps)
            }
            val u = img.planes[1]; val v = img.planes[2]
            val ub = u.buffer; val vb = v.buffer
            val urs = u.rowStride; val ups = u.pixelStride; val vrs = v.rowStride; val vps = v.pixelStride
            for (r in 0 until h / 2) for (c in 0 until w / 2) {
                out[o++] = vb.get(r * vrs + c * vps)
                out[o++] = ub.get(r * urs + c * ups)
            }
            return out
        }
    }
}
