package com.appdevper.mediaplayer.activity;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceFragment;
import android.preference.TwoStatePreference;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.appdevper.mediaplayer.BuildConfig;
import com.appdevper.mediaplayer.R;
import com.appdevper.mediaplayer.app.ServerSettings;
import com.appdevper.mediaplayer.app.ServerUpnpService;
import com.appdevper.mediaplayer.loader.FileCache;

public class SettingPreferenceActivity extends AppCompatActivity {

    private static String TAG = SettingPreferenceActivity.class.getSimpleName();

    private Handler mHandler = new Handler();
    private Toolbar mToolbar;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        initializeToolbar();
        getFragmentManager().beginTransaction().replace(R.id.container, new MyPreferenceFragment()).commit();
    }

    protected void initializeToolbar() {
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        if (mToolbar == null) {
            throw new IllegalStateException("Layout is required to include a Toolbar with id " + "'toolbar'");
        }

        //mToolbar.setNavigationIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
    }

    public class MyPreferenceFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.preferences);

            TwoStatePreference runningPref = findPref("running_switch");

            updateRunningState();

            runningPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((Boolean) newValue) {
                        startServer();
                    } else {
                        stopServer();
                    }
                    return true;
                }
            });

            Preference selectPref = findPref("media_select");
            selectPref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    if (!ServerUpnpService.isRunning()) {
                        startActivity(new Intent(SettingPreferenceActivity.this, SelectMediaActivity.class));
                    }
                    return false;
                }
            });

            EditTextPreference deName_pref = findPref("deName");
            deName_pref.setSummary(ServerSettings.getDeviceName());
            deName_pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String newNameString = (String) newValue;
                    if (preference.getSummary().equals(newNameString))
                        return false;
                    preference.setSummary(newNameString);
                    stopServer();
                    return true;
                }
            });

            Preference prefereces = findPref("clear");
            prefereces.setOnPreferenceClickListener(new OnPreferenceClickListener() {

                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showDialog();
                    return false;
                }
            });

            Preference help = findPref("help");
            help.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Log.v(TAG, "On preference help clicked");
                    Context context = SettingPreferenceActivity.this;
                    AlertDialog ad = new AlertDialog.Builder(context).setTitle(R.string.help_dlg_title).setMessage(R.string.help_dlg_message).setPositiveButton(R.string.ok, null).create();
                    ad.show();
                    Linkify.addLinks((TextView) ad.findViewById(android.R.id.message), Linkify.ALL);
                    return true;
                }
            });

            Preference about = findPref("about");
            about.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AlertDialog ad = new AlertDialog.Builder(SettingPreferenceActivity.this).setTitle(R.string.about_dlg_title).setMessage(R.string.about_dlg_message)
                            .setPositiveButton(getText(R.string.ok), null).create();
                    ad.show();
                    Linkify.addLinks((TextView) ad.findViewById(android.R.id.message), Linkify.ALL);
                    return true;
                }
            });

            Preference version = findPref("version");
            version.setSummary(BuildConfig.VERSION_NAME);

        }

        protected <T extends Preference> T findPref(CharSequence key) {
            return (T) this.findPreference(key);
        }

        BroadcastReceiver mFsActionsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.v(TAG, "action received: " + intent.getAction());
                // action will be ACTION_STARTED or ACTION_STOPPED
                updateRunningState();
                // or it might be ACTION_FAILEDTOSTART
                final TwoStatePreference runningPref = findPref("running_switch");
                if (intent.getAction().equals(ServerUpnpService.ACTION_FAILEDTOSTART)) {
                    runningPref.setChecked(false);
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runningPref.setSummary(R.string.running_summary_failed);
                        }
                    }, 100);
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runningPref.setSummary(R.string.running_summary_stopped);
                        }
                    }, 5000);
                }
            }
        };

        public void showDialog() {
            AlertDialog.Builder alertDialog = new AlertDialog.Builder(getActivity());
            alertDialog.setTitle("WANNING!");
            alertDialog.setMessage("Do you want to clear it?");
            alertDialog.setNegativeButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    FileCache fileCache = new FileCache(SettingPreferenceActivity.this);
                    fileCache.clear();
                }
            });

            alertDialog.setPositiveButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });

            alertDialog.show();
        }

        @Override
        public void onResume() {
            super.onResume();

            updateRunningState();

            Log.v(TAG, "onResume: Registering the server actions");

            IntentFilter filter = new IntentFilter();
            filter.addAction(ServerUpnpService.ACTION_STARTED);
            filter.addAction(ServerUpnpService.ACTION_STOPPED);
            filter.addAction(ServerUpnpService.ACTION_FAILEDTOSTART);
            registerReceiver(mFsActionsReceiver, filter);
        }

        @Override
        public void onPause() {
            super.onPause();

            Log.v(TAG, "onPause: Unregistering the Server actions");
            unregisterReceiver(mFsActionsReceiver);

        }

        private void updateRunningState() {
            TwoStatePreference runningPref = findPref("running_switch");
            Preference selectPref = findPref("media_select");
            CheckBoxPreference videoPref = findPref("allow_video");
            CheckBoxPreference audioPref = findPref("allow_audio");
            CheckBoxPreference imagePref = findPref("allow_image");
            if (ServerUpnpService.isRunning()) {
                videoPref.setEnabled(false);
                audioPref.setEnabled(false);
                imagePref.setEnabled(false);
                selectPref.setEnabled(false);
                runningPref.setChecked(true);
                runningPref.setSummary(R.string.running_summary_started);
                selectPref.setSummary(R.string.running_select_summary);
            } else {
                runningPref.setChecked(false);
                runningPref.setSummary(R.string.running_summary_stopped);
                selectPref.setSummary(R.string.select_summary);
                videoPref.setEnabled(true);
                audioPref.setEnabled(true);
                imagePref.setEnabled(true);
                selectPref.setEnabled(true);
            }
        }

        private void startServer() {
            sendBroadcast(new Intent(ServerUpnpService.ACTION_START_SERVER));
        }

        private void stopServer() {
            sendBroadcast(new Intent(ServerUpnpService.ACTION_STOP_SERVER));
        }
    }

    @Override
    public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_right);
    }

}
