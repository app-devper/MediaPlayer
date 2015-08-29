package com.appdevper.mediaplayer.fragment;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.UserData;
import com.appdevper.mediaplayer.adater.ItemListAdapter;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

public class VideoFragment extends Fragment {

	private static VideoFragment mInstance = null;

	public static synchronized VideoFragment getInstance() {
		if (mInstance == null)
			mInstance = new VideoFragment();
		return mInstance;
	}

	private ItemListAdapter itemList;
	private ListView list;

	public ItemListAdapter getAdapter() {
		return itemList;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		list = (ListView) getActivity().findViewById(R.id.listVideo);
		itemList = new ItemListAdapter(getActivity(), R.layout.item_row);
		list.setAdapter(itemList);
		itemList.addItemAll(UserData.getArVideo());
		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_video, container, false);
		return v;
	}

}
