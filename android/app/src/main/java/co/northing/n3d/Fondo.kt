package co.northing.n3d

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Dibuja la imagen de la cámara de ARCore como fondo de pantalla. */
class Fondo {
    var textura = 0
        private set
    private var programa = 0
    private var aPos = 0
    private var aTex = 0
    private val quad: FloatBuffer = flotantes(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = flotantes(FloatArray(8))

    fun crear() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textura = t[0]
        val obj = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(obj, textura)
        GLES20.glTexParameteri(obj, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(obj, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(obj, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(obj, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        programa = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, sombreador(GLES20.GL_VERTEX_SHADER, VS))
            GLES20.glAttachShader(it, sombreador(GLES20.GL_FRAGMENT_SHADER, FS))
            GLES20.glLinkProgram(it)
        }
        aPos = GLES20.glGetAttribLocation(programa, "a_Pos")
        aTex = GLES20.glGetAttribLocation(programa, "a_Tex")
    }

    fun dibujar(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        }
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(programa)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textura)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
    }

    private fun sombreador(tipo: Int, codigo: String): Int {
        val s = GLES20.glCreateShader(tipo)
        GLES20.glShaderSource(s, codigo)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private fun flotantes(v: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private const val VS = """
attribute vec4 a_Pos;
attribute vec2 a_Tex;
varying vec2 v_Tex;
void main() { gl_Position = a_Pos; v_Tex = a_Tex; }
"""
        private const val FS = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 v_Tex;
uniform samplerExternalOES u_Tex;
void main() { gl_FragColor = texture2D(u_Tex, v_Tex); }
"""
    }
}
