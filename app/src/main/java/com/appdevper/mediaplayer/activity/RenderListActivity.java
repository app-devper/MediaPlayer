package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.DeviceRenderListAdapter;
import com.appdevper.mediaplayer.adater.PlayListAdapter;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.app.MusicService;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.model.MusicProvider;
import com.appdevper.mediaplayer.ui.BaseActivity;
import com.appdevper.mediaplayer.util.DeviceItem;

public class RenderListActivity extends BaseActivity {

    private ArrayList<DeviceItem> deviceItem;
    private DeviceRenderListAdapter deviceList;
    private Toolbar mToolbar;
    private ListView listView;

    private MusicService mService;

    private boolean mBound;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;

        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        initializeToolbar();

        listView = (ListView) findViewById(R.id.listView);

        deviceItem = ShareData.getRenList();
        deviceList = new DeviceRenderListAdapter(this, R.layout.device_render_row);

        listView.setAdapter(deviceList);
        listView.setOnItemClickListener(new OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ShareData.setrDevice(deviceList.getItem(position));
                AppMediaPlayer.setService();
                if (!ShareData.getrDevice().getIslocal())
                    mService.connectUpnp(ShareData.getrDevice().toString());
                else
                    mService.disconnectUpnp();
                finish();
            }
        });

        setAdter();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, MusicService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    protected void initializeToolbar() {
        super.initializeToolbar();
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        if (mToolbar == null) {
            throw new IllegalStateException("Layout is required to include a Toolbar with id " + "'toolbar'");
        }

        mToolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
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
