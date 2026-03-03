package com.bigq.demodogcat.opengl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.bigq.demodogcat.opengl.CameraGLRenderer

/**
 * GLSurfaceView tùy chỉnh để render camera preview với overlay
 */
class GLTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var renderer: CameraGLRenderer? = null
    private var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
    }

    fun initialize(
        onSurfaceReady: (SurfaceTexture) -> Unit,
        onRendererCreated: (CameraGLRenderer) -> Unit
    ) {
        onSurfaceTextureAvailable = onSurfaceReady
        
        renderer = CameraGLRenderer { surfaceTexture ->
            post {
                onSurfaceReady(surfaceTexture)
            }
        }
        
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        
        onRendererCreated(renderer!!)
    }

    fun getRenderer(): CameraGLRenderer? = renderer

    fun requestRenderFrame() {
        requestRender()
    }
}
