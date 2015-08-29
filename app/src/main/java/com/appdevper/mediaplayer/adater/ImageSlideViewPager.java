package com.appdevper.mediaplayer.adater;

import it.sephiroth.android.library.imagezoom.ImageViewTouch;
import android.content.Context;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.View;

public class ImageSlideViewPager extends ViewPager {
	
	public ImageSlideViewPager(Context context) {

		super(context);

	}

	public ImageSlideViewPager(Context context, AttributeSet attrs) {

		super(context, attrs);

	}
	
	@Override
	protected boolean canScroll(View v, boolean checkV, int dx, int x, int y) {
		if (v instanceof ImageViewTouch) {
			return ((ImageViewTouch) v).canScroll(dx);
		} else {
			return super.canScroll(v, checkV, dx, x, y);
		}
	}
	
}
