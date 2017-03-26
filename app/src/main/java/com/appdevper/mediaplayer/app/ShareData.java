package com.appdevper.mediaplayer.app;

import com.appdevper.mediaplayer.util.ContentItem;
import com.appdevper.mediaplayer.util.DeviceItem;

import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;

public class ShareData {

	private static Device<?, ?, ?> device;
	private static DeviceItem renderDevice = null;
	private static ArrayList<DeviceItem> renList;
	public static ArrayList<ContentItem> aContent = new ArrayList<ContentItem>();
	public static ArrayList<ContentItem> aContentImage = new ArrayList<ContentItem>();
	public static int adCall = 0;

	public static Device<?, ?, ?> getDevice() {
		return device;
	}

	public static void setDevice(Device<?, ?, ?> device) {
		ShareData.device = device;
	}

	public static ArrayList<DeviceItem> getRenList() {
		return renList;
	}

	public static void setRenList(ArrayList<DeviceItem> renList) {
		ShareData.renList = renList;
	}

	public static DeviceItem getRenderDevice() {
		return renderDevice;
	}

	public static void setRenderDevice(DeviceItem renderDevice) {
		ShareData.renderDevice = renderDevice;
	}

}
