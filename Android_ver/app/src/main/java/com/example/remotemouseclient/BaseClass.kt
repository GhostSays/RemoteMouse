package com.example.remotemouseclient

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

// classe base para as activities
open class BaseClass: AppCompatActivity() {
    // variáveis compartilhadas entre as classes
    companion object {
        // variável usada para manter controle da activity atual
        @SuppressLint("StaticFieldLeak")
        var currentActivity: Activity? = null
            private set
        // sessão usada para conexão com o servidor
        val session = Session()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // rodar "pré-aquecimento" quando a activity terminar de carregar
        val rootView = findViewById<View>(android.R.id.content)
        rootView.post {
            warmUp()
        }

        // listener para quando o botão voltar for apertado
        onBackPressedDispatcher.addCallback(this){
            // chamar função para fechar a activity com animação
            finishWithAnimation()
        }
    }

    // função usada para fechar a activity com animação
    @Suppress("DEPRECATION")
    private fun finishWithAnimation() {
        finish()
        overridePendingTransition(R.anim.bounce_in, R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this // manter controle da activity atual
        Log.d("UI", "Activity atual mudada")
    }

    override fun onPause() {
        super.onPause()
        if(currentActivity == this) currentActivity = null // manter controle da activity atual
        Log.d("UI", "Activity atual = null")
    }

    // função usada para configurar a action bar de cada activity
    fun setActionBar(title: String) {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        // definir título da action bar
        supportActionBar?.title = title
    }

    open fun warmUp(){
        //TODO: to be implemented by child classes
    }
}