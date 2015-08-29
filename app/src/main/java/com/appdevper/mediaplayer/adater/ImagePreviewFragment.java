package com.appdevper.mediaplayer.adater;


import it.sephiroth.android.library.imagezoom.ImageViewTouch;
import it.sephiroth.android.library.imagezoom.ImageViewTouchBase.DisplayType;

import android.app.Activity;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.loader.ImageLoader;

public class ImagePreviewFragment extends Fragment {

    private static final String IMAGE_PATH_PARAM = "image_path";
    private static ImageLoader imageLoader;
    private static Activity a;
    private ImageViewTouch imageView;
    private ProgressBar pgBar;

    public static ImagePreviewFragment newInstance(String string, Activity activity) {
        a = activity;
        ImagePreviewFragment fragment = new ImagePreviewFragment();
        Bundle args = new Bundle();
        args.putString(IMAGE_PATH_PARAM, string);
        fragment.setArguments(args);

        return fragment;
    }

    public static Fragment newInstance(String string, ImageLoader iloader, Activity activity) {
        a = activity;
        ImagePreviewFragment fragment = new ImagePreviewFragment();
        imageLoader = iloader;
        Bundle args = new Bundle();
        args.putString(IMAGE_PATH_PARAM, string);
        fragment.setArguments(args);
        return fragment;
    }

    public static Fragment newInstance(String string, ImageLoader iloader) {
        ImagePreviewFragment fragment = new ImagePreviewFragment();
        imageLoader = iloader;
        Bundle args = new Bundle();
        args.putString(IMAGE_PATH_PARAM, string);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        imageLoader.DisplayImage(getArguments().getString(IMAGE_PATH_PARAM), imageView);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        final View v = inflater.inflate(R.layout.fragment_image_preview, container, false);
        imageView = (ImageViewTouch) v.findViewById(R.id.imgview);
        imageView.setDisplayType(DisplayType.FIT_TO_SCREEN);

        return v;

    }

}
