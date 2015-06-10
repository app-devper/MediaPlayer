package com.dlna.adater;

import java.util.ArrayList;
import java.util.List;

import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;

import com.dlna.R;
import com.dlna.activity.UserData;
import com.dlna.loader.ThumbLoader;
import com.dlna.mediaserver.ContentTree;

import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

public final class ItemListAdapter extends ArrayAdapter<Item> {
	private final int deviceItemLayoutResource;
	private ThumbLoader loader;

	public ItemListAdapter(Context paramContext, int paramInt) {
		super(paramContext, 0);
		this.deviceItemLayoutResource = paramInt;
		loader = new ThumbLoader(paramContext);
	}

	private View getWorkingView(View paramView) {
		if (paramView == null)
			return ((LayoutInflater) getContext().getSystemService("layout_inflater")).inflate(this.deviceItemLayoutResource, null);
		return paramView;
	}

	public List<Item> getSelect() {
		ArrayList<Item> localArrayList = new ArrayList<Item>();
		int i = getCount();
		for (int j = 0;; j++) {
			if (j >= i)
				return localArrayList;
			localArrayList.add((Item) getItem(j));
		}
	}

	public View getView(final int paramInt, View paramView, ViewGroup paramViewGroup) {

		View v = getWorkingView(paramView);

		final Item localItem = (Item) getItem(paramInt);

		TextView deviceName = (TextView) v.findViewById(R.id.d_r_name);
		ImageView img = (ImageView) v.findViewById(R.id.d_r_image);

		RelativeLayout rselect = (RelativeLayout) v.findViewById(R.id.r_select);
		if (UserData.getArSelectId().contains(localItem.getId())) {
			rselect.setBackgroundResource(R.color.color_gray_light);
		} else {
			rselect.setBackgroundResource(R.color.color_green);
		}
		deviceName.setText(localItem.getTitle());
		if (localItem.getId().contains(ContentTree.IMAGE_PREFIX)) {
			ImageItem item = (ImageItem) localItem;
			loader.DisplayImage(item.getLongDescription(), img);
		}

		v.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (UserData.sMode) {
					if (UserData.getArSelectId().contains(localItem.getId())) {
						UserData.getArSelectId().remove(localItem.getId());
					} else {
						UserData.getArSelectId().add(localItem.getId());
					}
					notifyDataSetChanged();
				}
			}
		});

		return v;
	}

	public void addItemAll(ArrayList<? extends Item> items) {

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			super.addAll(items);
		} else {
			for (Item item : items) {
				super.add(item);
			}
		}
	}
}
