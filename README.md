# 🌡️ Automação IR

App Android para controlar um **ar-condicionado** remotamente via **infravermelho (IR)**, usando **MQTT** como canal de comunicação e uma placa **ESP8266** como ponte entre a rede e o aparelho.

O ícone do app usa um **floco de neve branco sobre fundo azul**, combinando com a tela de abertura. Ele se adapta aos formatos do launcher (incluindo o circular) e oferece uma versão monocromática para os ícones temáticos do Android 13 ou superior. O app requer Android 11 ou superior.

---

## 📐 Arquitetura

```
┌───────────────┐    MQTT     ┌──────────────┐    IR      ┌─────────────────┐
│  App Android  │ ──────────▶ │   ESP8266    │ ─────────▶ │ Ar-Condicionado │
│ (controle/UI) │ ◀────────── │ (receptor IR │  (pulsos   │                 │
└───────────────┘             │  + emissor)  │  hexadec.) └─────────────────┘
```

**Fluxo de um comando:**
1. Usuário toca num botão do app
2. App publica um comando no tópico MQTT do hardware
3. ESP8266 recebe o comando, identifica na tabela `commands[]` e chama o handler
4. Handler converte para o código hexadecimal IR e envia via LED IR (38kHz)

---

## 📡 Comunicação MQTT

| Item | Valor |
|---|---|
| **Broker** | `tcp://broker.hivemq.com:1883` (público, para testes) |
| **Client ID** | Gerado aleatoriamente (`MqttClient.generateClientId()`) |
| **Clean Session** | `true` |
| **Reconexão automática** | `true` |
| **Tópico de saída** (app → ESP) | `fe6464c65c674037a8fea7aa34150954/hard` |
| **Tópico de entrada** (ESP → app) | `fe6464c65c674037a8fea7aa34150954/app` |

### Protocolo de mensagens (comandos aceitos pelo firmware)

O ESP8266 possui uma tabela `commands[]` que mapeia cada string recebida a um handler:

| Comando (app → ESP) | Handler | Ação |
|---|---|---|
| `ValidaPlacaOnline` | `handleValidaPlacaOnline` | **Handshake**: confirma que a placa está online (enviado ao conectar) |
| `ar-on` | `handleLigaAr` | Liga o ar-condicionado |
| `ar-off` | `handleDesligaAr` | Desliga o ar-condicionado |
| `temp-18` … `temp-27` | `handleTemp18` … `handleTemp27` | Define a temperatura (faixa 18–27 °C) |
| `modo-ventilador` | `handleModoVentilador` | Modo só ventilação |
| `modo-ar` | `handleModoAr` | Modo ar-condicionado |
| `velocidade_1` | `handleVelocidade1` | Velocidade do ventilador 1 |
| `velocidade_2` | `handleVelocidade2` | Velocidade do ventilador 2 |
| `velocidade_3` | `handleVelocidade3` | Velocidade do ventilador 3 |
| `velocidade_auto` | `handleVelocidadeAuto` | Velocidade automática |
| `desliga_led` | `handleDesligaLed` | Desliga o LED da placa |

> ⚠️ Os comandos são **strings exatas** (com hífen/underscore). O app publica exatamente esses valores.

---

## 🔌 Validação da placa (handshake)

O status na tela **não** reflete apenas a conexão com o broker — ele confirma que a **placa está viva e respondendo**.

### Fluxo

```
App abre ──▶ "Status: Desconectado"
   │
   ▼
Conecta no broker ──▶ "Aguardando resposta da placa..."
   │                  (publica "ValidaPlacaOnline" no tópico .../hard)
   ▼
ESP responde no tópico .../app ──▶ "Placa online ✓"
   │
   └─ (sem resposta em 5s) ──▶ "Placa offline (sem resposta)"
```

### Estados possíveis

| Status exibido | Significado |
|---|---|
| `Status: Desconectado` | App recém-aberto ou sem conexão com o broker |
| `Aguardando resposta da placa...` | Broker conectado, handshake enviado, aguardando retorno |
| `Placa online ✓` | A placa respondeu no tópico `.../app` |
| `Placa offline (sem resposta)` | Timeout de 5s atingido sem resposta da placa |
| `Status: Conexão Perdida` | Conexão MQTT caiu (estado é resetado para revalidar) |

### Implementação (app)

- `TIMEOUT_VALIDACAO_MS = 5000` — tempo máximo aguardando a resposta
- `Handler` + `postDelayed` na thread de UI para o timeout
- `onPlacaRespondeu()` é chamado por **qualquer** mensagem recebida no tópico `.../app` (cancela o timeout e marca online)
- Ao perder a conexão, `placaValidada = false` e o timeout é cancelado

### Requisito do firmware ⚠️

Para o handshake funcionar, o handler `handleValidaPlacaOnline` **precisa publicar uma resposta** no tópico do app:

```cpp
void handleValidaPlacaOnline() {
  // publica qualquer string de confirmacao no topico do app
  mqttClient.publish("fe6464c65c674037a8fea7aa34150954/app", "placa-ok");
}
```

Se o firmware não publicar nada, o app sempre mostrará "Placa offline" mesmo com a placa funcionando.

---

## 📱 Interface

Layout estilo **controle remoto**, em `ScrollView` (funciona em qualquer tamanho de tela):

```
┌─────────────────────────────┐
│  Placa online ✓             │
│  ┌───────────────────────┐  │
│  │        22°C           │  │  ← display grande da temperatura
│  └───────────────────────┘  │
│      [ − ]       [ + ]      │  ← ajuste 18–27 °C
│   [ VENTILADOR ]  [ AR ]    │  ← modos
│   [ 1 ] [ 2 ] [ 3 ] [AUTO]  │  ← velocidade
│  ╔═══════════════════════╗  │
│  ║   ⏻  LIGAR / DESLIGAR ║  │  ← botão de destaque
│  ╚═══════════════════════╝  │
│   [ Desliga LED da placa ]  │
└─────────────────────────────┘
```

**Comportamento:**
- Botão **Power** alterna `ar-on`/`ar-off` e muda de cor (verde ligado / vermelho desligado)
- **+/−** ajustam a temperatura dentro de 18–27 e publicam `temp-XX`
- **Modo** e **Velocidade** destacam em azul a opção ativa
- Todo controle publica MQTT de verdade (a UI reflete o que foi enviado)

---

## 🛠️ Stack técnica

| Componente | Tecnologia |
|---|---|
| Linguagem | **Java 8** |
| UI | LinearLayout + ScrollView + Material Components |
| Comunicação | Eclipse Paho MQTT v3 (`org.eclipse.paho.client.mqttv3:1.2.5`) |
| Build | Gradle, AGP 8.0.2 |
| Min SDK | 30 (Android 11) |
| Target SDK | 33 |

---

## 📂 Estrutura do projeto

```
app/src/main/
├── java/com/automacao/automaoir/
│   └── MainActivity.java        # tela única: UI + cliente MQTT + handshake
├── res/
│   ├── layout/activity_main.xml # layout da tela principal
│   └── values/                  # cores, strings, temas
└── AndroidManifest.xml          # permissões: INTERNET, ACCESS_NETWORK_STATE
```

---

## ⚠️ Pendências conhecidas (TODO)

- [ ] Remover dependência `paho.android.service:1.1.1` (não usada, quebrada no Android 12+)
- [ ] Remover duplicata do Material (`1.4.0` e `1.5.0`)
- [ ] Extrair tópicos/broker para `BuildConfig`
- [ ] Considerar broker próprio ou autenticado para uso real (broker público = qualquer um pode ver/espiar os comandos)
- [ ] Adicionar botão "tentar novamente" quando a placa estiver offline

### ✅ Já implementado

- [x] Validação da placa com timeout de 5s (handshake `ValidaPlacaOnline` → resposta no tópico `.../app`)
- [x] Conexão/publicação MQTT fora da thread de UI
- [x] Reconexão automática ao broker

---

## 🚀 Como rodar

1. Abra o projeto no **Android Studio**
2. Sincronize o Gradle
3. Rode em um dispositivo/emulador com **Android 11+**
4. A placa ESP8266 deve estar ligada e inscrita no tópico `.../hard`

> **Nota:** o broker `broker.hivemq.com` é público e não exige login — ótimo para testes, **não use em produção**.
