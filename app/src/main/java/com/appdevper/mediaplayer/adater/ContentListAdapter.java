package com.appdevper.mediaplayer.adater;

import java.util.ArrayList;

import android.content.Context;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.loader.ImageLoader;
import com.appdevper.mediaplayer.util.ContentItem;

public final class ContentListAdapter extends ArrayAdapter<ContentItem> {
	private final ImageLoader _imgDownloader;
	private final int ItemLayoutResource;
	private Context pContext;
	private SparseBooleanArray mCheckList;
	private Boolean select = false;
	private ImageView imgarrow;

	public ContentListAdapter(Context paramContext, int paramInt) {
		super(paramContext, 0);
		this.pContext = paramContext;
		this.ItemLayoutResource = paramInt;
		this._imgDownloader = new ImageLoader(pContext);
		this.mCheckList = new SparseBooleanArray();
		setClearSelection();
	}

	private View getWorkingView(View paramView) {
		if (paramView == null)
			return ((LayoutInflater) getContext().getSystemService("layout_inflater")).inflate(this.ItemLayoutResource, null);
		return paramView;
	}

	public void clearAdapter() {
		if ((this._imgDownloader == null) || (getCount() < 1))
			return;
		this._imgDownloader.clearCache();
		clear();
	}

	public ArrayList<ContentItem> getAll() {
		ArrayList<ContentItem> localArrayList = new ArrayList<ContentItem>();
		int i = getCount();
		for (int j = 0;; j++) {
			if (j >= i)
				return localArrayList;
			localArrayList.add((ContentItem) getItem(j));
		}
	}

	public ArrayList<ContentItem> getCheckAll() {
		ArrayList<ContentItem> localArrayList = new ArrayList<ContentItem>();
		int i = getCount();
		for (int j = 0;; j++) {
			if (j >= i)
				return localArrayList;
			if (mCheckList.get(j))
				localArrayList.add((ContentItem) getItem(j));
		}
	}

	public void setSelectAble(boolean selection) {
		select = selection;
		notifyDataSetChanged();
	}

	public void setCheckList(int postion, boolean selection) {
		mCheckList.put(postion, selection);
	}

	public boolean getCheckList(int postion) {
		return mCheckList.get(postion);
	}

	public void setClearSelection() {
		for (int i = 0; i < getCount(); i++) {
			mCheckList.put(i, false);
		}
	}

	public void setAllSelection(boolean selection) {
		if (selection) {
			for (int i = 0; i < getCount(); i++) {
				mCheckList.put(i, true);
			}
		} else {
			mCheckList.clear();
		}
		notifyDataSetChanged();
	}

	public View getView(int paramInt, View paramView, ViewGroup paramViewGroup) {

		View v = getWorkingView(paramView);
		ContentItem contentItem = (ContentItem) getItem(paramInt);

		TextView mTitle = (TextView) v.findViewById(R.id.m_title);
		TextView mTime = (TextView) v.findViewById(R.id.m_time);
		TextView sTitle = (TextView) v.findViewById(R.id.m_artis);
		ImageView img = (ImageView) v.findViewById(R.id.m_image);
		imgarrow = (ImageView) v.findViewById(R.id.img_arrow);

		mTitle.setText(contentItem.toString());
		sTitle.setText(contentItem.getSubtitle());
		mTime.setText(contentItem.getDuration());

		Log.i("ContentList", "URL=" + contentItem.getAlbumArtworkUri());

		_imgDownloader.setImage(contentItem.DefaultResource);
		img.setImageResource(contentItem.DefaultResource);

		if (contentItem.isContainer()) {
			imgarrow.setVisibility(View.VISIBLE);
			imgarrow.setImageResource(R.drawable.ic_arrow);
			if (contentItem.getIconArtworkUri() != null) {
				_imgDownloader.DisplayImage(contentItem.getIconArtworkUri(), img);
			}
			if (contentItem.getAlbumArtworkUri() != null) {
				_imgDownloader.DisplayImage(contentItem.getAlbumArtworkUri(), img);
			}

		} else {
			Log.i("ContentList", "Name=" + contentItem.getItem().getClass().getName());
			if (!select) {
				imgarrow.setVisibility(View.GONE);
			} else {
				imgarrow.setVisibility(View.VISIBLE);
				if (mCheckList.get(paramInt)) {
					imgarrow.setImageResource(R.drawable.btn_check_on_holo_light);
				} else {
					imgarrow.setImageResource(R.drawable.btn_check_off_holo_light);
				}

				// imgarrow.setImageResource(resId);

			}
			if (contentItem.getAlbumArtworkUri() != null) {
				_imgDownloader.DisplayImage(contentItem.getAlbumArtworkUri(), img);
			}
		}

		// Log.i("DeviceList","URL="+localDevice.getIconUri());
		if (ShareData.aContent.contains(contentItem)) {
			imgarrow.setVisibility(View.VISIBLE);
			imgarrow.setImageResource(R.drawable.btn_check_on_holo_light);
		}
		return v;
	}
	
}
