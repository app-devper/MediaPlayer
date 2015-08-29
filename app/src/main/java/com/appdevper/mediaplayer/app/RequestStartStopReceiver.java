package com.appdevper.mediaplayer.app;

import com.appdevper.mediaplayer.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

public class RequestStartStopReceiver extends BroadcastReceiver {

	static final String TAG = RequestStartStopReceiver.class.getSimpleName();

	@Override
	public void onReceive(Context context, Intent intent) {
		Log.v(TAG, "Received: " + intent.getAction());
		try {
			if (intent.getAction().equals(ServerUpnpService.ACTION_START_SERVER)) {
				Intent serverService = new Intent(context, ServerUpnpService.class);
				if (!ServerUpnpService.isRunning()) {
					warnIfNoExternalStorage();
					context.startService(serverService);
				}
			} else if (intent.getAction().equals(ServerUpnpService.ACTION_STOP_SERVER)) {
				Intent serverService = new Intent(context, ServerUpnpService.class);
				context.stopService(serverService);
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to start/stop on intent " + e.getMessage());
		}
	}

	private void warnIfNoExternalStorage() {
		String storageState = Environment.getExternalStorageState();
		if (!storageState.equals(Environment.MEDIA_MOUNTED)) {
			Log.v(TAG, "Warning due to storage state " + storageState);
			Toast toast = Toast.makeText(AppController.getAppContext(), R.string.storage_warning, Toast.LENGTH_LONG);
			toast.setGravity(Gravity.CENTER, 0, 0);
			toast.show();
		}
	}

}
