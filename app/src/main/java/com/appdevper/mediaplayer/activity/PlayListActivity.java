package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;
import java.util.HashMap;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.ContentListAdapter;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.util.ContentItem;

public class PlayListActivity extends ActionBarActivity {
	// Songs list
	public ArrayList<HashMap<String, String>> songsList = new ArrayList<HashMap<String, String>>();
	private ArrayList<ContentItem> sList;
	private ListView contentListView;
	private ContentListAdapter contentListAdapter;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list_item);

		contentListView = (ListView) findViewById(R.id.listrender);
		contentListAdapter = new ContentListAdapter(this, R.layout.media_row);
		contentListView.setAdapter(contentListAdapter);
		contentListView.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				int songIndex = position;

				Intent in = new Intent();
				in.putExtra("songIndex", songIndex);
				setResult(100, in);
				finish();
			}
		});

		// get all songs from sdcard
		sList = ShareData.getcList();

		setSong();

	}

	public void setSong() {
		runOnUiThread(new Runnable() {
			public void run() {
				try {

					// Containers first
					for (int i = 0; i < sList.size(); i++) {

						contentListAdapter.add(sList.get(i));
					}

				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		});
	}
}
