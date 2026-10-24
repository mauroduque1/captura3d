package co.northing.n3d

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var gl: GLSurfaceView
    private lateinit var aviso: TextView
    private lateinit var estado: TextView
    private lateinit var nombre: EditText
    private lateinit var bAuto: Button
    private lateinit var bEnviar: Button
    private lateinit var radar: Cobertura
    private lateinit var flash: View
    private lateinit var captura: Captura

    private var session: Session? = null
    private var instalacionPedida = false
    private val fondo = Fondo()
    private var texturaAsignada = false
    private var geometriaCambio = false
    private var anchoV = 0
    private var altoV = 0
    private var enviando = false

    private val prefs by lazy { getSharedPreferences("n3d", Context.MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        captura = Captura(File(filesDir, "captura").apply { mkdirs() }) { a -> runOnUiThread { mostrar(a) } }
        construirPantalla()
    }

    // ---------- Pantalla ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pastilla(color: Int) = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(color) }

    private fun boton(texto: String, color: Int = 0x99000000.toInt()) = Button(this).apply {
        text = texto; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = pastilla(color); setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun construirPantalla() {
        val raiz = FrameLayout(this)
        gl = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        raiz.addView(gl)
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        raiz.addView(flash)

        val arriba = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), 0)
        }
        nombre = EditText(this).apply {
            hint = "Nombre del espacio (ej. Hotel Hab 101)"; setTextColor(Color.WHITE); setHintTextColor(0xAAFFFFFF.toInt())
            background = pastilla(0x88000000.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10)); setSingleLine()
            setText(prefs.getString("ultimo_nombre", ""))
        }
        arriba.addView(nombre)
        estado = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(4), dp(10), 0, 0); text = "Fotos: 0 · Cobertura: 0%"
        }
        arriba.addView(estado)
        raiz.addView(arriba, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        radar = Cobertura(this)
        raiz.addView(radar, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(118); rightMargin = dp(16)
        })

        aviso = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = pastilla(Captura.GRIS); setPadding(dp(18), dp(10), dp(18), dp(10))
            text = "Apunta a la habitación"
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(120) })

        val abajo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        bAuto = boton("Auto: sí", Captura.VERDE).apply { setOnClickListener { alternarAuto() } }
        val bFoto = Button(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), 0x66FFFFFF) }
            setOnClickListener { captura.pedirFoto = true }
        }
        bEnviar = boton("Enviar ☁", Captura.VERDE).apply {
            setOnClickListener { enviar() }
            setOnLongClickListener { configurar(null); true }
        }
        val esp = { LinearLayout.LayoutParams(0, 1, 1f) }
        abajo.addView(bAuto); abajo.addView(View(this), esp()); abajo.addView(bFoto, LinearLayout.LayoutParams(dp(76), dp(76)))
        abajo.addView(View(this), esp()); abajo.addView(bEnviar)
        raiz.addView(abajo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val bNuevo = boton("Nuevo").apply { textSize = 13f; setOnClickListener { nuevo() } }
        raiz.addView(bNuevo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(118); leftMargin = dp(16) })
        setContentView(raiz)
    }

    private fun mostrar(a: Captura.Aviso) {
        aviso.text = a.texto
        (aviso.background as GradientDrawable).setColor(a.color)
        estado.text = "Fotos: ${a.fotos} · Cobertura: ${captura.porcentajeCobertura()}%"
        radar.actualizar(a.cobertura)
        if (a.flash) { flash.alpha = 0.5f; flash.animate().alpha(0f).setDuration(180).start() }
    }

    private fun alternarAuto() {
        captura.auto = !captura.auto
        bAuto.text = if (captura.auto) "Auto: sí" else "Auto: no"
        bAuto.background = pastilla(if (captura.auto) Captura.VERDE else 0x99000000.toInt())
    }

    private fun nuevo() {
        if (captura.tomas.isEmpty()) return
        AlertDialog.Builder(this).setMessage("¿Borrar las ${captura.tomas.size} fotos y empezar otro espacio?")
            .setPositiveButton("Borrar") { _, _ -> captura.bloqueada = true; captura.reiniciar(); captura.bloqueada = false; bEnviar.text = "Enviar ☁" }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------- Envío ----------
    private fun configurar(luego: (() -> Unit)?) {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val url = EditText(this).apply {
            hint = "https://northing3d.xxxx.workers.dev"; setText(prefs.getString("servidor", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine()
        }
        val clave = EditText(this).apply {
            hint = "Clave"; setText(prefs.getString("clave", "")); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        caja.addView(url); caja.addView(clave)
        AlertDialog.Builder(this).setTitle("Servidor Northing3D")
            .setMessage("Copia la dirección y la clave de MI_NORTHING3D.txt")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("servidor", url.text.toString().trim().trimEnd('/').removeSuffix("/captura"))
                    .putString("clave", clave.text.toString().trim()).apply()
                luego?.invoke()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun enviar() {
        if (enviando) return
        val n = captura.tomas.size
        if (n < 40) { toast("Toma al menos 40 fotos (ideal 100–200)"); return }
        val servidor = prefs.getString("servidor", "") ?: ""
        val clave = prefs.getString("clave", "") ?: ""
        if (!servidor.startsWith("https://") || clave.isEmpty()) { configurar { enviar() }; return }
        val titulo = nombre.text.toString().trim().ifEmpty { "Espacio" }
        prefs.edit().putString("ultimo_nombre", titulo).apply()
        enviando = true; captura.bloqueada = true; bEnviar.isEnabled = false; bEnviar.text = "Preparando…"
        Thread {
            try {
                while (captura.pendientes.get() > 0) Thread.sleep(100)
                val fotos = synchronized(captura.tomas) { captura.tomas.map { it.nombre } }
                val enlace = Envio.enviar(servidor, clave, titulo, captura.dir, fotos, captura.transforms()) { t ->
                    runOnUiThread { bEnviar.text = t }
                }
                runOnUiThread {
                    bEnviar.text = "Enviado ✓"
                    aviso.text = "Listo: el recorrido estará en ~25 min"
                    captura.reiniciar()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$titulo · Northing3D\n$enlace")
                    }, "Compartir recorrido"))
                }
            } catch (e: Envio.ClaveIncorrecta) {
                runOnUiThread { toast("Clave incorrecta"); bEnviar.text = "Enviar ☁"; configurar(null) }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo enviar: ${e.message}. Las fotos siguen guardadas."); bEnviar.text = "Enviar ☁" }
            } finally {
                runOnUiThread { enviando = false; captura.bloqueada = false; bEnviar.isEnabled = true }
            }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- ARCore ----------
    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !instalacionPedida)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { instalacionPedida = true; return }
                    else -> {}
                }
                val s = Session(this)
                val filtro = CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.BACK)
                s.getSupportedCameraConfigs(filtro)
                    .maxByOrNull { it.imageSize.width.toLong() * it.imageSize.height }
                    ?.let { s.cameraConfig = it }
                s.configure(Config(s).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                })
                session = s
            } catch (e: Exception) {
                toast("Este celular no pudo iniciar ARCore: ${e.message}"); return
            }
        }
        try { session?.resume() } catch (e: Exception) { toast("Cámara no disponible: ${e.message}"); session = null; return }
        gl.onResume()
        geometriaCambio = true
    }

    override fun onPause() {
        super.onPause()
        gl.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close(); session = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permisos: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, permisos, res)
        if (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Northing3D necesita la cámara para capturar"); finish()
        }
    }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        fondo.crear()
        texturaAsignada = false
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        anchoV = w; altoV = h; geometriaCambio = true
    }

    @Suppress("DEPRECATION")
    override fun onDrawFrame(g: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!texturaAsignada) { s.setCameraTextureName(fondo.textura); texturaAsignada = true }
        if (geometriaCambio && anchoV > 0) {
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation, anchoV, altoV); geometriaCambio = false
        }
        val frame = try { s.update() } catch (e: Exception) { return }
        fondo.dibujar(frame)
        captura.procesar(frame)
    }
}
