package com.example.remotemouseclient

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageButton
import androidx.fragment.app.DialogFragment

// fragment para mostrar o manual de uso
class ManualFragment: DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // usar o layout criado para este fragment
        return inflater.inflate(R.layout.fragment_manual, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // obter a WebView do layout
        val webView = view.findViewById<WebView>(R.id.webView)
        // desativar javaScript por segurança
        webView.settings.javaScriptEnabled = false

        // carregar o manual dos arquivos locais
        webView.loadUrl("file:///android_asset/manual.html")

        // obter botão de fechar do layout
        val okButton = view.findViewById<ImageButton>(R.id.okButton)
        // reagir ao ser clicado
        okButton.setOnClickListener {
            // fechar o fragment
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // definir tamanho e remover bordas do fragment
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(), // 90% da largura da tela
            (resources.displayMetrics.heightPixels * 0.8).toInt() // 80% da altura da tela
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // colocar tema personalizado no fragment
    override fun getTheme() = R.style.Theme_CustomDialog
}