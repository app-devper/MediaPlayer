package com.appdevper.mediaplayer.app;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

/**
 * Created by worawit on 11/14/15.
 */
public class UpnpPlayer {
    private Boolean rPlaying = false;
    private AndroidUpnpService upnpService;
    private Service<?, ?> service;
    private Service<?, ?> serviceAudio;
    private Playback playback;

    public UpnpPlayer(AndroidUpnpService upnpService) {
        this.upnpService = upnpService;
    }

    public void setService(Service<?, ?> service) {
        this.service = service;
    }

    public void setServiceAudio(Service<?, ?> serviceAudio) {
        this.serviceAudio = serviceAudio;
    }

    public void setPlayback(Playback playback) {
        this.playback = playback;
    }

    public Boolean isPlaying() {
        return rPlaying;
    }


    public void setURI(String uri, final Boolean autoPlay) {
        if (uri == null) {
            return;
        }

        toStop();

        try {
            upnpService.getControlPoint().execute(new SetAVTransportURI(service, uri) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

                @SuppressWarnings("rawtypes")
                @Override
                public void success(ActionInvocation invocation) {
                    if (autoPlay)
                        toPlay();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        playback.onPlay();
    }

    public long getCurrentMediaPosition() {
        final long[] current = {0};
        try {
            upnpService.getControlPoint().execute(new GetPositionInfo(service) {
                @Override
                public void received(ActionInvocation actionInvocation, PositionInfo positionInfo) {
                    current[0] = positionInfo.getTrackElapsedSeconds();
                }

                @Override
                public void failure(ActionInvocation actionInvocation, UpnpResponse upnpResponse, String s) {

                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return current[0];
    }

    public void getPositionInfo() {
        try {
            upnpService.getControlPoint().execute(new GetPositionInfo(service) {
                @Override
                public void received(ActionInvocation actionInvocation, PositionInfo positionInfo) {
                    positionInfo.getTrackDurationSeconds();
                }

                @Override
                public void failure(ActionInvocation actionInvocation, UpnpResponse upnpResponse, String s) {

                }
            });

        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    public void toPlay() {
        try {
            upnpService.getControlPoint().execute(new Play(service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

                @Override
                public void success(ActionInvocation invocation) {
                    rPlaying = true;
                }
            });
            playback.onPlay();
        } catch (Exception e) {
            e.printStackTrace();
            rPlaying = false;
        }

    }

    public void toPause() {
        try {
            upnpService.getControlPoint().execute(new Pause(service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }

            });
            rPlaying = false;
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void toStop() {
        try {
            upnpService.getControlPoint().execute(new Stop(service) {
                @SuppressWarnings("rawtypes")
                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    rPlaying = false;
                }
            });
            rPlaying = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void toSeek(String para) throws Exception {
        upnpService.getControlPoint().execute(new Seek(service, para) {
            @SuppressWarnings("rawtypes")
            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                rPlaying = false;
            }

            @Override
            public void success(ActionInvocation invocation) {
                rPlaying = true;
            }
        });
        playback.onPlay();
    }

    public boolean getMuteAction() {
        final boolean[] svolume = {false};
        upnpService.getControlPoint().execute(new GetMute(serviceAudio) {

            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void received(ActionInvocation paramAnonymousActionInvocation, boolean paramAnonymousBoolean) {
                svolume[0] = paramAnonymousBoolean;
            }
        });
        return svolume[0];
    }

    public int getVolumeAction() {
        final int[] volume = {0};
        upnpService.getControlPoint().execute(new GetVolume(serviceAudio) {
            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void received(ActionInvocation paramAnonymousActionInvocation, int paramAnonymousInt) {
                volume[0] = paramAnonymousInt;
            }
        });
        return volume[0];
    }

    public boolean setMuteAction(final boolean paramBoolean) {
        final boolean[] svolume = {false};
        upnpService.getControlPoint().execute(new SetMute(serviceAudio, paramBoolean) {
            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void success(ActionInvocation paramAnonymousActionInvocation) {
                svolume[0] = true;
            }
        });
        return svolume[0];
    }

    public boolean setVolumeAction(int paramInt) {
        final boolean[] svolume = {false};
        upnpService.getControlPoint().execute(new SetVolume(serviceAudio, paramInt) {
            @SuppressWarnings("rawtypes")
            public void failure(ActionInvocation paramAnonymousActionInvocation, UpnpResponse paramAnonymousUpnpResponse, String paramAnonymousString) {

            }

            @SuppressWarnings("rawtypes")
            public void success(ActionInvocation paramAnonymousActionInvocation) {
                svolume[0] = true;
            }
        });
        return svolume[0];
    }

    public interface Playback {
        void onPlay();
    }
}
