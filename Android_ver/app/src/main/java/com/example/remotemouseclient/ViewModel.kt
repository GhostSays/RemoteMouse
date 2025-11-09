package com.example.remotemouseclient

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.remotemouseclient.BaseClass.Companion.session
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.net.DatagramSocket

// view model para a splash screen
class MainActivityVM(application: Application): AndroidViewModel(application) {
    // variáveis para manter controle do load inicial
    private val _loadFinished = MutableLiveData<Boolean>()
    val loadFinished: LiveData<Boolean> = _loadFinished
    var animationFinished = false

    init {
        // iniciar load
        load()
    }

    // função para obter dados persistentes sem atrapalhar o loading
    private fun load(){
        // obter contexto da aplicação
        val context = getApplication<Application>().applicationContext
        // obter endereço de broadcast
        val broadCastIp = session.getBroadcastAddress(context)

        // verificar se o endereço de broadcast foi obtido com sucesso
        if (broadCastIp != null) {// enderço de broadcast obtido com sucesso
            // atualizar o endereço de broadcast da sessão
            Log.d("CONEXÃO", "Endereço de broadcast obtido com sucesso")
            session.broadcast = broadCastIp

            viewModelScope.launch {
                // carregar dados do aplicativo
                session.read(context)

                // obter servidores ativos na rede pelo broadcast UDP
                val broadcastHostList = session.broadcastUDP()
                broadcastHostList?.let { it ->
                    if (!it.isEmpty()) {
                        // verificar se há algum host conhecido na lista
                        val res = session.isKnownHostInList(it)
                        if (res > -1) {
                            Log.d("CONEXÃO", "Host conhecido ativo")
                            // tentar conectar no servidor por reconhecimento
                            session.acknowledgmentAuthentication(
                                it[res].ip,
                                res
                            )
                        }
                    }
                }
                // esperar alguns segundo e entrar na próxima activity
                Handler(Looper.getMainLooper()).postDelayed({
                    _loadFinished.value = true
                }, ValueUtility.SPLASH_SCREEN_DELAY_MILLIS)
            }
        } else {// não foi possível obter o endereço de broadcast
            Log.d("CONEXÃO", "Não foi possível obter o endereço de broadcast")
            viewModelScope.launch {
                session.read(context)

                // esperar alguns segundo e entrar na próxima activity
                Handler(Looper.getMainLooper()).postDelayed({
                    _loadFinished.value = true
                }, ValueUtility.SPLASH_SCREEN_DELAY_MILLIS)
            }
        }
    }

}

// view model para a activity de conexão
class ConnectionActivityVM: ViewModel() {
    // lista com servidores recebidos, apenas do view model
    private val _itemList = MutableLiveData<List<Host>>()
    // lista com servidores recebidos, para ser lida pelo view
    val itemList: LiveData<List<Host>> = _itemList
    // variáveis para manter controle do load
    var firstInit = true
    var loading = false
    // variável para manter controle do "pré-aquecimento"
    var warmedUp = false

    // função para obter dados para a atualização da UI
    fun load () {
        if (session.broadcast != null) {
            loading = true
            viewModelScope.launch {
                val res = session.broadcastUDP()
                loading = false
                _itemList.value = res
            }
        }
    }
}

class MenuActivityVM: ViewModel() {
    var warmedUp = false
}

class SocketViewModel : ViewModel() {
    init {
        // iniciar o socket UDP, caso esteja fechado ou não exista
        if (session.sock4 == null || session.sock4!!.isClosed) {
            session.sock4 = DatagramSocket()
            SocketManager.addSocketUDP(session.sock4)
        }
    }

    // função usada para manter controle dos recursos do socket
    override fun onCleared() {
        super.onCleared()
        // manter controle dos sockets utilizados pelo sistema
        SocketManager.removeSocketUDP(session.sock4)
        // fechar socket
        session.sock4?.close()
    }

    // função que serve de ponte entre a UI e o backend
    fun sendMessage(command: ByteArray){
        if(session.isConnected()) {
            // enviar mensagem
            viewModelScope.launch {
                session.messageUDP(command)
            }
        } else {
            showSnackBar("Não conectado!", Snackbar.LENGTH_SHORT, R.color.red, R.color.white)
        }
    }
}