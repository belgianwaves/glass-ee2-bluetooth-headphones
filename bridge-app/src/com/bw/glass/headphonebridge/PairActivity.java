package com.bw.glass.headphonebridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * One-tap launcher shortcut on Glass EE2: ensures the A2DP Sink focus bridge service
 * is running and opens Android Bluetooth Settings (which also enables discoverable mode).
 */
public final class PairActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!HeadphoneBridgeService.isGlassMateInstalled(this)) {
            HeadphoneBridgeService.start(this);
        }
        Intent btSettings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        btSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(btSettings);
        finish();
    }
}
