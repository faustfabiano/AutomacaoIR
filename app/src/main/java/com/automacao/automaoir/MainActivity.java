package com.automacao.automaoir;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MainActivity extends AppCompatActivity {

    private MqttClient mqttClient;
    private TextView statusTextView;
    private final String brokerUrl = "tcp://broker.hivemq.com:1883";
    private final String topic_subscriber = "fe6464c65c674037a8fea7aa34150954/app";
    private final String topic_publish = "fe6464c65c674037a8fea7aa34150954/hard";
    private final String clientId = MqttClient.generateClientId();

    // Tempo maximo para a placa responder ao ValidaPlacaOnline
    private static final long TIMEOUT_VALIDACAO_MS = 5000L;

    // Faixas suportadas pelo firmware do ESP8266
    private static final int TEMP_MIN = 18;
    private static final int TEMP_MAX = 27;

    // Handler roda na thread de UI para o timeout
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // Estado do ar-condicionado
    private boolean ligado = false;
    private int temperatura = 22;
    private String modo = "ar";            // "ar" | "ventilador"
    private String velocidade = "auto";    // "1" | "2" | "3" | "auto"

    // VARIAVEL GLOBAL DE CONTROLE:
    // true somente quando a placa respondeu ao handshake.
    // Enquanto for false, todos os botoes ficam desabilitados.
    private boolean placaValidada = false;

    // Views
    private TextView tvTemp;
    private Button btnPower, btnTempUp, btnTempDown;
    private Button btnModeVentilador, btnModeAr;
    private Button btnFan1, btnFan2, btnFan3, btnFanAuto;
    private Button btnDesligaLed;
    private Button btnTentarNovamente;

    // Todos os controles que dependem da placa estar online
    private Button[] controles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Exibe a splash screen com o logo antes de carregar a tela principal
        SplashScreen.installSplashScreen(this);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.statusTextView);
        tvTemp = findViewById(R.id.tvTemp);
        btnPower = findViewById(R.id.btnPower);
        btnTempUp = findViewById(R.id.btnTempUp);
        btnTempDown = findViewById(R.id.btnTempDown);
        btnModeVentilador = findViewById(R.id.btnModeVentilador);
        btnModeAr = findViewById(R.id.btnModeAr);
        btnFan1 = findViewById(R.id.btnFan1);
        btnFan2 = findViewById(R.id.btnFan2);
        btnFan3 = findViewById(R.id.btnFan3);
        btnFanAuto = findViewById(R.id.btnFanAuto);
        btnDesligaLed = findViewById(R.id.btnDesligaLed);
        btnTentarNovamente = findViewById(R.id.btnTentarNovamente);

        controles = new Button[]{
                btnPower, btnTempUp, btnTempDown,
                btnModeVentilador, btnModeAr,
                btnFan1, btnFan2, btnFan3, btnFanAuto,
                btnDesligaLed
        };

        // Status inicial: SEMPRE desconectado e controles bloqueados
        statusTextView.setText(R.string.status_desconectado);
        btnTentarNovamente.setVisibility(View.GONE);
        atualizarHabilitacaoControles();

        atualizarTemp();
        atualizarBotaoPower();
        atualizarDestaqueModo();
        atualizarDestaqueVelocidade();

        // Power -> ar-on | ar-off
        btnPower.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ligado = !ligado;
                publishMessage(topic_publish, ligado ? "ar-on" : "ar-off");
                atualizarBotaoPower();
            }
        });

        // Temperatura -> temp-18 ... temp-27
        btnTempUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (temperatura < TEMP_MAX) {
                    temperatura++;
                    atualizarTemp();
                    publishMessage(topic_publish, "temp-" + temperatura);
                }
            }
        });

        btnTempDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (temperatura > TEMP_MIN) {
                    temperatura--;
                    atualizarTemp();
                    publishMessage(topic_publish, "temp-" + temperatura);
                }
            }
        });

        // Modos -> modo-ventilador | modo-ar
        btnModeVentilador.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                modo = "ventilador";
                atualizarDestaqueModo();
                publishMessage(topic_publish, "modo-ventilador");
            }
        });

        btnModeAr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                modo = "ar";
                atualizarDestaqueModo();
                publishMessage(topic_publish, "modo-ar");
            }
        });

        // Velocidade -> velocidade_1 | velocidade_2 | velocidade_3 | velocidade_auto
        btnFan1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { definirVelocidade("1"); }
        });
        btnFan2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { definirVelocidade("2"); }
        });
        btnFan3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { definirVelocidade("3"); }
        });
        btnFanAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { definirVelocidade("auto"); }
        });

        // LED da placa -> desliga_led
        btnDesligaLed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMessage(topic_publish, "desliga_led");
            }
        });

        // Retry manual: reenvia o handshake sem precisar reabrir o app
        btnTentarNovamente.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iniciarValidacaoPlaca();
            }
        });

        connectToMqttBroker();
    }

    // ===== HABILITACAO / DESABILITACAO DOS CONTROLES =====

    // Unico ponto que liga/desliga os botoes conforme a placa
    private void atualizarHabilitacaoControles() {
        for (Button b : controles) {
            b.setEnabled(placaValidada);
            b.setAlpha(placaValidada ? 1.0f : 0.4f);
        }
    }

    private void definirVelocidade(String novaVelocidade) {
        velocidade = novaVelocidade;
        atualizarDestaqueVelocidade();
        publishMessage(topic_publish, "velocidade_" + velocidade);
    }

    private void atualizarTemp() {
        tvTemp.setText(temperatura + "\u00B0C");
    }

    private void atualizarBotaoPower() {
        if (ligado) {
            btnPower.setText(R.string.power_off);
            btnPower.setBackgroundColor(ContextCompat.getColor(this, R.color.verde_on));
        } else {
            btnPower.setText(R.string.power_on);
            btnPower.setBackgroundColor(ContextCompat.getColor(this, R.color.vermelho_off));
        }
    }

    private void atualizarDestaqueModo() {
        destacar(btnModeVentilador, modo.equals("ventilador"));
        destacar(btnModeAr, modo.equals("ar"));
    }

    private void atualizarDestaqueVelocidade() {
        destacar(btnFan1, velocidade.equals("1"));
        destacar(btnFan2, velocidade.equals("2"));
        destacar(btnFan3, velocidade.equals("3"));
        destacar(btnFanAuto, velocidade.equals("auto"));
    }

    private void destacar(Button b, boolean ativo) {
        b.setBackgroundColor(ativo
                ? ContextCompat.getColor(this, R.color.selecionado)
                : Color.parseColor("#E0E0E0"));
    }

    // ===== HANDSHAKE DE VALIDACAO DA PLACA =====

    // Envia o comando e comeca a contar o timeout. Roda na thread de UI.
    private void iniciarValidacaoPlaca() {
        placaValidada = false;
        atualizarHabilitacaoControles();
        btnTentarNovamente.setVisibility(View.GONE);
        statusTextView.setText(R.string.status_aguardando_placa);
        iniciarTimeoutValidacao();
        publishMessage(topic_publish, "ValidaPlacaOnline");
    }

    // Chamado quando chega QUALQUER mensagem do topico da placa
    private void onPlacaRespondeu() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!placaValidada) {
                    placaValidada = true;
                    cancelarTimeoutValidacao();
                    statusTextView.setText(R.string.status_placa_online);
                    btnTentarNovamente.setVisibility(View.GONE);
                    atualizarHabilitacaoControles();
                }
            }
        });
    }

    private void iniciarTimeoutValidacao() {
        cancelarTimeoutValidacao();
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!placaValidada) {
                    statusTextView.setText(R.string.status_placa_offline);
                    btnTentarNovamente.setVisibility(View.VISIBLE);
                    atualizarHabilitacaoControles();
                    Log.d("MQTT", "Timeout: placa nao respondeu ao ValidaPlacaOnline");
                }
            }
        };
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_VALIDACAO_MS);
    }

    private void cancelarTimeoutValidacao() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    // ===== CONEXAO MQTT =====

    // Conexao fora da thread de UI (evita travamento/crash)
    private void connectToMqttBroker() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mqttClient = new MqttClient(brokerUrl, clientId, null);
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setCleanSession(true);
                    options.setAutomaticReconnect(true);

                    // MqttCallbackExtended.connectComplete dispara a cada conexao
                    // (inclusive nas reconexoes automaticas), garantindo que o
                    // subscribe seja refeito e a placa revalidada.
                    mqttClient.setCallback(new MqttCallbackExtended() {
                        @Override
                        public void connectComplete(boolean reconnect, String serverURI) {
                            Log.d("MQTT", "Broker conectado (reconnect=" + reconnect + ")");
                            try {
                                mqttClient.subscribe(topic_subscriber);
                            } catch (MqttException e) {
                                Log.e("MQTT", "Erro ao subscrever: " + e.getMessage());
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    iniciarValidacaoPlaca();
                                }
                            });
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            String payload = new String(message.getPayload());
                            Log.d("MQTT", "Recebido de " + topic + ": " + payload);
                            onPlacaRespondeu();
                            disparaMensagem(payload);
                        }

                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.d("MQTT", "Conexao perdida: " + (cause != null ? cause.getMessage() : "sem detalhes"));
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    placaValidada = false;
                                    cancelarTimeoutValidacao();
                                    statusTextView.setText(R.string.status_conexao_perdida);
                                    btnTentarNovamente.setVisibility(View.GONE);
                                    atualizarHabilitacaoControles();
                                }
                            });
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {
                            Log.d("MQTT", "Mensagem entregue com sucesso");
                        }
                    });

                    mqttClient.connect(options);
                } catch (MqttException e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusTextView.setText(R.string.status_erro_conexao);
                            btnTentarNovamente.setVisibility(View.VISIBLE);
                            atualizarHabilitacaoControles();
                        }
                    });
                    Log.e("MQTT", "Erro ao conectar: " + e.getMessage());
                }
            }
        }).start();
    }

    // Publicacao tambem roda fora da thread de UI
    private void publishMessage(final String topic, final String message) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MqttClient c = mqttClient;
                if (c != null && c.isConnected()) {
                    try {
                        c.publish(topic, new MqttMessage(message.getBytes()));
                        Log.d("MQTT", "Publicado: " + topic + " - " + message);
                    } catch (MqttException e) {
                        Log.e("MQTT", "Erro ao publicar: " + e.getMessage());
                    }
                } else {
                    Log.d("MQTT", "Nao esta conectado ao broker");
                }
            }
        }).start();
    }

    private void disparaMensagem(final String messageToast) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, messageToast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelarTimeoutValidacao();
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }
}
