package com.appdevper.mediaplayer.fragment;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.appdevper.R;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.adater.DeviceRenderListAdapter;

public class RenderFragment extends Fragment {

	private ListView list;
	private DeviceRenderListAdapter deviceList;
	private static RenderFragment mInstance = null;

	public static Fragment newInstance(String string) {
		mInstance = new RenderFragment();
		Bundle args = new Bundle();
		args.putString("name", string);
		mInstance.setArguments(args);
		return mInstance;
	}

	public static synchronized RenderFragment getInstance() {
		if (mInstance == null)
			newInstance("name");
		return mInstance;
	}

	public DeviceRenderListAdapter getAdapter() {
		return deviceList;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		list = (ListView) getActivity().findViewById(R.id.listRender);
		deviceList = new DeviceRenderListAdapter(getActivity(), R.layout.device_render_row);
		list.setAdapter(deviceList);
		list.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				ShareData.setrDevice(deviceList.getItem(position));
				deviceList.notifyDataSetChanged();
			}
		});

//		list.setOnItemLongClickListener(new OnItemLongClickListener() {
//
//			@Override
//			public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int position, long id) {
//				ShareData.setrDevice(deviceList.getItem(position));
//				deviceList.notifyDataSetChanged();
//				startRemote();
//				return false;
//			}
//		});
		
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_render, container, false);
		return v;
	}

}
