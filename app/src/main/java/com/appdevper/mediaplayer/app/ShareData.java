package com.appdevper.mediaplayer.app;

import com.appdevper.mediaplayer.util.ContentItem;
import com.appdevper.mediaplayer.util.DeviceItem;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;

public class ShareData {

	private static AndroidUpnpService upnpService;
	private static Device<?, ?, ?> device;
	private static DeviceItem rDevice = null;
	private static ArrayList<DeviceItem> renList;
	public static ArrayList<ContentItem> aContent = new ArrayList<ContentItem>();
	public static ArrayList<ContentItem> aContentImage = new ArrayList<ContentItem>();
	public static int adCall = 0;
	
	public static ArrayList<ContentItem> getcList() {
		return aContent;
	}

	public static void setcList(ArrayList<ContentItem> cList) {
		ShareData.aContent = cList;
	}

	public static AndroidUpnpService getUpnpService() {
		return upnpService;
	}

	public static void setUpnpService(AndroidUpnpService upnpService) {
		ShareData.upnpService = upnpService;
	}

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

	public static DeviceItem getrDevice() {
		return rDevice;
	}

	public static void setrDevice(DeviceItem rDevice) {
		ShareData.rDevice = rDevice;
	}

}
