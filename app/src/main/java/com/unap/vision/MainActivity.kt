package com.unap.vision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * La actividad principal y el punto de entrada de la aplicación.
 *
 * Esta actividad se encarga de configurar el tema de la interfaz de usuario, habilitar el modo de borde a borde
 * y establecer el contenido de la pantalla principal, que es el [CameraScreen].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraScreen()
        }
    }
}