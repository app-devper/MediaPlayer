package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.DeviceRenderListAdapter;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.util.DeviceItem;

public class RenderListActivity extends ActionBarActivity {

	private ArrayList<DeviceItem> deviceItem;
	private DeviceRenderListAdapter deviceList;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list_item);

		ListView lvrd = (ListView) findViewById(R.id.listrender);

		deviceItem = ShareData.getRenList();

		deviceList = new DeviceRenderListAdapter(this, R.layout.device_render_row);

		lvrd.setAdapter(deviceList);

		lvrd.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

				ShareData.setrDevice(deviceList.getItem(position));
				setResult(200);
				finish();
			}
		});
		setAdter();
	}

	public void setAdter() {
		runOnUiThread(new Runnable() {
			public void run() {
				try {
					deviceList.clear();
					// Containers first
					for (int i = 0; i < deviceItem.size(); i++) {

						deviceList.add(deviceItem.get(i));
					}

				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		});
	}
}
