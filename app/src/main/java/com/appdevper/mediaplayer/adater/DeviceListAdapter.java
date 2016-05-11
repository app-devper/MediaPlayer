package com.appdevper.mediaplayer.adater;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.activity.RenderListActivity;
import com.appdevper.mediaplayer.app.AppMediaPlayer;
import com.appdevper.mediaplayer.loader.ImageLoader;
import com.appdevper.mediaplayer.util.DeviceItem;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class DeviceListAdapter extends ArrayAdapter<DeviceItem> {

    public DeviceListAdapter(Activity act) {
        super(act, 0);
    }

    public DeviceListAdapter(Activity act, ArrayList<DeviceItem> deviceItem) {
        super(act, 0, deviceItem);
    }

    public View getView(int paramInt, View paramView, ViewGroup parent) {

        if (paramView == null) {
            paramView = LayoutInflater.from(getContext()).inflate(R.layout.device_row, parent, false);
        }

        DeviceItem localDevice = getItem(paramInt);
        TextView deviceName = (TextView) paramView.findViewById(R.id.m_name);
        ImageView img = (ImageView) paramView.findViewById(R.id.m_image);
        deviceName.setText(localDevice.toString());
        if (!localDevice.getIslocal()) {
            AppMediaPlayer.getImageLoader().displayImage(localDevice.getIconUri(), img);
        } else {
            img.setImageResource(R.drawable.ic_launcher);
        }

        return paramView;
    }
}
