package com.appdevper.mediaplayer.fragment;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceType;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.appdevper.R;
import com.appdevper.mediaplayer.activity.ContentActivity;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.adater.DeviceListAdapter;

public class ServerFragment extends Fragment {

	private ListView list;
	private DeviceListAdapter deviceList;
	protected Device<?, ?, Service<?, ?>> cDevice;
	protected Service<?, ?> cServices;
	private static ServerFragment mInstance = null;

	public static Fragment newInstance(String string) {
		mInstance = new ServerFragment();
		Bundle args = new Bundle();
		args.putString("name", string);
		mInstance.setArguments(args);
		return mInstance;
	}

	public static synchronized ServerFragment getInstance() {
		if (mInstance == null)
			newInstance("name");
		return mInstance;
	}

	public DeviceListAdapter getAdapter() {
		return deviceList;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		list = (ListView) getActivity().findViewById(R.id.listServer);
		deviceList = new DeviceListAdapter(getActivity(), R.layout.device_row);
		list.setAdapter(deviceList);
		list.setOnItemClickListener(deviceItemClickListener);
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_server, container, false);
		return v;
	}

	private OnItemClickListener deviceItemClickListener = new OnItemClickListener() {

		@SuppressWarnings("unchecked")
		@Override
		public void onItemClick(AdapterView<?> parent, View v, int position, long id) {

			cDevice = deviceList.getItem(position).getDevice();
			cServices = cDevice.findService(new UDAServiceType("ContentDirectory"));
			ShareData.setDevice(cDevice);

			Intent intent = new Intent();
			intent.setClass(getActivity(), ContentActivity.class);
			startActivityForResult(intent, 200);

		}
	};

	@Override
	public void onResume() {
		super.onResume();
		deviceList.notifyDataSetChanged();
	};
}
