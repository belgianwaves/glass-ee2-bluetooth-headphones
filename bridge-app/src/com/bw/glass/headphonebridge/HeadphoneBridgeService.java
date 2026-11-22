package com.bw.glass.headphonebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Bridges Android 8.1's `A2dpSinkService` / `A2dpMediaBrowserService` on Google Glass EE2
 * so Glass behaves like standard Bluetooth stereo headphones (`A2DP Sink` + `AVRCP`).
 *
 * On Android 8.1 (`OPM1`), AOSP's `A2dpSinkStreamHandler` was designed for Android Auto head units
 * and keeps the Fluoride `AudioTrack` muted (`STATE_FOCUS_LOST`) when a phone begins streaming
 * until a local `MediaBrowser` client dispatches `play()` (`SNK_PLAY` -> `requestAudioFocus` ->
 * `informAudioFocusStateNative(1)` + gain `1.0f`).
 */
public final class HeadphoneBridgeService extends Service {
    private static final String TAG = "GlassHeadphoneBridge";
    private static final String CHANNEL_ID = "glass_a2dp_headphone_bridge";
    private static final int NOTIFICATION_ID = 1108;

    private static final int PROFILE_A2DP_SINK = 11;
    private static final int STATE_PLAYING = 10;
    private static final int STATE_NOT_PLAYING = 11;

    private static final String ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_A2DP_SINK_PLAYING_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED";

    private static final ComponentName MBS_COMPONENT = new ComponentName(
            "com.android.bluetooth",
            "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"
    );

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaBrowser mediaBrowser;
    private MediaController mediaController;
    private boolean receiverRegistered;
    private boolean pendingPlayGrant;

    public static boolean isGlassMateInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.bw.glassmate.app", 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    public static void start(Context context) {
        Intent svc = new Intent(context, HeadphoneBridgeService.class);
        context.startForegroundService(svc);
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                Log.i(TAG, "A2DP Sink connection state=" + state + " device=" + (dev != null ? dev.getAddress() : "null"));
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectBrowserIfNeeded();
                }
            } else if (ACTION_A2DP_SINK_PLAYING_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, STATE_NOT_PLAYING);
                Log.i(TAG, "A2DP Sink playing state=" + state);
                if (state == STATE_PLAYING) {
                    grantSinkAudioFocus();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    mainHandler.postDelayed(HeadphoneBridgeService.this::connectBrowserIfNeeded, 600L);
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    disconnectBrowser();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startMinimalForegroundNotification();
        if (isGlassMateInstalled(this)) {
            Log.i(TAG, "GlassMate detected — stopping standalone bridge service.");
            stopSelf();
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_A2DP_SINK_PLAYING_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btReceiver, filter);
        receiverRegistered = true;
        connectBrowserIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isGlassMateInstalled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        connectBrowserIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(btReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        disconnectBrowser();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startMinimalForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Bluetooth Headphone Bridge",
                    NotificationManager.IMPORTANCE_MIN
            );
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent btSettings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        PendingIntent pi = PendingIntent.getActivity(this, 0, btSettings, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Bluetooth Headphone Mode Active")
                .setContentText("Tap to open Bluetooth pairing settings")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    private boolean isA2dpSinkServiceAvailable() {
        try {
            Intent intent = new Intent("android.media.browse.MediaBrowserService");
            intent.setComponent(MBS_COMPONENT);
            return getPackageManager().resolveService(intent, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void connectBrowserIfNeeded() {
        if (!isA2dpSinkServiceAvailable()) {
            Log.d(TAG, "A2dpMediaBrowserService not enabled yet — waiting for Magisk module.");
            return;
        }
        if (mediaBrowser != null && mediaBrowser.isConnected()) {
            checkIfAlreadyStreamingAndGrantFocus();
            return;
        }
        disconnectBrowser();
        try {
            mediaBrowser = new MediaBrowser(
                    this,
                    MBS_COMPONENT,
                    new MediaBrowser.ConnectionCallback() {
                        @Override
                        public void onConnected() {
                            if (mediaBrowser == null) return;
                            try {
                                mediaController = new MediaController(
                                        HeadphoneBridgeService.this,
                                        mediaBrowser.getSessionToken()
                                );
                                Log.i(TAG, "Connected to A2dpMediaBrowserService");
                                if (pendingPlayGrant) {
                                    pendingPlayGrant = false;
                                    mediaController.getTransportControls().play();
                                } else {
                                    checkIfAlreadyStreamingAndGrantFocus();
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "MediaController creation failed: " + t.getMessage());
                            }
                        }

                        @Override
                        public void onConnectionSuspended() {
                            mediaController = null;
                        }

                        @Override
                        public void onConnectionFailed() {
                            mediaController = null;
                            mediaBrowser = null;
                        }
                    },
                    null
            );
            mediaBrowser.connect();
        } catch (Throwable t) {
            Log.w(TAG, "MediaBrowser.connect failed: " + t.getMessage());
        }
    }

    private void disconnectBrowser() {
        mediaController = null;
        if (mediaBrowser != null) {
            try {
                mediaBrowser.disconnect();
            } catch (Throwable ignored) {
            }
            mediaBrowser = null;
        }
    }

    private void grantSinkAudioFocus() {
        mainHandler.post(() -> {
            if (mediaController != null) {
                Log.i(TAG, "Dispatching SNK_PLAY to unmute A2DP Sink speaker stream");
                mediaController.getTransportControls().play();
            } else {
                pendingPlayGrant = true;
                connectBrowserIfNeeded();
            }
        });
    }

    private void checkIfAlreadyStreamingAndGrantFocus() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        List<BluetoothDevice> devices = proxy.getConnectedDevices();
                        Method isPlaying = proxy.getClass().getMethod("isA2dpPlaying", BluetoothDevice.class);
                        for (BluetoothDevice dev : devices) {
                            Object res = isPlaying.invoke(proxy, dev);
                            if (Boolean.TRUE.equals(res)) {
                                grantSinkAudioFocus();
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, PROFILE_A2DP_SINK);
        } catch (Throwable ignored) {
        }
    }
}
