package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.DeviceListAdapter;
import com.appdevper.mediaplayer.app.AppConfig;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.app.MusicService;
import com.appdevper.mediaplayer.app.ServerSettings;
import com.appdevper.mediaplayer.app.ServerUpnpService;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.util.DeviceItem;
import com.appdevper.mediaplayer.util.Utils;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;

public class MainActivity extends BaseActivity {

    private DeviceListRegistryListener deviceListRegistryListener;
    private final static String LOGTAG = MainActivity.class.getSimpleName();

    private ServiceConnection serviceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            AppMediaPlayer.setUpnpService((AndroidUpnpService) service);
            Log.d(LOGTAG, "AndroidUpnpService Connect");
            if (upnpCallBack != null) {
                upnpCallBack.onConnect();
            }

            if (ServerSettings.autoRun() && !ServerUpnpService.isRunning()) {
                sendBroadcast(new Intent(ServerUpnpService.ACTION_START_SERVER));
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            //AppMediaPlayer.setUpnpService(null);
            Log.d(LOGTAG, "AndroidUpnpService DisConnect");
            if (upnpCallBack != null) {
                upnpCallBack.onDisConnect();
            }

        }
    };

    private AdRequest adRequest;

    private InterstitialAd interstitialAd;
    public static final String EXTRA_START_SETTING = "com.appdevper.mediaplayer.EXTRA_START_SETTING";
    public static final String EXTRA_START_FULLSCREEN = "com.appdevper.mediaplayer.EXTRA_START_FULLSCREEN";
    public static final String EXTRA_CURRENT_MEDIA_DESCRIPTION = "com.appdevper.mediaplayer.CURRENT_MEDIA_DESCRIPTION";
    private DeviceListAdapter deviceList;
    private ListView listServer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeToolbar();

        deviceList = new DeviceListAdapter(this);

        listServer = (ListView) findViewById(R.id.listServer);
        listServer.setAdapter(deviceList);
        listServer.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                ShareData.setDevice(deviceList.getItem(position).getDevice());
                Intent intent = new Intent();
                intent.setClass(MainActivity.this, ContentActivity.class);
                intent.putExtra("name", deviceList.getItem(position).toString());
                startActivityForResult(intent, 200);
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left);
            }
        });

        ShareData.setRenList(new ArrayList<DeviceItem>());

        deviceListRegistryListener = new DeviceListRegistryListener();

        adRequest = new AdRequest.Builder().addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();

        interstitialAd = new InterstitialAd(this);
        interstitialAd.setAdUnitId(AppConfig.AD_UNIT_ID);

        interstitialAd.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                Log.d("AdListener", "onAdLoaded");
                //interstitialAd.show();
            }

            @Override
            public void onAdFailedToLoad(int errorCode) {
                String message = String.format("onAdFailedToLoad (%s)", Utils.getErrorReason(errorCode));
                Log.d("AdListener", message);
            }
        });

        //interstitialAd.loadAd(adRequest);

        bindService(new Intent(this, AndroidUpnpServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);

        if (savedInstanceState == null) {
            startFullScreenActivityIfNeeded(getIntent());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (AppMediaPlayer.getUpnpService() != null) {
            AppMediaPlayer.getUpnpService().getRegistry().removeListener(deviceListRegistryListener);
        }

        try {
            unbindService(serviceConnection);
            Log.v(LOGTAG, "unbindService(serviceConnection)");
        } catch (Exception ex) {
            Log.v(LOGTAG, "Can't unbindService(serviceConnection)");
        }
    }

    @Override
    protected void onMediaControllerConnected() {

    }

    @Override
    protected void onNewIntent(Intent intent) {
        startFullScreenActivityIfNeeded(intent);
    }

    private void startFullScreenActivityIfNeeded(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START_FULLSCREEN, false)) {
            Intent fullScreenIntent = new Intent(this, FullScreenPlayerActivity.class);
            fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            fullScreenIntent.putExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION, intent.getParcelableExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION));
            startActivity(fullScreenIntent);
            //overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left);
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_START_SETTING, false)) {
            Intent fullScreenIntent = new Intent(this, SettingPreferenceActivity.class);
            fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(fullScreenIntent);
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left);
        }
    }

    protected void searchNetwork() {
        if (AppMediaPlayer.getUpnpService() == null)
            return;
        Toast.makeText(this, R.string.searching_lan, Toast.LENGTH_SHORT).show();
        AppMediaPlayer.getUpnpService().getControlPoint().search();
    }

    public class DeviceListRegistryListener extends DefaultRegistryListener {

        @Override
        public void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice device) {

        }

        @Override
        public void remoteDeviceDiscoveryFailed(Registry registry, final RemoteDevice device, final Exception ex) {

        }

        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {

            if (device.getType().getNamespace().equals("schemas-upnp-org") && device.getType().getType().equals("MediaServer")) {
                final DeviceItem display = new DeviceItem(device, device.getDetails().getFriendlyName(), device.getDisplayString(), "(REMOTE) " + device.getType().getDisplayString());
                deviceAdded(display);
            } else if (device.getType().getNamespace().equals("schemas-upnp-org") && device.getType().getType().equals("MediaRenderer")) {
                final DeviceItem display = new DeviceItem(device, device.getDetails().getFriendlyName(), device.getDisplayString(), "(REMOTE) " + device.getType().getDisplayString());
                deviceRenAdded(display);
            }
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            final DeviceItem display = new DeviceItem(device, device.getDisplayString());
            deviceRemoved(display);
        }

        @Override
        public void localDeviceAdded(Registry registry, LocalDevice device) {
            final DeviceItem display = new DeviceItem(device, device.getDetails().getFriendlyName(), device.getDisplayString(), "(REMOTE) " + device.getType().getDisplayString());
            deviceAdded(display);
        }

        @Override
        public void localDeviceRemoved(Registry registry, LocalDevice device) {
            final DeviceItem display = new DeviceItem(device, device.getDisplayString());
            deviceRemoved(display);
        }

        public void deviceAdded(final DeviceItem di) {
            runOnUiThread(new Runnable() {
                public void run() {
                    deviceList.add(di);
                }
            });
        }

        public void deviceRenAdded(final DeviceItem di) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ShareData.getRenList().add(di);
                }
            });
        }

        public void deviceRemoved(final DeviceItem di) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ShareData.getRenList().remove(di);
                    deviceList.remove(di);
                }
            });
        }
    }

    private MusicService.UpnpCallBack upnpCallBack = new MusicService.UpnpCallBack() {
        @Override
        public void onConnect() {
            Log.i(LOGTAG, "onConnect");
            DeviceItem di = new DeviceItem(null, true);
            ShareData.getRenList().add(di);
            ShareData.setRenderDevice(di);
            AppMediaPlayer.getUpnpService().getRegistry().addListener(deviceListRegistryListener);
            AppMediaPlayer.getUpnpService().getControlPoint().search();
        }

        @Override
        public void onDisConnect() {

        }
    };
}