package com.haram.block;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {
    private static final int MEDIA_PROJECTION_REQUEST_CODE = 5678;
    private static final String PREFS = "com.haram.block";
    private static final String PREF_USER_WANTS_ACTIVE = "user_wants_active";

    // App -> Service command
    public static final String ACTION_SET_ACTIVE = "com.haram.block.ACTION_SET_ACTIVE";
    public static final String EXTRA_ACTIVE = "extra_active";

    private MediaProjectionManager mediaProjectionManager;
    private SwitchMaterial serviceSwitch;
    private SharedPreferences prefs;

    private ContentObserver accessibilityObserver;
    private boolean suppressSwitchCallback = false;
    private boolean pendingActivation = false; // Track if we're waiting to activate after permissions

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        serviceSwitch = findViewById(R.id.switchService);

        // Observe system accessibility setting
        accessibilityObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateSwitchState();
                checkPendingActivation();
            }
            @Override
            public void onChange(boolean selfChange) {
                updateSwitchState();
                checkPendingActivation();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilityObserver
        );

        // Initial state
        updateSwitchState();

        // Toggle listener
        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitchCallback) return;

            // Save user intent
            prefs.edit().putBoolean(PREF_USER_WANTS_ACTIVE, isChecked).apply();

            if (isChecked) {
                activateService();
            } else {
                deactivateService();
            }
        });
    }

    private void activateService() {
        boolean serviceEnabled = isAccessibilityServiceEnabled(this, ImageViewAccessibilityService.class);
        
        if (!serviceEnabled) {
            // Need to enable accessibility first
            pendingActivation = true;
            Toast.makeText(this, "Please enable the Accessibility Service", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // Accessibility is enabled, now check for media projection
        if (ImageViewAccessibilityService.sMediaProjectionResultData == null) {
            // Need media projection permission
            pendingActivation = true;
            startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    MEDIA_PROJECTION_REQUEST_CODE
            );
        } else {
            // Both permissions granted, activate the feature
            sendSetActive(true);
            Toast.makeText(this, "Service activated", Toast.LENGTH_SHORT).show();
        }
    }

    private void deactivateService() {
        pendingActivation = false;
        sendSetActive(false);
        Toast.makeText(this, "Service deactivated", Toast.LENGTH_SHORT).show();
    }

    private void checkPendingActivation() {
        // This is called when accessibility settings change
        if (pendingActivation) {
            boolean userWantsActive = prefs.getBoolean(PREF_USER_WANTS_ACTIVE, false);
            boolean serviceEnabled = isAccessibilityServiceEnabled(this, ImageViewAccessibilityService.class);
            
            if (userWantsActive && serviceEnabled) {
                // User just enabled accessibility, continue with media projection
                pendingActivation = false;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ImageViewAccessibilityService.sMediaProjectionResultData == null) {
                        startActivityForResult(
                                mediaProjectionManager.createScreenCaptureIntent(),
                                MEDIA_PROJECTION_REQUEST_CODE
                        );
                    } else {
                        sendSetActive(true);
                        Toast.makeText(this, "Service activated", Toast.LENGTH_SHORT).show();
                    }
                }, 500); // Small delay to ensure service is fully connected
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSwitchState();
        
        // Check if we were waiting for accessibility to be enabled
        if (pendingActivation) {
            checkPendingActivation();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (accessibilityObserver != null) {
            getContentResolver().unregisterContentObserver(accessibilityObserver);
        }
    }

    private void updateSwitchState() {
        boolean userWantsActive = prefs.getBoolean(PREF_USER_WANTS_ACTIVE, false);
        boolean serviceEnabled = isAccessibilityServiceEnabled(this, ImageViewAccessibilityService.class);
        boolean hasMediaProjection = ImageViewAccessibilityService.sMediaProjectionResultData != null;

        // The switch is ON only if user wants it AND service is enabled AND we have all permissions
        suppressSwitchCallback = true;
        serviceSwitch.setChecked(userWantsActive && serviceEnabled && hasMediaProjection);
        suppressSwitchCallback = false;
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<? extends AccessibilityService> serviceClass) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null || enabledServices.isEmpty()) return false;

        ComponentName componentName = new ComponentName(context, serviceClass);
        String flat = componentName.flattenToString();
        String flatShort = componentName.flattenToShortString();

        for (String enabled : enabledServices.split(":")) {
            if (flat.equalsIgnoreCase(enabled) || flatShort.equalsIgnoreCase(enabled)) {
                return true;
            }
        }
        return false;
    }

    private void sendSetActive(boolean active) {
        Intent i = new Intent(ACTION_SET_ACTIVE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ACTIVE, active);
        sendBroadcast(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            pendingActivation = false;
            
            if (resultCode == RESULT_OK && data != null) {
                // Save the media projection data
                ImageViewAccessibilityService.sMediaProjectionResultCode = resultCode;
                ImageViewAccessibilityService.sMediaProjectionResultData = data;
                
                // Now activate the service
                sendSetActive(true);
                Toast.makeText(this, "Service activated with screen capture", Toast.LENGTH_SHORT).show();
                
                // Update the switch to reflect the active state
                updateSwitchState();
            } else {
                // User denied screen capture
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                
                // Turn off the switch and clear the user preference
                prefs.edit().putBoolean(PREF_USER_WANTS_ACTIVE, false).apply();
                suppressSwitchCallback = true;
                serviceSwitch.setChecked(false);
                suppressSwitchCallback = false;
            }
        }
    }
}