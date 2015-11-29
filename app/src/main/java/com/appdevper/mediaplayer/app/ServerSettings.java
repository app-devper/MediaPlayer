package com.appdevper.mediaplayer.app;

import java.io.File;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

public class ServerSettings {

	private final static String TAG = ServerSettings.class.getSimpleName();

	public static File getChrootDir() {
		final SharedPreferences sp = getSharedPreferences();
		String dirName = sp.getString("chrootDir", "");
		File chrootDir = new File(dirName);
		if (dirName.equals("")) {
			if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
				chrootDir = Environment.getExternalStorageDirectory();
			} else {
				chrootDir = new File("/");
			}
		}
		if (!chrootDir.isDirectory()) {
			Log.e(TAG, "getChrootDir: not a directory");
			return null;
		}
		return chrootDir;
	}

	public static String getDeviceName() {
		final SharedPreferences sp = getSharedPreferences();
		String nameString = sp.getString("deName", android.os.Build.MODEL + " Server");
		Log.v(TAG, "Using Name: " + nameString);
		return nameString;
	}

	public static boolean allowAudio() {
		final SharedPreferences sp = getSharedPreferences();
		return sp.getBoolean("allow_audio", true);
	}

	public static boolean allowVideo() {
		final SharedPreferences sp = getSharedPreferences();
		return sp.getBoolean("allow_video", true);
	}

	public static boolean allowImage() {
		final SharedPreferences sp = getSharedPreferences();
		return sp.getBoolean("allow_image", true);
	}

	public static boolean autoRun() {
		final SharedPreferences sp = getSharedPreferences();
		return sp.getBoolean("auto_run", true);
	}

	public static void setSelectID(ArrayList<String> values) {
		SharedPreferences.Editor editor = getSharedPreferences().edit();
		JSONArray a = new JSONArray();
		for (int i = 0; i < values.size(); i++) {
			a.put(values.get(i));
		}
		if (!values.isEmpty()) {
			editor.putString("select_id", a.toString());
		} else {
			editor.putString("select_id", null);
		}
		editor.commit();
	}

	public static ArrayList<String> getSelectId() {
		String json = getSharedPreferences().getString("select_id", null);
		ArrayList<String> urls = new ArrayList<>();
		if (json != null) {
			try {
				JSONArray a = new JSONArray(json);
				for (int i = 0; i < a.length(); i++) {
					String url = a.optString(i);
					urls.add(url);
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		return urls;
	}

	private static SharedPreferences getSharedPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(AppMediaPlayer.getAppContext());
	}

}
