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
package com.appdevper.mediaplayer.app;

import android.media.session.PlaybackState;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.text.TextUtils;
import android.util.Log;

import com.appdevper.mediaplayer.model.MusicProvider;
import com.appdevper.mediaplayer.model.MusicProviderSource;
import com.appdevper.mediaplayer.util.LogHelper;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.common.images.WebImage;
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager;
import com.google.android.libraries.cast.companionlibrary.cast.callbacks.VideoCastConsumerImpl;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.CastException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.NoConnectionException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.TransientNetworkDisconnectionException;

import org.fourthline.cling.model.ModelUtil;
import org.json.JSONException;
import org.json.JSONObject;

import static android.support.v4.media.session.MediaSessionCompat.QueueItem;

public class UpnpPlayback implements Playback {

    private static final String TAG = LogHelper.makeLogTag(UpnpPlayback.class);

    private final MusicProvider mMusicProvider;

    private UpnpPlayer upnpPlayer;
    /**
     * The current PlaybackState
     */
    private int mState;
    /**
     * Callback for making completion/error calls on
     */
    private Callback mCallback;
    private volatile int mCurrentPosition;
    private volatile String mCurrentMediaId;

    public UpnpPlayback(MusicProvider musicProvider) {
        this.mMusicProvider = musicProvider;
        this.upnpPlayer = new UpnpPlayer(AppMediaPlayer.getUpnpService());
        this.upnpPlayer.setService(AppMediaPlayer.service);
        this.upnpPlayer.setServiceAudio(AppMediaPlayer.rService);
        this.upnpPlayer.setPlayback(playback);
    }

    @Override
    public void start() {

    }

    @Override
    public void stop(boolean notifyListeners) {
        try {
            upnpPlayer.toStop();
            mCurrentPosition = (int) upnpPlayer.getCurrentMediaPosition();
        } catch (Exception e) {
            e.printStackTrace();
            mCurrentPosition = 0;
        }
        mState = PlaybackState.STATE_STOPPED;
        if (notifyListeners && mCallback != null) {
            mCallback.onPlaybackStatusChanged(mState);
        }
    }

    @Override
    public void setState(int state) {
        this.mState = state;
    }

    @Override
    public int getCurrentStreamPosition() {
        try {
            return (int) upnpPlayer.getCurrentMediaPosition();
        } catch (Exception e) {
            LogHelper.e(TAG, e, "Exception getting media position");
        }
        return -1;
    }

    @Override
    public void setCurrentStreamPosition(int pos) {
        this.mCurrentPosition = pos;
    }

    @Override
    public void play(QueueItem item) {
        try {
            loadMedia(item.getDescription().getMediaId(), true);
            mState = PlaybackState.STATE_BUFFERING;
            if (mCallback != null) {
                mCallback.onPlaybackStatusChanged(mState);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "Exception loading media ", e, null);
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void pause() {
        try {
            if (upnpPlayer.isPlaying()) {
                upnpPlayer.toPause();
                mCurrentPosition = (int) upnpPlayer.getCurrentMediaPosition();
            }
            mState = PlaybackState.STATE_PAUSED;
            if (mCallback != null) {
                mCallback.onPlaybackStatusChanged(mState);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, e, "Exception pausing cast playback");
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }
    }

    @Override
    public void seekTo(int position) {
        if (mCurrentMediaId == null) {
            if (mCallback != null) {
                mCallback.onError("seekTo cannot be calling in the absence of mediaId.");
            }
            return;
        }
        try {
            if (upnpPlayer.isPlaying()) {
                mState = PlaybackState.STATE_BUFFERING;
            }
            upnpPlayer.toSeek(ModelUtil.toTimeString(position));
            mCurrentPosition = position;
            if (mCallback != null) {
                mCallback.onPlaybackStatusChanged(mState);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, e, "Exception pausing cast playback");
            if (mCallback != null) {
                mCallback.onError(e.getMessage());
            }
        }

    }

    @Override
    public void setCurrentMediaId(String mediaId) {
        this.mCurrentMediaId = mediaId;
    }

    @Override
    public String getCurrentMediaId() {
        return mCurrentMediaId;
    }

    @Override
    public void updateLastKnownStreamPosition() {
        mCurrentPosition = getCurrentStreamPosition();
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isPlaying() {
        return upnpPlayer.isPlaying();

    }

    @Override
    public int getState() {
        return mState;
    }

    private void loadMedia(String mediaId, boolean autoPlay) throws Exception {
       MediaMetadataCompat track = mMusicProvider.getMusic(mediaId);
        if (track == null) {
            throw new IllegalArgumentException("Invalid mediaId " + mediaId);
        }
        if (!TextUtils.equals(mediaId, mCurrentMediaId)) {
            mCurrentMediaId = mediaId;
            mCurrentPosition = 0;
            upnpPlayer.setURI(track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE), autoPlay);
        }
        else {
            upnpPlayer.toPlay();
        }

        Log.i(TAG, track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE));
    }

    UpnpPlayer.Playback playback = new UpnpPlayer.Playback() {
        @Override
        public void onPlay() {
            mState = PlaybackState.STATE_PLAYING;
            if (mCallback != null) {
                Log.d(TAG, "onPlay");
                mCallback.onPlaybackStatusChanged(mState);
            }
        }
    };

}
