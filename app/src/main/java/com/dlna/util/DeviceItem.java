package com.dlna.util;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.types.UDN;
import org.seamless.util.MimeType;

import android.graphics.drawable.Drawable;
import android.util.Log;

public class DeviceItem {

	private UDN udn;
	private Device device;
	private String[] label;
	private Drawable icon;
	private String _iconUri;
	private Boolean islocal;

	public DeviceItem(Device device) {
		this.udn = device.getIdentity().getUdn();
		this.device = device;
		this.islocal = false;
	}

	public DeviceItem(Device device, Boolean di) {
		this.udn = null;
		this.device = device;
		this.islocal = di;
	}

	public DeviceItem(Device device, String... label) {
		this.udn = device.getIdentity().getUdn();
		this.device = device;
		this.label = label;
		this.islocal = false;
	}

	public UDN getUdn() {
		return udn;
	}

	public Device getDevice() {
		return device;
	}

	public String[] getLabel() {
		return label;
	}

	public void setLabel(String[] label) {
		this.label = label;
	}

	public Drawable getIcon() {
		return icon;
	}

	public String getIconUri() {
		if (this._iconUri != null)
			return this._iconUri;
		Icon localIcon = findUsableIcon();
		if (localIcon != null) {
			String str = ((RemoteDevice) localIcon.getDevice()).normalizeURI(localIcon.getUri()).toString();
			this._iconUri = str;
		}
		return this._iconUri;
	}

	protected Icon findUsableIcon() {
		Icon[] arrayOfIcon = this.device.getIcons();
		int i = arrayOfIcon.length;
		for (int j = 0;; j++) {
			if (j >= i) {
				return null;
			}
			Icon localIcon = (Icon) arrayOfIcon[j];
			Log.i("icon", " ICON =" + localIcon.toString());
			int k = localIcon.getWidth();
			int m = localIcon.getHeight();
			Log.i("icon", " ICON =" + k + "  " + m);
			if ((k <= 125) && (m <= 125) && (isUsableImageType(localIcon.getMimeType()))) {
				Log.i("icon", " ICON check = true");
				return localIcon;
			} else {
				Log.i("icon", " ICON check = false");
			}

		}
	}

	public void setIcon(Drawable icon) {
		this.icon = icon;
	}

	public Boolean getIslocal() {
		return islocal;
	}

	public void setIslocal(Boolean islocal) {
		this.islocal = islocal;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;

		DeviceItem that = (DeviceItem) o;
		if (udn != null) {
			if (udn.equals(that.udn))
				return true;
		}
		return false;
	}

	@Override
	public int hashCode() {
		if (udn != null) {
			return udn.hashCode();
		}
		return 0;
	}

	@Override
	public String toString() {
		String display = android.os.Build.MODEL;

		if (device == null) {
			return display;
		}

		if (device.getDetails().getFriendlyName() != null)
			display = device.getDetails().getFriendlyName();
		else
			display = device.getDisplayString();

		return device.isFullyHydrated() ? display : display + " *";
	}

	protected boolean isUsableImageType(MimeType paramMimeType) {
		if (paramMimeType != null) {
			String str1;
			String str2;
			str1 = paramMimeType.getType();
			str2 = paramMimeType.getSubtype();
			Log.i("icon", " ICON TYPE =" + str1 + "/" + str2);
			if ((str1.equals("image"))) {
				Log.i("icon", "icon true val");
				return true;
			} else {
				Log.i("icon", "icon false val");
				return false;
			}
		}
		Log.i("icon", "icon false null");
		return false;
	}
}
