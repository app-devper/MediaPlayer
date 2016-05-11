package com.appdevper.mediaplayer.adater;


import com.appdevper.mediaplayer.loader.ImageLoader;

import java.util.ArrayList;

import android.app.Activity;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;


public class ImagePagerAdapter extends FragmentStatePagerAdapter {

    private ArrayList<String> listPath;
    private Activity activity;
    private ImageLoader imageLoader;

    public ImagePagerAdapter(FragmentManager fm, Activity act, ArrayList<String> list) {
        super(fm);
        this.activity = act;
        this.listPath = list;
        this.imageLoader = new ImageLoader(activity.getApplicationContext());
    }

    @Override
    public int getCount() {
        return listPath.size();
    }

    @Override
    public Fragment getItem(int position) {
        return ImagePreviewFragment.newInstance(listPath.get(position), imageLoader);
    }
}
