package com.example.sensorstreamerwearos.network;

import android.util.Log;
import com.example.sensorstreamerwearos.model.SensorData;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpRepository {
    private static final String TAG = "UdpRepository";
    private DatagramSocket socket;
    private String serverIp;
    private int serverPort;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isRunning = false;

    public void start(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        this.isRunning = true;
        
        executorService.execute(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                socket = new DatagramSocket();
                Log.d(TAG, "UDP Socket created");
            } catch (SocketException e) {
                Log.e(TAG, "Error creating socket", e);
            }
        });
    }

    public void sendData(SensorData data) {
        if (!isRunning || socket == null) return;

        executorService.execute(() -> {
            try {
                InetAddress address = InetAddress.getByName(serverIp);
                StringBuilder sb = new StringBuilder();
                sb.append(System.currentTimeMillis()).append(","); // Unix timestamp
                sb.append(data.type).append(",");
                sb.append(data.timestamp).append(","); // Sensor timestamp
                
                for (int i = 0; i < data.values.length; i++) {
                    sb.append(data.values[i]);
                    if (i < data.values.length - 1) {
                        sb.append(",");
                    }
                }

                byte[] buffer = sb.toString().getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, serverPort);
                socket.send(packet);
                Log.d(TAG, "Sent: " + sb.toString());

            } catch (UnknownHostException e) {
                Log.e(TAG, "Unknown host: " + serverIp, e);
            } catch (IOException e) {
                Log.e(TAG, "Error sending data", e);
            }
        });
    }

    public void stop() {
        isRunning = false;
        executorService.execute(() -> {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        });
    }
}
