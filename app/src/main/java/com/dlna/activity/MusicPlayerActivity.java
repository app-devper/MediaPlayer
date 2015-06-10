package com.dlna.activity;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.GetTransportInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportInfo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dlna.R;
import com.dlna.fragment.ServerFragment;
import com.dlna.loader.ImageLoader;
import com.dlna.util.ContentItem;
import com.dlna.util.Utils;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

public class MusicPlayerActivity extends ActionBarActivity implements SeekBar.OnSeekBarChangeListener {

	private ImageView btnPlay;
	// private ImageButton btnForward;
	// private ImageButton btnBackward;
	private ImageView btnNext;
	private ImageView btnPrevious;
	// private ImageButton btnPlaylist;
	private ImageView btnRepeat;
	private ImageView btnShuffle;
	private SeekBar songProgressBar;
	private TextView songTitleLabel;
	private TextView songCurrentDurationLabel;
	private TextView songTotalDurationLabel;
	private ImageView imgPlay;
	// Handler to update UI timer, progress bar etc,.
	private Handler mHandler = new Handler();;

	private int currentSongIndex = 0;
	private boolean isShuffle = false;
	private boolean isRepeat = false;

	private ImageLoader _imgDownloader;

	private ContentItem contentItem;

	private TextView subTitle;
	private AdRequest adRequest;
	private ImageView btnStop;
	private InterstitialAd interstitialAd;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_player);

		// All player buttons
		btnPlay = (ImageView) findViewById(R.id.btnPlay);
		btnStop = (ImageView) findViewById(R.id.btnStop);
		// btnForward = (ImageButton) findViewById(R.id.btnForward);
		// btnBackward = (ImageButton) findViewById(R.id.btnBackward);
		btnNext = (ImageView) findViewById(R.id.btnForward);
		btnPrevious = (ImageView) findViewById(R.id.btnBackward);
		// btnPlaylist = (ImageButton) findViewById(R.id.btnPlaylist);
		btnRepeat = (ImageView) findViewById(R.id.btnRepeat);
		btnShuffle = (ImageView) findViewById(R.id.btnShuffle);
		songProgressBar = (SeekBar) findViewById(R.id.songProgressBar);
		songTitleLabel = (TextView) findViewById(R.id.songTitle);
		subTitle = (TextView) findViewById(R.id.subTitle);
		songCurrentDurationLabel = (TextView) findViewById(R.id.songCurrentDurationLabel);
		songTotalDurationLabel = (TextView) findViewById(R.id.songTotalDurationLabel);
		imgPlay = (ImageView) findViewById(R.id.imgPlay);

		AdView adView = (AdView) findViewById(R.id.adView);
		adRequest = new AdRequest.Builder().addTestDevice("FC30F813719E71A110A143F708B6C212").addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();

		adView.loadAd(adRequest);

		btnRepeat.setVisibility(View.GONE);
		btnShuffle.setVisibility(View.GONE);

		_imgDownloader = new ImageLoader(this);

		// Listeners
		songProgressBar.setOnSeekBarChangeListener(this); // Important

		currentSongIndex = 0;
		// play selected song
		initContent();

		btnPlay.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (ShareData.getrDevice().getIslocal()) {
					contentItem = AppController.getContent();
					if (contentItem != null) {
						if (contentItem.getType().equals("audio")) {
							if (AppController.isPlaying()) {
								AppController.pauseMusic();
							} else {
								AppController.playMusic();
							}
						} else {
							AppController.setMedia(contentItem);
						}
					}
				} else {

					if (AppController.toPlaying()) {
						AppController.toPause();
						btnPlay.setImageResource(R.drawable.btn_play);
					} else {
						AppController.toPlay();
						btnPlay.setImageResource(R.drawable.btn_pause);
					}

				}
			}
		});

		btnStop.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (ShareData.getrDevice().getIslocal()) {
					AppController.stopMusic();
				} else {
					AppController.toStop();
					btnPlay.setImageResource(R.drawable.btn_play);
				}
			}
		});

		btnNext.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (ShareData.getrDevice().getIslocal()) {
					contentItem = AppController.nextMusic();
				} else {
					contentItem = AppController.toNextMusic();
				}
				setContent(contentItem);
			}
		});

		btnPrevious.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View arg0) {
				if (ShareData.getrDevice().getIslocal()) {
					contentItem = AppController.prevMusic();

				} else {
					contentItem = AppController.toPrevMusic();
				}
				setContent(contentItem);
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
					// make repeat to true
					isShuffle = true;
					Toast.makeText(getApplicationContext(), "Shuffle is ON", Toast.LENGTH_SHORT).show();
					// make shuffle to false
					isRepeat = false;
					btnShuffle.setImageResource(R.drawable.btn_shuffle_focused);
					btnRepeat.setImageResource(R.drawable.btn_repeat);
				}
			}
		});
		
		interstitialAd = new InterstitialAd(this);
		interstitialAd.setAdUnitId(ShareData.AD_UNIT_ID);

		interstitialAd.setAdListener(new AdListener() {
			@Override
			public void onAdLoaded() {
				Log.d("AdListener", "onAdLoaded");
				interstitialAd.show();
			}

			@Override
			public void onAdFailedToLoad(int errorCode) {
				String message = String.format("onAdFailedToLoad (%s)", Utils.getErrorReason(errorCode));
				Log.d("AdListener", message);
			}
		});
        if(ShareData.adCall %3 == 0){
        	interstitialAd.loadAd(adRequest);
        }
        ShareData.adCall++;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode == 100) {
			currentSongIndex = data.getExtras().getInt("songIndex");
			if (ShareData.getrDevice().getIslocal()) {
				AppController.setMedia(ShareData.aContent.get(currentSongIndex));
			} else {
				AppController.sendRender(ShareData.aContent.get(currentSongIndex));
			}
			setContent(ShareData.aContent.get(currentSongIndex));
		}
		if (resultCode == 200) {
			if (!ShareData.getrDevice().getIslocal()) {
				AppController.setService();
				AppController.stopMusic();
				AppController.sendRender(AppController.getContent());
			} else {
				AppController.toStop();
				AppController.setMedia(AppController.getContent());
			}
			ServerFragment.getInstance().getAdapter().notifyDataSetChanged();
		}

	}

	public void initContent() {
		// Play song
		contentItem = AppController.getContent();

		try {
			setContent(contentItem);
			if (contentItem == null) {
				if (ShareData.aContent.size() > 0) {
					if (ShareData.getrDevice().getIslocal()) {
						AppController.setMedia(ShareData.aContent.get(0));
					} else {
						AppController.sendRender(ShareData.aContent.get(0));
					}
					setContent(ShareData.aContent.get(0));
				}
			}
			songProgressBar.setProgress(0);
			songProgressBar.setMax(100);

			// Updating progress bar
			updateProgressBar();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void setContent(ContentItem contentItem) {
		if (contentItem != null) {
			String songTitle = contentItem.getItem().getTitle();
			songTitleLabel.setText(songTitle);
			subTitle.setText(contentItem.getSubtitle());
			_imgDownloader.setImage(contentItem.DefaultResource);
			imgPlay.setImageResource(contentItem.DefaultResource);
			if (contentItem.getType().equals("audio") || contentItem.getType().equals("video")) {
				_imgDownloader.DisplayImage(contentItem.getAlbumArtworkUri(), imgPlay);
			} else {
				_imgDownloader.DisplayImage(contentItem.getResourceUri(), imgPlay);
			}
		}
	}

	/**
	 * Update timer on seekbar
	 * */
	public void updateProgressBar() {
		mHandler.postDelayed(mUpdateTimeTask, 100);
	}

	/**
	 * Background Runnable thread
	 * */
	private Runnable mUpdateTimeTask = new Runnable() {
		public void run() {

			if (ShareData.getrDevice().getIslocal()) {
				long totalDuration = AppController.getDuration();
				long currentDuration = AppController.getCurrentPosition();

				// Displaying Total Duration time
				songTotalDurationLabel.setText("" + Utils.milliSecondsToTimer(totalDuration));
				// Displaying time completed playing
				songCurrentDurationLabel.setText("" + Utils.milliSecondsToTimer(currentDuration));

				// Updating progress bar
				time = (int) (Utils.getProgressPercentage(currentDuration, totalDuration));
				songProgressBar.setProgress(time);

				if (AppController.isPlaying()) {
					btnPlay.setImageResource(R.drawable.btn_pause);
				} else {
					btnPlay.setImageResource(R.drawable.btn_play);
				}
				if (time < 99) {
					mHandler.postDelayed(this, 100);
				} else {
					mHandler.removeCallbacks(mUpdateTimeTask);
					time = 0;
					contentItem = AppController.nextMusic();
					setContent(contentItem);
					updateProgressBar();
				}

			} else {
				try {
					getPositionInfo();
					long totalDuration = positionInfo.getTrackDurationSeconds();
					long currentDuration = positionInfo.getTrackElapsedSeconds();
					// Displaying Total Duration time
					songTotalDurationLabel.setText("" + Utils.secondsToTimer(totalDuration));
					// Displaying time completed playing
					songCurrentDurationLabel.setText("" + Utils.secondsToTimer(currentDuration));
					time = positionInfo.getElapsedPercent();
					songProgressBar.setProgress(time);
				} catch (Exception e) {
					e.printStackTrace();

				}

				// try {
				// getTransportInfo();
				// if
				// (transportInfo.getCurrentTransportState().equals(TransportState.PLAYING))
				// {
				// btnPlay.setImageResource(R.drawable.btn_pause);
				// } else if
				// (transportInfo.getCurrentTransportState().equals(TransportState.PAUSED_PLAYBACK))
				// {
				// btnPlay.setImageResource(R.drawable.btn_play);
				// }
				// } catch (Exception e) {
				// e.printStackTrace();
				// }

				if (time < 100) {
					mHandler.postDelayed(this, 500);
				} else {
					mHandler.removeCallbacks(mUpdateTimeTask);
					time = 0;
					contentItem = AppController.toNextMusic();
					setContent(contentItem);
					updateProgressBar();
				}
			}
		}
	};
	protected PositionInfo positionInfo = new PositionInfo();
	protected int time = 0;
	protected TransportInfo transportInfo = new TransportInfo();

	private static String TAG = MusicPlayerActivity.class.getSimpleName();

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
			Intent i = new Intent(getApplicationContext(), RenderListActivity.class);
			startActivityForResult(i, 200);
			break;
		case R.id.playlist:
			Intent i1 = new Intent(getApplicationContext(), PlayListActivity.class);
			startActivityForResult(i1, 100);
			break;
		}

		return false;
	}

	/**
	 * 
	 * */
	@Override
	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {

	}

	/**
	 * When user starts moving the progress handler
	 * */
	@Override
	public void onStartTrackingTouch(SeekBar seekBar) {
		// remove message Handler from updating progress bar
		mHandler.removeCallbacks(mUpdateTimeTask);
	}

	/**
	 * When user stops moving the progress hanlder
	 * */
	@Override
	public void onStopTrackingTouch(SeekBar seekBar) {
		mHandler.removeCallbacks(mUpdateTimeTask);
		if (ShareData.getrDevice().getIslocal()) {
			int totalDuration = AppController.getDuration();
			int currentPosition = Utils.progressToTimer(seekBar.getProgress(), totalDuration);
			AppController.seekTo(currentPosition);
		} else {
			long totalDuration = positionInfo.getTrackDurationSeconds();
			int currentPosition = Utils.progressToTimerSeconds(seekBar.getProgress(), totalDuration);
			AppController.toSeek(Utils.secondsToTimer(currentPosition));
			time = seekBar.getProgress();
			songProgressBar.setProgress(time);
		}
		// update timer progress again
		updateProgressBar();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// mp.release();
		mHandler.removeCallbacks(mUpdateTimeTask);
	}

	private void getPositionInfo() throws Exception {

		AppController.upnpService.getControlPoint().execute(new GetPositionInfo(AppController.instanceid, AppController.service) {

			@SuppressWarnings("rawtypes")
			@Override
			public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {

			}

			@SuppressWarnings("rawtypes")
			@Override
			public void received(ActionInvocation invocation, PositionInfo pInfo) {
				positionInfo = pInfo;
				Log.i(TAG, "positionInfo.getAbsCount() = " + positionInfo.getAbsCount());
				Log.i(TAG, "positionInfo.getAbsTime() = " + positionInfo.getAbsTime());
				Log.i(TAG, "positionInfo.getElapsedPercent() = " + positionInfo.getElapsedPercent());
				Log.i(TAG, "positionInfo.getRelCount() = " + positionInfo.getRelCount());
				Log.i(TAG, "positionInfo.getRelTime() = " + positionInfo.getRelTime());
				Log.i(TAG, "positionInfo.getTrackDuration() = " + positionInfo.getTrackDuration());
				Log.i(TAG, "positionInfo.getTrackDurationSeconds() = " + positionInfo.getTrackDurationSeconds());
				Log.i(TAG, "positionInfo.getTrackElapsedSeconds() = " + positionInfo.getTrackElapsedSeconds());
				Log.i(TAG, "positionInfo.getTrackMetaData() = " + positionInfo.getTrackMetaData());
				Log.i(TAG, "positionInfo.getTrackRemainingSeconds() = " + positionInfo.getTrackRemainingSeconds());
				Log.i(TAG, "positionInfo.getTrackURI() = " + positionInfo.getTrackURI());
			}
		});

	}

	private void getTransportInfo() throws Exception {

		AppController.upnpService.getControlPoint().execute(new GetTransportInfo(AppController.instanceid, AppController.service) {

			@SuppressWarnings("rawtypes")
			@Override
			public void failure(ActionInvocation arg0, UpnpResponse arg1, String arg2) {

			}

			@SuppressWarnings("rawtypes")
			@Override
			public void received(ActionInvocation invocation, TransportInfo tInfo) {
				transportInfo = tInfo;
				Log.i(TAG, "transportInfo.getCurrentSpeed() = " + transportInfo.getCurrentSpeed());
				Log.i(TAG, "transportInfo.getCurrentTransportState() = " + transportInfo.getCurrentTransportState());
				Log.i(TAG, "transportInfo.getCurrentTransportStatus() = " + transportInfo.getCurrentTransportStatus());

			}

		});

	}

	// private void getMediaInfo() {
	//
	// AppController.upnpService.getControlPoint().execute(new
	// GetMediaInfo(AppController.instanceid, AppController.service) {
	//
	// @SuppressWarnings("rawtypes")
	// @Override
	// public void failure(ActionInvocation arg0, UpnpResponse arg1, String
	// arg2) {
	//
	// }
	//
	// @SuppressWarnings("rawtypes")
	// @Override
	// public void received(ActionInvocation arg0, MediaInfo arg1) {
	//
	// MediaInfo mediaInfo = arg1;
	// Log.i(TAG, "mediaInfo.getCurrentURI() = " + mediaInfo.getCurrentURI());
	// Log.i(TAG, "mediaInfo.getCurrentURIMetaData() = " +
	// mediaInfo.getCurrentURIMetaData());
	// Log.i(TAG, "mediaInfo.getMediaDuration() = " +
	// mediaInfo.getMediaDuration());
	// Log.i(TAG, "mediaInfo.getNextURI() = " + mediaInfo.getNextURI());
	// Log.i(TAG, "mediaInfo.getNextURIMetaData() = " +
	// mediaInfo.getNextURIMetaData());
	// Log.i(TAG, "mediaInfo.getNumberOfTracks() = " +
	// mediaInfo.getNumberOfTracks());
	// Log.i(TAG, "mediaInfo.getPlayMedium() = " + mediaInfo.getPlayMedium());
	// Log.i(TAG, "mediaInfo.getRecordMedium() = " +
	// mediaInfo.getRecordMedium());
	// Log.i(TAG, "mediaInfo.getWriteStatus() = " + mediaInfo.getWriteStatus());
	//
	// }
	// });
	// }

}