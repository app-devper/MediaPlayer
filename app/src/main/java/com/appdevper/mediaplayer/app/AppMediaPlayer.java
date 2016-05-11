package com.appdevper.mediaplayer.app;

import java.io.IOException;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.activity.MainActivity;
import com.appdevper.mediaplayer.loader.ImageLoader;
import com.appdevper.mediaplayer.util.ContentItem;
import com.google.android.libraries.cast.companionlibrary.cast.CastConfiguration;
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager;

public class AppMediaPlayer extends Application {

    private static final String TAG = AppMediaPlayer.class.getSimpleName();
    private static Context sContext;
    private static MediaPlayer mPlayer;
    private static int length;
    private static int current;
    private static ContentItem currentContent = null;
    private static AndroidUpnpService upnpService;
    public static Service<?, ?> service;
    public static Device<?, ?, Service<?, ?>> renderer;
    public static Service<?, ?> rService;
    private static Boolean rPlaying = false;
    public final static UnsignedIntegerFourBytes instanceid = new UnsignedIntegerFourBytes(0);
    private static ImageLoader imageLoader;

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = getApplicationContext();
        String applicationId = getResources().getString(R.string.cast_application_id);
        VideoCastManager.initialize(
                getApplicationContext(),
                new CastConfiguration.Builder(applicationId)
                        .enableWifiReconnection()
                        .enableAutoReconnect()
                        .enableDebug()
                        .setTargetActivity(MainActivity.class)
                        .build());
        startService(new Intent(getApplicationContext(), MusicService.class));
    }

    public static Context getAppContext() {
        if (sContext == null) {
            Log.e(TAG, "Global context not set");
        }
        return sContext;
    }

    public static AndroidUpnpService getUpnpService() {
        return upnpService;
    }

    public static ImageLoader getImageLoader() {
        if (imageLoader == null) {
            imageLoader = new ImageLoader(sContext);
        }
        return imageLoader;
    }

    public static void setUpnpService(AndroidUpnpService upnp) {
        upnpService = upnp;
    }

    public static Boolean isPlaying() {
        if (currentContent == null) {
            return false;
        }
        if (mPlayer != null) {
            return mPlayer.isPlaying();
        }
        return false;
    }

    public static void playMusic() {
        if (currentContent == null) {
            return;
        }
        if (mPlayer != null) {
            if (!mPlayer.isPlaying())
                mPlayer.start();
        }
    }

    public static void pauseMusic() {
        if (currentContent == null) {
            return;
        }
        if (mPlayer != null) {
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
                length = mPlayer.getCurrentPosition();
            }
        }
    }

    public static void seekTo(int seek) {
        if (currentContent == null) {
            return;
        }
        if (mPlayer != null) {
            if (mPlayer.isPlaying()) {
                mPlayer.seekTo(seek);
            }
        }
    }

    public static void stopMusic() {
        if (currentContent == null) {
            return;
        }
        if (mPlayer != null) {
            mPlayer.pause();
            mPlayer.seekTo(0);
        }
    }

    public static ContentItem nextMusic() {
        if (currentContent == null) {
            return null;
        }
        ContentItem cItem = null;
        if (mPlayer != null) {

            if (current < ShareData.aContent.size() - 1) {
                cItem = ShareData.aContent.get(current + 1);
                setMedia(cItem);

            } else {
                cItem = ShareData.aContent.get(0);
                setMedia(cItem);

            }
        }
        return cItem;
    }

    public static ContentItem prevMusic() {
        if (currentContent == null) {
            return null;
        }
        ContentItem cItem = null;
        if (mPlayer != null) {
            if (current > 0) {
                cItem = ShareData.aContent.get(current - 1);
                setMedia(cItem);

            } else {
                cItem = ShareData.aContent.get(ShareData.aContent.size() - 1);
                setMedia(cItem);

            }
        }
        return cItem;
    }

    public static int getCurrentPosition() {
        if (currentContent == null) {
            return 0;
        }
        if (mPlayer != null) {
            return mPlayer.getCurrentPosition();
        }
        return 0;
    }

    public static int getDuration() {
        if (currentContent == null) {
            return 0;
        }
        if (mPlayer != null) {
            return mPlayer.getDuration();
        }
        return 0;
    }

    public static ContentItem getContent() {
        return currentContent;
    }

    public static void setMedia(ContentItem content) {
        if (content == null) {
            return;
        }
        stopMusic();
        if (!ShareData.aContent.contains(content)) {
            ShareData.aContent.add(content);
        }
        currentContent = content;
        current = ShareData.aContent.indexOf(content);

        if (content.getType().equals("audio")) {
            if (mPlayer == null) {
                mPlayer = new MediaPlayer();
                mPlayer.setOnPreparedListener(new OnPreparedListener() {

                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        mp.start();
                    }
                });

                mPlayer.setOnErrorListener(new OnErrorListener() {

                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        Toast.makeText(sContext, "music player failed", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                });
            }
            String playURI = content.getResourceUri();
            mPlayer.stop();
            mPlayer.reset();
            try {
                mPlayer.setDataSource(playURI);
                mPlayer.prepare();
            } catch (IllegalArgumentException e) {
                Log.v(TAG, e.getMessage());
            } catch (IllegalStateException e) {
                Log.v(TAG, e.getMessage());
            } catch (IOException e) {
                Log.v(TAG, e.getMessage());
            }
        } else if (content.getType().equals("video")) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setDataAndType(Uri.parse(content.getItem().getFirstResource().getValue()), content.getMimeType());
            sContext.startActivity(intent);
        }

    }

    @SuppressWarnings("unchecked")
    public static void setService() {
        if (!ShareData.getrDevice().getIslocal()) {
            renderer = ShareData.getrDevice().getDevice();
            service = renderer.findService(new UDAServiceId("AVTransport"));
            rService = renderer.findService(new UDAServiceId("RenderingControl"));
        }
    }

    public static Boolean toPlaying() {
        return rPlaying;
    }

    public static void sendRender(ContentItem content) {
        if (content == null) {
            return;
        }
        toStop();
        if (!ShareData.aContent.contains(content)) {
            ShareData.aContent.add(content);
        }
        currentContent = content;
        current = ShareData.aContent.indexOf(content);

        try {
            upnpService.getControlPoint().execute(new SetAVTransportURI(instanceid, service, content.getResourceUri()) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

                @SuppressWarnings("rawtypes")
                @Override
                public void success(ActionInvocation invocation) {
                    toPlay();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void toPlay() {
        try {
            upnpService.getControlPoint().execute(new Play(instanceid, service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

            });
            rPlaying = true;
        } catch (Exception e) {
            e.printStackTrace();
            rPlaying = false;
        }

    }

    public static void toPause() {
        try {
            upnpService.getControlPoint().execute(new Pause(instanceid, service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

                }

            });
            rPlaying = false;
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static ContentItem toNextMusic() {
        if (currentContent == null) {
            return null;
        }
        ContentItem cItem;

        if (current < ShareData.aContent.size() - 1) {
            cItem = ShareData.aContent.get(current + 1);
            sendRender(cItem);
        } else {
            cItem = ShareData.aContent.get(0);
            sendRender(cItem);
        }

        return cItem;
    }

    public static ContentItem toPrevMusic() {
        if (currentContent == null) {
            return null;
        }
        ContentItem cItem = null;

        if (current > 0) {
            cItem = ShareData.aContent.get(current - 1);
            sendRender(cItem);
        } else {
            cItem = ShareData.aContent.get(ShareData.aContent.size() - 1);
            sendRender(cItem);
        }

        return cItem;
    }

    public static void toStop() {
        try {
            upnpService.getControlPoint().execute(new Stop(instanceid, service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

            });
            rPlaying = false;
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void toSeek(String para) {
        try {
            upnpService.getControlPoint().execute(new Seek(instanceid, service, para) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void getMuteAction() {

        upnpService.getControlPoint().execute(new GetMute(instanceid, rService) {
            private boolean svolume;

            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void received(ActionInvocation paramAnonymousActionInvocation, boolean paramAnonymousBoolean) {
                svolume = paramAnonymousBoolean;
            }
        });
    }

    public static void getVolumeAction() {
        upnpService.getControlPoint().execute(new GetVolume(instanceid, rService) {
            private int volume;

            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void received(ActionInvocation paramAnonymousActionInvocation, int paramAnonymousInt) {
                volume = paramAnonymousInt;
            }
        });
    }

    public static void setMuteAction(final boolean paramBoolean) {

        upnpService.getControlPoint().execute(new SetMute(instanceid, rService, paramBoolean) {
            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void success(ActionInvocation paramAnonymousActionInvocation) {

            }
        });
    }

    public static void setVolumeAction(int paramInt) {

        upnpService.getControlPoint().execute(new SetVolume(instanceid, rService, paramInt) {
            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void success(ActionInvocation paramAnonymousActionInvocation) {

            }
        });
    }

}
