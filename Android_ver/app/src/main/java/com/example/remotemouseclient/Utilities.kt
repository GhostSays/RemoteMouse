package com.example.remotemouseclient

import android.app.Activity
import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.collections.mutableListOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

// variável para usar DataStore Preferences
val Context.dataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "data")
// key para salvar lista de hosts conhecidos
val LIST_KEY = stringPreferencesKey("knownHost")

// bytes usados para troca de mensagens
object AuthenticationUtility{
    const val AUT_CODIGO: Byte = 0X01 // autenticação por código
    const val AUT_CONFIANCA: Byte = 0X02 // autenticação por reconhecimento
    const val CON_PERMITIDA: Byte = 0X01 // conexão permitida
    const val CON_NEGADA: Byte = 0x02 // conexão negada pelo servidor
    const val CON_PERMITIDA_C: Byte = 0x03 // conexão permitida, dispositivo considerado confiável
    const val COD_INCORRETO: Byte = 0x04 // código incorreto enviado
    const val CON_OCUPADA: Byte = 0x05 // servidor está ocupado
    const val CON_CONECTADO: Byte = 0X06 // cliente já conectado
    const val CON_LIVRE: Byte = 0x07 // servidor disponível
    const val CON_DESCONECTADO: Byte = 0x08 // cliente se desconectou,
                                            // ou foi desconectado pelo servido
}

// valores para serem usados como fomar de utilidade
object ValueUtility {
    const val TIME_OUT_MILLIS = 3000 // tempo de carregamento para procurar servidores
    const val CONNECTION_TIMEOUT = 2000 // tempo de espera para receber pacotes
    const val SPLASH_SCREEN_DELAY_MILLIS:Long = 1000 // tempo de espera até a splash screen sumir
    const val MOUSE_POINTER_ALPHA = 0.4f // valor de suavização da velocidade do ponteiro do mouse
    const val MOUSE_SCROLL_ALPHA = 0.05f // valor de suavização da velocidade do scroll do mouse
}

// modelo de dados para sessão e RecyclerView
data class Host(val ip: String, var status: String)

// objeto para manter controle dos sockets atualmente usados pelo aplicativo
object SocketManager {
    // lista de sockets UDP abertos no aplicativo
    private val socketsUDP = mutableListOf<DatagramSocket?>()
    // lista de sockets TCP abertos no aplicativo
    private val socketsTCP = mutableListOf<Socket?>()

    // função para manter controle de sockets UDP
    fun addSocketUDP (socket: DatagramSocket?) {
        // adicionar socket na lista
        socketsUDP.add(socket)
        Log.d("SOCKET", "SocketUDP: ${socket!!.localPort} adicionado")
    }

    // função para manter controle de sockets TCP
    fun addSocketTCP (socket: Socket?) {
        // adicionar socket na lista
        socketsTCP.add(socket)
        Log.d("SOCKET", "SocketTCP: ${socket!!.localPort} adicionado")
    }

    // função para manter controle de sockets UDP
    fun removeSocketUDP(socket: DatagramSocket?) {
        socketsUDP.remove(socket)
        Log.d("SOCKET", "SocketUDP: ${socket!!.localPort} removido")
    }

    // função para manter controle de sockets TCP
    fun removeSocketTCP(socket: Socket?) {
        socketsTCP.remove(socket)
        Log.d("SOCKET", "SocketTCP: ${socket!!.localPort} removido")
    }

    // fechar sockets quando não forem mais necessários
    fun closeAllSockets(){
        for (socket in socketsUDP) {
            // verificar se o socket está aberto
            if (socket != null && !socket.isClosed) {
                Log.d("SOCKET", "SocketUDP: ${socket.localPort} fechado")
                socket.close()
            }
        }
        for (socket in socketsTCP) {
            // verificar se o socket está aberto
            if (socket != null && !socket.isClosed) {
                Log.d("SOCKET", "SocketTCP: ${socket.localPort} fechado")
                socket.close()
            }
        }
        Log.d("SOCKET", "Sockets fechados com sucesso")
        // limpar listas de sockets
        socketsUDP.clear()
        socketsTCP.clear()
    }
}

// classe para conexão entre cliente e servidor
class Session {
    companion object {
        private const val PORT_STATUS = 54321 // porta para verificar status do servidor
        private const val PORT_LOGIN = 53100 // porta para logar no servidor
        private const val PORT_MESSAGE = 53100 // porta para enviar comandos ao servidor
        const val PADDING: Byte = 0x00 // padding
        const val MOUSE_RB_FLAG: Byte = 0x02 // botão esquerdo do mouse
        const val MOUSE_RB_DOWN: Byte = 0x08 // botão direito do mouse pressionado
        const val MOUSE_RB_UP: Byte = 0x10 // botão direito do mouse solto
        const val MOUSE_LB_FLAG: Byte = 0x03 // botão esquerdo do mouse
        const val MOUSE_LB_DOWN: Byte = 0x02 // botão esquerdo do mouse pressionado
        const val MOUSE_LB_UP: Byte = 0x04 // botão esquerdo do mouse solto
        const val MOUSE_MB_FLAG: Byte = 0x04 // botão do meio do mouse
        const val MOUSE_MB_DOWN: Byte = 0x20 // botão do meio do mouse pressionado
        const val MOUSE_MB_UP: Byte = 0x40 // botão do meio do mouse solto
        const val MOUSE_POINTER_MOVEMENT_FLAG: Byte = 0x01 // movimento do ponteiro do mouse
        const val MOUSE_SCROLL_FLAG: Byte = 0x05 // movimento do scroll do mouse
    }

    private var knownHosts = mutableListOf<String>() // servidores conhecidos
    private var host: String? = null // ip do host atualmente conectado
    private var seqId = 0 // número atual da sequência de comandos
    private var adapterHostPosition = 0 // posição do host atual no RecyclerView.
                                        // usada para modificar o item no RecyclerView
    var broadcast: String? = null // endereço usado para fazer broadcast
    var sock1: DatagramSocket? = null // porta usada para fazer UDP broadcast
    var sock2: DatagramSocket? = null // porta usada para desconexão entre cliente e servidor
    var sock3: Socket? = null // porta usada para login
    var sock4: DatagramSocket? = null // porta usada para envio de comandos ao servidor
    lateinit var connectivityManager: ConnectivityManager // gerenciador de rede

    // função para salvar dados persistentes
    private suspend fun write() = withContext(Dispatchers.IO){
        val gson = Gson() // biblioteca Gson

        try {
            // criar um json usando a lista de hosts conhecidos
            val newJson = gson.toJson(knownHosts)
            // verificar se há alguma activity ativa para salvar os dados
            if (BaseClass.currentActivity != null){
                // gravar dados em memória persistente
                BaseClass.currentActivity!!.dataStore.edit { prefs ->
                    prefs[LIST_KEY] = newJson
                }
                Log.d("STORAGE", "Dados salvos com sucesso")
            } else {
                Log.d("STORAGE", "Não foi possível salvar dados")
            }
        } catch (e: Exception) {
            Log.d("STORAGE", "Erro: ", e)
        }
    }

    // função para ler dados persistentes
    suspend fun read(context: Context) = withContext(Dispatchers.IO){
        val gson = Gson() // biblioteca Gson

        try {
            // ler do arquivo
            val json = context.dataStore.data.first()[LIST_KEY] ?: "[]"
            // criar objeto para salvar os dados na memória ram
            val type = object: TypeToken<MutableList<String>>() {}.type
            // passar os dados para a lista de hosts conhecidos
            knownHosts = gson.fromJson(json, type)
            Log.d("STORAGE", "Dados lidos com sucesso")
        } catch (e: Exception) {
            Log.d("STORAGE", "Erro: ", e)
        }
    }

    // função para retornar a máscara de rede
    private fun prefixLengthToNetmask(prefixLength: Int): InetAddress {
        val mask = ByteArray(4)
        for (i in 0 until prefixLength) {
            mask[i / 8] = (mask[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
        }
        return InetAddress.getByAddress(mask)
    }

    // função para obter o endereço utilizado para broadcast
    fun getBroadcastAddress(context: Context): String?{
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties: LinkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null

        // obter endereço IPV4 do dispositivo
        val ipv4Address = linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address } ?: return null

        // obter o prefix lenght do endereço ip
        val prefixLength = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.prefixLength ?: return null

        // obter a máscara de rede
        val netMask = prefixLengthToNetmask(prefixLength)

        // calcular broadcast: (IP & máscara) | (~máscara)
        val ipByte = ipv4Address.address
        val maskBytes = netMask.address
        val broadcastBytes = ByteArray(4)
        for (i in 0..3){
            val ip = ipByte[i].toInt() and 0xFF
            val mask = maskBytes[i].toInt() and 0xFF
            broadcastBytes[i] = (ip or (mask.inv() and 0xFF)).toByte()
        }

        // retornar o endereço usado para broadcast
        return InetAddress.getByAddress(broadcastBytes).hostAddress
    }

    // função para obter os hosts ativos na rede por meio de UDP broadcast
    suspend fun broadcastUDP(): List<Host>? = withContext(Dispatchers.IO){
        val hostList = mutableListOf<Host>() // lista para adicionar os hosts achados com o broadcast

        try{
            // verificar o socket é null ou está fechado
            if (sock1 == null || sock1!!.isClosed){
                // criar/abrir um novo socket
                sock1 = DatagramSocket()
                // adicionar o socket ao gerenciador de sockets
                SocketManager.addSocketUDP(sock1)
            }

            val message = byteArrayOf(0x01) // mensagem a ser enviada
            // pacote a ser enviado via UDP
            val packet = DatagramPacket(
                message, // mensagem
                message.size, // tamanho da mensagem
                InetAddress.getByName(broadcast), // endereço de broadcast
                PORT_STATUS // porta para qual o pacote será enviado
            )
            // enviar mensagem
            sock1!!.send(packet)

            val buffer = ByteArray(1) // buffer para receber a mensagem
            val serverResponse = DatagramPacket(buffer, buffer.size) // guardar mensagem no buffer
            // configurar timeout do socket
            sock1!!.soTimeout = ValueUtility.CONNECTION_TIMEOUT
            // duração do loop while
            val endTime = System.currentTimeMillis() + ValueUtility.TIME_OUT_MILLIS

            var i = 0 // posição atual da lista
            while(System.currentTimeMillis() < endTime){
                try{
                    // receber pacote
                    sock1!!.receive(serverResponse)
                    // verificar qual a resposta recebida
                    when(serverResponse.data[0]){
                        AuthenticationUtility.CON_CONECTADO -> {
                            // cliente já conectado
                            serverResponse.address.hostAddress?.let{
                                // adicionar ip na lista de hosts
                                hostList.add(Host(it, "Conectado!"))
                                // verificar se este cliente já está como host conectado
                                if (this@Session.host != it) { // conectar cliente localmente
                                    connect(it, i)
                                } else { // manter controle da posição do host atual no RecyclerView
                                    adapterHostPosition = i
                                }
                            }
                        }

                        AuthenticationUtility.CON_LIVRE -> {
                            // servidor livre
                            serverResponse.address.hostAddress?.let{
                                // adicionar ip na lista de hosts
                                hostList.add(Host(it, "Disponível!"))
                            }
                        }

                        AuthenticationUtility.CON_OCUPADA -> {
                            // servidor ocupado
                            serverResponse.address.hostAddress?.let{
                                // adicionar ip na lista de hosts
                                hostList.add(Host(it, "Indisponível!"))
                            }
                        }
                    }
                    // posição atual da lista +1
                    i++
                }catch (_: Exception){
                    Log.d("CONEXÃO", "Timeout")

                    // ir para próxima iteração
                    continue
                }
            }
            // remover socket do gerenciador de sockets
            SocketManager.removeSocketUDP(sock1)
            // fechar socket
            sock1!!.close()
            // retornar lista com os hosts encontrados
            return@withContext hostList.toList() // retornar lista de hosts
        } catch (e: Exception){
            // fechar socket, caso esteja aberto
            sock1?.let {
                if (!it.isClosed) {
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketUDP(sock1)
                    it.close()
                }
            }
            Log.d("CONEXÃO", "Erro, broadcast: ", e)
            // retornar lista com os hosts encontrados
            return@withContext null // retornar lista de hosts
        }
    }

    // função para verificar se há algum host conhecido na lista passada(retorna -1, em caso de erro)
    fun isKnownHostInList(broadcastHostList: List<Host>): Int{
        for ((index, value) in broadcastHostList.withIndex()) {
            // verificar se esse ip está na lista de hosts conhecidos
            if (value.ip in knownHosts) {
                return index
            }
        }
        // retornar erro
        return -1
    }

    // função para verificar se o host está na lista dos hosts conhecidos(retorna true se estiver na lista)
    fun isKnownHostInList(knownHost: String) = knownHost in knownHosts

    // função para verificar se o cliente está conectado em ALGUM servidor
    fun isConnected() = host != null

    // função chamada ao conectar o cliente no servidor
    private fun connect(host: String, position: Int){
        this.host = host // manter controle do host atual
        this.adapterHostPosition = position // manter controle da posição do host atual, usada no RecyclerView
        resetSeqId() // resetar sequência de comandos para início de conexão
        waitDisconnection() // chamar função que espera desconexão por parte do servidor
        // mostrar snackbar com o texto "Conectado!"
        showSnackBar(
            "Conectado!",
            Snackbar.LENGTH_SHORT,
            R.color.green,
            R.color.white
        )
        Log.d("CONEXÃO", "Cliente conectado no servidor: $host")
        wifiKeeper()
    }

    // função chamada ao desconectar o cliente do servidor
    private fun disconnect() {
        val message = byteArrayOf(AuthenticationUtility.CON_DESCONECTADO) // mensagem a ser enviada para o servidor
        // pacote a ser enviado via UDP
        val packet = DatagramPacket(
            message, // mensagem
            message.size, // tamanho da mensagem
            InetAddress.getByName(this.host), // endereço ip do host
            PORT_STATUS // porta para a qual a mensagem será enviada
        )

        try {
            sock2?.send(packet) // enviar mensagem ao servidor
            sock2?.close() // fechar socket de verificação de status
        } catch (e: Exception) {
            // verificar se o socket ainda está aberto, e fechar ele caso esteja
            if (!sock2!!.isClosed) {
                sock2!!.close()
            }
            Log.d("CONEXÃO", "Erro, desconexão: ", e)
        }

        // verificar se a acitivity atual é a ConnectionActivity
        if(BaseClass.currentActivity is ConnectionActivity){
            // mudar item no RecycleView, usar a thread de UI
            BaseClass.currentActivity?.runOnUiThread{
                (BaseClass.currentActivity as ConnectionActivity)
                    .adapter.updateItem(adapterHostPosition, "Desconectado!")
            }
        }

        Log.d("CONEXÃO", "Cliente desconectado do servidor: ${this.host}")
        this.host = null // manter controle do host atual
        // mostrar snackbar com o texto "Desconectado!"
        showSnackBar(
            "Desconectado!",
            Snackbar.LENGTH_SHORT,
            R.color.red,
            R.color.white
        )
    }

    // função chamada ao conectar em um servidor,
    // usada para manter controle de conexão entre servidor e cliente
    @OptIn(DelicateCoroutinesApi::class)
    private fun waitDisconnection(){
        // manter controle de conexão entre cliente e servidor, rodar em thread de IO.
        // continuar rodando até receber mensagem ou o aplicativo finalizar (GlobalScope)
        GlobalScope.launch(Dispatchers.IO){
            try{
                // verificar se o socket está fechado ou não foi criado ainda
                if (sock2 == null || sock2!!.isClosed){
                    // criar/abrir um novo socket
                    sock2 = DatagramSocket(PORT_LOGIN)
                }

                Log.d("CONEXÃO", "Controle de conexão iniciado")

                val buffer = ByteArray(1) // buffer para receber mensagems
                val response = DatagramPacket(buffer, buffer.size)

                // loop para receber mensagens de desconexão
                while(true){
                    // esperar até receber alguma mensagem
                    sock2!!.receive(response)
                    // verificar se resposta recebida é do servidor atualmente conectado
                    if(response.address.hostAddress == this@Session.host){
                        disconnect() // desconectar cliente do servidor
                        break
                    }
                }
            }catch (e: Exception){
                disconnect() // desconectar cliente do servidor
                Log.d("CONEXÃO", "Erro, esperando desconexão: ", e)
            }
            Log.d("CONEXÃO", "Controle de conexão finalizado")
        }
    }

    // função usada para manter controle do wifi
    private fun wifiKeeper() {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            // wifi conectado
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    Log.d("NETWORK", "Wi-Fi conectado")
                }
            }

            // wifi perdido ou rede desligada
            override fun onLost(network: Network) {
                disconnect()
                connectivityManager.unregisterNetworkCallback(this)
                Log.d("NETWORK", "Callback desregistrado")
                Log.d("NETWORK", "Rede perdida ou Wi-Fi desligado")
            }
        }

        // registrar callback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        Log.d("NETWORK", "Callback registrado")
    }

    // função para fazer login no servidor
    suspend fun login(
        cod: ByteArray, // código usado para fazer login
        host: String, // host no qual deseja logar
        adapterHostPosition: Int // posição do host, passado por parâmetro, dentro do RecyclerView
    ): String = withContext(Dispatchers.IO){
        try{
            // verificar se o socket é null ou está fechado
            if (sock3 == null || sock3!!.isClosed){
                // criar um novo socket
                sock3 = Socket()
                // permitir uso da porta logo após fechar ela
                sock3!!.reuseAddress = true
                // fazer o bind do socket na porta descrita
                sock3!!.bind(InetSocketAddress(PORT_LOGIN))
                // adicionar o socket ao gerenciador de sockets
                SocketManager.addSocketTCP(sock3)
            }

            val message = ByteArray(8) // mensagem a ser enviada
            message[0] = 0x08 // tamanho da mensagem em bytes
            message[1] = AuthenticationUtility.AUT_CODIGO // tipo de autenticação
            // copiar o código passado por parâmetro em message a partir da 3° posição
            System.arraycopy(cod, 0, message, 2, cod.size)

            // abrir conexão com o servidor
            sock3!!.connect(InetSocketAddress(host, PORT_LOGIN))
            val input = sock3!!.inputStream // recepção de dados
            val output = sock3!!.outputStream // envio de dados

            // enviar mensagem de verificação de status
            output.write(message) // escrever no buffer
            output.flush() // forçar envio

            val buffer = ByteArray(1) // buffer para receber mensagens
            val bytesRead = input.read(buffer) // receber mensagem e escrever no buffer
            if (bytesRead < 0) {
                throw IOException("servidor finalizou conexão")
            }
            val received = buffer[0] // obter mensagem gravada no buffer

            // enviar comfirmação para finalizar conexão
            output.write(message) // escrever no buffer
            output.flush() // forçar envio

            when(received){
                AuthenticationUtility.CON_CONECTADO -> {
                    // cliente já conectado
                    connect(host, adapterHostPosition)
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Cliente já conectado")

                    return@withContext "Conectado!"
                }
                AuthenticationUtility.CON_PERMITIDA -> {
                    // conexão permitida
                    connect(host, adapterHostPosition)
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Conexão normal")

                    return@withContext "Conectado!"
                }
                AuthenticationUtility.CON_NEGADA -> {
                    // conexão negada
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Conexão negada")

                    return@withContext "Negada!"
                }
                AuthenticationUtility.CON_PERMITIDA_C -> {
                    // conexão permitida com confiança
                    connect(host, adapterHostPosition)
                    if (host !in knownHosts){
                        knownHosts.add(host) // adicionar host na lista de hosts conhecidos
                        write() // salvar dados
                    }
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Conexão confiável")

                    return@withContext "Conectado!"
                }
                AuthenticationUtility.COD_INCORRETO -> {
                    // código errado
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Código incorreto")

                    return@withContext "Código Incorreto!"
                }
                AuthenticationUtility.CON_OCUPADA -> {
                    // servidor ocupado
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()

                    return@withContext "Indisponível!"
                }
            }
            // remover socket do gerenciador de sockets
            SocketManager.removeSocketTCP(sock3)
            sock3!!.close()

            return@withContext "Erro!"
        }catch (e: Exception){
            sock3?.let {
                if (!it.isClosed) {
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    it.close()
                }
            }
            Log.d("CONEXÂO", "Erro, autenticação por código:", e)

            return@withContext "Erro!"
        }

    }

    // função para fazer login no servidor, usando reconhecimento por parte do servidor
    suspend fun acknowledgmentAuthentication(
        knownHost: String,
        adapterHostPosition: Int
    ): String = withContext(Dispatchers.IO){
        try {
            // verificar se o socket é null ou está fechado
            if (sock3 == null || sock3!!.isClosed){
                // criar um novo socket
                sock3 = Socket()
                // permitir que a porta possa ser usada logo após fechar o socket
                sock3!!.reuseAddress = true
                // fazer o bind na porta "PORT_LOGIN"
                sock3!!.bind(InetSocketAddress(PORT_LOGIN))
                sock3!!.soTimeout = ValueUtility.CONNECTION_TIMEOUT
                // adicionar o socket ao gerenciador de sockets
                SocketManager.addSocketTCP(sock3)
            }

            val packet = ByteArray(2) // pacote a ser enviado
            packet[0] = 0x02 // tamanho do pacote
            packet[1] = AuthenticationUtility.AUT_CONFIANCA // tipo de autenticação

            // abrir conexão TCP com o servidor
            sock3!!.connect(InetSocketAddress(knownHost, PORT_LOGIN))
            val input = sock3!!.inputStream // recepção de dados
            val output = sock3!!.outputStream // envio de dados

            output.write(packet) // escrever no buffer
            output.flush() // forçar envio

            val buffer = ByteArray(1) // buffer para receber mensagens
            val bytesRead = input.read(buffer) // receber mensagem e escrever no buffer
            if (bytesRead < 0) {
                throw IOException("servidor finalizou conexão")
            }
            val received = buffer[0] // mensagem gravada no buffer

            // enviar mensagem de confirmação para finalizar conexão
            output.write(packet) // escrever no buffer
            output.flush() // forçar envio

            when(received){
                AuthenticationUtility.CON_PERMITIDA_C -> {
                    // conexão por reconhecimento permitida
                    connect(knownHost, adapterHostPosition)
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Conexão confiável permitida")

                    return@withContext "Conectado!"
                }

                AuthenticationUtility.CON_NEGADA -> {
                    // conexão por reconhecimento negada
                    knownHosts.remove(knownHost) // remover host da lista de hosts conhecidos
                    write() // salvar dados
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                    Log.d("CONEXÃO", "Conexão confiável negada")

                    return@withContext "Negada!"
                }
            }

            return@withContext "Erro!"
        } catch (e: Exception) {
            sock3?.let {
                if (!sock3!!.isClosed) {
                    // remover socket do gerenciador de sockets
                    SocketManager.removeSocketTCP(sock3)
                    sock3!!.close()
                }
            }
            Log.d("CONEXÂO", "Erro, autenticação por reconhecimento: ", e)

            return@withContext "Erro!"
        }

    }

    // função para fazer XOR da mensagem a ser enviada
    private fun checksumXor(message: ByteArray): Byte {
        var result = 0
        for (i in 0..3) {
            result = result xor (message[i].toInt() and 0xFF)
        }
        return result.toByte()
    }

    // função para aumentar a sequência de comandos até 255 e depois voltar a 0
    private fun addSeqId(){
        seqId = (seqId + 1) % 256
        Log.d("CONEXÃO", "SeqId atual: $seqId")
    }

    // função para zerar a sequência de comandos
    private fun resetSeqId(){
        seqId = 0
        Log.d("CONEXÃO", "SeqId resetado")
    }

    // função para enviar mensagem ao servidor
    suspend fun messageUDP(command: ByteArray, repeatTimes: Int = 1) =  withContext(Dispatchers.IO){
        if (command.size < 3) {
            Log.d("CONEXÃO", "Comando inválido")
            return@withContext
        }

        val message = ByteArray(5) // array a ser enviado como mensagem
        message[0] = seqId.toByte() // seqId atual
        // copiar o comando passado por parâmetro em message a partir da 2° posição
        System.arraycopy(command, 0, message, 1, command.size)
        message[4] = checksumXor(message) // checksum

        repeat(repeatTimes) {
            host?.let {
                try {
                    val packet = DatagramPacket(
                        message, // mensagem
                        message.size, // tamanho da mensagem
                        InetAddress.getByName(this@Session.host), // endereço ip do host
                        PORT_MESSAGE // porta para a qual a mensagem será enviada
                    )
                    sock4!!.send(packet)
                    addSeqId()
                } catch (e: Exception) {
                    Log.d("CONEXÃO", "Erro, mensagem UDP: ", e)
                }
            }
        }
    }
}

// função usada para mostrar uma snackbar na activity atual
fun showSnackBar(
    message: String,
    duration: Int = Snackbar.LENGTH_SHORT,
    backgroundColor: Int? = null,
    textColor: Int? = null,
    activity: Activity? = BaseClass.currentActivity
){
    // verificar se há alguma activity ativa para mostrar a snackbar
    activity?.let { activity ->
        // criar snackbar
        val snackBar = Snackbar.make(
            activity.findViewById(android.R.id.content),
            message,
            duration
        )

        // obter textView da snackbar
        val textView = snackBar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        // centralizar texto da snackbar
        textView?.let{
            it.textAlignment = View.TEXT_ALIGNMENT_CENTER
            it.gravity = Gravity.CENTER_HORIZONTAL
        }

        // mudar cor de fundo da snackbar
        backgroundColor?.let{
            snackBar.view.setBackgroundColor(ContextCompat.getColor(activity, it))
        }

        // mudar cor do texto da snackbar
        textColor?.let {
            snackBar.setTextColor(ContextCompat.getColor(activity, it))
        }

        // mostrar snackbar
        snackBar.show()
        Log.d("UI", "Snackbar mostrada")
    }
}