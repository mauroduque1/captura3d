package co.northing.n3d

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Sube la captura al servidor Northing3D (Cloudflare) y arranca el procesamiento (Modal). */
object Envio {
    class ClaveIncorrecta : IOException("Clave incorrecta")

    fun enviar(
        base: String, clave: String, titulo: String, dir: File,
        fotos: List<String>, transforms: String, progreso: (String) -> Unit
    ): String {
        val r = JSONObject(pedir("POST", "$base/api/trabajos", clave, "application/json",
            JSONObject().put("titulo", titulo).toString().toByteArray()))
        val id = r.getString("id"); val enlace = r.getString("enlace")
        val hechas = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        try {
            fotos.map { n ->
                pool.submit(Runnable {
                    reintentar { pedir("PUT", "$base/api/trabajos/$id/fotos/$n", clave, "image/jpeg", File(dir, n).readBytes()) }
                    progreso("Subiendo ${hechas.incrementAndGet() * 100 / fotos.size}%")
                })
            }.forEach { it.get() }
        } finally { pool.shutdown() }
        reintentar { pedir("PUT", "$base/api/trabajos/$id/poses", clave, "application/json", transforms.toByteArray()) }
        pedir("POST", "$base/api/trabajos/$id/procesar", clave, "application/json", "{}".toByteArray())
        return enlace
    }

    private fun reintentar(f: () -> Unit) {
        var ultimo: Exception? = null
        repeat(4) { i ->
            try { f(); return } catch (e: ClaveIncorrecta) { throw e } catch (e: Exception) {
                ultimo = e; Thread.sleep(1500L * (i + 1))
            }
        }
        throw IOException("Sin conexión estable: ${ultimo?.message}")
    }

    private fun pedir(metodo: String, url: String, clave: String, tipo: String, cuerpo: ByteArray): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("x-clave", clave)
            c.setRequestProperty("content-type", tipo)
            c.doOutput = true
            c.setFixedLengthStreamingMode(cuerpo.size)
            c.outputStream.use { it.write(cuerpo) }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw ClaveIncorrecta()
            if (code >= 400) throw IOException("Servidor respondió $code")
            return txt
        } finally { c.disconnect() }
    }
}
