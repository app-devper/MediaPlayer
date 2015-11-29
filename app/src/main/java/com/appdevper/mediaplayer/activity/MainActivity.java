package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;
import java.util.Locale;

import org.fourthline.cling.android.AndroidUpnpService;
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
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.Tab;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.AppConfiguration;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.app.FindUpnpService;
import com.appdevper.mediaplayer.app.MusicService;
import com.appdevper.mediaplayer.app.ServerSettings;
import com.appdevper.mediaplayer.app.ServerUpnpService;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.fragment.RenderFragment;
import com.appdevper.mediaplayer.fragment.ServerFragment;
import com.appdevper.mediaplayer.ui.ActionBarCastActivity;
import com.appdevper.mediaplayer.ui.BaseActivity;
import com.appdevper.mediaplayer.util.DeviceItem;
import com.appdevper.mediaplayer.util.LogHelper;
import com.appdevper.mediaplayer.util.Utils;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

public class MainActivity extends BaseActivity {

    private DeviceListRegistryListener deviceListRegistryListener;
    private final static String LOGTAG = MainActivity.class.getSimpleName();

    private MusicService mService;

    private ServiceConnection serviceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            mService = binder.getService();
            mService.setUpnpCallBack(upnpCallBack);
            Log.v(LOGTAG, "Connected to Music Service");
            if (AppMediaPlayer.getUpnpService() != null) {
                upnpCallBack.onConnect();
            }
        }

        public void onServiceDisconnected(ComponentName className) {

        }
    };

    private AdRequest adRequest;
    private SectionsPagerAdapter mSectionsPagerAdapter;
    private ViewPager mViewPager;
    private InterstitialAd interstitialAd;
    public static final String EXTRA_START_FULLSCREEN = "com.appdevper.mediaplayer.EXTRA_START_FULLSCREEN";
    public static final String EXTRA_CURRENT_MEDIA_DESCRIPTION = "com.appdevper.mediaplayer.CURRENT_MEDIA_DESCRIPTION";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeToolbar();
        //AdView adView = (AdView) findViewById(R.id.adView);

        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(mSectionsPagerAdapter);

        ShareData.setRenList(new ArrayList<DeviceItem>());

        deviceListRegistryListener = new DeviceListRegistryListener();

        adRequest = new AdRequest.Builder().addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();

        interstitialAd = new InterstitialAd(this);
        interstitialAd.setAdUnitId(AppConfiguration.AD_UNIT_ID);

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

        bindService(new Intent(this, MusicService.class), serviceConnection, Context.BIND_AUTO_CREATE);

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
        } catch (Exception ex) {
            Log.v(LOGTAG, "Can't unbindService(serviceConnection)");
        }
        Log.v(LOGTAG, "unbindService(serviceConnection)");
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
                    int position = -1;
                    try {
                        position = ServerFragment.getInstance().getAdapter().getPosition(di);
                    } catch (Exception e) {

                    }

                    if (position >= 0) {
                        ServerFragment.getInstance().getAdapter().remove(di);
                        ServerFragment.getInstance().getAdapter().insert(di, position);
                    } else {
                        ServerFragment.getInstance().getAdapter().add(di);
                    }
                }
            });
        }

        public void deviceRenAdded(final DeviceItem di) {
            runOnUiThread(new Runnable() {
                public void run() {
                    int position = -1;
                    try {
                        position = RenderFragment.getInstance().getAdapter().getPosition(di);
                    } catch (Exception e) {

                    }
                    if (position >= 0) {
                        RenderFragment.getInstance().getAdapter().remove(di);
                        RenderFragment.getInstance().getAdapter().insert(di, position);
                    } else {
                        RenderFragment.getInstance().getAdapter().add(di);
                        ShareData.getRenList().add(di);
                    }

                }
            });
        }

        public void deviceRemoved(final DeviceItem di) {
            runOnUiThread(new Runnable() {
                public void run() {
                    ServerFragment.getInstance().getAdapter().remove(di);
                    RenderFragment.getInstance().getAdapter().remove(di);
                    ShareData.getRenList().remove(di);
                }
            });
        }
    }

    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            Fragment fragment = null;
            switch (position) {
                case 0:
                    fragment = ServerFragment.newInstance("HOME");
                    break;
                case 1:
                    fragment = RenderFragment.newInstance("HOME");
                    break;

            }

            return fragment;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            Locale l = Locale.getDefault();
            switch (position) {
                case 0:
                    return "server".toUpperCase(l);
                case 1:
                    return "renderer".toUpperCase(l);

            }
            return null;
        }
    }

    private MusicService.UpnpCallBack upnpCallBack = new MusicService.UpnpCallBack() {
        @Override
        public void onConnect() {
            Log.i(LOGTAG, "onConnect");
            DeviceItem di = new DeviceItem(null, true);
            try {
                RenderFragment.getInstance().getAdapter().add(di);
            } catch (Exception e) {
                // TODO: handle exception
            }

            ShareData.getRenList().add(di);
            ShareData.setrDevice(di);
            AppMediaPlayer.getUpnpService().getRegistry().addListener(deviceListRegistryListener);
            AppMediaPlayer.getUpnpService().getControlPoint().search();
        }

        @Override
        public void onDisConnect() {

        }
    };
}