package com.automacao.automaoir;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MainActivity extends AppCompatActivity {

    private MqttClient mqttClient;
    private TextView statusTextView;
    private final String brokerUrl = "tcp://broker.hivemq.com:1883"; // Broker MQTT público

    private final String topic_subscriber = "fe6464c65c674037a8fea7aa34150954/app";

    private final String topic_publish = "fe6464c65c674037a8fea7aa34150954/hard";
    private final String clientId = MqttClient.generateClientId();

    private TextView tvSeekBarValue; // Torna a variável global, fora do método onCreate


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa a interface
        Button button1 = findViewById(R.id.button1);
        Button button2 = findViewById(R.id.button2);
        Button button3 = findViewById(R.id.button3);
        Button button4 = findViewById(R.id.button4);
        statusTextView = findViewById(R.id.statusTextView);

        tvSeekBarValue = findViewById(R.id.tvSeekBarValue);
        SeekBar seekBar = findViewById(R.id.seekBar);

        Spinner spinner = findViewById(R.id.spinner);



        // Criando um array com as opções para o dropdown
        String[] options = {"1", "2", "3", "Auto"};

        // Criando um adapter para vincular o array ao Spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // Vinculando o adapter ao Spinner
        spinner.setAdapter(adapter);


        // Adicionando um listener para quando o usuário selecionar uma opção
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                // Exibindo a opção selecionada
//                textViewResult.setText("Resultado: " + options[position]);
                statusTextView.setText("TESTE" +   options[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // Ação quando nada é selecionado (opcional)
            }
        });

        // Conectar ao broker MQTT
        connectToMqttBroker();

        // Ações dos botões
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMessage(topic_publish, "ar-on");
            }
        });

        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMessage(topic_publish, "ar-off");
            }
        });

        button3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMessage(topic_publish, "desliga_led");
            }
        });

        button4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publishMessage(topic_publish, "Ação do Botão 4");
            }
        });

        // Listener para mudanças no SeekBar
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Atualiza o TextView com o valor do progresso
                System.out.println("SEEK BAR" + progress);
                tvSeekBarValue.setText("Value: " + progress);
                statusTextView.setText("TESTE" +  progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Chamado quando o usuário começa a interagir com o SeekBar
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Chamado quando o usuário para de interagir com o SeekBar
            }
        });
    }

    // Função para conectar ao broker MQTT
    private void connectToMqttBroker() {
        try {
            mqttClient = new MqttClient(brokerUrl, clientId, null);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            mqttClient.connect(options);

            mqttClient.subscribe(topic_subscriber);

            statusTextView.setText("Status: Conectado");

            publishMessage(topic_publish, "ValidaPlacaOnline ");
            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String messageContent = new String(message.getPayload());
                    disparaMensagem(messageContent);

                }

                @Override
                public void connectionLost(Throwable cause) {
                    statusTextView.setText("Status: Conexão Perdida");
                    Log.d("MQTT", "Conexão perdida: " + (cause != null ? cause.getMessage() : "Sem detalhes"));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    Log.d("MQTT", "Mensagem entregue com sucesso");
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
            statusTextView.setText("Status: Erro na Conexão");
            Log.e("MQTT", "Erro ao tentar se conectar ao broker: " + e.getMessage());
        }
    }

    // Função para publicar mensagens em tópicos MQTT
    private void publishMessage(String topic, String message) {
        if (mqttClient.isConnected()) {
            try {
                MqttMessage mqttMessage = new MqttMessage(message.getBytes());
                mqttClient.publish(topic, mqttMessage);
                Log.d("MQTT", "Mensagem publicada: " + topic + " - " + message);
            } catch (MqttException e) {
                e.printStackTrace();
                Log.d("MQTT", "Erro ao publicar mensagem");
            }
        } else {
            statusTextView.setText("Status: Desconectado");
            Log.d("MQTT", "Não está conectado ao broker");
        }
    }

    private void disparaMensagem(String messageToast){
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
        try {
            mqttClient.disconnect();
            statusTextView.setText("Status: Desconectado");
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}