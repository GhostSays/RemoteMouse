package com.example.remotemouseclient

import android.app.ActivityOptions
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.activity.viewModels
import android.widget.ProgressBar
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : BaseClass() {
    // variável para guardar o motion layout
    private lateinit var motionLayout: MotionLayout
    // variável para guardar a barra de progresso
    private lateinit var progressBar: ProgressBar
    // viewModel para impedir carregamento infinito ou bugs
    private val viewModel: MainActivityVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?){
        setTheme(R.style.Theme_RemoteMouseClient) // voltar para tema principal
        super.onCreate(savedInstanceState)
        // tela cheia com conteúdo dentro das áreas do sistema
        enableEdgeToEdge()
        // deixar os ícones da status bar CLAROS
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        // configurar o layout como view
        setContentView(R.layout.activity_main)
        // ajustar automaticamente o padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.motionLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ajustar a imagem a ser animada de modo que não haja pixelação
        val imageView = findViewById<ImageView>(R.id.appIcon)
        imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val drawable = imageView.drawable
        if (drawable is BitmapDrawable) {
            drawable.setFilterBitmap(true) // ativa interpolação bilinear
        }

        // motionLayout do layout
        motionLayout = findViewById(R.id.motionLayout)
        // barra de progresso do layout
        progressBar = findViewById(R.id.progressBar)

        // começar animação do motionLayout
        motionLayout.post {
            // verificar se a animação já foi terminada
            if (!viewModel.animationFinished) {
                motionLayout.transitionToEnd()
            } else {
                motionLayout.progress = 1f
            }
        }
        // listener do motionLayout
        motionLayout.setTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(motionLayout: MotionLayout?, startId: Int, endId: Int) {}
            override fun onTransitionChange(motionLayout: MotionLayout?, startId: Int, endId: Int, progress: Float) {}
            override fun onTransitionTrigger(motionLayout: MotionLayout?, triggerId: Int, positive: Boolean, progress: Float) {}

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                // começar a carregar dados quando a animação estiver completa
                viewModel.animationFinished = true
                viewModel.loadFinished.observe(this@MainActivity) {
                    if (it) {
                        // começar uma nova activity
                        val intent = Intent(this@MainActivity, MenuActivity::class.java)
                        // configurar transição da activity
                        val options = ActivityOptions.makeCustomAnimation(
                            this@MainActivity,
                            R.anim.bounce_in,
                            R.anim.bounce_out
                        )
                        // iniciar uma nova activity
                        startActivity(intent, options.toBundle())
                        // fechar esta activity, pois é a splash screen
                        finish()
                    }
                }
            }
        })
    }
}