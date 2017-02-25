package com.appdevper.mediaplayer.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;

import com.appdevper.mediaplayer.mediaserver.ContentNode;
import com.appdevper.mediaplayer.mediaserver.ContentTree;
import com.appdevper.mediaplayer.mediaserver.MediaServer;
import com.appdevper.mediaplayer.util.Utils;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.DIDLObject.Property;
import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.WriteStatus;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;

public class ServerUpnpService extends AndroidUpnpServiceImpl {
    private static final String TAG = ServerUpnpService.class.getSimpleName();
    private static MediaServer mediaServer;
    static public final String ACTION_STARTED = "APPSERVER_STARTED";
    static public final String ACTION_STOPPED = "APPSERVER_STOPPED";
    static public final String ACTION_FAILEDTOSTART = "APPSERVER_FAILEDTOSTART";
    static public final String ACTION_START_SERVER = "ACTION_START_APPSERVER";
    static public final String ACTION_STOP_SERVER = "ACTION_STOP_APPSERVER";

    static private boolean b = false;
    private Context context;


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        context = getApplicationContext();
        startServer();
        return START_NOT_STICKY;
    }

    private void startServer() {
        try {
            mediaServer = new MediaServer(getLocalInetAddress(), context, ServerSettings.getDeviceName());
            initMedia();
            prepareMediaServer();
        } catch (Exception e) {
            e.printStackTrace();
            if (mediaServer != null)
                mediaServer.stop();
            mediaServer = null;
        }

        if (mediaServer != null) {
            try {
                upnpService.getRegistry().addDevice(mediaServer.getDevice());
                context.sendBroadcast(new Intent(ServerUpnpService.ACTION_STARTED));
                b = true;
            } catch (Exception ex) {
                ex.printStackTrace();
                b = false;
                mediaServer.stop();
                context.sendBroadcast(new Intent(ServerUpnpService.ACTION_STOPPED));
            }
        } else {
            b = false;
            context.sendBroadcast(new Intent(ServerUpnpService.ACTION_STOPPED));
        }

    }

    @Override
    public void onDestroy() {
        stopServer();
        new Shutdown().execute(upnpService);
    }

    public static boolean isRunning() {
        return b;
    }

    private static InetAddress getLocalInetAddress() {
        if (!isConnectedToLocalNetwork()) {
            Log.e(TAG, "getLocalInetAddress called and no connection");
            return null;
        }
        // TODO: next if block could probably be removed
        if (isConnectedUsingWifi()) {
            Context context = AppMediaPlayer.getAppContext();
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ipAddress = wm.getConnectionInfo().getIpAddress();
            if (ipAddress == 0)
                return null;
            return Utils.intToInet(ipAddress);
        }

        try {
            Enumeration<NetworkInterface> netinterfaces = NetworkInterface.getNetworkInterfaces();
            while (netinterfaces.hasMoreElements()) {
                NetworkInterface netinterface = netinterfaces.nextElement();
                Enumeration<InetAddress> adresses = netinterface.getInetAddresses();
                while (adresses.hasMoreElements()) {
                    InetAddress address = adresses.nextElement();
                    // this is the condition that sometimes gives problems
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        return address;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void stopServer() {
        b = false;
        if (mediaServer != null) {
            upnpService.getRegistry().removeDevice(mediaServer.getDevice());
            mediaServer.stop();
        }
        context.sendBroadcast(new Intent(ServerUpnpService.ACTION_STOPPED));
    }

    private static boolean isConnectedToLocalNetwork() {
        boolean connected;
        Context context = AppMediaPlayer.getAppContext();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        connected = ni != null && ni.isConnected() && (ni.getType() & (ConnectivityManager.TYPE_WIFI | ConnectivityManager.TYPE_ETHERNET)) != 0;
        if (!connected) {
            Log.d(TAG, "Device not connected to a network, see if it is an AP");
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            try {
                Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
                connected = (Boolean) method.invoke(wm);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return connected;
    }

    private static boolean isConnectedUsingWifi() {
        Context context = AppMediaPlayer.getAppContext();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }

    class Shutdown extends AsyncTask<UpnpService, Void, Void> {
        @Override
        protected Void doInBackground(UpnpService... svcs) {
            UpnpService svc = svcs[0];
            if (null != svc) {
                try {
                    svc.shutdown();
                } catch (java.lang.IllegalArgumentException ex) {

                    ex.printStackTrace();
                }
            }
            return null;
        }
    }

    private void initMedia() {
        UserData.getArVideo().clear();
        UserData.getArAudio().clear();
        UserData.getArImage().clear();

        if (ServerSettings.allowVideo()) {
            UserData.setArVideo(initVideo());
        }
        if (ServerSettings.allowAudio()) {
            UserData.setArAudio(initAudio());
        }
        if (ServerSettings.allowImage()) {
            UserData.setArImage(initImage());
        }
    }

    private void prepareMediaServer() {

        ContentNode rootNode = ContentTree.getRootNode();

        // Video Container
        Container videoContainer = new Container();
        videoContainer.setClazz(new DIDLObject.Class("object.container"));
        videoContainer.setId(ContentTree.VIDEO_ID);
        videoContainer.setParentID(ContentTree.ROOT_ID);
        videoContainer.setTitle("Videos");
        videoContainer.setChildCount(0);
        videoContainer.setCreator("MediaServer");
        videoContainer.setRestricted(true);
        videoContainer.setWriteStatus(WriteStatus.NOT_WRITABLE);

        rootNode.getContainer().addContainer(videoContainer);
        rootNode.getContainer().setChildCount(rootNode.getContainer().getChildCount() + 1);

        ContentTree.addNode(ContentTree.VIDEO_ID, new ContentNode(ContentTree.VIDEO_ID, videoContainer));

        ArrayList<VideoItem> arVideo = UserData.getArVideo();
        for (VideoItem videoItem : arVideo) {
            if (!UserData.getArSelectId().contains(videoItem.getId())) {
                videoContainer.addItem(videoItem);
                videoContainer.setChildCount(videoContainer.getChildCount() + 1);
                ContentTree.addNode(videoItem.getId(), new ContentNode(videoItem.getId(), videoItem, videoItem.getDescription()));

                Log.v(TAG, "added video item " + videoItem.getTitle() + "from " + videoItem.getDescription());
            }
        }

        // Audio Container
        Container audioContainer = new Container(ContentTree.AUDIO_ID, ContentTree.ROOT_ID, "Audios", "MediaServer", new DIDLObject.Class("object.container"), 0);
        audioContainer.setRestricted(true);
        audioContainer.setWriteStatus(WriteStatus.NOT_WRITABLE);

        rootNode.getContainer().addContainer(audioContainer);
        rootNode.getContainer().setChildCount(rootNode.getContainer().getChildCount() + 1);

        ContentTree.addNode(ContentTree.AUDIO_ID, new ContentNode(ContentTree.AUDIO_ID, audioContainer));

        ArrayList<MusicTrack> arAudio = UserData.getArAudio();
        for (MusicTrack musicTrack : arAudio) {
            if (!UserData.getArSelectId().contains(musicTrack.getId())) {
                audioContainer.addItem(musicTrack);
                audioContainer.setChildCount(audioContainer.getChildCount() + 1);
                ContentTree.addNode(musicTrack.getId(), new ContentNode(musicTrack.getId(), musicTrack, musicTrack.getDescription()));

                Log.v(TAG, "added audio item " + musicTrack.getTitle() + "from " + musicTrack.getDescription());
            }
        }

        // Image Container
        Container imageContainer = new Container(ContentTree.IMAGE_ID, ContentTree.ROOT_ID, "Images", "MediaServer", new DIDLObject.Class("object.container"), 0);
        imageContainer.setRestricted(true);
        imageContainer.setWriteStatus(WriteStatus.NOT_WRITABLE);

        rootNode.getContainer().addContainer(imageContainer);
        rootNode.getContainer().setChildCount(rootNode.getContainer().getChildCount() + 1);

        ContentTree.addNode(ContentTree.IMAGE_ID, new ContentNode(ContentTree.IMAGE_ID, imageContainer));

        ArrayList<ImageItem> arImage = UserData.getArImage();
        for (ImageItem imageItem : arImage) {
            if (!UserData.getArSelectId().contains(imageItem.getId())) {
                imageContainer.addItem(imageItem);
                imageContainer.setChildCount(imageContainer.getChildCount() + 1);
                ContentTree.addNode(imageItem.getId(), new ContentNode(imageItem.getId(), imageItem, imageItem.getDescription()));
                Log.v(TAG, "added image item " + imageItem.getTitle() + "from " + imageItem.getDescription());
            }
        }

    }

    private ArrayList<VideoItem> initVideo() {
        ArrayList<VideoItem> arItem = new ArrayList<VideoItem>();
        String[] videoColumns = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DATA, MediaStore.Video.Media.ARTIST, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RESOLUTION};
        Cursor cursor = context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoColumns, null, null, null);
        if (cursor.moveToFirst()) {
            do {
                String id = ContentTree.VIDEO_PREFIX + cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media._ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE));
                String creator = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST));
                String filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE));
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
                String resolution = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION));

                id = id + filePath.substring(filePath.lastIndexOf("."));

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, "http://" + mediaServer.getAddress() + "/" + id);
                res.setDuration(duration / (1000 * 60 * 60) + ":" + cTime((duration % (1000 * 60 * 60)) / (1000 * 60)) + ":" + cTime((duration % (1000 * 60)) / 1000));
                res.setResolution(resolution);

                VideoItem videoItem = new VideoItem(id, ContentTree.VIDEO_ID, title, creator, res);
                videoItem.setDescription(filePath);
                arItem.add(videoItem);
                Log.v(TAG, "added video item " + title + "from " + filePath);
            } while (cursor.moveToNext());
        }
        return arItem;
    }

    private ArrayList<MusicTrack> initAudio() {
        ArrayList<MusicTrack> arItem = new ArrayList<MusicTrack>();
        String[] audioColumns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM};
        Cursor cursor = context.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioColumns, MediaStore.Audio.Media.DATA + " like ? ", new String[]{"%mp3"}, null);
        if (cursor.moveToFirst()) {
            do {
                String id = ContentTree.AUDIO_PREFIX + cursor.getInt(cursor.getColumnIndex(MediaStore.Audio.Media._ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                String creator = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                String filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE));
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                String album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));

                id = id + filePath.substring(filePath.lastIndexOf("."));

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, "http://" + mediaServer.getAddress() + "/" + id);

                res.setDuration(duration / (1000 * 60 * 60) + ":" + cTime((duration % (1000 * 60 * 60)) / (1000 * 60)) + ":" + cTime((duration % (1000 * 60)) / 1000));

                MusicTrack musicTrack = new MusicTrack(id, ContentTree.AUDIO_ID, title, creator, album, new PersonWithRole(creator, "Performer"), res);
                musicTrack.setDescription(filePath);
                arItem.add(musicTrack);

                Log.v(TAG, "added audio item " + title + "from " + filePath);
            } while (cursor.moveToNext());
        }
        return arItem;
    }

    private ArrayList<ImageItem> initImage() {
        ArrayList<ImageItem> arItem = new ArrayList<ImageItem>();
        String[] imageColumns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.TITLE, MediaStore.Images.Media.DATA, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE};
        Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageColumns, null, null, null);
        if (cursor.moveToFirst()) {
            do {
                String _id = "" + cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media._ID));
                String id = ContentTree.IMAGE_PREFIX + cursor.getInt(cursor.getColumnIndex(MediaStore.Images.Media._ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.TITLE));
                String creator = "unkown";
                String filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));
                String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE));
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE));

                id = id + filePath.substring(filePath.lastIndexOf("."));

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, "http://" + mediaServer.getAddress() + "/" + id);
                @SuppressWarnings("rawtypes")
                Property albumArtURI = new DIDLObject.Property.UPNP.ALBUM_ART_URI(URI.create("http://" + mediaServer.getAddress() + "/" + id));
                ImageItem imageItem = new ImageItem(id, ContentTree.IMAGE_ID, title, creator, res);
                imageItem.addProperty(albumArtURI);
                imageItem.setDescription(filePath);
                imageItem.setLongDescription(_id);
                arItem.add(imageItem);

                Log.v(TAG, "added image item " + title + "from " + filePath);
            } while (cursor.moveToNext());
        }
        return arItem;
    }

    private String cTime(long t) {
        String s = t + "";
        if (t < 10) {
            s = "0" + t;
        }
        return s;
    }

}