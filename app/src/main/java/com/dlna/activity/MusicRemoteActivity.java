package com.dlna.activity;

import java.util.ArrayList;
import java.util.Random;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.callback.GetMediaInfo;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.GetTransportInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dlna.R;
import com.dlna.loader.ImageLoader;
import com.dlna.util.ContentItem;
import com.dlna.util.Utils;


public class MusicRemoteActivity extends ActionBarActivity implements SeekBar.OnSeekBarChangeListener {

	private ImageButton btnPlay;
	private ImageButton btnVolume;
	private ImageButton btnNext;
	private ImageButton btnPrevious;
	private ImageButton btnRepeat;
	private ImageButton btnShuffle;
	private SeekBar songProgressBar;
	private SeekBar volumeProgressBar;
	private TextView songTitleLabel;
	private TextView songCurrentDurationLabel;
	private TextView songTotalDurationLabel;
	private ImageView imgPlay;

	private Handler mHandler = new Handler();

	private int volume = 0;
	private int time = 0;
	private boolean svolume = false;

	private int currentSongIndex = 0;
	private boolean isShuffle = false;
	private boolean isRepeat = false;
	private boolean isPlaying;

	private ArrayList<ContentItem> sList;

	private ImageLoader _imgDownloader;

	private ContentItem contentItem;

	private TextView subTitle;
	private PositionInfo positionInfo = new PositionInfo();
	private TransportInfo transportInfo = new TransportInfo();
	private Intent i;
	private AndroidUpnpService upnpService;
	private Service<?, ?> service;
	private String TAG = "Info Media";
	private Device<?, ?, Service<?, ?>> renderer;
	private Service<?, ?> rService;
	private final UnsignedIntegerFourBytes instanceid = new UnsignedIntegerFourBytes(0);

	@SuppressWarnings("unchecked")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_remote);

		isPlaying = false;

		btnPlay = (ImageButton) findViewById(R.id.btnPlay);
		btnVolume = (ImageButton) findViewById(R.id.btnVolume);

		btnNext = (ImageButton) findViewById(R.id.btnForward);
		btnPrevious = (ImageButton) findViewById(R.id.btnBackward);

		btnRepeat = (ImageButton) findViewById(R.id.btnRepeat);
		btnShuffle = (ImageButton) findViewById(R.id.btnShuffle);
		songProgressBar = (SeekBar) findViewById(R.id.songProgressBar);
		volumeProgressBar = (SeekBar) findViewById(R.id.seekvolume);
		songTitleLabel = (TextView) findViewById(R.id.songTitle);
		subTitle = (TextView) findViewById(R.id.subTitle);
		songCurrentDurationLabel = (TextView) findViewById(R.id.songCurrentDurationLabel);
		songTotalDurationLabel = (TextView) findViewById(R.id.songTotalDurationLabel);
		imgPlay = (ImageView) findViewById(R.id.imgPlay);

		upnpService = ShareData.getUpnpService();
		renderer = ShareData.getrDevice().getDevice();

		service = renderer.findService(new UDAServiceId("AVTransport"));
		rService = renderer.findService(new UDAServiceId("RenderingControl"));

		_imgDownloader = new ImageLoader(this);

		songProgressBar.setOnSeekBarChangeListener(this);

		sList = ShareData.getcList();

		currentSongIndex = 0;

		playSong(currentSongIndex);

		volumeProgressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				mHandler.removeCallbacks(mUpdateVolume);
				volume = seekBar.getProgress();
				setVolumeAction(volume);
				mHandler.postDelayed(mUpdateVolume, 100);
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {

				mHandler.removeCallbacks(mUpdateVolume);
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

			}
		});

		btnVolume.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (!svolume) {
					Toast.makeText(getApplicationContext(), "Volume is OFF", Toast.LENGTH_SHORT).show();
					setMuteAction(true);
				} else {
					Toast.makeText(getApplicationContext(), "Volume is ON", Toast.LENGTH_SHORT).show();
					setMuteAction(false);
				}
			}
		});

		btnPlay.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {

				if (isPlaying) {
					toPause();

				} else {
					toPlay();
				}

			}
		});

		btnNext.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {

				mHandler.removeCallbacks(mUpdateTimeTask);
				time = 0;
				songProgressBar.setProgress(time);
				if (currentSongIndex < (sList.size() - 1)) {
					playSong(currentSongIndex + 1);
					currentSongIndex = currentSongIndex + 1;
				} else {

					playSong(0);
					currentSongIndex = 0;
				}

			}
		});

		btnPrevious.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				mHandler.removeCallbacks(mUpdateTimeTask);
				time = 0;
				songProgressBar.setProgress(time);
				if (currentSongIndex > 0) {
					playSong(currentSongIndex - 1);
					currentSongIndex = currentSongIndex - 1;
				} else {
					playSong(sList.size() - 1);
					currentSongIndex = sList.size() - 1;
				}

			}
		});

		btnRepeat.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (isRepeat) {
					isRepeat = false;
					Toast.makeText(getApplicationContext(), "Repeat is OFF", Toast.LENGTH_SHORT).show();
					btnRepeat.setImageResource(R.drawable.btn_repeat);
				} else {
					// make repeat to true
					isRepeat = true;
					Toast.makeText(getApplicationContext(), "Repeat is ON", Toast.LENGTH_SHORT).show();
					// make shuffle to false
					isShuffle = false;
					btnRepeat.setImageResource(R.drawable.btn_repeat_focused);
					btnShuffle.setImageResource(R.drawable.btn_shuffle);
				}
			}
		});

		btnShuffle.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (isShuffle) {
					isShuffle = false;
					Toast.makeText(getApplicationContext(), "Shuffle is OFF", Toast.LENGTH_SHORT).show();
					btnShuffle.setImageResource(R.drawable.btn_shuffle);
				} else {

					isShuffle = true;
					Toast.makeText(getApplicationContext(), "Shuffle is ON", Toast.LENGTH_SHORT).show();

					isRepeat = false;
					btnShuffle.setImageResource(R.drawable.btn_shuffle_focused);
					btnRepeat.setImageResource(R.drawable.btn_repeat);
				}
			}
		});

	}

	@SuppressWarnings("unchecked")
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (resultCode == 100) {
			mHandler.removeCallbacks(mUpdateTimeTask);
			time = 0;
			songProgressBar.setProgress(time);
			currentSongIndex = data.getExtras().getInt("songIndex");
			playSong(currentSongIndex);
		}
		if (resultCode == 200) {

			time = 0;
			songProgressBar.setProgress(time);

			if (ShareData.getrDevice().getIslocal()) {
				toStop();
				Intent intent = new Intent();
				intent.setClass(this, MusicPlayerActivity.class);
				intent.putExtra("songIndex", currentSongIndex);
				startActivity(intent);
				finish();
			} else {
				toStop();
				renderer = ShareData.getrDevice().getDevice();
				service = renderer.findService(new UDAServiceType("AVTransport"));
				rService = renderer.findService(new UDAServiceId("RenderingControl"));
				playSong(currentSongIndex);

			}
		}

	}

	public void nextSong() {
		time = 0;
		songProgressBar.setProgress(time);
		if (isRepeat) {

			playSong(currentSongIndex);
		} else if (isShuffle) {
			// shuffle is on - play a random song
			Random rand = new Random();
			currentSongIndex = rand.nextInt((sList.size() - 1) - 0 + 1) + 0;

			playSong(currentSongIndex);
		} else {
			// no repeat or shuffle ON - play next song
			if (currentSongIndex < (sList.size() - 1)) {

				playSong(currentSongIndex + 1);
				currentSongIndex = currentSongIndex + 1;
			} else {

				playSong(0);
				currentSongIndex = 0;
			}
		}
	}

	public void playSong(int songIndex) {
		toStop();

		contentItem = sList.get(songIndex);
		String songTitle = contentItem.getItem().getTitle();
		songTitleLabel.setText(songTitle);
		subTitle.setText(contentItem.getSubtitle());
		_imgDownloader.setImage(contentItem.DefaultResource);
		imgPlay.setImageResource(contentItem.DefaultResource);
		_imgDownloader.DisplayImage(sList.get(songIndex).getAlbumArtworkUri(), imgPlay);

		songProgressBar.setProgress(0);
		songProgressBar.setMax(100);

		sendRender(contentItem);

		updateProgressBar();
		mHandler.removeCallbacks(mUpdateVolume);
		mHandler.postDelayed(mUpdateVolume, 1000);
	}

	public void updateProgressBar() {

		mHandler.removeCallbacks(mUpdateTimeTask);
		mHandler.postDelayed(mUpdateTimeTask, 1000);

	}

	private Runnable mUpdateTimeTask = new Runnable() {
		public void run() {

			getPositionInfo();
			getTransportInfo();

			if (transportInfo.getCurrentTransportState().equals(TransportState.PLAYING)) {

				isPlaying = true;
				btnPlay.setImageResource(R.drawable.btn_pause);
			} else if (transportInfo.getCurrentTransportState().equals(TransportState.PAUSED_PLAYBACK)) {
				isPlaying = false;
				btnPlay.setImageResource(R.drawable.btn_play);

			}

			long totalDuration = positionInfo.getTrackDurationSeconds();
			long currentDuration = positionInfo.getTrackElapsedSeconds();

			// Displaying Total Duration time
			songTotalDurationLabel.setText("" + Utils.secondsToTimer(totalDuration));
			// Displaying time completed playing
			songCurrentDurationLabel.setText("" + Utils.secondsToTimer(currentDuration));

			songProgressBar.setProgress(time);

			if (time < 100) {
				mHandler.postDelayed(this, 1000);
			} else {
				mHandler.removeCallbacks(mUpdateTimeTask);
				nextSong();
			}
		}
	};

	private Runnable mUpdateVolume = new Runnable() {
		public void run() {
			getMuteAction();
			getVolumeAction();

			if (svolume) {
				btnVolume.setImageResource(R.drawable.ic_volume_muted);
			} else {
				btnVolume.setImageResource(R.drawable.ic_volume_on);
			}

			volumeProgressBar.setProgress(volume);
			mHandler.postDelayed(this, 1000);
		}
	};

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.player, menu);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.share:
			i = new Intent(getApplicationContext(), RenderListActivity.class);
			startActivityForResult(i, 200);
			break;
		case R.id.playlist:
			i = new Intent(getApplicationContext(), PlayListActivity.class);
			startActivityForResult(i, 100);
			break;
		}

		return false;
	}

	@Override
	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {

	}

	@Override
	public void onStartTrackingTouch(SeekBar seekBar) {
		mHandler.removeCallbacks(mUpdateTimeTask);
	}

	@Override
	public void onStopTrackingTouch(SeekBar seekBar) {
		mHandler.removeCallbacks(mUpdateTimeTask);

		long totalDuration = positionInfo.getTrackDurationSeconds();
		int currentPosition = Utils.progressToTimerSeconds(seekBar.getProgress(), totalDuration);
		toSeek(Utils.secondsToTimer(currentPosition));
		time = seekBar.getProgress();
		songProgressBar.setProgress(time);
		updateProgressBar();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mHandler.removeCallbacks(mUpdateTimeTask);
		mHandler.removeCallbacks(mUpdateVolume);
	}

	@Override
	public void onBackPressed() {
		super.onBackPressed();

		mHandler.removeCallbacks(mUpdateTimeTask);
		mHandler.removeCallbacks(mUpdateVolume);
	}

	protected void sendRender(ContentItem content) {
		try {
			upnpService.getControlPoint().execute(new SetAVTransportURI(instanceid, service, content.getResourceUri()) {
				@SuppressWarnings("rawtypes")
				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

				}

				@SuppressWarnings("rawtypes")
				@Override
				public void success(ActionInvocation invocation) {
					toPlay();
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	protected void toPlay() {
		try {

			upnpService.getControlPoint().execute(new Play(instanceid, service) {
				@SuppressWarnings("rawtypes")
				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

				}

			});
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	protected void toPause() {
		try {
			upnpService.getControlPoint().execute(new Pause(instanceid, service) {
				@SuppressWarnings("rawtypes")
				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

				}

			});
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	protected void toStop() {
		try {
			upnpService.getControlPoint().execute(new Stop(instanceid, service) {
				@SuppressWarnings("rawtypes")
				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

				}

			});
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	protected void toSeek(String para) {
		try {
			upnpService.getControlPoint().execute(new Seek(instanceid, service, para) {
				@SuppressWarnings("rawtypes")
				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {

				}

			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected void getMediaInfo() {

		upnpService.getControlPoint().execute(new GetMediaInfo(instanceid, service) {

			@SuppressWarnings("rawtypes")
			@Override
			public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {

			}

			@SuppressWarnings("rawtypes")
			@Override
			public void received(ActionInvocation arg0, MediaInfo arg1) {

				MediaInfo mediaInfo = arg1;
				Log.i(TAG, "mediaInfo.getCurrentURI() = " + mediaInfo.getCurrentURI());
				Log.i(TAG, "mediaInfo.getCurrentURIMetaData() = " + mediaInfo.getCurrentURIMetaData());
				Log.i(TAG, "mediaInfo.getMediaDuration() = " + mediaInfo.getMediaDuration());
				Log.i(TAG, "mediaInfo.getNextURI() = " + mediaInfo.getNextURI());
				Log.i(TAG, "mediaInfo.getNextURIMetaData() = " + mediaInfo.getNextURIMetaData());
				Log.i(TAG, "mediaInfo.getNumberOfTracks() = " + mediaInfo.getNumberOfTracks());
				Log.i(TAG, "mediaInfo.getPlayMedium() = " + mediaInfo.getPlayMedium());
				Log.i(TAG, "mediaInfo.getRecordMedium() = " + mediaInfo.getRecordMedium());
				Log.i(TAG, "mediaInfo.getWriteStatus() = " + mediaInfo.getWriteStatus());

			}
		});
	}

	protected void getPositionInfo() {

		upnpService.getControlPoint().execute(new GetPositionInfo(instanceid, service) {

			@SuppressWarnings("rawtypes")
			@Override
			public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {

			}

			@SuppressWarnings("rawtypes")
			@Override
			public void received(ActionInvocation invocation, PositionInfo pInfo) {
				positionInfo = pInfo;
				time = pInfo.getElapsedPercent();
				// Log.i(TAG,"positionInfo.getAbsCount() = "+positionInfo.getAbsCount());
				// Log.i(TAG,"positionInfo.getAbsTime() = "+positionInfo.getAbsTime());
				// Log.i(TAG,"positionInfo.getElapsedPercent() = "+positionInfo.getElapsedPercent());
				// Log.i(TAG,"positionInfo.getRelCount() = "+positionInfo.getRelCount());
				// Log.i(TAG,"positionInfo.getRelTime() = "+positionInfo.getRelTime());
				// Log.i(TAG,"positionInfo.getTrackDuration() = "+positionInfo.getTrackDuration());
				// Log.i(TAG,"positionInfo.getTrackDurationSeconds() = "+positionInfo.getTrackDurationSeconds());
				// Log.i(TAG,"positionInfo.getTrackElapsedSeconds() = "+positionInfo.getTrackElapsedSeconds());
				// Log.i(TAG,"positionInfo.getTrackMetaData() = "+positionInfo.getTrackMetaData());
				// Log.i(TAG,"positionInfo.getTrackRemainingSeconds() = "+positionInfo.getTrackRemainingSeconds());
				// Log.i(TAG,"positionInfo.getTrackURI() = "+positionInfo.getTrackURI());
			}
		});

	}

	protected void getTransportInfo() {

		upnpService.getControlPoint().execute(new GetTransportInfo(instanceid, service) {

			@SuppressWarnings("rawtypes")
			@Override
			public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {

				// arg1.getStatusMessage();

			}

			@SuppressWarnings("rawtypes")
			@Override
			public void received(ActionInvocation invocation, TransportInfo tInfo) {
				transportInfo = tInfo;
				// Log.i(TAG,"transportInfo.getCurrentSpeed() = "+transportInfo.getCurrentSpeed());
				// Log.i(TAG,"transportInfo.getCurrentTransportState() = "+transportInfo.getCurrentTransportState());
				// Log.i(TAG,"transportInfo.getCurrentTransportStatus() = "+transportInfo.getCurrentTransportStatus());

			}

		});

	}

	protected void getMuteAction() {

		upnpService.getControlPoint().execute(new GetMute(instanceid, rService) {
			@SuppressWarnings("rawtypes")
			public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

			}

			@SuppressWarnings("rawtypes")
			public void received(ActionInvocation paramAnonymousActionInvocation, boolean paramAnonymousBoolean) {
				svolume = paramAnonymousBoolean;
			}
		});
	}

	protected void getVolumeAction() {
		upnpService.getControlPoint().execute(new GetVolume(instanceid, rService) {
			@SuppressWarnings("rawtypes")
			public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

			}

			@SuppressWarnings("rawtypes")
			public void received(ActionInvocation paramAnonymousActionInvocation, int paramAnonymousInt) {
				volume = paramAnonymousInt;
			}
		});
	}

	protected void setMuteAction(final boolean paramBoolean) {

		upnpService.getControlPoint().execute(new SetMute(instanceid, rService, paramBoolean) {
			@SuppressWarnings("rawtypes")
			public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

			}

			@SuppressWarnings("rawtypes")
			public void success(ActionInvocation paramAnonymousActionInvocation) {

			}
		});
	}

	protected void setVolumeAction(int paramInt) {

		upnpService.getControlPoint().execute(new SetVolume(instanceid, rService, paramInt) {
			@SuppressWarnings("rawtypes")
			public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

			}

			@SuppressWarnings("rawtypes")
			public void success(ActionInvocation paramAnonymousActionInvocation) {

			}
		});
	}
}