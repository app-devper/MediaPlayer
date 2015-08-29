package com.appdevper.mediaplayer.app;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;

import android.net.wifi.WifiManager;
import android.os.AsyncTask;

public class FindUpnpService extends AndroidUpnpServiceImpl {
	protected AndroidUpnpServiceConfiguration createConfiguration(WifiManager wifiManager) {
		return new AndroidUpnpServiceConfiguration(wifiManager) {

		};
	}

//	@Override
//	public void onDestroy() {
//
//		new Thread(new Runnable() {
//
//			@Override
//			public void run() {
//				if (!ModelUtil.ANDROID_EMULATOR && isListeningForConnectivityChanges()) {
//					unregisterReceiver(((AndroidWifiSwitchableRouter) upnpService.getRouter()).getBroadcastReceiver());
//				}
//
//				new Shutdown().execute(upnpService);
//
//			}
//		}).run();
//	}

	class Shutdown extends AsyncTask<UpnpService, Void, Void> {
		@Override
		protected Void doInBackground(UpnpService... svcs) {
			UpnpService svc = svcs[0];
			if (null != svc) {
				try {
					svc.shutdown();
				} catch (java.lang.IllegalArgumentException ex) {
					ex.printStackTrace();
				}
			}
			return null;
		}
	}

}