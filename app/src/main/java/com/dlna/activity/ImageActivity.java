package com.dlna.activity;

import java.util.ArrayList;

import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.ViewPager;

import com.dlna.R;
import com.dlna.adater.ImagePagerAdapter;
import com.dlna.adater.ImageSlideViewPager;
import com.dlna.util.ContentItem;

public class ImageActivity extends FragmentActivity {

	private static final String TAG = "ImageActivity";

	private ViewPager imageSliderPager;

	private ArrayList<String> listPath;

	private ImagePagerAdapter mPagerAdapter;
	private ArrayList<ContentItem> sList;

	private int select = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.image_preview);

		imageSliderPager = (ImageSlideViewPager) findViewById(R.id.image_silde_pager);

		sList = ShareData.aContentImage;
		select = getIntent().getExtras().getInt("position");
		listPath = new ArrayList<String>();

		for (int i = 0; i < sList.size(); i++) {

			listPath.add(sList.get(i).getResourceUri());
		}

		initPager();
	}

	private void initPager() {

		mPagerAdapter = new ImagePagerAdapter(getSupportFragmentManager(), ImageActivity.this, listPath);
		imageSliderPager.setAdapter(mPagerAdapter);
		imageSliderPager.setCurrentItem(select);
	}

}
