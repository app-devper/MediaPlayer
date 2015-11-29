package com.appdevper.mediaplayer.activity;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.ContentItemAdapter;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.model.MusicProvider;
import com.appdevper.mediaplayer.ui.BaseActivity;
import com.appdevper.mediaplayer.util.ContentActionCallback;
import com.appdevper.mediaplayer.util.ContentItem;
import com.google.android.gms.ads.AdRequest;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.support.model.container.Container;

import java.util.List;
import java.util.Stack;

public class ContentActivity extends BaseActivity {

    private ListView contentListView;
    private ContentItemAdapter contentAdapter;
    private AndroidUpnpService upnpService;
    private boolean aSelect = false;
    private final static String TAG = ContentActivity.class.getSimpleName();
    private Device<?, ?, ?> cDevice;
    private Service<?, ?> cServices;
    private String type;
    private ContentItem mainContent;
    private Stack<ContentItem> stackContent;
    private AdRequest adRequest;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content);

        initializeToolbar();

        contentListView = (ListView) findViewById(R.id.contentList);

        contentAdapter = new ContentItemAdapter(this);
        contentListView.setAdapter(contentAdapter);
        contentListView.setOnItemClickListener(contentItemClickListener);
        contentListView.setOnItemLongClickListener(itemLongClink);

        upnpService = AppMediaPlayer.getUpnpService();
        cDevice = ShareData.getDevice();

        stackContent = new Stack<>();

        try {
            cServices = cDevice.findService(new UDAServiceType("ContentDirectory"));
            mainContent = new ContentItem(createRootContainer(cServices), cServices);
            upnpService.getControlPoint().execute(new ContentActionCallback(this, mainContent.getService(), mainContent.getContainer(), contentAdapter));
        } catch (Exception e) {
            e.printStackTrace();
        }

        AppMediaPlayer.setService();

        adRequest = new AdRequest.Builder().addTestDevice("FC30F813719E71A110A143F708B6C212").addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();

        //adView.loadAd(adRequest);

    }

    private final MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (metadata == null) {
                return;
            }
            Log.d(TAG, "Received metadata change to media " + metadata.getDescription().getMediaId());
            contentAdapter.notifyDataSetChanged();
        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
            super.onPlaybackStateChanged(state);
            Log.d(TAG, "Received state change: " + state);

            contentAdapter.notifyDataSetChanged();
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    return onBack();
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean onBack() {
        if (!stackContent.isEmpty()) {
            mainContent = stackContent.pop();
            upnpService.getControlPoint().execute(new ContentActionCallback(this, mainContent.getService(), mainContent.getContainer(), contentAdapter));
            aSelect = false;
            return true;
        } else {
            setResult(200);
            finish();
            return true;
        }

    }

    protected Container createRootContainer(Service<?, ?> service) {
        Container rootContainer = new Container();
        rootContainer.setId("0");
        rootContainer.setTitle("Content Directory on " + service.getDevice().getDisplayString());
        return rootContainer;
    }

    private OnItemClickListener contentItemClickListener = new OnItemClickListener() {

        @Override
        public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
            sMedia(position);

        }
    };

    private OnItemLongClickListener itemLongClink = new OnItemLongClickListener() {

        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
            ContentItem content = contentAdapter.getItem(position);
            if (!content.isContainer()) {
                showDialog(content);
            }
            return true;
        }
    };

    private void sMedia(int position) {
        ContentItem content = contentAdapter.getItem(position);
        type = content.getType();
        Log.d("___CLINK____", content.isContainer().toString());
        if (content.isContainer()) {
            stackContent.push(mainContent);
            mainContent = content;
            upnpService.getControlPoint().execute(new ContentActionCallback(ContentActivity.this, content.getService(), content.getContainer(), contentAdapter));
            aSelect = false;
        } else {
            try {
                if (ShareData.getrDevice().getIslocal()) {
                    if (type.equals("video")) {
                        AppMediaPlayer.setMedia(content);
                    } else if (type.equals("audio")) {
                        ShareData.aContent = contentAdapter.getAll();
                        MusicProvider.getInstance().retrieveMedia(ShareData.aContent);
                        onMediaItemSelected(String.valueOf(content.getResourceUri().hashCode()));
                    } else if (type.equals("image")) {
                        ShareData.aContentImage = contentAdapter.getAll();
                        AppMediaPlayer.setMedia(content);
                        Intent intent = new Intent();
                        intent.putExtra("position", position);
                        intent.setClass(ContentActivity.this, ImageActivity.class);
                        startActivity(intent);
                    }

                } else {

                    if (type.equals("video")) {
                        AppMediaPlayer.stopMusic();
                        AppMediaPlayer.sendRender(content);
                    } else if (type.equals("audio")) {
                        ShareData.aContent = contentAdapter.getAll();
                        MusicProvider.getInstance().retrieveMedia(ShareData.aContent);
                        onMediaItemSelected(String.valueOf(content.getResourceUri().hashCode()));
                    } else if (type.equals("image")) {
                        AppMediaPlayer.sendRender(content);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

    }

    public void onMediaItemSelected(String mediaId) {
        getMediaController().getTransportControls().playFromMediaId(mediaId, null);
    }

    private void showDialog(final ContentItem content) {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);

        alertDialog.setTitle("Download file!");

        alertDialog.setMessage("You want to download this file?");

        alertDialog.setNegativeButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                download(content);
                dialog.cancel();
            }
        });

        alertDialog.setPositiveButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        alertDialog.show();
    }

    private void download(ContentItem content) {
        if (isDownloadManagerAvailable(ContentActivity.this)) {
            String url = content.getResourceUri();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setDescription("Dowload file with Media player");
            request.setTitle("Dowload " + content.toString());
            // in order for this if to run, you must use the android 3.2 to
            // compile your app
            String[] ss = url.split("\\.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            }
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, content.toString() + "." + ss[ss.length - 1]);

            // get download service and enqueue file
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
        } else {
            Toast.makeText(ContentActivity.this, "Can not download file.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isDownloadManagerAvailable(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setClassName("com.android.providers.downloads.ui", "com.android.providers.downloads.ui.DownloadList");
            List<ResolveInfo> list = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return list.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onMediaControllerConnected() {
        if (getMediaController() != null) {
            getMediaController().registerCallback(mMediaControllerCallback);
        }
    }
}