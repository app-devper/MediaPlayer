package com.appdevper.mediaplayer.activity;

import java.util.List;
import java.util.Stack;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.support.model.container.Container;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.adater.ContentListAdapter;
import com.appdevper.mediaplayer.app.AppController;
import com.appdevper.mediaplayer.app.ShareData;
import com.appdevper.mediaplayer.util.ContentActionCallback;
import com.appdevper.mediaplayer.util.ContentItem;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;

public class ContentActivity extends ActionBarActivity {

	private ListView contentListView;
	private ContentListAdapter contentListAdapter;
	private AndroidUpnpService upnpService;
	private boolean aSelect = false;
	private final static String TAG = ContentActivity.class.getSimpleName();
	private Device<?, ?, ?> cDevice;
	private Service<?, ?> cServices;
	private String type;
	private ContentItem mainContent;
	private Stack<ContentItem> stackContent;
	private Menu menu;
	private MenuItem cItem;
	private AdRequest adRequest;
	private RelativeLayout dialogPlay;
	private TextView dialogName;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_content);

		contentListView = (ListView) findViewById(R.id.contentList);

		dialogPlay = (RelativeLayout) findViewById(R.id.dialogPlay);
		dialogName = (TextView) findViewById(R.id.dialogName);
		dialogPlay.setVisibility(View.GONE);
		AdView adView = (AdView) findViewById(R.id.adView);

		contentListAdapter = new ContentListAdapter(this, R.layout.media_row);
		contentListView.setAdapter(contentListAdapter);

		contentListView.setOnItemClickListener(contentItemClickListener);
		contentListView.setOnItemLongClickListener(itemLongClink);

		upnpService = ShareData.getUpnpService();
		cDevice = ShareData.getDevice();

		stackContent = new Stack<ContentItem>();

		try {
			cServices = cDevice.findService(new UDAServiceType("ContentDirectory"));
			mainContent = new ContentItem(createRootContainer(cServices), cServices);
			upnpService.getControlPoint().execute(new ContentActionCallback(this, mainContent.getService(), mainContent.getContainer(), contentListAdapter));
		} catch (Exception e) {

		}

		AppController.setService();

		adRequest = new AdRequest.Builder().addTestDevice("FC30F813719E71A110A143F708B6C212").addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();

		adView.loadAd(adRequest);

	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_BACK:
				return onBack();
			}
		}

		return super.dispatchKeyEvent(event);
	}

	private boolean onBack() {
		if (!stackContent.isEmpty()) {
			mainContent = stackContent.pop();
			upnpService.getControlPoint().execute(new ContentActionCallback(this, mainContent.getService(), mainContent.getContainer(), contentListAdapter));
			cItem = menu.findItem(R.id.c_select);
			cItem.setIcon(R.drawable.ic_content_add);
			// contentListAdapter.setSelectAble(false);
			// contentListAdapter.setClearSelection();
			aSelect = false;
			return true;
		} else {
			setResult(200);
			finish();
			return true;

		}

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.content, menu);
		this.menu = menu;
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.c_play:
			Intent intent = new Intent();
			intent.setClass(ContentActivity.this, MusicPlayerActivity.class);
			startActivity(intent);
			break;

		case R.id.c_select:
			update();
			break;
		}
		return false;
	}

	private void update() {
		if (!aSelect) {
			cItem = menu.findItem(R.id.c_select);
			cItem.setIcon(R.drawable.ic_content_save);
			// contentListAdapter.setSelectAble(true);
			// contentListAdapter.setClearSelection();
			Toast.makeText(getApplicationContext(), "Select Mode", Toast.LENGTH_SHORT).show();
			aSelect = true;
		} else {
			cItem = menu.findItem(R.id.c_select);
			cItem.setIcon(R.drawable.ic_content_add);
			// contentListAdapter.setSelectAble(false);
			// contentListAdapter.setClearSelection();
			Toast.makeText(getApplicationContext(), "Play Mode", Toast.LENGTH_SHORT).show();
			aSelect = false;
		}
	}

	protected Container createRootContainer(Service<?, ?> service) {
		Container rootContainer = new Container();
		rootContainer.setId("0");
		rootContainer.setTitle("Content Directory on " + service.getDevice().getDisplayString());
		return rootContainer;
	}

	private OnItemClickListener contentItemClickListener = new OnItemClickListener() {

		@Override
		public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
			sMedia(v, position);
			contentListAdapter.notifyDataSetChanged();
		}
	};

	private OnItemLongClickListener itemLongClink = new OnItemLongClickListener() {

		@Override
		public boolean onItemLongClick(AdapterView<?> parent, View v, int position, long id) {
			ContentItem content = contentListAdapter.getItem(position);
			if (!content.isContainer()) {
				showDialog(content);
			}
			return true;
		}
	};

	private void sMedia(View v, int position) {

		ContentItem content = contentListAdapter.getItem(position);
		type = content.getType();
		Log.d("___CLINK____", content.isContainer().toString());
		if (content.isContainer()) {
			stackContent.push(mainContent);
			mainContent = content;
			upnpService.getControlPoint().execute(new ContentActionCallback(ContentActivity.this, content.getService(), content.getContainer(), contentListAdapter));
			cItem = menu.findItem(R.id.c_select);
			cItem.setIcon(R.drawable.ic_content_add);
			// contentListAdapter.setSelectAble(false);
			// contentListAdapter.setClearSelection();
			aSelect = false;
			// Log.d("___CLINK____", content.getContainer().toString());
		} else {

			if (!aSelect) {

				try {

					if (ShareData.getrDevice().getIslocal()) {

						if (type.equals("video")) {
							AppController.setMedia(content);
						} else if (type.equals("audio")) {
							AppController.setMedia(content);
						} else if (type.equals("image")) {
							ShareData.aContentImage = contentListAdapter.getAll();
							AppController.setMedia(content);
							Intent intent = new Intent();
							intent.putExtra("position", position);
							intent.setClass(ContentActivity.this, ImageActivity.class);
							startActivity(intent);
						}

					} else {

						if (type.equals("video")) {
							AppController.stopMusic();
							AppController.sendRender(content);
						} else if (type.equals("audio")) {
							AppController.stopMusic();
							AppController.sendRender(content);
						} else if (type.equals("image")) {
							AppController.sendRender(content);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}

			} else {

				if (ShareData.aContent.contains(content)) {
					if (!AppController.getContent().equals(content))
						ShareData.aContent.remove(content);
				} else {
					ShareData.aContent.add(content);
				}

			}

		}

	}

	private void showDialog(final ContentItem content) {
		AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);

		alertDialog.setTitle("Download file!");

		alertDialog.setMessage("You want to download this file?");

		alertDialog.setNegativeButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				download(content);
				dialog.cancel();
			}
		});

		alertDialog.setPositiveButton("Cancel", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
			}
		});

		alertDialog.show();
	}

	private void download(ContentItem content) {
		if (isDownloadManagerAvailable(ContentActivity.this)) {
			String url = content.getResourceUri();
			DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
			request.setDescription("Dowload file with Media player");
			request.setTitle("Dowload " + content.toString());
			// in order for this if to run, you must use the android 3.2 to
			// compile your app
			String[] ss = url.split("\\.");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				request.allowScanningByMediaScanner();
				request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
			}
			request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, content.toString() + "." + ss[ss.length - 1]);

			// get download service and enqueue file
			DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
			manager.enqueue(request);
		} else {
			Toast.makeText(ContentActivity.this, "Can not download file.", Toast.LENGTH_SHORT).show();
		}
	}

	private boolean isDownloadManagerAvailable(Context context) {
		try {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
				return false;
			}
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_LAUNCHER);
			intent.setClassName("com.android.providers.downloads.ui", "com.android.providers.downloads.ui.DownloadList");
			List<ResolveInfo> list = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
			return list.size() > 0;
		} catch (Exception e) {
			return false;
		}
	}

}