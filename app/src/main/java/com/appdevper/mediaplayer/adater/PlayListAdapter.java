package com.appdevper.mediaplayer.adater;

import android.app.Activity;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import com.appdevper.mediaplayer.R;

import java.util.ArrayList;

/**
 * Created by worawit on 11/10/15.
 */
public class PlayListAdapter extends ArrayAdapter<MediaMetadata> {

    public PlayListAdapter(Activity context, ArrayList<MediaMetadata> metadatas) {
        super(context, R.layout.media_list_item, metadatas);
    }

    public ArrayList<MediaMetadata> getAll() {
        ArrayList<MediaMetadata> localArrayList = new ArrayList<>();
        int i = getCount();
        for (int j = 0; ; j++) {
            if (j >= i)
                return localArrayList;
            localArrayList.add(getItem(j));
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        MediaMetadata item = getItem(position);

        int itemState = MediaItemViewHolder.STATE_PLAYABLE;
        MediaController controller = ((Activity) getContext()).getMediaController();
        if (controller != null && controller.getMetadata() != null) {
            String currentPlaying = controller.getMetadata().getDescription().getMediaId();
            String musicId = item.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);

            if (currentPlaying != null && currentPlaying.equals(musicId)) {
                PlaybackState pbState = controller.getPlaybackState();
                if (pbState == null || pbState.getState() == PlaybackState.STATE_ERROR) {
                    itemState = MediaItemViewHolder.STATE_NONE;
                } else if (pbState.getState() == PlaybackState.STATE_PLAYING) {
                    itemState = MediaItemViewHolder.STATE_PLAYING;
                } else {
                    itemState = MediaItemViewHolder.STATE_PAUSED;
                }
            }
        }

        return MediaItemViewHolder.setupView((Activity) getContext(), convertView, parent, item, itemState);
    }
}