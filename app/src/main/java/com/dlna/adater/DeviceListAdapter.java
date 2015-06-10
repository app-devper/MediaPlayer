package com.dlna.adater;

import com.dlna.R;
import com.dlna.loader.ImageLoader;
import com.dlna.util.DeviceItem;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class DeviceListAdapter extends ArrayAdapter<DeviceItem> {
	private final ImageLoader imgDownloader;
	private final int deviceItemLayoutResource;

	public DeviceListAdapter(Context paramContext, int paramInt) {
		super(paramContext, 0);
		this.deviceItemLayoutResource = paramInt;
		this.imgDownloader = new ImageLoader(paramContext);
	}

	private View getWorkingView(View paramView) {
		if (paramView == null)
			return ((LayoutInflater) getContext().getSystemService("layout_inflater")).inflate(this.deviceItemLayoutResource, null);
		return paramView;
	}

	public void ClearAdapter() {
		if ((this.imgDownloader == null) || (getCount() < 1))
			return;
		this.imgDownloader.clearCache();
		clear();
	}

	public List<DeviceItem> getAll() {
		ArrayList<DeviceItem> localArrayList = new ArrayList<DeviceItem>();
		int i = getCount();
		for (int j = 0;; j++) {
			if (j >= i)
				return localArrayList;
			localArrayList.add((DeviceItem) getItem(j));
		}
	}

	public View getView(int paramInt, View paramView, ViewGroup paramViewGroup) {

		View v = getWorkingView(paramView);
		DeviceItem localDevice = (DeviceItem) getItem(paramInt);

		TextView deviceName = (TextView) v.findViewById(R.id.m_name);
		ImageView img = (ImageView) v.findViewById(R.id.m_image);
		deviceName.setText(localDevice.toString());
		if (!localDevice.getIslocal()) {
			// Log.i("DeviceList", "URL=" + localDevice.getIconUri());
			imgDownloader.DisplayImage(localDevice.getIconUri(), img);
		} else {
			img.setImageResource(R.drawable.ic_launcher);
		}

		return v;
	}
}
