package com.example.sensorstreamerwearos;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
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
    private static final String DEFAULT_IP = "192.168.68.54";
    
    private ActivityMainBinding binding;
    private HeartRateForegroundService mService;
    private boolean mBound = false;
    private Button toggleButton;
    private boolean isRunning = false;

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

        toggleButton = binding.toggleButton;
        toggleButton.setOnClickListener(v -> {
            animateButtonPress();
            toggleStreaming();
        });

        binding.pingButton.setOnClickListener(v -> {
            new com.example.sensorstreamerwearos.network.WatchDataSender(this).sendPing();
            Toast.makeText(this, "Ping sent", Toast.LENGTH_SHORT).show();
        });

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

    private void toggleStreaming() {
        if (isRunning) {
            stopStreaming();
        } else {
            startStreaming();
        }
    }

    private void startStreaming() {
        Intent intent = new Intent(this, HeartRateForegroundService.class);
        intent.setAction("START");
        intent.putExtra("IP", DEFAULT_IP);
        startService(intent);
        updateUI(true);
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, HeartRateForegroundService.class);
        intent.setAction("STOP");
        startService(intent);
        updateUI(false);
    }

    private void updateUI(boolean running) {
        isRunning = running;
        if (running) {
            // Stop state
            toggleButton.setText("Stop");
            toggleButton.setBackgroundResource(R.drawable.button_stop);
        } else {
            // Start state
            toggleButton.setText("Start");
            toggleButton.setBackgroundResource(R.drawable.button_start);
        }
    }

    private void animateButtonPress() {
        // Scale down animation
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(toggleButton, "scaleX", 0.92f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(toggleButton, "scaleY", 0.92f);
        scaleDownX.setDuration(100);
        scaleDownY.setDuration(100);

        // Scale up animation
        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(toggleButton, "scaleX", 1.0f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(toggleButton, "scaleY", 1.0f);
        scaleUpX.setDuration(100);
        scaleUpY.setDuration(100);

        // Create animation set
        AnimatorSet scaleDown = new AnimatorSet();
        scaleDown.play(scaleDownX).with(scaleDownY);
        scaleDown.setInterpolator(new AccelerateDecelerateInterpolator());

        AnimatorSet scaleUp = new AnimatorSet();
        scaleUp.play(scaleUpX).with(scaleUpY);
        scaleUp.setInterpolator(new AccelerateDecelerateInterpolator());

        // Play animations sequentially
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.play(scaleDown).before(scaleUp);
        animatorSet.start();
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
