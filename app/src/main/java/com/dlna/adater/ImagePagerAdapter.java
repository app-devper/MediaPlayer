package com.dlna.adater;




import com.dlna.loader.ImageLoader;

import java.util.ArrayList;

import android.app.Activity;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;


public class ImagePagerAdapter extends FragmentStatePagerAdapter{
    
    private ArrayList <String> listPath;
    private Activity activity;
    public ImageLoader imageLoader; 
	
	public ImagePagerAdapter(FragmentManager fm,Activity act,ArrayList <String> list) {
		super(fm);
		this.activity=act;
		this.listPath =list;
		imageLoader = new ImageLoader(activity.getApplicationContext());
	}

	public ImagePagerAdapter(FragmentManager fm,Activity act,ArrayList <String> list ,String refer) {
        super(fm);
        this.activity=act;
        this.listPath =list;
        imageLoader = new ImageLoader(activity.getApplicationContext());
        imageLoader.setReferer(refer);
    }
	
	public ImagePagerAdapter(FragmentManager fm) {
        super(fm);
    }
	
	@Override
	public int getCount() {

		return listPath.size();
	}
	
	@Override
	public Fragment getItem(int position) {
		
		return ImagePreviewFragment.newInstance(listPath.get(position),imageLoader,activity);
	}
	
	
}
