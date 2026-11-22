package com.bw.glass.headphonebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GlassHeadphoneBridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HeadphoneBridgeService.isGlassMateInstalled(context)) {
            Log.i(TAG, "GlassMate detected — yielding A2DP Sink focus bridge to GlassMate.");
            return;
        }
        HeadphoneBridgeService.start(context);
    }
}
