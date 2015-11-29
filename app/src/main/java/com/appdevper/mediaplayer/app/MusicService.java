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

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.media.MediaRouter;
import android.text.TextUtils;
import android.util.Log;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.activity.MainActivity;
import com.appdevper.mediaplayer.fragment.RenderFragment;
import com.appdevper.mediaplayer.fragment.ServerFragment;
import com.appdevper.mediaplayer.model.MusicProvider;
import com.appdevper.mediaplayer.util.AlbumArtCache;
import com.appdevper.mediaplayer.util.DeviceItem;
import com.appdevper.mediaplayer.util.LogHelper;
import com.appdevper.mediaplayer.util.MediaIDHelper;
import com.appdevper.mediaplayer.util.QueueHelper;
import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager;
import com.google.android.libraries.cast.companionlibrary.cast.callbacks.VideoCastConsumerImpl;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;


public class MusicService extends Service implements Playback.Callback {

    // Extra on MediaSession that contains the Cast device name currently connected to
    public static final String EXTRA_CONNECTED_CAST = "com.appdevper.mediaplayer.CAST_NAME";
    // The action of the incoming Intent indicating that it contains a command
    // to be executed (see {@link #onStartCommand})
    public static final String ACTION_CMD = "com.appdevper.mediaplayer.ACTION_CMD";
    // The key in the extras of the incoming Intent indicating the command that
    // should be executed (see {@link #onStartCommand})
    public static final String CMD_NAME = "CMD_NAME";
    // A value of a CMD_NAME key in the extras of the incoming Intent that
    // indicates that the music playback should be paused (see {@link #onStartCommand})
    public static final String CMD_PAUSE = "CMD_PAUSE";
    // A value of a CMD_NAME key that indicates that the music playback should switch
    // to local playback from cast playback.
    public static final String CMD_STOP_CASTING = "CMD_STOP_CASTING";

    private static final String TAG = LogHelper.makeLogTag(MusicService.class);
    // Action to thumbs up a media item
    private static final String CUSTOM_ACTION_THUMBS_UP = "com.appdevper.mediaplayer.THUMBS_UP";
    // Delay stopSelf by using a handler.
    private static final int STOP_DELAY = 30000;

    // Music catalog manager
    private MusicProvider mMusicProvider;
    private MediaSession mSession;
    // "Now playing" queue:
    private List<MediaSession.QueueItem> mPlayingQueue;
    private int mCurrentIndexOnQueue;
    private MediaNotificationManager mMediaNotificationManager;
    // Indicates whether the service was started.
    private boolean mServiceStarted;
    private Bundle mSessionExtras;
    private final DelayedStopHandler mDelayedStopHandler = new DelayedStopHandler(this);
    private Playback mPlayback;
    private MediaRouter mMediaRouter;
    private UpnpCallBack upnpCallBack;
    private final IBinder mBinder = new LocalBinder();

    private final VideoCastConsumerImpl mCastConsumer = new VideoCastConsumerImpl() {

        @Override
        public void onApplicationConnected(ApplicationMetadata appMetadata, String sessionId, boolean wasLaunched) {
            // In case we are casting, send the device name as an extra on MediaSession metadata.
            mSessionExtras.putString(EXTRA_CONNECTED_CAST, mCastManager.getDeviceName());
            mSession.setExtras(mSessionExtras);
            // Now we can switch to CastPlayback
            Playback playback = new CastPlayback(mMusicProvider);
            mMediaRouter.setMediaSession(mSession);
            switchToPlayer(playback, true);
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected");
            mSessionExtras.remove(EXTRA_CONNECTED_CAST);
            mSession.setExtras(mSessionExtras);
            Playback playback = new LocalPlayback(MusicService.this, mMusicProvider);
            mMediaRouter.setMediaSession(null);
            switchToPlayer(playback, false);
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            AppMediaPlayer.setUpnpService((AndroidUpnpService) service);
            Log.d(TAG, "AndroidUpnpService Connect in MusicService");
            if (upnpCallBack != null) {
                upnpCallBack.onConnect();
            }
            if (ServerSettings.autoRun()) {
                ServerUpnpService.getInstance().startServer();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            AppMediaPlayer.setUpnpService(null);
            Log.d(TAG, "AndroidUpnpService DisConnect in MusicService");
            if (upnpCallBack != null) {
                upnpCallBack.onDisConnect();
            }
            ServerUpnpService.getInstance().stopServer();
        }
    };

    private VideoCastManager mCastManager;

    public void connectUpnp(String deviceName) {
        Log.d(TAG, "connectUpnp");
        mSessionExtras.putString(EXTRA_CONNECTED_CAST, deviceName);
        mSession.setExtras(mSessionExtras);
        Playback playback = new UpnpPlayback(mMusicProvider);
        mMediaRouter.setMediaSession(mSession);
        switchToPlayer(playback, true);
    }

    public void disconnectUpnp() {
        Log.d(TAG, "disconnectUpnp");
        mSessionExtras.remove(EXTRA_CONNECTED_CAST);
        mSession.setExtras(mSessionExtras);
        Playback playback = new LocalPlayback(MusicService.this, mMusicProvider);
        mMediaRouter.setMediaSession(null);
        switchToPlayer(playback, false);
    }

    /*
     * (non-Javadoc)
     * @see android.app.Service#onCreate()
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mPlayingQueue = new ArrayList<>();
        mMusicProvider = MusicProvider.getInstance();

        // Start a new MediaSession
        mSession = new MediaSession(this, "MusicService");
        mSession.setCallback(new MediaSessionCallback());
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mPlayback = new LocalPlayback(this, mMusicProvider);
        mPlayback.setState(PlaybackState.STATE_NONE);
        mPlayback.setCallback(this);
        mPlayback.start();

        Context context = getApplicationContext();
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 99, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mSession.setSessionActivity(pi);

        mSessionExtras = new Bundle();

        mSession.setExtras(mSessionExtras);

        updatePlaybackState(null);

        mMediaNotificationManager = new MediaNotificationManager(this);
        mCastManager = VideoCastManager.getInstance();
        mCastManager.addVideoCastConsumer(mCastConsumer);
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());

        bindService(new Intent(this, FindUpnpService.class), serviceConnection, Context.BIND_AUTO_CREATE);

    }

    /**
     * (non-Javadoc)
     *
     * @see android.app.Service#onStartCommand(Intent, int, int)
     */
    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        if (startIntent != null) {
            String action = startIntent.getAction();
            String command = startIntent.getStringExtra(CMD_NAME);
            if (ACTION_CMD.equals(action)) {
                if (CMD_PAUSE.equals(command)) {
                    if (mPlayback != null && mPlayback.isPlaying()) {
                        handlePauseRequest();
                    }
                } else if (CMD_STOP_CASTING.equals(command)) {
                    if (mCastManager.isConnected()) {
                        mCastManager.disconnect();
                    } else {
                        disconnectUpnp();
                    }
                }
            }
        }
        // Reset the delay handler to enqueue a message to stop the service if
        // nothing is playing.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
        return START_STICKY;
    }

    /**
     * (non-Javadoc)
     *
     * @see android.app.Service#onDestroy()
     */
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        // Service is being killed, so make sure we release our resources
        handleStopRequest(null);

        mCastManager = VideoCastManager.getInstance();
        mCastManager.removeVideoCastConsumer(mCastConsumer);

        mDelayedStopHandler.removeCallbacksAndMessages(null);

        mSession.release();

        try {
            unbindService(serviceConnection);
            Log.v(TAG, "unbindService(serviceConnection)");
        } catch (Exception ex) {
            Log.v(TAG, "Can't unbindService(serviceConnection)");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public MediaSession.Token getSessionToken() {
        return mSession.getSessionToken();
    }

    public void setUpnpCallBack(UpnpCallBack upnpCallBack) {
        this.upnpCallBack = upnpCallBack;
    }

    private final class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            Log.d(TAG, "play");

            if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
                mPlayingQueue = QueueHelper.getRandomQueue(mMusicProvider);
                mSession.setQueue(mPlayingQueue);
                mSession.setQueueTitle(getString(R.string.random_queue_title));
                // start playing from the beginning of the queue
                mCurrentIndexOnQueue = 0;
            }

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                handlePlayRequest();
            }
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            Log.d(TAG, "OnSkipToQueueItem:" + queueId);

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                // set the current index on queue from the music Id:
                mCurrentIndexOnQueue = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId);
                // play the music
                handlePlayRequest();
            }
        }

        @Override
        public void onSeekTo(long position) {
            Log.d(TAG, "onSeekTo:" + position);
            mPlayback.seekTo((int) position);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(TAG, "playFromMediaId mediaId:" + mediaId + "  extras=" + extras);
            mPlayingQueue = QueueHelper.getPlayingQueue(mediaId, mMusicProvider);
            mSession.setQueue(mPlayingQueue);
            String queueTitle = getString(R.string.browse_musics_by_genre_subtitle, MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            mSession.setQueueTitle(queueTitle);

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                // set the current index on queue from the media Id:
                mCurrentIndexOnQueue = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, mediaId);

                if (mCurrentIndexOnQueue < 0) {
                    Log.e(TAG, "playFromMediaId: media ID " + mediaId +
                            " could not be found on queue. Ignoring.");
                } else {
                    // play the music
                    handlePlayRequest();
                }
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "pause. current state=" + mPlayback.getState());
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            Log.d(TAG, "stop. current state=" + mPlayback.getState());
            handleStopRequest(null);
        }

        @Override
        public void onSkipToNext() {
            Log.d(TAG, "skipToNext");
            mCurrentIndexOnQueue++;
            if (mPlayingQueue != null && mCurrentIndexOnQueue >= mPlayingQueue.size()) {
                // This sample's behavior: skipping to next when in last song returns to the
                // first song.
                mCurrentIndexOnQueue = 0;
            }
            if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
                handlePlayRequest();
            } else {
                Log.e(TAG, "skipToNext: cannot skip to next. next Index=" +
                        mCurrentIndexOnQueue + " queue length=" +
                        (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "skipToPrevious");
            mCurrentIndexOnQueue--;
            if (mPlayingQueue != null && mCurrentIndexOnQueue < 0) {
                // This sample's behavior: skipping to previous when in first song restarts the
                // first song.
                mCurrentIndexOnQueue = 0;
            }
            if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
                handlePlayRequest();
            } else {
                Log.e(TAG, "skipToPrevious: cannot skip to previous. previous Index=" +
                        mCurrentIndexOnQueue + " queue length=" +
                        (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onCustomAction(@NonNull String action, Bundle extras) {
            if (CUSTOM_ACTION_THUMBS_UP.equals(action)) {
                Log.i(TAG, "onCustomAction: favorite for current track");
                MediaMetadata track = getCurrentPlayingMusic();
                if (track != null) {
                    String musicId = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                    mMusicProvider.setFavorite(musicId, !mMusicProvider.isFavorite(musicId));
                }
                // playback state needs to be updated because the "Favorite" icon on the
                // custom action will change to reflect the new favorite state.
                updatePlaybackState(null);
            } else {
                Log.e(TAG, "Unsupported action: " + action);
            }
        }

        @Override
        public void onPlayFromSearch(final String query, final Bundle extras) {
            Log.d(TAG, "playFromSearch  query=" + query + " extras=" + extras);
        }
    }

    /**
     * Handle a request to play music
     */
    private void handlePlayRequest() {
        Log.d(TAG, "handlePlayRequest: mState=" + mPlayback.getState());

        mDelayedStopHandler.removeCallbacksAndMessages(null);
        if (!mServiceStarted) {
            Log.v(TAG, "Starting service");
            startService(new Intent(getApplicationContext(), MusicService.class));
            mServiceStarted = true;
        }

        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            updateMetadata();
            mPlayback.play(mPlayingQueue.get(mCurrentIndexOnQueue));
        }
    }

    /**
     * Handle a request to pause music
     */
    private void handlePauseRequest() {
        Log.d(TAG, "handlePauseRequest: mState=" + mPlayback.getState());
        mPlayback.pause();
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
    }

    /**
     * Handle a request to stop music
     */
    private void handleStopRequest(String withError) {
        Log.d(TAG, "handleStopRequest: mState=" + mPlayback.getState() + " error=" + withError);
        mPlayback.stop(true);
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);

        updatePlaybackState(withError);

        // service is no longer necessary. Will be started again if needed.
        stopSelf();
        mServiceStarted = false;
    }

    private void updateMetadata() {
        if (!QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            Log.e(TAG, "Can't retrieve current metadata.");
            updatePlaybackState(getResources().getString(R.string.error_no_metadata));
            return;
        }
        MediaSession.QueueItem queueItem = mPlayingQueue.get(mCurrentIndexOnQueue);

        MediaMetadata track = mMusicProvider.getMusic(queueItem.getDescription().getMediaId());
        if (track == null) {
            throw new IllegalArgumentException("Invalid musicId ");
        }
        final String trackId = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);

//         Log.d(TAG, "Updating metadata for MusicID= " + musicId);
        mSession.setMetadata(track);

        // Set the proper album artwork on the media session, so it can be shown in the
        // locked screen and in other places.
        if (track.getDescription().getIconBitmap() == null && track.getDescription().getIconUri() != null) {
            String albumUri = track.getDescription().getIconUri().toString();
            AlbumArtCache.getInstance().fetch(albumUri, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    MediaSession.QueueItem queueItem = mPlayingQueue.get(mCurrentIndexOnQueue);
                    MediaMetadata track = mMusicProvider.getMusic(trackId);
                    track = new MediaMetadata.Builder(track)
                            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon)
                            .build();

                    mMusicProvider.updateMusic(trackId, track);

                    // If we are still playing the same music
                    String currentPlayingId = queueItem.getDescription().getMediaId();
                    if (trackId.equals(currentPlayingId)) {
                        mSession.setMetadata(track);
                    }
                }
            });
        }
    }

    /**
     * Update the current media player state, optionally showing an error message.
     *
     * @param error if not null, error message to present to the user.
     */
    private void updatePlaybackState(String error) {
        Log.d(TAG, "updatePlaybackState, playback state=" + mPlayback.getState());
        long position = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        if (mPlayback != null && mPlayback.isConnected()) {
            position = mPlayback.getCurrentStreamPosition();
        }

        PlaybackState.Builder stateBuilder = new PlaybackState.Builder().setActions(getAvailableActions());

        setCustomAction(stateBuilder);
        int state = mPlayback.getState();

        // If there is an error message, send it to the playback state:
        if (error != null) {
            // Error states are really only supposed to be used for errors that cause playback to
            // stop unexpectedly and persist until the user takes action to fix it.
            stateBuilder.setErrorMessage(error);
            state = PlaybackState.STATE_ERROR;
        }
        stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());

        // Set the activeQueueItemId if the current index is valid.
        if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            MediaSession.QueueItem item = mPlayingQueue.get(mCurrentIndexOnQueue);
            stateBuilder.setActiveQueueItemId(item.getQueueId());
        }

        mSession.setPlaybackState(stateBuilder.build());

        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED) {
            mMediaNotificationManager.startNotification();
        }
    }

    private void setCustomAction(PlaybackState.Builder stateBuilder) {
        MediaMetadata currentMusic = getCurrentPlayingMusic();
        if (currentMusic != null) {
            // Set appropriate "Favorite" icon on Custom action:
            String musicId = currentMusic.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            int favoriteIcon = R.drawable.ic_star_off;
            if (mMusicProvider.isFavorite(musicId)) {
                favoriteIcon = R.drawable.ic_star_on;
            }
            Log.d(TAG, "updatePlaybackState, setting Favorite custom action of music " +
                    musicId + " current favorite=" + mMusicProvider.isFavorite(musicId));
            Bundle customActionExtras = new Bundle();
            stateBuilder.addCustomAction(new PlaybackState.CustomAction.Builder(
                    CUSTOM_ACTION_THUMBS_UP, getString(R.string.favorite), favoriteIcon)
                    .setExtras(customActionExtras)
                    .build());
        }
    }

    private long getAvailableActions() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID |
                PlaybackState.ACTION_PLAY_FROM_SEARCH;
        if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
            return actions;
        }
        if (mPlayback.isPlaying()) {
            actions |= PlaybackState.ACTION_PAUSE;
        }
        if (mCurrentIndexOnQueue > 0) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (mCurrentIndexOnQueue < mPlayingQueue.size() - 1) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        return actions;
    }

    private MediaMetadata getCurrentPlayingMusic() {
        if (QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
            MediaSession.QueueItem item = mPlayingQueue.get(mCurrentIndexOnQueue);
            if (item != null) {
                Log.d(TAG, "getCurrentPlayingMusic for musicId=" +
                        item.getDescription().getMediaId());
                return mMusicProvider.getMusic(item.getDescription().getMediaId());
            }
        }
        return null;
    }

    /**
     * Implementation of the Playback.Callback interface
     */
    @Override
    public void onCompletion() {
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
            // In this sample, we restart the playing queue when it gets to the end:
            mCurrentIndexOnQueue++;
            if (mCurrentIndexOnQueue >= mPlayingQueue.size()) {
                mCurrentIndexOnQueue = 0;
            }
            handlePlayRequest();
        } else {
            // If there is nothing to play, we stop and release the resources:
            handleStopRequest(null);
        }
    }

    @Override
    public void onPlaybackStatusChanged(int state) {
        updatePlaybackState(null);
    }

    @Override
    public void onError(String error) {
        updatePlaybackState(error);
    }

    @Override
    public void onMetadataChanged(String mediaId) {
        Log.d(TAG, "onMetadataChanged" + mediaId);
        List<MediaSession.QueueItem> queue = QueueHelper.getPlayingQueue(mediaId, mMusicProvider);
        int index = QueueHelper.getMusicIndexOnQueue(queue, mediaId);
        if (index > -1) {
            mCurrentIndexOnQueue = index;
            mPlayingQueue = queue;
            updateMetadata();
        }
    }

    /**
     * Helper to switch to a different Playback instance
     *
     * @param playback switch to this playback
     */
    private void switchToPlayer(Playback playback, boolean resumePlaying) {
        if (playback == null) {
            throw new IllegalArgumentException("Playback cannot be null");
        }
        // suspend the current one.
        int oldState = mPlayback.getState();
        int pos = mPlayback.getCurrentStreamPosition();
        String currentMediaId = mPlayback.getCurrentMediaId();
        Log.d(TAG, "Current position from " + playback + " is " + pos);
        mPlayback.stop(false);
        playback.setCallback(this);
        playback.setCurrentStreamPosition(pos < 0 ? 0 : pos);
        playback.setCurrentMediaId(currentMediaId);
        playback.start();
        // finally swap the instance
        mPlayback = playback;
        switch (oldState) {
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_PAUSED:
                mPlayback.pause();
                break;
            case PlaybackState.STATE_PLAYING:
                if (resumePlaying && QueueHelper.isIndexPlayable(mCurrentIndexOnQueue, mPlayingQueue)) {
                    mPlayback.play(mPlayingQueue.get(mCurrentIndexOnQueue));
                } else if (!resumePlaying) {
                    mPlayback.pause();
                } else {
                    mPlayback.stop(true);
                }
                break;
            case PlaybackState.STATE_NONE:
                break;
            default:
                Log.d(TAG, "Default called. Old state is " + oldState);
        }
    }

    public class LocalBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    /**
     * A simple handler that stops the service if playback is not active (playing)
     */
    private static class DelayedStopHandler extends Handler {
        private final WeakReference<MusicService> mWeakReference;

        private DelayedStopHandler(MusicService service) {
            mWeakReference = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            MusicService service = mWeakReference.get();
            if (service != null && service.mPlayback != null) {
                if (service.mPlayback.isPlaying()) {
                    Log.d(TAG, "Ignoring delayed stop since the media player is in use.");
                    return;
                }
                Log.d(TAG, "Stopping service with delay handler.");
                service.stopSelf();
                service.mServiceStarted = false;
            }
        }

    }

    public interface UpnpCallBack {
        void onConnect();

        void onDisConnect();
    }
}