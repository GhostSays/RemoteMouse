package com.example.remotemouseclient

import android.app.ActivityOptions
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import android.util.Log
import androidx.activity.viewModels
import kotlin.getValue

class MenuActivity : BaseClass() {
    // viewModel para código de ser rodado mais que uma vez
    private val viewModel: MenuActivityVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // tela cheia com conteúdo dentro das áreas do sistema
        enableEdgeToEdge()
        // configurar o layout como view
        setContentView(R.layout.activity_menu)
        // ajustar automaticamente o padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.menu_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // configurar a action bar
        setActionBar("Menu")

        // ImageButton(MouseButton) do layout
        val mouseButton = findViewById<ImageButton>(R.id.MouseButton)
        // listener para reagir ao ser clicado
        mouseButton.setOnClickListener {
            // criar nova intent
            val intent = Intent(this, MouseActivity::class.java)
            // configurar transição da activity
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.bounce_in,
                R.anim.bounce_out
            )
            // iniciar uma nova activity
            startActivity(intent, options.toBundle())
        }

        // ImageButton(ConButton) do layout
        val conButton = findViewById<ImageButton>(R.id.ConButton)
        // listener para reagir ao ser clicado
        conButton.setOnClickListener {
            // criar uma nova intent
            val intent = Intent(this, ConnectionActivity::class.java)
            // configurar transição da activity
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.bounce_in,
                R.anim.bounce_out
            )
            // iniciar uma nova activity
            startActivity(intent, options.toBundle())
        }

        // ImageButton(manualButton) do layout
        val manualButton = findViewById<ImageButton>(R.id.manualButton)
        // listener para reagir ao ser clicado
        manualButton.setOnClickListener {
            val popUp = ManualFragment()
            popUp.show(supportFragmentManager, "Manual")
        }
    }

    // função usada para "pré-aquecer" componentes antes de usar. Funciona para evitar lags iniciais
    override fun warmUp(){
        // pré-aquecer apenas uma vez
        if (!viewModel.warmedUp) {
            // criar dummy fragment
            val dummy = ManualFragment()
            dummy.show(supportFragmentManager, "Manual")
            // forçar criação imediata do fragment
            supportFragmentManager.executePendingTransactions()

            // deixar a janela invisível e sem animação
            dummy.dialog?.window?.apply{
                setWindowAnimations(0)
                setDimAmount(0f)
                decorView.alpha = 0f
            }

            // fechar dummy fragment após a renderização
            dummy.dialog?.window?.decorView?.post {
                dummy.dismissAllowingStateLoss()
                viewModel.warmedUp = true
                Log.d("WARMUP", "MenuActivity: pré-aquecido")
            }
        }
    }
}