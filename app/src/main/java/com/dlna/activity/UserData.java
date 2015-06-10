package com.dlna.activity;

import java.util.ArrayList;

import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.VideoItem;

public class UserData {

	private static ArrayList<VideoItem> arVideo = new ArrayList<VideoItem>();
	private static ArrayList<MusicTrack> arAudio = new ArrayList<MusicTrack>();
	private static ArrayList<ImageItem> arImage = new ArrayList<ImageItem>();
	public static Boolean sMode = false;
	private static ArrayList<String> arSelectId = new ArrayList<String>();

	public static ArrayList<VideoItem> getArVideo() {
		return arVideo;
	}

	public static void setArVideo(ArrayList<VideoItem> arVideo) {
		UserData.arVideo = arVideo;
	}

	public static ArrayList<MusicTrack> getArAudio() {
		return arAudio;
	}

	public static void setArAudio(ArrayList<MusicTrack> arAudio) {
		UserData.arAudio = arAudio;
	}

	public static ArrayList<ImageItem> getArImage() {
		return arImage;
	}

	public static void setArImage(ArrayList<ImageItem> arImage) {
		UserData.arImage = arImage;
	}

	public static ArrayList<String> getArSelectId() {
		return arSelectId;
	}

	public static void setArSelectId(ArrayList<String> arSelectId) {
		UserData.arSelectId = arSelectId;
	}

}
