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
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.sensorstreamerwearos.workout.service.WorkoutService;
import com.example.sensorstreamerwearos.workout.ui.WorkoutTimerActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redirect-only activity. Never renders UI.
 * If workout is active -> WorkoutTimerActivity. Otherwise -> finish.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WorkoutUI";

    private boolean mWorkoutBound = false;
    private final ServiceConnection workoutServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            WorkoutService.LocalBinder binder = (WorkoutService.LocalBinder) service;
            WorkoutService workoutService = binder.getService();
            if (workoutService.isWorkoutActive()) {
                Log.i(TAG, "MainActivity redirecting to WorkoutTimerActivity (workout active)");
                Intent intent = new Intent(MainActivity.this, WorkoutTimerActivity.class);
                String sessionId = workoutService.getActiveSessionId();
                if (sessionId != null) {
                    intent.putExtra("sessionId", sessionId);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "Start workout from phone", Toast.LENGTH_SHORT).show();
            }
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {}
            mWorkoutBound = false;
            finish();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mWorkoutBound = false;
        }
    };

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), isGranted -> {
                boolean allGranted = true;
                for (Map.Entry<String, Boolean> entry : isGranted.entrySet()) {
                    if (!Boolean.TRUE.equals(entry.getValue())) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    Log.d(TAG, "Permissions granted");
                } else {
                    Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
                }
                performRedirect();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();
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
            Log.d(TAG, "Requesting permissions: " + permissions);
            requestPermissionLauncher.launch(permissions.toArray(new String[0]));
        } else {
            performRedirect();
        }
    }

    private void performRedirect() {
        Intent workoutIntent = new Intent(this, WorkoutService.class);
        if (bindService(workoutIntent, workoutServiceConnection, Context.BIND_AUTO_CREATE)) {
            mWorkoutBound = true;
        } else {
            Toast.makeText(this, "Start workout from phone", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onStop() {
        if (mWorkoutBound) {
            try {
                unbindService(workoutServiceConnection);
            } catch (IllegalArgumentException ignored) {}
            mWorkoutBound = false;
        }
        super.onStop();
    }
}
