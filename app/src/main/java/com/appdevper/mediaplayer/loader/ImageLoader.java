package com.appdevper.mediaplayer.loader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.util.Utils;

public class ImageLoader {
	private static final String TAG = "ImageLoader";

	MemoryCache memoryCache = new MemoryCache();
	FileCache fileCache;
	private String refer;
	private Map<ImageView, String> imageViews = Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
	ExecutorService executorService;
	int stub_id = R.drawable.ic_image_item;

	public ImageLoader(Context context) {
		fileCache = new FileCache(context);
		executorService = Executors.newFixedThreadPool(20);
	}

	public void setReferer(String refer) {
		this.refer = refer;
	}

	public void setImage(int img) {
		this.stub_id = img;
	}

	public void DisplayImage(String url, ImageView imageView) {
		imageViews.put(imageView, url);
		Bitmap bitmap = memoryCache.get(url);
		if (bitmap != null)
			imageView.setImageBitmap(bitmap);
		else {
			queuePhoto(url, imageView);
			imageView.setImageResource(stub_id);
		}
	}

	private void queuePhoto(String url, ImageView imageView) {
		PhotoToLoad p = new PhotoToLoad(url, imageView);
		executorService.submit(new PhotosLoader(p));
	}

	private Bitmap getBitmap(String url) {
		File f = fileCache.getFile(url);

		// from SD cache
		Bitmap b = decodeFile(f);
		if (b != null)
			return b;

		// from web
		try {
			Bitmap bitmap = null;
			URL imageUrl = new URL(url);

			HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();

			InputStream is = conn.getInputStream();
			OutputStream os = new FileOutputStream(f);
			Utils.CopyStream(is, os);
			os.close();
			bitmap = decodeFile(f);
			return bitmap;
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
		}
	}

	// decodes image and scales it to reduce memory consumption
	private Bitmap decodeFile(File f) {
		try {
			// decode image
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inSampleSize = 1;
			return BitmapFactory.decodeStream(new FileInputStream(f), null, o);

		} catch (FileNotFoundException e) {
		}
		return null;
	}

	// Task for the queue
	private class PhotoToLoad {
		public String url;
		public ImageView imageView;

		public PhotoToLoad(String u, ImageView i) {
			url = u;
			imageView = i;
		}
	}

	class PhotosLoader implements Runnable {
		PhotoToLoad photoToLoad;

		PhotosLoader(PhotoToLoad photoToLoad) {
			this.photoToLoad = photoToLoad;
		}

		@Override
		public void run() {
			if (imageViewReused(photoToLoad))
				return;
			Bitmap bmp = getBitmap(photoToLoad.url);
			memoryCache.put(photoToLoad.url, bmp);
			if (imageViewReused(photoToLoad))
				return;
			BitmapDisplayer bd = new BitmapDisplayer(bmp, photoToLoad);
			Activity a = (Activity) photoToLoad.imageView.getContext();
			a.runOnUiThread(bd);
		}
	}

	boolean imageViewReused(PhotoToLoad photoToLoad) {
		String tag = imageViews.get(photoToLoad.imageView);
		if (tag == null || !tag.equals(photoToLoad.url))
			return true;
		return false;
	}

	// Used to display bitmap in the UI thread
	class BitmapDisplayer implements Runnable {
		Bitmap bitmap;
		PhotoToLoad photoToLoad;

		public BitmapDisplayer(Bitmap b, PhotoToLoad p) {
			bitmap = b;
			photoToLoad = p;
		}

		public void run() {
			if (imageViewReused(photoToLoad))
				return;
			if (bitmap != null) {
				photoToLoad.imageView.setImageBitmap(bitmap);
			} else
				photoToLoad.imageView.setImageResource(stub_id);
		}
	}

	public void clearCache() {
		memoryCache.clear();
		// fileCache.clear();
	}
	
	public void setTrackInfo(String uri) {
		String[] parts = uri.split("/");
		String trackNameExt = parts[parts.length-1];
		final String trackName = trackNameExt.split("[.]")[0];
		final String artist = parts[parts.length-2];
		System.out.println("Track info " + trackName + " plus " + artist);
			
		// download cover if possible - I keep them as cover.jpg in the CD's directory
		// Rebuild path with last part changed
		parts[parts.length-1] = "cover.jpg";
		StringBuffer result = new StringBuffer();
		if (parts.length > 0) {
			result.append(parts[0]);
			for (int i = 1; i < parts.length; i++) {
				result.append("/");
				result.append(parts[i]);
			 }	
		} 
		
		// Download url and either get a valid image or null
		ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
		int count = 0;
		try {
			URL url = new URL(Uri.encode(result.toString(), ":/"));
			System.out.println("Opening stream " + url.toString());
			BufferedInputStream reader = new BufferedInputStream(url.openStream());
			byte[] buff = new byte[1024]; 
			int nread;

			while ((nread = reader.read(buff, 0, 1024)) != -1) {
				imageStream.write(buff, 0, nread);
				count += nread;
			}
		} catch(Exception e) {
			System.out.println("Image failed " + e.toString());
		}
		// try to decode - get valid image or nulll
		final Bitmap image = BitmapFactory.decodeByteArray(imageStream.toByteArray(), 0, count);

	}

}
