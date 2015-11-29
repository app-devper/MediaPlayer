package com.appdevper.mediaplayer.activity;

import java.util.ArrayList;
import java.util.Locale;

import org.fourthline.cling.support.model.PersonWithRole;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;
import org.seamless.util.MimeType;

import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBar.Tab;
import android.support.v7.app.ActionBar.TabListener;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.ServerSettings;
import com.appdevper.mediaplayer.app.UserData;
import com.appdevper.mediaplayer.fragment.AudioFragment;
import com.appdevper.mediaplayer.fragment.ImageFragment;
import com.appdevper.mediaplayer.fragment.VideoFragment;
import com.appdevper.mediaplayer.mediaserver.ContentTree;
import com.appdevper.mediaplayer.ui.BaseActivity;
import com.appdevper.mediaplayer.ui.PlaybackControlsFragment;
import com.appdevper.mediaplayer.util.LogHelper;
import com.appdevper.mediaplayer.util.ResourceHelper;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

public class SelectMediaActivity extends AppCompatActivity {


    private final static String TAG = SelectMediaActivity.class.getSimpleName();
    private int page = 0;
    private ViewPager mViewPager;
    private SectionsPagerAdapter mSectionsPagerAdapter;
    private ArrayList<Fragment> arrFragment;
    private ArrayList<String> arrTitle;
    private Toolbar mToolbar;
    private PlaybackControlsFragment mControlsFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeToolbar();

        mViewPager = (ViewPager) findViewById(R.id.pager);

        UserData.setArSelectId(ServerSettings.getSelectId());

        mControlsFragment = (PlaybackControlsFragment) getFragmentManager().findFragmentById(R.id.fragment_playback_controls);
        if (mControlsFragment == null) {
            throw new IllegalStateException("Mising fragment with id 'controls'. Cannot continue.");
        }

        hidePlaybackControls();
        initMedia();
    }

    protected void initializeToolbar() {
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

    protected void hidePlaybackControls() {
        getFragmentManager().beginTransaction().hide(mControlsFragment).commit();
    }

    private void initMedia() {

        arrFragment = new ArrayList<>();
        arrTitle = new ArrayList<>();

        if (ServerSettings.allowVideo()) {
            UserData.setArVideo(initVideo());
            page++;
            arrFragment.add(VideoFragment.getInstance());
            arrTitle.add("videos");
        }
        if (ServerSettings.allowAudio()) {
            UserData.setArAudio(initAudio());
            page++;
            arrFragment.add(AudioFragment.getInstance());
            arrTitle.add("audios");
        }
        if (ServerSettings.allowImage()) {
            UserData.setArImage(initImage());
            page++;
            arrFragment.add(ImageFragment.getInstance());
            arrTitle.add("images");
        }

        if (page > 0) {
            final ActionBar actionBar = getSupportActionBar();
            actionBar.setTitle(ServerSettings.getDeviceName());
            mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
            mViewPager.setAdapter(mSectionsPagerAdapter);

        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.select, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.icAdd:
                if (UserData.sMode) {
                    ServerSettings.setSelectID(UserData.getArSelectId());
                    Toast.makeText(getApplicationContext(), "Save media content selection.", Toast.LENGTH_SHORT).show();
                    item.setIcon(getResources().getDrawable(R.drawable.ic_content_add, getTheme()));
                    item.setTitle("ADD");
                } else {
                    item.setIcon(getResources().getDrawable(R.drawable.ic_content_save, getTheme()));
                    item.setTitle("SAVE");
                    Toast.makeText(getApplicationContext(), R.string.selectmode, Toast.LENGTH_SHORT).show();
                }

                UserData.sMode = !UserData.sMode;
                break;
        }
        return super.onOptionsItemSelected(item);
    }


    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {
            return arrFragment.get(position);
        }

        @Override
        public int getCount() {
            return page;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            Locale l = Locale.getDefault();
            return arrTitle.get(position).toUpperCase(l);
        }
    }

    private ArrayList<VideoItem> initVideo() {
        ArrayList<VideoItem> arItem = new ArrayList<>();
        String[] videoColumns = {MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DATA, MediaStore.Video.Media.ARTIST, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RESOLUTION};
        Cursor cursor = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoColumns, null, null, null);
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

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, filePath);
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
        ArrayList<MusicTrack> arItem = new ArrayList<>();
        String[] audioColumns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM};
        Cursor cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioColumns, MediaStore.Audio.Media.DATA + " like ? ", new String[]{"%mp3"}, null);
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

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, filePath);

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
        ArrayList<ImageItem> arItem = new ArrayList<>();
        String[] imageColumns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.TITLE, MediaStore.Images.Media.DATA, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE};
        Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageColumns, null, null, null);
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

                Res res = new Res(new MimeType(mimeType.substring(0, mimeType.indexOf('/')), mimeType.substring(mimeType.indexOf('/') + 1)), size, filePath);
                //@SuppressWarnings("rawtypes")
                //Property albumArtURI = new DIDLObject.Property.UPNP.ALBUM_ART_URI(URI.create(filePath));
                ImageItem imageItem = new ImageItem(id, ContentTree.IMAGE_ID, title, creator, res);
                //imageItem.addProperty(albumArtURI);
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