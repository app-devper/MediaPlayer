/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.appdevper.mediaplayer.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.MusicService;
import com.appdevper.mediaplayer.model.MusicProvider;
import com.appdevper.mediaplayer.ui.*;
import com.appdevper.mediaplayer.util.AlbumArtCache;
import com.appdevper.mediaplayer.util.LogHelper;
import com.google.android.libraries.cast.companionlibrary.utils.Utils;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

public class FullScreenPlayerActivity extends ActionBarCastActivity {
    private static final String TAG = LogHelper.makeLogTag(FullScreenPlayerActivity.class);
    private static final long PROGRESS_UPDATE_INTERNAL = 1000;
    private static final long PROGRESS_UPDATE_INITIAL_INTERVAL = 100;

    private ImageView mSkipPrev;
    private ImageView mSkipNext;
    private ImageView mPlayPause;
    private TextView mStart;
    private TextView mEnd;
    private SeekBar mSeekbar;
    private TextView mLine1;
    private TextView mLine2;
    private TextView mLine3;
    private ProgressBar mLoading;
    private View mControllers;
    private Drawable mPauseDrawable;
    private Drawable mPlayDrawable;
    private ImageView mBackgroundImage;

    private String mCurrentArtUrl;
    private final Handler mHandler = new Handler();

    private final Runnable mUpdateProgressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
        }
    };

    private final ScheduledExecutorService mExecutorService = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> mScheduleFuture;
    private PlaybackState mLastPlaybackState;

    private final MediaController.Callback mCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
            LogHelper.d(TAG, "onPlaybackstate changed", state);
            updatePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (metadata != null) {
                updateMediaDescription(metadata.getDescription());
                updateDuration(metadata);
            }
        }
    };

    private MusicService mService;

    private boolean mBound;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            connectToSession(mService.getSessionToken());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_player);
        initializeToolbar();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }

        mBackgroundImage = (ImageView) findViewById(R.id.background_image);
        mPauseDrawable = getDrawable(R.drawable.uamp_ic_pause_white_48dp);
        mPlayDrawable = getDrawable(R.drawable.uamp_ic_play_arrow_white_48dp);
        mPlayPause = (ImageView) findViewById(R.id.play_pause);
        mSkipNext = (ImageView) findViewById(R.id.next);
        mSkipPrev = (ImageView) findViewById(R.id.prev);
        mStart = (TextView) findViewById(R.id.startText);
        mEnd = (TextView) findViewById(R.id.endText);
        mSeekbar = (SeekBar) findViewById(R.id.seekBar1);
        mLine1 = (TextView) findViewById(R.id.line1);
        mLine2 = (TextView) findViewById(R.id.line2);
        mLine3 = (TextView) findViewById(R.id.line3);
        mLoading = (ProgressBar) findViewById(R.id.progressBar1);
        mControllers = findViewById(R.id.controllers);

        mSkipNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaController.TransportControls controls = getMediaController().getTransportControls();
                controls.skipToNext();
            }
        });

        mSkipPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaController.TransportControls controls = getMediaController().getTransportControls();
                controls.skipToPrevious();
            }
        });

        mPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlaybackState state = getMediaController().getPlaybackState();
                if (state != null) {
                    MediaController.TransportControls controls = getMediaController().getTransportControls();
                    switch (state.getState()) {
                        case PlaybackState.STATE_PLAYING: // fall through
                        case PlaybackState.STATE_BUFFERING:
                            controls.pause();
                            stopSeekbarUpdate();
                            break;
                        case PlaybackState.STATE_PAUSED:
                        case PlaybackState.STATE_STOPPED:
                            controls.play();
                            scheduleSeekbarUpdate();
                            break;
                        default:
                            LogHelper.d(TAG, "onClick with state ", state.getState());
                    }
                }
            }
        });

        mSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mStart.setText(Utils.formatMillis(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopSeekbarUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                getMediaController().getTransportControls().seekTo(seekBar.getProgress());
                scheduleSeekbarUpdate();
            }
        });

        // Only update from the intent if we are not recreating from a config change:
        if (savedInstanceState == null) {
            updateFromParams(getIntent());
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.findItem(R.id.playlist).setVisible(true);
        return true;
    }

    private void connectToSession(MediaSession.Token token) {
        MediaController mediaController = new MediaController(FullScreenPlayerActivity.this, token);
        if (mediaController.getMetadata() == null) {
            finish();
            return;
        }
        setMediaController(mediaController);
        mediaController.registerCallback(mCallback);
        PlaybackState state = mediaController.getPlaybackState();
        updatePlaybackState(state);
        MediaMetadata metadata = mediaController.getMetadata();
        if (metadata != null) {
            updateMediaDescription(metadata.getDescription());
            updateDuration(metadata);
        }
        updateProgress();
        if (state != null && (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_BUFFERING)) {
            scheduleSeekbarUpdate();
        }
    }

    private void updateFromParams(Intent intent) {
        if (intent != null) {
            MediaDescription description = intent.getParcelableExtra(MainActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION);
            if (description != null) {
                updateMediaDescription(description);
            }
        }
    }

    private void scheduleSeekbarUpdate() {
        stopSeekbarUpdate();
        if (!mExecutorService.isShutdown()) {
            mScheduleFuture = mExecutorService.scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            mHandler.post(mUpdateProgressTask);
                        }
                    }, PROGRESS_UPDATE_INITIAL_INTERVAL, PROGRESS_UPDATE_INTERNAL, TimeUnit.MILLISECONDS);
        }
    }

    private void stopSeekbarUpdate() {
        if (mScheduleFuture != null) {
            mScheduleFuture.cancel(false);
        }
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
        if (getMediaController() != null) {
            getMediaController().unregisterCallback(mCallback);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSeekbarUpdate();
        mExecutorService.shutdown();
    }

    private void fetchImageAsync(@NonNull final MediaDescription description) {
        if (description.getIconUri() == null) {
            com.appdevper.mediaplayer.util.Utils.downloadBitmap(getResources(), description.getMediaId(), mBackgroundImage);
            return;
        }
        String artUrl = description.getIconUri().toString();
        mCurrentArtUrl = artUrl;
        AlbumArtCache cache = AlbumArtCache.getInstance();
        Bitmap art = cache.getBigImage(artUrl);
        if (art == null) {
            art = description.getIconBitmap();
        }
        if (art != null) {
            // if we have the art cached or from the MediaDescription, use it:
            mBackgroundImage.setImageBitmap(art);
        } else {
            // otherwise, fetch a high res version and update:
            cache.fetch(artUrl, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    if (artUrl.equals(mCurrentArtUrl)) {
                        mBackgroundImage.setImageBitmap(bitmap);
                    }
                }

                @Override
                public void onError(String artUrl, Exception e) {
                    com.appdevper.mediaplayer.util.Utils.downloadBitmap(getResources(), description.getMediaId(), mBackgroundImage);
                }
            });
        }
    }

    private void updateMediaDescription(MediaDescription description) {
        if (description == null) {
            return;
        }
        LogHelper.d(TAG, "updateMediaDescription called ");
        mLine1.setText(description.getTitle());
        mLine2.setText(description.getSubtitle());
        fetchImageAsync(description);
    }

    private void updateDuration(MediaMetadata metadata) {
        if (metadata == null) {
            return;
        }
        LogHelper.d(TAG, "updateDuration called");
        int duration = (int) metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        mSeekbar.setMax(duration);
        mEnd.setText(Utils.formatMillis(duration));
    }

    private void updatePlaybackState(PlaybackState state) {
        if (state == null) {
            return;
        }
        mLastPlaybackState = state;
        if (getMediaController() != null && getMediaController().getExtras() != null) {
            String castName = getMediaController().getExtras().getString(MusicService.EXTRA_CONNECTED_CAST);
            String line3Text = castName == null ? "" : getResources().getString(R.string.casting_to_device, castName);
            mLine3.setText(line3Text);
        }

        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPauseDrawable);
                mControllers.setVisibility(VISIBLE);
                scheduleSeekbarUpdate();
                break;
            case PlaybackState.STATE_PAUSED:
                mControllers.setVisibility(VISIBLE);
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekbarUpdate();
                break;
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_STOPPED:
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekbarUpdate();
                break;
            case PlaybackState.STATE_BUFFERING:
                mPlayPause.setVisibility(INVISIBLE);
                mLoading.setVisibility(VISIBLE);
                mLine3.setText(R.string.loading);
                stopSeekbarUpdate();
                break;
            default:
                LogHelper.d(TAG, "Unhandled state ", state.getState());
        }

        mSkipNext.setVisibility((state.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) == 0 ? INVISIBLE : VISIBLE);
        mSkipPrev.setVisibility((state.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0 ? INVISIBLE : VISIBLE);
    }

    private void updateProgress() {
        if (mLastPlaybackState == null) {
            return;
        }
        long currentPosition = mLastPlaybackState.getPosition();
        if (mLastPlaybackState.getState() != PlaybackState.STATE_PAUSED) {
            long timeDelta = SystemClock.elapsedRealtime() - mLastPlaybackState.getLastPositionUpdateTime();
            currentPosition += (int) timeDelta * mLastPlaybackState.getPlaybackSpeed();
        }
        mSeekbar.setProgress((int) currentPosition);
    }
}
