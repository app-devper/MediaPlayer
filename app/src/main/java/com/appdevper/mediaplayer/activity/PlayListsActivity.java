package com.appdevper.mediaplayer.activity;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.PlayListAdapter;
import com.appdevper.mediaplayer.model.MusicProvider;

public class PlayListsActivity extends BaseActivity {
    private final static String TAG = PlayListsActivity.class.getSimpleName();
    private Toolbar mToolbar;
    private ListView listView;
    private PlayListAdapter adapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        initializeToolbar();

        listView = (ListView) findViewById(R.id.listView);
        adapter = new PlayListAdapter(this, MusicProvider.getInstance().getListMusic());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                onMediaItemSelected(MusicProvider.getInstance().getListMusic().get(i).getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            }
        });
    }

    @Override
    protected void initializeToolbar() {
        super.initializeToolbar();
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        if (mToolbar == null) {
            throw new IllegalStateException("Layout is required to include a Toolbar with id " + "'toolbar'");
        }

        //mToolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
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

    public void onMediaItemSelected(String mediaId) {
        getSupportMediaController().getTransportControls().playFromMediaId(mediaId, null);
    }

    private final MediaControllerCompat.Callback mMediaControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            if (metadata == null) {
                return;
            }
            Log.d(TAG, "Received metadata change to media " + metadata.getDescription().getMediaId());
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            Log.d(TAG, "Received state change: " + state);
            adapter.notifyDataSetChanged();
        }
    };

    @Override
    protected void onMediaControllerConnected() {
        if (getSupportMediaController() != null) {
            getSupportMediaController().registerCallback(mMediaControllerCallback);
        }
    }
}
