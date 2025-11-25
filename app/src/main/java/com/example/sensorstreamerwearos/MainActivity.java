package com.example.sensorstreamerwearos;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.sensorstreamerwearos.databinding.ActivityMainBinding;
import com.example.sensorstreamerwearos.service.HeartRateForegroundService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    private HeartRateForegroundService mService;
    private boolean mBound = false;
    private TextView ipAddr;
    private Button startButton, stopButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            HeartRateForegroundService.LocalBinder binder = (HeartRateForegroundService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            updateUI(mService.isRunning());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), isGranted -> {
                boolean allGranted = true;
                for (Map.Entry<String, Boolean> entry : isGranted.entrySet()) {
                    if (!entry.getValue()) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    Log.d(TAG, "Permissions granted");
                } else {
                    Toast.makeText(this, "Permissions required for sensor data", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ipAddr = binding.ipAddr;
        startButton = binding.StartStreaming;
        stopButton = binding.StopStreaming;

        startButton.setOnClickListener(v -> startStreaming());
        stopButton.setOnClickListener(v -> stopStreaming());

        checkPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, HeartRateForegroundService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            unbindService(connection);
            mBound = false;
        }
    }

    private void startStreaming() {
        String ip = ipAddr.getText().toString();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter IP Address", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, HeartRateForegroundService.class);
        intent.setAction("START");
        intent.putExtra("IP", ip);
        startService(intent);
        updateUI(true);
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, HeartRateForegroundService.class);
        intent.setAction("STOP");
        startService(intent);
        updateUI(false);
    }

    private void updateUI(boolean isRunning) {
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BODY_SENSORS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }

        if (!permissions.isEmpty()) {
            requestPermissionLauncher.launch(permissions.toArray(new String[0]));
        }
    }
}
