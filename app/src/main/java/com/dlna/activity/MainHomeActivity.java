package com.dlna.activity;

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

import com.dlna.R;
import com.dlna.fragment.RenderFragment;
import com.dlna.fragment.ServerFragment;
import com.dlna.util.DeviceItem;
import com.dlna.util.Utils;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

public class MainHomeActivity extends ActionBarActivity implements ActionBar.TabListener {

	private DeviceListRegistryListener deviceListRegistryListener;
	private final static String LOGTAG = MainHomeActivity.class.getSimpleName();
	private ServiceConnection serviceConnection = new ServiceConnection() {

		public void onServiceConnected(ComponentName className, IBinder service) {
			ShareData.setUpnpService((AndroidUpnpService) service);

			Log.v(LOGTAG, "Connected to UPnP Service");

			DeviceItem di = new DeviceItem(null, true);
			try {
				RenderFragment.getInstance().getAdapter().add(di);
			} catch (Exception e) {
				// TODO: handle exception
			}
			
			ShareData.getRenList().add(di);
			ShareData.setrDevice(di);
			ShareData.getUpnpService().getRegistry().addListener(deviceListRegistryListener);
			ShareData.getUpnpService().getControlPoint().search();
		}

		public void onServiceDisconnected(ComponentName className) {
			ShareData.setUpnpService(null);

		}
	};

	private AdRequest adRequest;
	private SectionsPagerAdapter mSectionsPagerAdapter;
	private ViewPager mViewPager;
	private InterstitialAd interstitialAd;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		AdView adView = (AdView) findViewById(R.id.adView);
		final ActionBar actionBar = getSupportActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

		mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

		mViewPager = (ViewPager) findViewById(R.id.pager);
		mViewPager.setAdapter(mSectionsPagerAdapter);

		mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
			@Override
			public void onPageSelected(int position) {
				actionBar.setSelectedNavigationItem(position);
			}
		});

		for (int i = 0; i < mSectionsPagerAdapter.getCount(); i++) {

			actionBar.addTab(actionBar.newTab().setText(mSectionsPagerAdapter.getPageTitle(i)).setTabListener(this));
		}

		ShareData.setRenList(new ArrayList<DeviceItem>());

		deviceListRegistryListener = new DeviceListRegistryListener();

		if (ServerSettings.autoRun()) {
			sendBroadcast(new Intent(ServerUpnpService.ACTION_START_SERVER));
		}

		bindService(new Intent(this, FindUpnpService.class), serviceConnection, Context.BIND_AUTO_CREATE);

		adRequest = new AdRequest.Builder().addTestDevice("FC30F813719E71A110A143F708B6C212").addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();
		adView.loadAd(adRequest);
		
		interstitialAd = new InterstitialAd(this);
		interstitialAd.setAdUnitId(ShareData.AD_UNIT_ID);

		interstitialAd.setAdListener(new AdListener() {
			@Override
			public void onAdLoaded() {
				Log.d("AdListener", "onAdLoaded");
				interstitialAd.show();
			}

			@Override
			public void onAdFailedToLoad(int errorCode) {
				String message = String.format("onAdFailedToLoad (%s)", Utils.getErrorReason(errorCode));
				Log.d("AdListener", message);
			}
		});

	    interstitialAd.loadAd(adRequest);
		
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (ShareData.getUpnpService() != null) {
			ShareData.getUpnpService().getRegistry().removeListener(deviceListRegistryListener);
		}
		try {
			unbindService(serviceConnection);
		} catch (Exception ex) {
			Log.v(LOGTAG, "Can't unbindService(serviceConnection)");
		}
		Log.v(LOGTAG, "unbindService(serviceConnection)");
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.refresh:
			searchNetwork();
			break;

		case R.id.action_settings:
			startActivity(new Intent(this, SettingPreferenceActivity.class));
			break;
		}

		return false;
	}

	protected void searchNetwork() {
		if (ShareData.getUpnpService() == null)
			return;
		Toast.makeText(this, R.string.searching_lan, Toast.LENGTH_SHORT).show();
		ShareData.getUpnpService().getControlPoint().search();
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

	@Override
	public void onTabReselected(Tab arg0, FragmentTransaction arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onTabSelected(Tab tab, FragmentTransaction arg1) {
		mViewPager.setCurrentItem(tab.getPosition());
	}

	@Override
	public void onTabUnselected(Tab arg0, FragmentTransaction arg1) {
		// TODO Auto-generated method stub

	}
}