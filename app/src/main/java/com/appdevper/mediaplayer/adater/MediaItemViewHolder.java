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
package com.appdevper.mediaplayer.adater;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.loader.ImageLoader;
import com.appdevper.mediaplayer.util.ContentItem;
import com.appdevper.mediaplayer.util.Utils;

public class MediaItemViewHolder {

    static final int STATE_INVALID = -1;
    static final int STATE_NONE = 0;
    static final int STATE_PLAYABLE = 1;
    static final int STATE_PAUSED = 2;
    static final int STATE_PLAYING = 3;
    static final int STATE_FOLDER = 4;
    static final int STATE_IMAGE = 5;

    private static ColorStateList sColorStatePlaying;
    private static ColorStateList sColorStateNotPlaying;


    ImageView mImageView;
    TextView mTitleView;
    TextView mDescriptionView;
    ImageView mImageBack;
    RelativeLayout layGrid;

    static View setupView(Activity activity, View convertView, ViewGroup parent, ContentItem item, int state) {

        if (sColorStateNotPlaying == null || sColorStatePlaying == null) {
            initializeColorStateLists(activity);
        }

        MediaItemViewHolder holder;

        Integer cachedState = STATE_INVALID;

        if (convertView == null) {
            convertView = LayoutInflater.from(activity).inflate(R.layout.media_list_item, parent, false);
            holder = new MediaItemViewHolder();
            holder.mImageView = (ImageView) convertView.findViewById(R.id.play_eq);
            holder.mTitleView = (TextView) convertView.findViewById(R.id.title);
            holder.mDescriptionView = (TextView) convertView.findViewById(R.id.description);
            convertView.setTag(holder);
        } else {
            holder = (MediaItemViewHolder) convertView.getTag();
            cachedState = (Integer) convertView.getTag(R.id.tag_mediaitem_state_cache);
        }

        holder.mTitleView.setText(item.toString());
        holder.mDescriptionView.setText(item.getSubtitle());

        // If the state of convertView is different, we need to adapt the view to the
        // new state.
        if (cachedState == null || cachedState != state) {
            switch (state) {
                case STATE_PLAYABLE:
                    Drawable pauseDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_play_arrow_black_36dp);
                    DrawableCompat.setTintList(pauseDrawable, sColorStateNotPlaying);
                    holder.mImageView.setImageDrawable(pauseDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                case STATE_PLAYING:
                    AnimationDrawable animation = (AnimationDrawable) ContextCompat.getDrawable(activity, R.drawable.ic_equalizer_white_36dp);
                    DrawableCompat.setTintList(animation, sColorStatePlaying);
                    holder.mImageView.setImageDrawable(animation);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    animation.start();
                    break;
                case STATE_PAUSED:
                    Drawable playDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_equalizer1_white_36dp);
                    DrawableCompat.setTintList(playDrawable, sColorStatePlaying);
                    holder.mImageView.setImageDrawable(playDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                case STATE_FOLDER:
                    Drawable folderDrawable = ContextCompat.getDrawable(activity, item.getDefaultResource());
                    DrawableCompat.setTintList(folderDrawable, sColorStateNotPlaying);
                    holder.mImageView.setImageDrawable(folderDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                case STATE_IMAGE:
                    Drawable imageDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_play_arrow_black_36dp);
                    DrawableCompat.setTintList(imageDrawable, sColorStateNotPlaying);
                    holder.mImageView.setImageDrawable(imageDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                default:
                    holder.mImageView.setVisibility(View.GONE);
            }
            convertView.setTag(R.id.tag_mediaitem_state_cache, state);
        }

        return convertView;
    }

    static View setupView(Activity activity, View convertView, ViewGroup parent, MediaMetadataCompat item, int state) {

        if (sColorStateNotPlaying == null || sColorStatePlaying == null) {
            initializeColorStateLists(activity);
        }

        MediaItemViewHolder holder;

        Integer cachedState = STATE_INVALID;

        if (convertView == null) {
            convertView = LayoutInflater.from(activity).inflate(R.layout.media_list_item, parent, false);
            holder = new MediaItemViewHolder();
            holder.mImageView = (ImageView) convertView.findViewById(R.id.play_eq);
            holder.mTitleView = (TextView) convertView.findViewById(R.id.title);
            holder.mDescriptionView = (TextView) convertView.findViewById(R.id.description);
            convertView.setTag(holder);
        } else {
            holder = (MediaItemViewHolder) convertView.getTag();
            cachedState = (Integer) convertView.getTag(R.id.tag_mediaitem_state_cache);
        }

        holder.mTitleView.setText(item.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        holder.mDescriptionView.setText(item.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));

        // If the state of convertView is different, we need to adapt the view to the
        // new state.
        if (cachedState == null || cachedState != state) {
            switch (state) {
                case STATE_PLAYABLE:
                    Drawable pauseDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_play_arrow_black_36dp);
                    DrawableCompat.setTintList(pauseDrawable, sColorStateNotPlaying);
                    holder.mImageView.setImageDrawable(pauseDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                case STATE_PLAYING:
                    AnimationDrawable animation = (AnimationDrawable)
                            ContextCompat.getDrawable(activity, R.drawable.ic_equalizer_white_36dp);
                    DrawableCompat.setTintList(animation, sColorStatePlaying);
                    holder.mImageView.setImageDrawable(animation);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    animation.start();
                    break;
                case STATE_PAUSED:
                    Drawable playDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_equalizer1_white_36dp);
                    DrawableCompat.setTintList(playDrawable, sColorStatePlaying);
                    holder.mImageView.setImageDrawable(playDrawable);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                default:
                    holder.mImageView.setVisibility(View.GONE);
            }
            convertView.setTag(R.id.tag_mediaitem_state_cache, state);
        }

        return convertView;
    }

    static View setupGridView(Activity activity, View convertView, ViewGroup parent, ContentItem item, int state) {

        if (sColorStateNotPlaying == null || sColorStatePlaying == null) {
            initializeColorStateLists(activity);
        }

        MediaItemViewHolder holder;

        Integer cachedState = STATE_INVALID;

        if (convertView == null) {
            convertView = LayoutInflater.from(activity).inflate(R.layout.media_grid_item, parent, false);
            holder = new MediaItemViewHolder();
            holder.layGrid = (RelativeLayout) convertView.findViewById(R.id.layGrid);
            holder.mImageBack = (ImageView) convertView.findViewById(R.id.imgBack);
            holder.mImageView = (ImageView) convertView.findViewById(R.id.play_eq);
            holder.mTitleView = (TextView) convertView.findViewById(R.id.title);
            holder.mDescriptionView = (TextView) convertView.findViewById(R.id.description);
            convertView.setTag(holder);
        } else {
            holder = (MediaItemViewHolder) convertView.getTag();
            cachedState = (Integer) convertView.getTag(R.id.tag_mediaitem_state_cache);
        }
        GridView grid = (GridView) parent;
        int size = grid.getRequestedColumnWidth();

        holder.mTitleView.setText(item.toString());
        holder.mDescriptionView.setText(item.getSubtitle());
        int w = holder.layGrid.getLayoutParams().width;
        // holder.layGrid.setLayoutParams(new GridView.LayoutParams(w, w));
        holder.mImageBack.setLayoutParams(new RelativeLayout.LayoutParams(w, w));
        if (item.isContainer()) {
            holder.mImageBack.setImageResource(item.getDefaultResource());
        } else {
            Utils.downloadBitmap(activity.getResources(), item, holder.mImageBack);
        }

        // If the state of convertView is different, we need to adapt the view to the
        // new state.
        if (cachedState == null || cachedState != state) {
            switch (state) {
                case STATE_PLAYABLE:
                    holder.mImageView.setImageDrawable(activity.getDrawable(R.drawable.ic_play_arrow_black_36dp));
                    holder.mImageView.setImageTintList(sColorStateNotPlaying);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                case STATE_PLAYING:
                    AnimationDrawable animation = (AnimationDrawable) activity.getDrawable(R.drawable.ic_equalizer_white_36dp);
                    holder.mImageView.setImageDrawable(animation);
                    holder.mImageView.setImageTintList(sColorStatePlaying);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    if (animation != null) animation.start();
                    break;
                case STATE_PAUSED:
                    holder.mImageView.setImageDrawable(activity.getDrawable(R.drawable.ic_equalizer1_white_36dp));
                    holder.mImageView.setImageTintList(sColorStateNotPlaying);
                    holder.mImageView.setVisibility(View.VISIBLE);
                    break;
                default:
                    holder.mImageView.setVisibility(View.GONE);
            }
            convertView.setTag(R.id.tag_mediaitem_state_cache, state);
        }

        return convertView;
    }

    static private void initializeColorStateLists(Context ctx) {
        sColorStateNotPlaying = ColorStateList.valueOf(ctx.getResources().getColor(R.color.media_item_icon_not_playing));
        sColorStatePlaying = ColorStateList.valueOf(ctx.getResources().getColor(R.color.media_item_icon_playing));
    }

}

