package com.example.remotemouseclient

import android.app.AlertDialog
import android.view.View
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*

class ConnectionActivity : BaseClass() {
    // adapter do RecyclerView
    lateinit var adapter: ItemAdapter
    // viewModel para impedir carregamento infinito ou bugs
    private val viewModel: ConnectionActivityVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // tela cheia com conteúdo dentro das áreas do sistema
        enableEdgeToEdge()
        // configurar o layout como view
        setContentView(R.layout.activity_connection)
        // ajustar automaticamente o padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.connection_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // configurar a action bar
        setActionBar("Conexão")

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView) // RecyclerView
        val progressBar = findViewById<ProgressBar>(R.id.progressBar2) // barra de carregamento
        val errorMessage = findViewById<TextView>(R.id.errorMessage) // mensagem de erro
        val syncButton = findViewById<ImageButton>(R.id.syncImageButton) // botão de sincronização
        // listener do botão de sincronização
        syncButton.setOnClickListener {
            // verificar se o endereço de broadcast está ok
            if (session.broadcast != null) {// endereço de broadcast obtido com sucesso
                // limpar UI
                clearUI(recyclerView, progressBar, syncButton, errorMessage)
                // obter dados para a atualização da UI
                viewModel.load()
            } else {
                // obter endereço de broadcast
                val broadCastIp = session.getBroadcastAddress(this)

                // verificar se o endereço de broadcast foi obtido com sucesso
                if (broadCastIp != null) {// endereço de broadcast obtido com sucesso
                    // atualizar o endereço de broadcast da sessão
                    session.broadcast = broadCastIp
                    Log.d("CONEXÂO", "Endereço de broadcast obtido com sucesso")
                    // limpar UI
                    clearUI(recyclerView, progressBar, syncButton, errorMessage)
                    // obter dados para a atualização da UI
                    viewModel.load()
                } else {// não foi possível obter o endereço de broadcast
                    Log.d("CONEXÃO", "Não foi possível obter o endereço de broadcast")
                    showSnackBar(
                        "Wifi Desligado",
                        Snackbar.LENGTH_LONG,
                        R.color.red,
                        R.color.white
                    )
                }
            }
        }

        // definir o LayoutManager (lista vertical)
        recyclerView.layoutManager = LinearLayoutManager(this@ConnectionActivity)
        // desativar as animações do RecyclerView
        recyclerView.itemAnimator = null

        // criar o Adapter
        adapter = ItemAdapter(listOf()) {// comportamento ao clicar o item
            item, position ->

            // verificar se o botão clicado contêm um host conhecido
            if (session.isKnownHostInList(item.ip)) {
                // atualizar texto do item
                adapter.updateItem(position, "Autenticando...")
                lifecycleScope.launch {
                    // tentar conectar no servidor por reconhecimento
                    val res  = session.acknowledgmentAuthentication(item.ip, position)
                    // atualizar texto do item
                    adapter.updateItem(position, res)
                }
            } else {
                // mostrar dialog box para inserir o código de conexão
                alertDialogShow { cod ->
                    cod?.let {
                        // atualizar texto do item
                        adapter.updateItem(position, "Autenticando...")
                        lifecycleScope.launch {
                            // tentar conectar no servidor com código passado
                            val res = session.login(cod.toByteArray(),
                                item.ip,
                                position)
                            // atualizar texto do item
                            adapter.updateItem(position, res)
                        }
                    }
                }
            }
        }

        // ligar o Adapter no RecyclerView
        recyclerView.adapter = adapter

        // verificar se o endereço de broadcast foi obtido na inicialização
        if (session.broadcast == null) {
            // tentar obter endereço de broacast
            val broadCastIp = session.getBroadcastAddress(this)

            if (broadCastIp != null) {// endereço de broadcast obtido com sucesso
                // atualizar endereço de broadcast da sessão
                session.broadcast = broadCastIp
                // limpar UI
                clearUI(recyclerView, progressBar, syncButton, errorMessage)
                Log.d("CONEXÃO", "Endereço de broadcast obtido com sucesso")
            } else {// não foi possível obter endereço de broadcast
                viewModel.firstInit = false
                progressBar.visibility = View.GONE
                errorMessage.text = getString(R.string.carregamento_erro)
                errorMessage.visibility = View.VISIBLE
                syncButton.visibility = View.VISIBLE

                Log.d("CONEXÃO", "Não foi possível obter o endereço de broadcast")
                showSnackBar(
                    "Wifi Desligado",
                    Snackbar.LENGTH_LONG,
                    R.color.red,
                    R.color.white,
                    this
                )
            }
        } else {
            // limpar UI
            clearUI(recyclerView, progressBar, syncButton, errorMessage)
        }

        // atualizar UI quando os dados trocarem o valor
        viewModel.itemList.observe(this) {
            if (!viewModel.loading){
                Log.d("UI", "UI atualizada")
                // verificar se ouve erro ao conseguir itens
                if (it != null) {// não houve erro, verificar se a lista não está vazia
                    if (!it.isEmpty()) {// há conteúdo, atualizar RecyclerView
                        adapter.updateData(it)
                        progressBar.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    } else {// está vazia, mostrar mensagem
                        adapter.updateData(listOf())
                        progressBar.visibility = View.GONE
                        errorMessage.text = getString(R.string.servidor_erro)
                        errorMessage.visibility = View.VISIBLE
                    }
                } else {// houve erro, mostrar mensagem
                    adapter.updateData(listOf())
                    progressBar.visibility = View.GONE
                    errorMessage.text = getString(R.string.carregamento_erro)
                    errorMessage.visibility = View.VISIBLE
                }
                syncButton.visibility = View.VISIBLE
            }
        }

        // obter dados para a atualização da UI, caso seja a primeira inicialização
        if (viewModel.firstInit) {
            Log.d("UI", "Primeira inicialização")
            viewModel.firstInit = false
            // obter dados para a atualização da UI
            viewModel.load()
        }
    }

    // função para mostrar um alertdialog
    private fun alertDialogShow(onResult: (String?) -> Unit){
        // inflar layout
        val view = LayoutInflater.from(this@ConnectionActivity).inflate(R.layout.custom_dialog, null)
        // botão sim no layout
        val btnYes = view.findViewById<Button>(R.id.yesButton)
        // botão não no layout
        val btnNo = view.findViewById<Button>(R.id.noButton)
        // editText no layout
        val codInput = view.findViewById<EditText>(R.id.codInput)

        // quantidade de caracteres aceita no editText
        val maxLength = 6
        // listener para saber quando o texto for modificado
        codInput.addTextChangedListener(object: TextWatcher{
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if(s != null && s.length == maxLength){// ativar o botão sim quando houver 6 caracteres no editText
                    btnYes.isEnabled = true
                }else if(btnYes.isEnabled){// desativar o botão sim,
                                            // se ele estiver ativado e com menos de 6 caracteres no editText
                    btnYes.isEnabled = false
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // construir alertDialog usando o layout
        val builder = AlertDialog.Builder(this@ConnectionActivity, R.style.Theme_CustomDialog)
            .setView(view)
            .create()


        // listener do botão sim
        btnYes.setOnClickListener {
            builder.dismiss()
            onResult(codInput.text.toString().uppercase())
        }

        // listener do botão não
        btnNo.setOnClickListener {
            builder.dismiss()
            onResult(null)
        }

        // mostrar alertdialog
        builder.show()
    }

    // função usada para "pré-aquecer" componentes antes de usar. Funciona para evitar lags iniciais
    override fun warmUp(){
        // pré-aquecer apenas uma vez
        if (!viewModel.warmedUp) {
            // criar dummy dialog
            val view = LayoutInflater.from(this@ConnectionActivity).inflate(R.layout.custom_dialog, null)
            val dummyDialog = AlertDialog.Builder(this@ConnectionActivity, R.style.Theme_CustomDialog)
                .setView(view)
                .create()

            // deixar a janela invisível
            dummyDialog.window?.apply {
                setWindowAnimations(0)
                setDimAmount(0f)
                decorView.alpha = 0f
            }
            // renderizar janela
            dummyDialog.show()

            // fechar dummy dialog após ocorrer a renderização
            dummyDialog.window?.decorView?.post {
                dummyDialog.dismiss()
                viewModel.warmedUp = true
                Log.d("UI", "ConnectionActivity: pré-aquecido")
            }
        }
    }

    // função para reiniciar o UI
    private fun clearUI(recyclerView: RecyclerView,
                        progressBar: ProgressBar,
                        syncButton: ImageButton,
                        errorMessage: TextView){
        progressBar.visibility = View.VISIBLE
        syncButton.visibility = View.GONE
        errorMessage.visibility = View.GONE
        recyclerView.visibility = View.GONE
    }
}