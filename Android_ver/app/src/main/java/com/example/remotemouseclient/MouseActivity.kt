package com.example.remotemouseclient

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.DatagramSocket
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MouseActivity : BaseClass() {
    // view model para cuidar da lógica do socket
    private val viewModel: SocketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // tela cheia com conteúdo dentro das áreas do sistema
        enableEdgeToEdge()
        // configurar o layout como view
        setContentView(R.layout.activity_mouse)
        // ajustar automaticamente o padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.touch_layout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // configurar a action bar
        setActionBar("Mouse Virtual")

        // impedir que o usuário tire prints
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // variáveis para manter controle de quantos dedos tocaram a tela
        var pointerCount = 0
        var oneFinger = false
        var twoFingers = false
        var threeFingers = false

        // variáveis para manter controle de botões pressionados
        var pressed = false
        var middleButtonPressed = false
        var leftButtonPressed = false
        var rightButtonPressed = false

        // variáveis para manter controle de tempo de toque
        var lastTapTime1 = 0L
        var lastTapTime2 = 0L
        var lastTapTime3 = 0L
        val doubleTapThreshold = ViewConfiguration.getDoubleTapTimeout()

        // variáveis para manter controle do movimento do dedo na tela
        var activePointerId = MotionEvent.INVALID_POINTER_ID
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var lastX = 0f
        var lastY = 0f

        // comando a ser enviado para o servidor
        val command = ByteArray(3)

        // view para receber eventos da tela
        val touchView = findViewById<View>(R.id.touch_layout)
        // lidar com eventos da tela
        touchView.setOnTouchListener {v, event ->
            // tempo do sistema
            val currentTime = System.currentTimeMillis()

            // filtrar eventos
            when(event.actionMasked) {
                // ação de toque na tela
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // verificar se o botão não está pressionado
                    if (!pressed) {
                        // manter controle de quantos dedos tocaram a tela
                        pointerCount = event.pointerCount

                        // reagir de acordo com a quantidade de dedos que tocaram a tela
                        when(pointerCount) {
                            1 -> { // 1 dedo, botão esquerdo do mouse
                                // manter controle do dedo principal na tela
                                activePointerId = event.getPointerId(0)
                                lastX = event.x
                                lastY = event.y
                                if (oneFinger && (currentTime - lastTapTime1) <= doubleTapThreshold) {
                                    // manter controle de clique
                                    oneFinger = false
                                    leftButtonPressed = true
                                    pressed = true
                                    pointerCount = 0

                                    // enviar comando
                                    command[0] = Session.MOUSE_LB_FLAG
                                    command[1] = Session.MOUSE_LB_DOWN
                                    command[2] = Session.PADDING
                                    viewModel.sendMessage(command)

                                    Log.d("TOUCHSCREEN", "Botão esquerdo pressionado")
                                } else {
                                    // resetar clique
                                    oneFinger = false
                                }
                                // manter controle para doubleTap
                                lastTapTime1 = currentTime
                            }

                            2 -> { // 2 dedos, botão direito do mouse
                                if (twoFingers && (currentTime - lastTapTime2) <= doubleTapThreshold) {
                                    // manter controle de clique
                                    twoFingers = false
                                    rightButtonPressed = true
                                    pressed = true
                                    pointerCount = 0

                                    // enviar comando
                                    command[0] = Session.MOUSE_RB_FLAG
                                    command[1] = Session.MOUSE_RB_DOWN
                                    command[2] = Session.PADDING
                                    viewModel.sendMessage(command)

                                    Log.d("TOUCHSCREEN", "Botão direito pressionado")
                                } else {
                                    // resetar clique
                                    twoFingers = false
                                }
                                // manter controle para doubleTap
                                lastTapTime2 = currentTime
                            }

                            3 -> { // 3 dedos, botão do meio do mouse
                                if (threeFingers && (currentTime - lastTapTime3) <= doubleTapThreshold) {
                                    // manter controle de clique
                                    threeFingers = false
                                    middleButtonPressed = true
                                    pressed = true
                                    pointerCount = 0

                                    // enviar comando
                                    command[0] = Session.MOUSE_MB_FLAG
                                    command[1] = Session.MOUSE_MB_DOWN
                                    command[2] = Session.PADDING
                                    viewModel.sendMessage(command)

                                    Log.d("TOUCHSCREEN", "Botão do meio pressionado")
                                } else {
                                    // resetar clique
                                    threeFingers = false
                                }
                                // manter controle para doubleTap
                                lastTapTime3 = currentTime
                            }

                            else -> { // ação padrão, pois não lógica para mais de 3 dedos
                                pointerCount = 3
                            }
                        }
                    }
                }

                // ação de movimento na tela
                MotionEvent.ACTION_MOVE -> {
                    // manter controle do dedo principal na tela
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    // verificar se o dedo principal está na tela
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dX = (x - lastX)
                        val dY = (y - lastY)

                        // calcular a distância percorrida pelo dedo principal
                        val distance = sqrt((dX * dX + dY * dY).toDouble())

                        // apenas aceitar movimentos maiores que o touchSlop
                        if (distance > touchSlop) {
                            // verificar se o botão do meio está pressionado
                            if (!middleButtonPressed && pointerCount == 3) { // botão do meio não pressionado e 3 dedos na tela, rodar roda do mouse
                                // suavizar movimento do mouse
                                val smoothDY = (dY*ValueUtility.MOUSE_SCROLL_ALPHA).roundToInt().coerceIn(-10, 10)

                                // enviar comandos suavizados
                                command[0] = Session.MOUSE_SCROLL_FLAG
                                command[1] = smoothDY.toByte()
                                command[2] = Session.PADDING
                                viewModel.sendMessage(command)

                                Log.d("TOUCHSCREEN", "Rodando roda do mouse: $smoothDY")
                            } else { // botão do meio pressionado ou com dedos < 3 , mover ponteiro do mouse
                                // suavizar movimento do mouse
                                val smoothDX = (dX*ValueUtility.MOUSE_POINTER_ALPHA).roundToInt().coerceIn(-25, 25)
                                val smoothDY = (dY*ValueUtility.MOUSE_POINTER_ALPHA).roundToInt().coerceIn(-25, 25)

                                // enviar comandos suavizados
                                command[0] = Session.MOUSE_POINTER_MOVEMENT_FLAG
                                command[1] = smoothDX.toByte()
                                command[2] = smoothDY.toByte()
                                viewModel.sendMessage(command)

                                Log.d("TOUCHSCREEN", "Movendo ponteiro do mouse: $smoothDX, $smoothDY")
                            }
                        }

                        // manter controle do dedo principal na tela
                        lastX = event.getX(pointerIndex)
                        lastY = event.getY(pointerIndex)
                    }
                }

                // ação de levantar dedo da tela, usada para manter controle do dedo principal
                MotionEvent.ACTION_POINTER_UP -> {
                    // verificar se foi o dedo principal que foi levantado
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        // obter novo dedo principal
                        val leavingIndex = event.actionIndex
                        val newPointerIndex = if (event.pointerCount > 1) {
                            if (leavingIndex == 0) 1 else 0
                        } else {
                            -1
                        }

                        // configurar novo dedo principal e seus parâmetros
                        if (newPointerIndex != -1) {
                            activePointerId = event.getPointerId(newPointerIndex)
                            lastX = event.getX(newPointerIndex)
                            lastY = event.getY(newPointerIndex)
                        } else {
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            Log.d("TOUCHSCREEN", "Pointer Nulo")
                        }
                    }
                }

                // ação de levantar dedo da tela, usada para manter controle de cliques
                MotionEvent.ACTION_UP -> {
                    // zerar parâmetros de localização
                    lastY = 0f
                    lastX = 0f

                    // manter controle de quantos dedos tocaram a tela
                    when(pointerCount) {
                        1 -> {
                            oneFinger = true
                        }

                        2 -> {
                            twoFingers = true
                        }

                        3 -> {
                            threeFingers = true
                        }
                    }

                    // reagir de acordo com o botão pressionado
                    when(true) {
                        leftButtonPressed -> {
                            // manter controle de clique
                            Log.d("TOUCHSCREEN", "Botão esquerdo solto")
                            leftButtonPressed = false
                            pressed = false

                            // enviar comandos
                            command[0] = Session.MOUSE_LB_FLAG
                            command[1] = Session.MOUSE_LB_UP
                            command[2] = Session.PADDING
                            viewModel.sendMessage(command)
                        }
                        rightButtonPressed -> {
                            // manter controle de clique
                            Log.d("TOUCHSCREEN", "Botão direito solto")
                            rightButtonPressed = false
                            pressed = false

                            // enviar comandos
                            command[0] = Session.MOUSE_RB_FLAG
                            command[1] = Session.MOUSE_RB_UP
                            command[2] = Session.PADDING
                            viewModel.sendMessage(command)
                        }
                        middleButtonPressed -> {
                            // manter controle de clique
                            Log.d("TOUCHSCREEN", "Botão do meio solto")
                            middleButtonPressed = false
                            pressed = false

                            // enviar comandos
                            command[0] = Session.MOUSE_MB_FLAG
                            command[1] = Session.MOUSE_MB_UP
                            command[2] = Session.PADDING
                            viewModel.sendMessage(command)
                        }
                        else -> Unit
                    }

                    // manter controle do dedo principal na tela
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }

            // usado para motivos de acessibilidade
            v.performClick()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // configurar socket para enviar comandos
        if (session.sock4 == null || session.sock4!!.isClosed) {
            session.sock4 = DatagramSocket()
            // manter controle do socket
            SocketManager.addSocketUDP(session.sock4)
        }
    }
}