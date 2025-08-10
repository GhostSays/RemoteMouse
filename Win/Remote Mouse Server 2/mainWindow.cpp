#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>
#include <ShellScalingAPI.h>
#include <iostream>
#include <fstream>
#include <vector>
#include <unordered_set>
#include <algorithm>
#include <random>
#include <string>
#include <thread>
#include <atomic>
#include "resource.h"

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "Shcore.lib")

#define PORTA_STATUS 54321 // porta para verificar o status do servidor
#define PORTA_COMANDOS 53100 // porta usada para login e escutar comandos
#define WM_TRAYICON (WM_USER + 1) // mensagem personalizada para o ícone na bandeja do sistema
#define ID_TRAY_EXIT 1001 // ID do item de menu "Sair" no ícone da bandeja
#define ID_BTN_DESCONECTAR 1002 // ID do botão "Desconectar" na janela principal
#define ID_MENU_SOBRE 40001 // ID do menu "Sobre"
#define ID_MENU_DISPOSITIVOS 40002 // ID do menu "Dispositivos"

// enumeração para os comandos do mouse
enum MouseCommand : uint8_t {
	MOUSE_MOVE = 0x01, // comando para mover o mouse
	MOUSE_RIGHTBUTTON = 0x02, // comando para o botão direito do mouse
	MOUSE_LEFTBUTTON = 0x03, // comando para o botão esquerdo do mouse
	MOUSE_MIDDLEBUTTON = 0x04, // comando para o botão do meio do mouse
	MOUSE_WHEEL = 0x05 // comando para a roda do mouse
};

// enumeração para os tipos de autenticação
enum TiposAutenticacao :uint8_t {
    AUTENTICACAO_CODIGO = 0x01, // autenticação por código
	AUTENTICACAO_C = 0x02 // autenticação por dispositivo confiável
};

// enumeração para as respostas do servidor
enum RespostasServidor : uint8_t {
	CON_PERMITIDA = 0x01, // conexão permitida
	CON_NAO_PERMITIDA = 0x02, // conexão não permitida
	CON_PERMITIDA_C = 0x03, // conexão permitida, dispositivo confiável
	CON_CODIGO_INCORRETO = 0x04, // código incorreto
	SERVIDOR_OCUPADO = 0x05, // servidor ocupado
    CLIENTE_CONECTADO = 0x06, // cliente já conectado
    SERVIDOR_LIVRE = 0x07, // servidor livre
    DESCONEXAO = 0x08 // desconexão do cliente feita pelo servidor
};

// enumeração para os tipos de autenticação
enum AutenticacaoCodigo : int {
	AUT_CODIGO_INCORRETO = -1, // código incorreto
	AUT_PERMITIDA = 1, // autenticação permitida
	AUT_NAO_PERMITIDA = 0, // autenticação não permitida
	AUT_PERMITIDA_C = 2 // autenticação permitida, dispositivo confiável
};

enum Bounds : int {
	JANELA_PRINCIPAL_LARGURA = 300, // largura da janela principal
	JANELA_PRINCIPAL_ALTURA = 300, // altura da janela principal
	TEXTO_LARGURA = 170, // largura do controle de texto
	TEXTO_ALTURA = 100, // altura do controle de texto
	TEXTO_X = 60, // posição X do controle de texto
	TEXTO_Y = 80, // posição Y do controle de texto
	BOTAO_DESCONECTAR_LARGURA = 120, // largura do botão "Desconectar"
	BOTAO_DESCONECTAR_ALTURA = 40, // altura do botão "Desconectar"
    BOTAO_DESCONECTAR_X = 90, // posição X do botão "Desconectar"
    BOTAO_DESCONECTAR_Y = 200, // posição Y do botão "Desconectar"
	FONTE_TAMANHO = 20 // tamanho da fonte usada no programa
};

// dados da aplicação
struct AppData {
    HWND hwndStatic;  // controle de texto
};

// prototipos das funções
LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam);
INT_PTR CALLBACK DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam);
void VerificarStatus(unsigned short port, SOCKET& outSocket);
void ReceberComandos(unsigned short port, SOCKET& outSocketUDP, SOCKET& outSocketTCP, AppData* pData);
bool CheckSum(const uint8_t(&buffer)[5]);
void SimularMouse(const uint8_t(&buffer)[5]);
void MostrarErroFormatado(const char* titulo, const char* formato, ...);
wchar_t* StringParaWChar(const std::string& str);

// classe para gerenciar a conscientização de DPI
class DpiAwareness {
	HMONITOR hMonitor; // monitor associado à janela
	HWND hwnd; // handle da janela
	UINT dpi; // DPI do monitor

    public:
        void Init(HWND hwnd) {
            this->hwnd = hwnd;
            hMonitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            GetDpiForMonitor(hMonitor, MDT_EFFECTIVE_DPI, &dpi, &dpi);
		}

        int Scale(int value) {
            return MulDiv(value, dpi, 96); // 96 é o DPI padrão (100%)
        }
};

// classe usada para criação de sessões 
class Sessao {
    bool ocupado; // indica se a sessão está ocupada
	HINSTANCE hInstance; // handle da instância do aplicativo
    HWND hBtnDesconectar; // handle do botão "Desconectar"
	HWND hwnd; // handle da janela principal
    AppData* pData; // ponteiro para os dados da aplicação
	SOCKET socketStatus; // socket para enviar mensagens de status
    std::vector<sockaddr_in> dispositivosConhecidos; // lista de dispositivos conhecidos
    sockaddr_in clienteAtivo; // endereço do cliente ativo
	std::string textoEspera = u8"Esperando Conexão\nCódigo: "; // texto exibido enquanto espera por conexão
	std::string textoConectado = u8"Conectado\n IP: "; // texto exibido quando conectado
    std::string codigo; // código de autenticação gerado aleatoriamente
	uint8_t seqId; // ID sequencial para identificar a sessão

	// método para gerar um código aleatório de 6 caracteres
    void GerarCodigo() {
        const std::string caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        const int tamanho = caracteres.size();

        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<> distrib(0, tamanho - 1);

        std::string codigoGerado;

        for (int i = 0; i < 6; ++i) {
            codigoGerado += caracteres[distrib(gen)];
        }
        codigo = codigoGerado;
    }

	// atualizar o texto da janela para mostrar que está esperando conexão
    void SetWindowTextTextoEspera() {
        wchar_t* texto = StringParaWChar(textoConectado);
        SetWindowText(pData->hwndStatic, StringParaWChar(textoEspera + codigo));
        delete[] texto; // liberar memória alocada
    }

	// atualizar o texto da janela para mostrar que está conectado
    void SetWindowTextConectado() {
        // converter IP para string
        char ipStr[INET_ADDRSTRLEN] = { 0 };
        inet_ntop(AF_INET, &clienteAtivo.sin_addr, ipStr, sizeof(ipStr));

        // obter string da porta (convertendo da ordem de rede para a ordem do host)
        std::string porta = std::to_string(ntohs(clienteAtivo.sin_port));

		// formatar o texto com o IP
        wchar_t* texto = StringParaWChar(textoConectado+ipStr+"\n Porta: " + porta);
        SetWindowText(pData->hwndStatic, texto);
        delete[] texto; // liberar memória alocada
    }

	// salvar a lista de dispositivos conhecidos em um arquivo binário
    void SalvarLista() {
        std::ofstream out("dados.dat", std::ios::binary);
		// verificar se o arquivo foi aberto corretamente
        if (!out) {
            OutputDebugString(L"Erro ao abrir arquivo para salvar lista de dispositivos conhecidos.\n");
		    return;
        }
		// escrever os dados no arquivo e fechar o arquivo
        size_t tam = dispositivosConhecidos.size();
        out.write(reinterpret_cast<const char*>(&tam), sizeof(size_t));
        out.write(reinterpret_cast<const char*>(dispositivosConhecidos.data()), tam * sizeof(sockaddr_in));
        out.close();
		OutputDebugString(L"Lista de dispositivos conhecidos salva com sucesso.\n");
    }

	// carregar a lista de dispositivos conhecidos de um arquivo binário para a memória
    void CarregarLista() {
        std::ifstream in("dados.dat", std::ios::binary);
		// verificar se o arquivo foi aberto corretamente
        if (!in) {
            OutputDebugString(L"Erro ao carregar lista de dispositivos conhecidos.\n");
            return;
        }
		// ler os dados do arquivo e armazenar na lista de dispositivos conhecidos
        size_t tam;
        in.read(reinterpret_cast<char*>(&tam), sizeof(size_t));
        dispositivosConhecidos.resize(tam);
        in.read(reinterpret_cast<char*>(dispositivosConhecidos.data()), tam * sizeof(sockaddr_in));
        in.close();
		OutputDebugString(L"Lista de dispositivos conhecidos carregada com sucesso.\n");
	}

    public:

	    // construtor da classe Sessao
        Sessao() {
		    // inicializar os membros da classe
            this->pData = nullptr;
		    this->hInstance = nullptr;
		    this->hwnd = nullptr;
		    this->hBtnDesconectar = nullptr;
		    this->socketStatus = INVALID_SOCKET;
		    this->ocupado = false;
		    this -> seqId = 0;
            clienteAtivo = {};
		    CarregarLista(); // carregar lista de dispositivos conhecidos ao iniciar a sessão
        }

	    // método para passar os dados da aplicação para a sessão
        void ControleDeTexto(AppData* pData) {
            this->pData = pData;
        }

	    // método para passar o handle da instância da janela principal
        void SetHInstance(const HINSTANCE &hInstance) {
            this->hInstance = hInstance;
	    }

	    // método para passar o handle da janela principal
        void SetHWND(const HWND &hwnd) {
            this->hwnd = hwnd;
	    }

	    // método para passar o handle do botão "Desconectar"
        void SetHBtnDesconectar(const HWND &hBtnDesconectar) {
            this->hBtnDesconectar = hBtnDesconectar;
        }

	    // método para passar o socket de status
        void SetSocketStatus(SOCKET &socket) {
            this->socketStatus = socket;
	    }

	    // método para verificar se o dispositivo é dado como confiável
        bool DispositivoEhConhecido(sockaddr_in clientAddr) {
            auto it = std::find_if(dispositivosConhecidos.begin(), dispositivosConhecidos.end(),[clientAddr](const sockaddr_in& c) {
                return c.sin_addr.s_addr == clientAddr.sin_addr.s_addr;}
            );
            if (it != dispositivosConhecidos.end()) {
			    clienteAtivo = clientAddr; // salvar o cliente ativo
			    ocupado = true; // marcar a sessão como ocupada
			    SetWindowTextConectado(); // atualizar o texto da janela
			    ShowWindow(hBtnDesconectar, SW_SHOW); // mostrar o botão "Desconectar"
                OutputDebugString(L"Dispositivo conhecido conectado.\n");
                seqId = 0; // resetar seqId para uma nova conexão
                OutputDebugString(L"SeqId resetado.\n");
			    return true; // dispositivo conhecido
            }
            else {
                OutputDebugString(L"Conexão negada, dispositivo desconhecido.\n");
			    return false; // dispositivo desconhecido
            }
        }

	    // método para autenticar o cliente com base no código recebido
        int Autenticar(std::string codigoRecebido, sockaddr_in clientAddr) {
            if (codigoRecebido == codigo) {
			    // receber a respoa do usuário na janela de diálogo
			    INT_PTR conexao = DialogBox(hInstance, MAKEINTRESOURCE(IDD_DIALOG1), hwnd, DialogProc);
			    // responder com base na resposta do usuário
                if (conexao == 1 || conexao == 0) {
                    if (conexao == 1) {
					    clienteAtivo = clientAddr; // salvar o cliente ativo
					    ocupado = true; // marcar a sessão como ocupada
					    SetWindowTextConectado();// atualizar o texto da janela
					    ShowWindow(hBtnDesconectar, SW_SHOW); // mostrar o botão "Desconectar"
					    // verificar se o dispositivo já não esta na lista de conhecidos
                        auto it = std::find_if(dispositivosConhecidos.begin(), dispositivosConhecidos.end(), [clientAddr](const sockaddr_in& c) {
                            return c.sin_addr.s_addr == clientAddr.sin_addr.s_addr; }
                        );
                        if (it == dispositivosConhecidos.end()) {
                            dispositivosConhecidos.push_back(clientAddr); // adicionar dispositivo à lista de conhecidos
                            SalvarLista(); // salvar a lista de dispositivos conhecidos
						    OutputDebugString(L"Dispositivo conectado e adicionado à lista de conhecidos.\n");
                        }
                        else {
						    OutputDebugString(L"Dispositivo conectado, mas já conhecido, não adicionado novamente.\n");
                        }
                        seqId = 0; // resetar seqId para uma nova conexão
                        OutputDebugString(L"SeqId resetado.\n");
					    return AUT_PERMITIDA_C; // autenticação bem-sucedida e dispositivo confiável
                    }
                    else {
                        // dispositivo não é confiável
					    clienteAtivo = clientAddr; // salvar o cliente ativo
					    ocupado = true; // marcar a sessão como ocupada
                        SetWindowTextConectado(); // atualizar o texto da janela principal
					    ShowWindow(hBtnDesconectar, SW_SHOW); // mostrar o botão "Desconectar"
					    OutputDebugString(L"Dispositivo conectado, mas não confiável.\n");
                        seqId = 0; // resetar seqId para uma nova conexão
                        OutputDebugString(L"SeqId resetado.\n");
					    return AUT_PERMITIDA; // autenticação bem-sucedida, mas dispositivo não confiável
                    }
                }
                else {
				    OutputDebugString(L"Usuário recusou a conexão.\n");
				    return AUT_NAO_PERMITIDA; // acesso recusado pelo usuário
                }
            }
            else {
			    OutputDebugString(L"Código incorreto recebido.\n");
			    return AUT_CODIGO_INCORRETO; // código incorreto
            }
        }

	    // método para verificar se o dispositivo já está conectado
        bool DispositivoConectado(sockaddr_in clientAddr) {
		    // retorna true se o endereço do cliente ativo for igual ao endereço do cliente recebido  
            return clienteAtivo.sin_addr.s_addr == clientAddr.sin_addr.s_addr;
	    }

	    // método para atualizar a sessão quando uma sessão é finalizada
        void AtualizarSessao() {
		    clienteAtivo = {}; // limpar o cliente ativo
		    ocupado = false; // liberar a sessão
		    GerarCodigo(); // gerar um novo código
		    SetWindowTextTextoEspera(); // atualizar o texto da janela
		    ShowWindow(hBtnDesconectar, SW_HIDE); // esconder o botão "Desconectar"
		    OutputDebugString(L"Sessão atualizada, esperando nova conexão.\n");
        }

	    // método para desconectar o cliente ativo e enviar um byte de desconexão forçada
        void Desconectar() {
		    uint8_t desconexao = DESCONEXAO; // byte de desconexão
            OutputDebugString(L"Enviando byte de desconexão forçada para cliente conectado.\n");
		    int sent = sendto(socketStatus, (const char*)&desconexao, sizeof(desconexao), 0, (sockaddr*)&clienteAtivo, sizeof(sockaddr_in));
            if (sent == SOCKET_ERROR) {
                OutputDebugString(L"Erro ao enviar byte de desconexão forçada.\n");
            }
            else {
			    OutputDebugString(L"Byte de desconexão forçada enviado com sucesso.\n");
            }
		    AtualizarSessao(); // atualizar a sessão
        }

	    // método para verificar se o servidor está ocupado
        bool ServidorOcupado() {
            return ocupado;
        }

	    // método para limpar a lista de dispositivos conhecidos e atualizar a ListBox e o arquivo
        void LimparListaDispositivos(HWND hDlg) {
            if (dispositivosConhecidos.empty()) {
			    OutputDebugString(L"A lista de dispositivos conhecidos já está vazia.\n");
            }
            else {
                dispositivosConhecidos.clear(); // limpar a lista de dispositivos conhecidos
                AtualizarListBox(hDlg); // atualizar a ListBox na janela principal
                SalvarLista(); // salvar a lista vazia
                OutputDebugString(L"Lista de dispositivos conhecidos limpa.\n");
            }
        }

	    // método para atualizar a ListBox com os dispositivos conhecidos
        void AtualizarListBox(HWND hDlg) {
		    HWND hList = GetDlgItem(hDlg, IDC_LIST1); // obter o handle da ListBox
		    char ipStr[INET_ADDRSTRLEN] = { 0 }; // string para armazenar o endereço IP
		    WCHAR ipWStr[INET_ADDRSTRLEN] = { 0 }; // string para armazenar o endereço IP em formato wide
		    SendMessage(hList, LB_RESETCONTENT, 0, 0); // limpar a ListBox antes de adicionar novos itens
            for(const auto& dispositivo : dispositivosConhecidos) {           
                inet_ntop(AF_INET, &dispositivo.sin_addr, ipStr, sizeof(ipStr));// Converter o endereço IP para string
                mbstowcs_s(NULL, ipWStr, INET_ADDRSTRLEN, ipStr,_TRUNCATE); // converter char → wchar_t
			    SendMessage(hList, LB_ADDSTRING, 0, (LPARAM)ipWStr); // adicionar o IP à ListBox
		    }
		    OutputDebugString(L"ListBox atualizada com os dispositivos conhecidos.\n");
        }

        uint8_t LerSeqId() {
            return this->seqId;
        }

        void SeqId(uint8_t &seq) {
            this->seqId = seq;
        }
};

std::atomic<bool> rodando(true); // variável atômica para controlar o loop das threads

NOTIFYICONDATA nid = {}; // dados do ícone na bandeja do sistema
HMENU hTrayMenu; // menu do ícone na bandeja do sistema
HFONT hFont; // fonte usada no programa

Sessao sessaoAtual; // instância da classe Sessao para gerenciar a sessão atual

DpiAwareness dpiAwareness; // instância da classe DpiAwareness para gerenciar o dpi awareness

// função para rodar o programa principal
int WINAPI WinMain(
    _In_ HINSTANCE hInstance,
    _In_opt_ HINSTANCE hPrevInstance,
    _In_ LPSTR lpCmdLine,
    _In_ int nCmdShow
) {
    // definir a consciência de DPI do processo para escalar corretamente em monitores de alta DPI
	SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE);
	
    // registrar a classe da janela
	const wchar_t CLASS_NAME[] = L"RemoteMouseServerClass"; // nome da classe da janela
	WNDCLASSEX wc = {}; // estrutura para registrar a classe da janela
	wc.cbSize = sizeof(WNDCLASSEX); // tamanho da estrutura
	wc.lpfnWndProc = WndProc; // função de callback para processar mensagens da janela
	wc.hInstance = hInstance; // handle da instância do aplicativo
	wc.hIcon = (HICON)LoadImage(hInstance, MAKEINTRESOURCE(IDI_ICON1), IMAGE_ICON, 32, 32, LR_DEFAULTCOLOR); // ícone da janela 32x32
	wc.hIconSm = (HICON)LoadImage(hInstance, MAKEINTRESOURCE(IDI_ICON1), IMAGE_ICON, 16, 16, LR_DEFAULTCOLOR); // ícone da janela 16x16
	wc.lpszClassName = CLASS_NAME; // setar nome da classe da janela 
	RegisterClassEx(&wc);// registrar a classe da janela

    // Alocar espaço para a struct com dados da aplicação
    AppData* pData = new AppData();

	// carregar menu da janela principal, carregado do .rc
	HMENU hMenu = LoadMenu(hInstance, MAKEINTRESOURCE(IDR_MENU1));

    // criar a janela
    HWND hWnd = CreateWindowExW(
		0, // estilo estendido
		CLASS_NAME, // nome da classe da janela
		L"Mouse Remoto", // título da janela
		WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, // estilo da janela
		CW_USEDEFAULT, CW_USEDEFAULT, // posição
		NULL, NULL, // largura e altura
        NULL, hMenu, hInstance, pData
    );

	// verificar se a janela foi criada corretamente e tratar
    if (hWnd == NULL) {
        if (pData) {
            delete pData;
            OutputDebugString(L"Memória pData liberada.\n");
        }

        MostrarErroFormatado("Erro", "Não foi possível criar a janela: %d", GetLastError());
        return 1; // erro ao criar a janela
    }
  
	int width = dpiAwareness.Scale(JANELA_PRINCIPAL_LARGURA); // largura da janela principal
	int height = dpiAwareness.Scale(JANELA_PRINCIPAL_ALTURA); // altura da janela principal
	RECT rect = { 0, 0, width, height }; // rect das dimensões da janela principal
	AdjustWindowRect(&rect, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, FALSE); // definir o valor do rect para ajustar a janela ao tamanho correto

	// definir o tamanho da janela principal
	SetWindowPos(
        hWnd, NULL,
		0, 0, // posição X e Y
		rect.right - rect.left, rect.bottom - rect.top, // largura e altura ajustadas
		SWP_NOZORDER | SWP_NOMOVE // não mover a janela, apenas redimensionar
    );

	sessaoAtual.SetHInstance(hInstance); // definir a instância do aplicativo na sessão atual
	sessaoAtual.SetHWND(hWnd); // definir o handle da janela principal na sessão atual

    // Adiciona ícone à bandeja
	nid.cbSize = sizeof(nid); // tamanho da estrutura NOTIFYICONDATA
	nid.hWnd = hWnd; // handle da janela principal
	nid.uID = 1; // ID do ícone na bandeja
	nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP; // flags para o ícone na bandeja
	nid.uCallbackMessage = WM_TRAYICON; // mensagem personalizada para o ícone na bandeja
	nid.hIcon = (HICON)LoadImage(hInstance, MAKEINTRESOURCE(IDI_ICON1), IMAGE_ICON, 16, 16, LR_DEFAULTCOLOR); // ícone do ícone na bandeja
	wcscpy_s(nid.szTip, L"Servidor On-line"); // texto exibido quando o mouse passa sobre o ícone na bandeja
	Shell_NotifyIcon(NIM_ADD, &nid); // adicionar o ícone à bandeja do sistema

	WSADATA wsaData; // estrutura para armazenar informações do Winsock
    // verificar se o Winsock foi inicializado corretamente
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        MostrarErroFormatado("Erro", "Não foi possível inicializar o Winsock: %d", WSAGetLastError());
		// liberar recursos alocados
        DestroyWindow(hWnd);
        return 1;
    }

	SOCKET sock1 = INVALID_SOCKET, sock2 = INVALID_SOCKET, sock3 = INVALID_SOCKET; // sockets criadas para comunicação
    std::thread th1, th2; // criar threads para inicializar depois
	bool th1Running = false, th2Running = false; // variáveis para controlar se as threads estão rodando

    try {
		th1 = std::thread(VerificarStatus, PORTA_STATUS, std::ref(sock1)); // iniciar a thread para verificar o status do servidor
		th1Running = true; // marcar a thread de status como rodando
		th2 = std::thread(ReceberComandos, PORTA_COMANDOS, std::ref(sock2), std::ref(sock3), pData); // iniciar a thread para login e receber comandos do cliente
		th2Running = true; // marcar a thread de comandos como rodando
    }
    catch (const std::exception& e) {// tratar erros ao iniciar as threads e finalizar corretamente   
        MostrarErroFormatado("Erro", "Não foi possível iniciar as threads: %s", e.what());

        // verificar se há algum cliente conectado
        if (sessaoAtual.ServidorOcupado()) {
			sessaoAtual.Desconectar(); // desconectar o cliente ativo
        }

		// liberar recursos alocados
        if (sock1 != INVALID_SOCKET) {
            closesocket(sock1);
            OutputDebugString(L"Socket sock1 fechado.\n");
        }
        if (sock2 != INVALID_SOCKET) {
            closesocket(sock2);
            OutputDebugString(L"Socket sock2 fechado.\n");
        }
        if (sock3 != INVALID_SOCKET) {
            closesocket(sock3);
            OutputDebugString(L"Socket sock3 fechado.\n");
        }
        DestroyWindow(hWnd);

        // esperar as threads terminarem
        if (th1Running) {
            th1.join();
            th1Running = false;
        }
        if (th2Running) {
            th2.join();
            th2Running = false;
        }

        // liberar recursos
        WSACleanup();
        OutputDebugString(L"Winsock limpo.\n");

		return 1; // erro ao iniciar as threads
    }

    // receber mensagens da janela
    MSG msg = {};
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    // verificar se há algum cliente conectado e enviar mensagem de desconexão forçada
    if (sessaoAtual.ServidorOcupado()) {
		sessaoAtual.Desconectar(); // desconectar o cliente ativo
    }


	// liberar recursos alocados
    if (sock1 != INVALID_SOCKET) {
        closesocket(sock1);
        OutputDebugString(L"Socket sock1 fechado.\n");
    }
    if (sock2 != INVALID_SOCKET) {
        closesocket(sock2);
        OutputDebugString(L"Socket sock2 fechado.\n");
    }
    if (sock3 != INVALID_SOCKET) {
        closesocket(sock3);
        OutputDebugString(L"Socket sock3 fechado.\n");
    }

    // esperar as threads terminarem
    if (th1Running) {
        th1.join();
        th1Running = false;
    }
    if (th2Running) {
        th2.join();
        th2Running = false;
    }
 
	//liberar recursos alocados
    WSACleanup();
    OutputDebugString(L"Winsock limpo.\n");

	return 0; // sucesso ao finalizar o aplicativo
}

// função de callback para processar mensagens da janela
LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
	AppData* pData = (AppData*)GetWindowLongPtr(hWnd, GWLP_USERDATA); // obter o ponteiro para a struct de dados
	static HINSTANCE hInstance; // variável estática para armazenar a instância do aplicativo

    switch (message) {
        case WM_CREATE: {// tratar a mensagem WM_CREATE, que é enviada quando a janela é criada

            CREATESTRUCT* cs = (CREATESTRUCT*)lParam; // accessar o ponteiro para a struct de dados
		    hInstance = cs->hInstance; // obter a instância do aplicativo
		    pData = (AppData*)cs->lpCreateParams; // passar o ponteiro para a struct de dados

            SetWindowLongPtr(hWnd, GWLP_USERDATA, (LONG_PTR)pData); // guardar a struct de dados na janela

		    dpiAwareness.Init(hWnd); // inicializar a conscientização de DPI

            int tamanhoFonte = dpiAwareness.Scale(FONTE_TAMANHO); // escalar o tamanho da fonte

            // criar uma fonte para o programa
            hFont = CreateFontW(
                tamanhoFonte, // tamanho da fonte
                0, 0, 0, // estilo da fonte
                FW_BOLD, // negrito
                FALSE, FALSE, FALSE, // não usar itálico, sublinhado ou tachado 
                ANSI_CHARSET, OUT_DEFAULT_PRECIS, // charset ANSI e precisão de saída padrão
                CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, // qualidade de saída padrão
                DEFAULT_PITCH | FF_SWISS, // espaçamento padrão e família de fontes suíça
                L"Arial" // nome da fonte
            );

		    int textoLargura = dpiAwareness.Scale(TEXTO_LARGURA); // escalar a largura do controle de texto
		    int textoAltura = dpiAwareness.Scale(TEXTO_ALTURA); // escalar a altura do controle de texto
		    int textoX = dpiAwareness.Scale(TEXTO_X); // escalar a posição X do controle de texto
		    int textoY = dpiAwareness.Scale(TEXTO_Y); // escalar a posição Y do controle de texto

            // criar um controle de texto
            pData->hwndStatic = CreateWindowExW(
			    0, // estilo estendido
			    L"STATIC", // classe do controle de texto 
			    L"Iniciando Servidor", // texto inicial
			    WS_CHILD | WS_VISIBLE | SS_CENTER, // estilo do controle de texto
			    textoX, textoY, // posição x, y
			    textoLargura, textoLargura, // largura, altura
                hWnd, NULL, cs->hInstance, NULL
            );
            // aplicar a fonte ao controle de texto
            SendMessage(pData->hwndStatic, WM_SETFONT, (WPARAM)hFont, TRUE);

            sessaoAtual.ControleDeTexto(pData); // passar os dados da aplicação para a sessão

		    int botaoDLargura = dpiAwareness.Scale(BOTAO_DESCONECTAR_LARGURA); // escalar a largura do botão "Desconectar"
		    int botaoDAltura = dpiAwareness.Scale(BOTAO_DESCONECTAR_ALTURA); // escalar a altura do botão "Desconectar"
		    int botaoDX = dpiAwareness.Scale(BOTAO_DESCONECTAR_X); // escalar a posição X do botão "Desconectar"
		    int botaoDY = dpiAwareness.Scale(BOTAO_DESCONECTAR_Y); // escalar a posição Y do botão "Desconectar"

		    // criar um botão "Desconectar"
            HWND hBtnDesconectar = CreateWindowEx(
                0, // estilo estendido
                L"BUTTON", // Classe do botão
                L"", // texto
                WS_TABSTOP | WS_CHILD | BS_OWNERDRAW, // estilo
                botaoDX, botaoDY, // posição x, y
                botaoDLargura, botaoDAltura, // largura, altura
                hWnd, // janela pai
                (HMENU)ID_BTN_DESCONECTAR, // ID do botão
			    (HINSTANCE)GetWindowLongPtr(hWnd, GWLP_HINSTANCE), // instância do aplicativo
                NULL    // parâmetro adicional
            );
            // aplicar a fonte ao botão "Desconectar"
            SendMessage(hBtnDesconectar, WM_SETFONT, (WPARAM)hFont, TRUE);

		    sessaoAtual.SetHBtnDesconectar(hBtnDesconectar); // passar o handle do botão de desconectar para a sessão atual

            break;
        }
        case WM_CTLCOLORSTATIC: { // tratar a mensagem WM_CTLCOLORSTATIC, que é enviada quando um controle estático precisa ser pintado

		    HDC hdcStatic = (HDC)wParam; // handle do contexto de dispositivo do controle estático
		    SetTextColor(hdcStatic, RGB(0, 0, 0)); // definir a cor do texto do controle estático
            SetBkColor(hdcStatic, GetSysColor(COLOR_WINDOW)); // mesma cor da janela

            return (LRESULT)GetSysColorBrush(COLOR_WINDOW); // pintar o fundo corretamente
        }
	    case WM_PAINT: { // tratar a mensagem WM_PAINT, que é enviada quando a janela precisa ser repintada

		    PAINTSTRUCT ps; // estrutura para armazenar informações de pintura
		    HDC hdc = BeginPaint(hWnd, &ps); // iniciar a pintura da janela

		    RECT rect; // estrutura para armazenar as coordenadas do retângulo
		    GetClientRect(hWnd, &rect); // obter as coordenadas do retângulo da janela
		    FillRect(hdc, &rect, (HBRUSH)(COLOR_WINDOW + 1)); // preencher o fundo da janela com a cor do sistema

		    EndPaint(hWnd, &ps); // finalizar a pintura da janelas

            return 0;
        }
        case WM_DRAWITEM: { // tratar a mensagem WM_DRAWITEM, que é enviada quando um item de controle precisa ser desenhado

		    LPDRAWITEMSTRUCT lpDraw = (LPDRAWITEMSTRUCT)lParam; // obter a estrutura de desenho do item

            if (lpDraw->CtlID == ID_BTN_DESCONECTAR) {
                // determinar cor do botão dependendo do estado
			    COLORREF bgColor; // cor de fundo do botão

                if (lpDraw->itemState & ODS_SELECTED) {
				    bgColor = RGB(200, 50, 50); // pressionado, cor vermelho escuro
                }
                else {
				    bgColor = RGB(255, 100, 100); // normal, cor vermelho claro
                }

                // preencher fundo
			    HBRUSH hBrush = CreateSolidBrush(bgColor); // criar um pincel com a cor de fundo
			    FillRect(lpDraw->hDC, &lpDraw->rcItem, hBrush); // preencher o retângulo do item com o pincel
			    DeleteObject(hBrush); // liberar o pincel

			    // texto centralizado e fundo transparente
			    SetBkMode(lpDraw->hDC, TRANSPARENT); // definir o modo de fundo transparente
			    SetTextColor(lpDraw->hDC, RGB(255, 255, 255)); // definir a cor do texto como branco

			    // desenhar o texto "Desconectar" no botão
                DrawText(lpDraw->hDC, L"Desconectar", -1, &lpDraw->rcItem,
                    DT_CENTER | DT_VCENTER | DT_SINGLELINE);

                return TRUE;
            }
            break;
        }
	    case WM_TRAYICON: { // tratar a mensagem personalizada do ícone na bandeja do sistema

            if (lParam == WM_RBUTTONUP) {
                // tratar o clique com o botão direito do mouse
                POINT pt; // variável para armazenar dados do cursor
                GetCursorPos(&pt); // conseguir a posição do cursor

			    hTrayMenu = CreatePopupMenu(); // criar um menu pop-up
			    AppendMenu(hTrayMenu, MF_STRING, ID_TRAY_EXIT, L"Sair"); // adicionar item "Sair" ao menu

			    SetForegroundWindow(hWnd); // garantir que a janela principal esteja em primeiro plano
			    // exibir o menu pop-up na posição do cursor
                TrackPopupMenu(hTrayMenu, TPM_BOTTOMALIGN | TPM_LEFTALIGN,
                    pt.x, pt.y, 0, hWnd, NULL);

			    DestroyMenu(hTrayMenu); // destruir o menu pop-up após o uso
            }
            else if (lParam == WM_LBUTTONUP) {
                // exibir a janela ao clicar com o botão esquerdo
                ShowWindow(hWnd, SW_SHOW);
			    UpdateWindow(hWnd); // atualizar a janela
			    SetForegroundWindow(hWnd); // garantir que a janela principal esteja em primeiro plano
            }
            break;
        }
	    case WM_COMMAND: { // tratar a mensagem WM_COMMAND, que é enviada quando um comando é enviado para a janela
        
            switch (LOWORD(wParam)) {
                case ID_TRAY_EXIT: {
                    DestroyWindow(hWnd); // destruir a janela
                    break;
                }
                case ID_BTN_DESCONECTAR: {
                    sessaoAtual.Desconectar(); // desconectar o cliente ativo
                    break;
                }
                case ID_MENU_SOBRE: {
			        OutputDebugString(L"Exibindo informações sobre o aplicativo.\n");
			        DialogBox(hInstance, MAKEINTRESOURCE(IDD_DIALOG2), hWnd, DialogProc); // exibir a caixa de diálogo "Sobre"
                    break;
                }
                case ID_MENU_DISPOSITIVOS: {
			        OutputDebugString(L"Exibindo lista de dispositivos conhecidos.\n");
			        DialogBoxParam(hInstance, MAKEINTRESOURCE(IDD_DIALOG3), hWnd, DialogProc, (LPARAM)IDD_DIALOG3); // exibir a caixa de diálogo com a lista de dispositivos conhecidos
                    break;
                }
            }
            break;
        }
	    case WM_SYSCOMMAND: { // tratar a mensagem WM_SYSCOMMAND, dessa vez, usado apenas para mudar o comportamento de minimizar a janela
       
            if ((wParam & 0xFFF0) == SC_MINIMIZE) {
                // interceptar o comando de minimizar
                ShowWindow(hWnd, SW_HIDE);  // ocultar a janela

                return 0; // impedir o comportamento padrão (minimizar)
            }
            break;
        }
	    case WM_DESTROY: {// tratar a mensagem WM_DESTROY, dessa vez, usada para liberar e finalizar o aplicativo
		
		    // liberar recursos alocados
            if (pData) {
                delete pData;
                OutputDebugString(L"Memória pData liberada\n");
            }
            rodando.store(false); // sinalizar que o servidor não está mais rodando
		    PostQuitMessage(0); // enviar mensagem de saída para o loop de mensagens
            Shell_NotifyIcon(NIM_DELETE, &nid); // remover o ícone da bandeja do sistema
            OutputDebugString(L"Icône da bandeja removido.\n");

            return 0;
        }
    }
    return DefWindowProc(hWnd, message, wParam, lParam);
}

// função de callback para processar mensagens da caixa de diálogo
INT_PTR CALLBACK DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
	    case WM_INITDIALOG: { // tratar a mensagem WM_INITDIALOG, que é enviada quando a caixa de diálogo é inicializada

		    // verificar se quem mandou a mensagem foi a caixa de diálogo de dispositivos conhecidos
            if ((int)lParam == IDD_DIALOG3)
            {
			    sessaoAtual.AtualizarListBox(hDlg); // preencher a ListBox
            }

            SetForegroundWindow(hDlg); // forçar a janela ao topo
		    // definir a posição da DialogBox no topo
            SetWindowPos(hDlg, HWND_TOPMOST, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            return TRUE;
	    }
	    case WM_COMMAND: { // tratar a mensagem WM_COMMAND, que é enviada quando um comando é enviado para a caixa de diálogo

            switch (LOWORD(wParam)) {
                case IDYES: {
			        BOOL isChecked = (IsDlgButtonChecked(hDlg, IDC_CHECK1) == BST_CHECKED); // obter a resposta do usuário
			        EndDialog(hDlg, isChecked); // finalizar a caixa de diálogo e retornar o valor da resposta
                    return TRUE;
                }
                case IDNO: {
			        EndDialog(hDlg, IDNO); // finalizar a caixa de diálogo e retornar o valor de não
                    return TRUE;
                }
                case IDOK: {
			        EndDialog(hDlg, IDOK); // finalizar a caixa de diálogo e retornar o valor de ok
                    return TRUE;
                }
                case ID_LIMPAR: {
                    sessaoAtual.LimparListaDispositivos(hDlg); // limpar a lista de dispositivos conhecidos
			        return TRUE;
                }
            }
            break;
        }
        case WM_CLOSE: {
		    EndDialog(hDlg, 0); // finalizar a caixa de diálogo
            return TRUE;
        }
    }
    return FALSE;
}

// função para verificar o status do servidor e responder ao cliente
void VerificarStatus(unsigned short port, SOCKET& outSocket) {
    try {
		// criar socket UDP
        SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        // verificar se o socket foi criado corretamente
        if (sock == INVALID_SOCKET) {
            throw std::runtime_error("erro ao criar socket (porta " + std::to_string(port) + ")");
        }

        
        outSocket = sock; // atribuir o socket criado à referência passada
		sessaoAtual.SetSocketStatus(sock); // definir o socket de status na sessão atual

        // criar e inicializar a estrutura sockaddr_in para o endereço do servidor
        sockaddr_in serverAddr;
        memset(&serverAddr, 0, sizeof(serverAddr));

        // configurar o endereço do servidor
		serverAddr.sin_family = AF_INET; // família de endereços IPv4
		serverAddr.sin_addr.s_addr = INADDR_ANY; // escuta em todas as interfaces
		serverAddr.sin_port = htons(port); // porta do servidor

        // fazer bind do socket ao endereço configurado e verificar se foi bem-sucedido
        if (bind(sock, (sockaddr*)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR) {
            throw std::runtime_error("erro ao fazer bind na porta " + std::to_string(port));
        }

        OutputDebugString(L"Servidor UDP escutando na porta: 54321.\n");

        // criar e inicializar a estrutura sockaddr_in para o endereço do cliente
        sockaddr_in clientAddr;
        memset(&clientAddr, 0, sizeof(clientAddr));

        int addrLen = sizeof(clientAddr);// tamanho do endereço do cliente
		uint8_t respostaStatus; // variável para armazenar o status do servidor
        uint8_t seqId = 0; // variável para resetar seqId
        uint8_t buffer[1]; // buffer para receber byte de confirmação

        // loop para receber e responder mensagens enquanto o servidor estiver rodando
        while (rodando.load()) {
            // receber dados do cliente
            int recvLen = recvfrom(sock, (char*)buffer, sizeof(buffer), 0, (sockaddr*)&clientAddr, &addrLen);

			// verificar se o servidor ainda está rodando, e finalizar o loop se não estiver
            if (!rodando.load()) break;

            // verificar se houve erro ao receber dados e tratar
            if (recvLen == SOCKET_ERROR) {
				int erro = WSAGetLastError(); // obter o código de erro
                if (erro == WSAEINTR || erro == WSAENOTSOCK || erro == WSAECONNRESET) {
					OutputDebugString(L"Servidor encerrado ou erro de conexão.\n");
                    break;
                }
                OutputDebugString(L"Erro ao receber pacote do cliente.\n");
                continue;
            }
            OutputDebugString(L"Pacote recebido.\n");

			// verificar status atual do servidor
            if (sessaoAtual.ServidorOcupado()) {
                if (sessaoAtual.DispositivoConectado(clientAddr)) {
					respostaStatus = CLIENTE_CONECTADO; // resposta de cliente conectado
					// enviar resposta ao cliente
					int sent = sendto(sock, (const char*)&respostaStatus, sizeof(respostaStatus), 0, (sockaddr*)&clientAddr, addrLen);
                    if( sent == SOCKET_ERROR) {
                        OutputDebugString(L"Erro ao enviar resposta de cliente conectado.\n");
						continue;
					}
					OutputDebugString(L"Cliente conectado, resposta enviada com sucesso.\n");
					sessaoAtual.SeqId(seqId); // resetar seqId para o cliente conectado
                    OutputDebugString(L"SeqId resetado.\n");
                }
                else {
					respostaStatus = SERVIDOR_OCUPADO; // resposta de servidor ocupado
					// enviar resposta ao cliente
					int sent = sendto(sock, (const char*)&respostaStatus, sizeof(respostaStatus), 0, (sockaddr*)&clientAddr, addrLen);
                    if (sent == SOCKET_ERROR) {
                        OutputDebugString(L"Erro ao enviar resposta de servidor ocupado.\n");
						continue;
                    }
					OutputDebugString(L"Servidor ocupado, resposta enviada com sucesso.\n");
                }
            }
            else {
				respostaStatus = SERVIDOR_LIVRE; // resposta de servidor livre
				// enviar resposta ao cliente
                int sent = sendto(sock, (const char*)&respostaStatus, sizeof(respostaStatus), 0, (sockaddr*)&clientAddr, addrLen);
                if (sent == SOCKET_ERROR) {
                    OutputDebugString(L"Erro ao enviar resposta de servidor livre.\n");
					continue;
				}
				OutputDebugString(L"Servidor livre, resposta enviada com sucesso.\n");
            }
        }
    }
    catch (const std::exception& e) {// tratar erros ao iniciar o servidor e finalizar corretamente
        MostrarErroFormatado("Erro", "Não foi possível iniciar o servidor: %s", e.what());
        // encontrar a janela principal pelo nome da classe
		HWND hwnd = FindWindowW(L"RemoteMouseServerClass", NULL); 
        if (hwnd != NULL) {
			// enviar mensagem de fechamento para a janela principal, se encontrada
            PostMessage(hwnd, WM_CLOSE, 0, 0);
        }
    }
}

// função para fazer loggin e receber comandos do cliente
void ReceberComandos(unsigned short port, SOCKET& outSocketUDP, SOCKET& outSocketTCP, AppData* pData) {
    try {
		SOCKET sockUDP, sockTCP, novoCliente; // sockets para comunicação UDP e TCP

		// criar socket UDP
        sockUDP = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        // verificar se o socket foi criado corretamente
        if (sockUDP == INVALID_SOCKET) {
            throw std::runtime_error("erro ao criar socket (porta " + std::to_string(port) + ")");
        }
		outSocketUDP = sockUDP; // atribuir o socket UDP criado à referência passada

		// criar socket TCP
        sockTCP = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        // verificar se o socket foi criado corretamente
        if (sockTCP == INVALID_SOCKET) {
            throw std::runtime_error("erro ao criar socket (porta " + std::to_string(port) + ")");
        }
		outSocketTCP = sockTCP; // atribuir o socket TCP criado à referência passada

        // criar e inicializar a estrutura sockaddr_in para o endereço do servidor
        sockaddr_in addrTCP{}, addrUDP{};
        memset(&addrTCP, 0, sizeof(addrTCP));
        memset(&addrUDP, 0, sizeof(addrUDP));

        // configurar o endereço do servidor
		addrTCP.sin_family = AF_INET; // família de endereços IPv4
        addrTCP.sin_addr.s_addr = INADDR_ANY; // Escuta em todas as interfaces
		addrTCP.sin_port = htons(port); // porta do servidor
        addrUDP = addrTCP; // Usar o mesmo endereço para UDP

        // fazer bind do socket ao endereço configurado e verificar se foi bem-sucedido
        if (bind(sockTCP, (sockaddr*)&addrTCP, sizeof(addrTCP)) == SOCKET_ERROR ||
            bind(sockUDP, (sockaddr*)&addrUDP, sizeof(addrUDP)) == SOCKET_ERROR) {
            throw std::runtime_error("erro ao fazer bind na porta " + std::to_string(port));
        }

		// escutar o socket tCP e verificar se foi bem-sucedido
        if (listen(sockTCP, SOMAXCONN) == SOCKET_ERROR) {
            throw std::runtime_error("erro no listen TCP, porta: " + std::to_string(port));
        }

        OutputDebugString(L"Servidor escutando na porta UDP: 53100 \nServidor escutando na porta TCP: 53100\n");

        // criar e inicializar a estrutura sockaddr_in para o endereço do cliente
        sockaddr_in clientAddr;
        memset(&clientAddr, 0, sizeof(clientAddr));  

        int addrLen = sizeof(clientAddr);// tamanho do endereço do cliente 
        /*
           buffer para receber dados
           bufferUDP[0] = seqId do comando
           bufferUDP[1] = tipo de comando
           bufferUDP[2] = flag do botão | x do mouse
           bufferUDP[3] = 0 | y do mouse
           bufferUDP[4] = checksum do comando
        */
        uint8_t bufferUDP[5];
        /*
          buffer para receber dados TCP
          bufferTCP[0] = tipo de autenticação
          bufferTCP[1-7] = codigo de autenticação
        */
        uint8_t bufferTCP[8];
        uint8_t seqId; // variável para guardar o valor da seqId da sessão
        const int tamanhoBufferTCP = sizeof(bufferTCP);

        sessaoAtual.AtualizarSessao(); // atualizar a sessão, que atualiza tanto texto na janela assim como o código de autenticação

        // loop para receber comandos enquanto o servidor estiver rodando
        while (rodando.load()) {
			fd_set readfds; // conjunto de descritores de arquivo para o select
			FD_ZERO(&readfds); // limpar o conjunto de descritores de arquivo
			FD_SET(sockTCP, &readfds); // adicionar o socket TCP ao conjunto de descritores
			FD_SET(sockUDP, &readfds); // adicionar o socket UDP ao conjunto de descritores

			// esperar por dados em qualquer um dos sockets e verificar se houve erro
            if (select(0, &readfds, nullptr, nullptr, nullptr) == SOCKET_ERROR) {
                throw std::runtime_error("erro no select");
            }

			// verificar se o servidor ainda está rodando, e se não estiver, sair do loop
            if (!rodando.load()) break;

			// verificar se foi o socketTCP que recebeu dados
            if (FD_ISSET(sockTCP, &readfds)) {
				uint8_t respostaConexao; // variável para armazenar a resposta de conexão

                // aceitar uma nova conexão TCP
                novoCliente = accept(sockTCP, (sockaddr*)&clientAddr, &addrLen);
				// verificar se a conexão foi aceita corretamente
                if (novoCliente == INVALID_SOCKET) {
                    OutputDebugString(L"Erro ao aceitar conexão TCP.\n");
					continue; // continuar o loop para receber mais mensagens
                }

                DWORD timeout = 5000; // tempo limite de 5 segundos
                // definir o tempo limite de recebimento de modo que o recv não bloqueie indefinidamente
                setsockopt(novoCliente, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout, sizeof(timeout));

				// receber dados do cliente
                int recvLen = recv(novoCliente, (char*)bufferTCP, tamanhoBufferTCP, 0);
				// verificar se houve erro ao receber dados
                if (recvLen == SOCKET_ERROR || recvLen == 0) {
					int erro = WSAGetLastError(); // obter o código de erro
                    if (erro == WSAEINTR || erro == WSAENOTSOCK || erro == WSAECONNRESET || erro == WSAETIMEDOUT) {
                        closesocket(novoCliente); // fechar o socket se houver erro
                        novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                        OutputDebugString(L"Conexão finalizada ou timeout.\n");
						continue; // continuar o loop para receber mais mensagens
                    }
                    OutputDebugString(L"Erro ao receber dados do cliente.\n");
                    closesocket(novoCliente); // fechar o socket se houver erro
                    novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
					continue; // continuar o loop para receber mais mensagens
                }

				// verificar se o servidor está ocupado e enviar resposta ao cliente
                if (sessaoAtual.ServidorOcupado()) {
                    OutputDebugString(L"Servidor ocupado, enviando resposta para o cliente.\n");
                    respostaConexao = SERVIDOR_OCUPADO; // resposta de servidor ocupado
                    // enviar resposta para o cliente
                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0); 
                    if (bytesSent == SOCKET_ERROR) {
                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                    }
                    closesocket(novoCliente); // fechar socket do cliente
                    novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                    continue; // servidor ocupado, não aceitar novas conexões
                }

                int tamanhoMensagem = bufferTCP[0]; // tamanho da mensagem recebida
                if (tamanhoMensagem > tamanhoBufferTCP) {
                    OutputDebugString(L"Tamanho da mensagem recebida é maior que o buffer TCP, enviando resposta para o cliente.\n");
                    respostaConexao = CON_CODIGO_INCORRETO; // resposta de servidor ocupado
                    // enviar resposta para o cliente
                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0);
                    if (bytesSent == SOCKET_ERROR) {
                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                    }
                    closesocket(novoCliente); // fechar o socket se o tamanho da mensagem for maior que o buffer
                    novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
					continue; // continuar o loop para receber mais mesnagens
                }

                int offset = recvLen; // tamanho da mensagem recebida
                // loop para receber a mensagem de forma completa
                while (offset < tamanhoMensagem) {
                    // receber mais dados do cliente
                    recvLen = recv(novoCliente, (char*)bufferTCP + offset, tamanhoBufferTCP - offset, 0);
                    // verificar se houve algum erro
                    if (recvLen == SOCKET_ERROR) {
                        int erro = WSAGetLastError();
                        if (erro == WSAEINTR || erro == WSAENOTSOCK || erro == WSAECONNRESET || erro == WSAETIMEDOUT) {
                            OutputDebugString(L"Conexão finalizada ou timeout.\n");
                            closesocket(novoCliente); // fechar o socket se houver erro
                            novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                            break;
                        }
                        OutputDebugString(L"Erro ao receber dados do cliente.\n");
                        closesocket(novoCliente); // fechar o socket se houver erro
                        novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                        break;
                    }
                    if (recvLen == 0) {
                        OutputDebugString(L"Cliente desconectou.\n");
                        closesocket(novoCliente);// fechar o socket se o cliente desconectar
                        novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                        break;
                    }
                    // atualizar o offset com o tamanho da mensagem recebida para a próxima iteração
					offset += recvLen;
                }
                // verificar se a mensagem foi recebida de forma completa
                if (offset == tamanhoMensagem) {
                    OutputDebugString(L"Dados recebidos com sucesso do cliente \n");
                    switch (bufferTCP[1]) {
					    case AUTENTICACAO_CODIGO: { // tratar autenticação por código
                            OutputDebugString(L"Autenticação por código recebida \n");
                            // pegar o código da mensagem recebida e converter em string
                            std::string codigoRecebido((char*)bufferTCP + 2, tamanhoMensagem - 2);
                            // verificar se o código recebido é válido
                            int respostaAutenticacao = sessaoAtual.Autenticar(codigoRecebido, clientAddr);

                            // responder de acordo com o resultado da autenticação
                            switch (respostaAutenticacao) {
                                case AUT_CODIGO_INCORRETO: { // código incorreto
                                    OutputDebugString(L"Código incorreto recebido.\n");
                                    // enviar resposta para o cliente
                                    respostaConexao = CON_CODIGO_INCORRETO; // resposta de código incorreto
                                    // enviar resposta para o cliente
                                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0);
                                    // verificar se houve erro ao enviar a resposta
                                    if (bytesSent == SOCKET_ERROR) {
                                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                    }
                                    break;
                                }
                                case AUT_NAO_PERMITIDA: { // autenticação não permitida
                                    OutputDebugString(L"Autenticação não permitida.\n");
                                    // enviar resposta para o cliente
                                    respostaConexao = CON_NAO_PERMITIDA; // resposta de conexão não permitida
                                    // enviar resposta para o cliente
                                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0);
                                    // verificar se houve erro ao enviar a resposta
                                    if (bytesSent == SOCKET_ERROR) {
                                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                    }
                                    break;
                                }
                                case AUT_PERMITIDA: { // autenticação permitida, dispositivo não confiável
                                    // enviar resposta para o cliente
                                    respostaConexao = CON_PERMITIDA; // resposta de conexão permitida
                                    // enviar resposta para o cliente
                                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0);
                                    // verificar se houve erro ao enviar a resposta
                                    if (bytesSent == SOCKET_ERROR) {
                                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                        sessaoAtual.Desconectar(); // desconectar o cliente se houve erro ao enviar a resposta
                                        break;
                                    }
                                    OutputDebugString(L"Autenticação permitida, dispositivo não confiável.\n");
                                    break;
                                }
                                case AUT_PERMITIDA_C: { // autenticação permitida, dispositivo confiável
                                    // enviar resposta para o cliente
                                    respostaConexao = CON_PERMITIDA_C; // resposta de conexão permitida para dispositivo confiável
                                    // enviar resposta para o cliente
                                    int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(respostaConexao), 0);
                                    // verificar se houve erro ao enviar a resposta
                                    if (bytesSent == SOCKET_ERROR) {
                                        OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                        sessaoAtual.Desconectar(); // desconectar o cliente se houve erro ao enviar a resposta
                                        break;
                                    }
                                    OutputDebugString(L"Autenticação permitida, dispositivo confiável.\n");
                                    break;
                                }
                            }
                            break;
					    }
					    case AUTENTICACAO_C: { // tratar autenticação por dispositivo confiável
                            // verificar se o dispositivo é conhecido
                            if (sessaoAtual.DispositivoEhConhecido(clientAddr)) {
                                respostaConexao = CON_PERMITIDA_C; // conexão permitida para dispositivo confiável
                                int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(CON_PERMITIDA_C), 0); // enviar resposta de conexão permitida
                                if (bytesSent == SOCKET_ERROR) {
                                    OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                    sessaoAtual.Desconectar(); // desconectar o cliente se houve erro ao enviar a resposta
                                    break;
                                }
                                OutputDebugString(L"Dispositivo confiável conectado.\n");
                            }
                            else {
                                respostaConexao = CON_NAO_PERMITIDA; // conexão não permitida para dispositivo desconhecido
                                int bytesSent = send(novoCliente, (const char*)&respostaConexao, sizeof(CON_NAO_PERMITIDA), 0); // enviar resposta de conexão não permitida
                                if (bytesSent == SOCKET_ERROR) {
                                    OutputDebugString(L"Erro ao enviar resposta para o cliente.\n");
                                }
                                OutputDebugString(L"Dispositivo desconhecido tentou se conectar.\n");
                            }
                            break;
                        }
                    }
                    closesocket(novoCliente); // fechar o socket após receber os dados
                    novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
					continue; // continuar o loop para receber mais mensagens
                }
                else {
                    OutputDebugString(L"Mensagem incompleta.\n");
                    closesocket(novoCliente); // fechar o socket se os dados não foram recebidos corretamente
                    novoCliente = INVALID_SOCKET; // evitar uso de socket inválido
                    continue; // continuar o loop para receber mais mensagens
                }
            }

            if (FD_ISSET(sockUDP, &readfds)) {
                // receber dados do cliente
                int recvLen = recvfrom(sockUDP, (char*)bufferUDP, sizeof(bufferUDP), 0, (sockaddr*)&clientAddr, &addrLen);           
                // verificar se houve erro ao receber dados e tratar
                if (recvLen == SOCKET_ERROR) {
					int erro = WSAGetLastError(); // obter o código de erro
                    if (erro == WSAEINTR || erro == WSAENOTSOCK || erro == WSAECONNRESET) {
						OutputDebugString(L"Servidor encerrado ou erro de conexão.\n");
                        break;
                    }
                    OutputDebugString(L"Não foi possível receber pacote do cliente.\n");
                    continue;
                }

				// verificar se o cliente que enviou os comandos está conectado
                if (!sessaoAtual.DispositivoConectado(clientAddr)) {
                    OutputDebugString(L"Dispositivo não conectado, portanto ignorar comandos.\n");
					continue; // ignorar comandos de dispositivos não conectados
                }

                OutputDebugString(L"Comando recebido! \n");
                seqId = sessaoAtual.LerSeqId();
				// verificar se o comando recebido é válido e em sequência
                if (CheckSum(bufferUDP) && bufferUDP[0] != seqId && uint8_t(bufferUDP[0] - seqId) <= 127) {
                    OutputDebugString(L"Comando válido e em sequência \n");
					SimularMouse(bufferUDP); // simular o mouse com os dados recebidos
                    sessaoAtual.SeqId(bufferUDP[0]); // atualizar o ID da sequência de comandos
                }
                else {
                    OutputDebugString(L"Comando fora de sequência, duplicado ou adulterado.\n");
                }
            }
        }
    }
    catch (const std::exception& e) {// tratar erros ao iniciar o servidor e finalizar corretamente
        MostrarErroFormatado("Erro", "Não foi possível iniciar o servidor: %s", e.what());
        // encontrar a janela principal pelo nome da classe
        HWND hwnd = FindWindowW(L"RemoteMouseServerClass", NULL);
        if (hwnd != NULL) {
            // enviar mensagem de fechamento do programa se encontrar a janela
            PostMessage(hwnd, WM_CLOSE, 0, 0);
        }
    }
}

// checksum XOR para verificar a integridade dos dados recebidos
bool CheckSum(const uint8_t(&buffer)[5]) {
    // calcular o checksum dos 4 primeiros bytes e comparar com o 5º byte
    uint8_t checksum = 0;
    for (int i = 0; i < 4; ++i) {
        checksum ^= buffer[i];
    }
    return checksum == buffer[4]; // retorna verdadeiro se o checksum for válido
}

// função para simular o mouse com os dados recebidos
void SimularMouse(const uint8_t(&buffer)[5]) {// interpretar os dados recebidos e simular o mouse
    static INPUT input[1] = {};// estrutura de entrada para simular o mouse
    memset(input, 0, sizeof(input));// limpar a estrutura de entrada

    switch (buffer[1]) {
        case MOUSE_MOVE: { // mover mouse
            OutputDebugString(L"Comando de movimento de mouse recebido.\n");
			input[0].type = INPUT_MOUSE; // definir o tipo de entrada como mouse
            input[0].mi.dx = static_cast<LONG>(static_cast<int8_t>(buffer[2])); // movimento relativo do mouse no eixo X
            input[0].mi.dy = static_cast<LONG>(static_cast<int8_t>(buffer[3])); // movimento relativo do mouse no eixo Y
			input[0].mi.dwFlags = MOUSEEVENTF_MOVE; // definir a flag de movimento do mouse
			// emular entrada do mouse
            if (SendInput(1, input, sizeof(INPUT)) == 0) {
                OutputDebugString(L"Erro ao movimentar mouse.\n");
            }
			OutputDebugString(L"Mouse movido com sucesso.\n");
            break;
        }
        case MOUSE_RIGHTBUTTON: { // pressionar botão direito do mouse
			// verificar se o comando está correto
            if (buffer[3] == 0) {
                OutputDebugString(L"Comando para pressionar botão direito do mouse recebido.\n");
				input[0].type = INPUT_MOUSE; // definir o tipo de entrada como mouse
                input[0].mi.dwFlags = static_cast<DWORD>(buffer[2]); // botão direito
				// emular entrada do mouse
                if (SendInput(1, input, sizeof(INPUT)) == 0) {
                    OutputDebugString(L"Erro ao pressionar botão direito do mouse.\n");
                }
				OutputDebugString(L"Botão direito do mouse pressionado com sucesso.\n");
                break;
            }
            OutputDebugString(L"Comando desconhecido recebido!\n");
            break;
        }
		case MOUSE_LEFTBUTTON: { // pressionar botão esquerdo do mouse
			// verificar se o comando está correto
            if (buffer[3] == 0) {
                OutputDebugString(L"Comando para pressionar botão esquerdo do mouse recebido.\n");
				input[0].type = INPUT_MOUSE; // definir o tipo de entrada como mouse
                input[0].mi.dwFlags = static_cast<DWORD>(buffer[2]); // botão esquerdo
				// emular entrada do mouse
                if (SendInput(1, input, sizeof(INPUT)) == 0) {
                    OutputDebugString(L"Erro ao pressionar botão esquerdo do mouse.\n");
                }
				OutputDebugString(L"Botão esquerdo do mouse pressionado com sucesso.\n");
                break;
            }
            OutputDebugString(L"Comando desconhecido recebido!\n");
            break;
        }
		case MOUSE_MIDDLEBUTTON: { // pressionar botão do meio do mouse
			// verificar se o comando está correto
            if (buffer[3] == 0) {
                OutputDebugString(L"Comando para pressionar botão do meio do mouse recebido.\n");
				input[0].type = INPUT_MOUSE; // definir o tipo de entrada como mouse
                input[0].mi.dwFlags = static_cast<DWORD>(buffer[2]); // botão do meio
				// emular entrada do mouse
                if (SendInput(1, input, sizeof(INPUT)) == 0) {
                    OutputDebugString(L"Erro ao pressionar botão do meio do mouse.\n");
                }
				OutputDebugString(L"Botão do meio do mouse pressionado com sucesso.\n");
                break;
            }
            OutputDebugString(L"Comando desconhecido recebido!\n");
            break;
        }
		case MOUSE_WHEEL: { // rodar a roda do mouse
			// verificar se o comando está correto
            if (buffer[3] == 0) {
                OutputDebugString(L"Comando para rodar a roda do mouse recebido.\n");
				input[0].type = INPUT_MOUSE; // definir o tipo de entrada como mouse
				input[0].mi.dwFlags = MOUSEEVENTF_WHEEL; // definir a flag de roda do mouse
				input[0].mi.mouseData = static_cast<DWORD>(static_cast<int8_t>(buffer[2])) * WHEEL_DELTA; // movimento da roda do mouse
				// emular entrada do mouse
                if (SendInput(1, input, sizeof(INPUT)) == 0) {
                    OutputDebugString(L"Erro ao rodar a roda do mouse.\n");
                }
				OutputDebugString(L"Roda do mouse rodada com sucesso.\n");
                break;
            }
            OutputDebugString(L"Comando desconhecido recebido!\n");
            break;
        }
        default: {
            OutputDebugString(L"Comando desconhecido recebido!\n");
            break;
        }
    }
}

// função para exibir uma mensagem de erro formatada
void MostrarErroFormatado(const char* titulo, const char* formato, ...) {
	char mensagem[512]; // buffer para armazenar a mensagem formatada

	va_list args; // lista de argumentos variáveis
	va_start(args, formato); // inicializar a lista de argumentos
    vsnprintf(mensagem, sizeof(mensagem), formato, args);  // string formatada
	va_end(args); // finalizar a lista de argumentos

	MessageBoxA(NULL, mensagem, titulo, MB_OK | MB_ICONERROR); // exibir a mensagem de erro em uma caixa de diálogo
}

// função para converter uma string UTF-8 para um wchar_t*
wchar_t* StringParaWChar(const std::string& str) {
	int tam = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, nullptr, 0); // obter o tamanho necessário
	if (tam == 0) return nullptr; // verificar se a conversão falhou

	wchar_t* buffer = new wchar_t[tam]; // alocar memória para o buffer
	MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, buffer, tam); // converter a string UTF-8 para wchar_t*
    return buffer;
}