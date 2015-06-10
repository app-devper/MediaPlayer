package com.dlna.fragment;

import com.dlna.R;
import com.dlna.activity.UserData;
import com.dlna.adater.ItemListAdapter;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;


public class ImageFragment extends Fragment {

	private static ImageFragment mInstance = null;

	public static synchronized ImageFragment getInstance() {
		if (mInstance == null)
			mInstance = new ImageFragment();
		return mInstance;
	}

	private ItemListAdapter itemList;
	private ListView list;

	public ItemListAdapter getAdapter() {
		return itemList;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		list = (ListView) getActivity().findViewById(R.id.listImage);
		itemList = new ItemListAdapter(getActivity(), R.layout.item_row);
		list.setAdapter(itemList);
		itemList.addItemAll(UserData.getArImage());
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_image, container, false);
		return v;
	}
}
