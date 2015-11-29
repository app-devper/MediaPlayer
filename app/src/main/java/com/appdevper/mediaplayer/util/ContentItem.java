package com.appdevper.mediaplayer.util;

import com.appdevper.mediaplayer.R;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.AudioBook;
import org.fourthline.cling.support.model.item.AudioBroadcast;
import org.fourthline.cling.support.model.item.AudioItem;
import org.fourthline.cling.support.model.item.ImageItem;
import org.fourthline.cling.support.model.item.Item;
import org.fourthline.cling.support.model.item.Movie;
import org.fourthline.cling.support.model.item.MusicTrack;
import org.fourthline.cling.support.model.item.MusicVideoClip;
import org.fourthline.cling.support.model.item.Photo;
import org.fourthline.cling.support.model.item.VideoBroadcast;
import org.fourthline.cling.support.model.item.VideoItem;

import java.net.URI;
import java.util.List;

public class ContentItem {
    private Device device;
    private Service service;
    private DIDLObject content;
    private String id;
    private Boolean isContainer;
    private int defaultResource;
    private String className;
    private String type = "";
    private String resourceUri;
    private String mimeType;
    private String subtitle = "";
    private MusicTrack musicItem;
    private String duration = "";

    public ContentItem(Container container, Service service) {
        this.service = service;
        this.device = service.getDevice();
        this.content = container;
        this.id = container.getId();
        this.isContainer = true;
        this.subtitle = "Folder";
        this.defaultResource = R.drawable.ic_folder;
    }

    public ContentItem(Item item, Service service) {
        this.service = service;
        this.content = item;
        this.className = content.getClass().getName();
        this.id = item.getId();
        this.isContainer = false;
        populateMoreInfoFromItem();
    }

    public Container getContainer() {
        if (isContainer)
            return (Container) content;
        else {
            return null;
        }
    }

    public Item getItem() {
        if (isContainer)
            return null;
        else
            return (Item) content;
    }

    public List<Res> getRes() {
        return content.getResources();
    }

    public Service getService() {
        return service;
    }

    public String getAlbumArtworkUri() {
        try {
            URI localURI = this.content.getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
            if (localURI == null) {
                return null;
            }
            String str2;
            if (localURI.isAbsolute())
                str2 = localURI.toString();
            else
                str2 = normalizeUri(localURI);
            return str2;
        } catch (Exception localException) {
            return null;
        }
    }

    public String getIconArtworkUri() {
        try {
            URI localURI = this.content.getFirstPropertyValue(DIDLObject.Property.UPNP.ICON.class);
            if (localURI == null) {
                return null;
            }
            String str2;
            if (localURI.isAbsolute())
                str2 = localURI.toString();
            else
                str2 = normalizeUri(localURI);
            return str2;
        } catch (Exception localException) {
            return null;
        }
    }

    private String normalizeUri(URI paramURI) {
        if (device == null) {
            return null;
        }
        //this._log.info("Uri value to normalize : " + paramURI.toString());
        if ((device instanceof RemoteDevice))
            return ((RemoteDevice) device).normalizeURI(paramURI).toString();
        return paramURI.toString();
    }

    public Boolean isContainer() {
        return isContainer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ContentItem that = (ContentItem) o;

        if (!id.equals(that.id)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return content.hashCode();
    }

    @Override
    public String toString() {
        return content.getTitle();
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getDefaultResource() {
        return defaultResource;
    }

    private void populateMoreInfoFromItem() {
        Res firstResource = content.getFirstResource();
        this.resourceUri = firstResource.getValue();
        this.mimeType = firstResource.getProtocolInfo().getContentFormatMimeType().toString();
        this.subtitle = this.mimeType;
        setDuration(firstResource);
        if (getMusicItem()) {
            this.musicItem = new MusicTrack((Item) this.content);
            this.subtitle = this.musicItem.getFirstArtist().getName();
            this.defaultResource = R.drawable.nocover_audio;
            this.type = "audio";
            return;
        }

        if (isAudioItemOrDerivative()) {
            this.defaultResource = R.drawable.nocover_audio;
            this.type = "audio";
            return;
        }
        if (isImageItemOrDerivative()) {
            this.defaultResource = R.drawable.ic_image_item;
            this.type = "image";
            return;
        }
        if (isVideoItemOrDerivative()) {
            this.defaultResource = R.drawable.nocover_audio;
            this.type = "video";
            return;
        }
    }

    private void setDuration(Res firstResource) {
        if (isImageItemOrDerivative()) {
            return;
        }
        String[] time = firstResource.getDuration().split(".000");
        this.duration = time[0];

    }

    public String getType() {
        return type;
    }

    private boolean getMusicItem() {
        String str = MusicTrack.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    public String getResourceUri() {
        return resourceUri;
    }

    public boolean isAudioItemOrDerivative() {
        return (isAudioItem()) || (isAudioBookItem()) || (isAudioBroadcastItem()) || (getMusicItem());
    }

    private boolean isAudioItem() {
        String str = AudioItem.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isAudioBookItem() {
        String str = AudioBook.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isAudioBroadcastItem() {
        String str = AudioBroadcast.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isPhotoItem() {
        String str = Photo.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    public boolean isImageItemOrDerivative() {
        return (isImageItem()) || (isPhotoItem());
    }

    private boolean isImageItem() {
        String str = ImageItem.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    public boolean isVideoItemOrDerivative() {
        return (isVideoItem()) || (isVideoBroadcastItem()) || (isMusicVideoClipItem()) || (isMovieItem());
    }

    private boolean isVideoItem() {
        String str = VideoItem.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isVideoBroadcastItem() {
        String str = VideoBroadcast.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isMusicVideoClipItem() {
        String str = MusicVideoClip.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    private boolean isMovieItem() {
        String str = Movie.class.getName();
        return (str != null) && (str.equalsIgnoreCase(this.className));
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getDuration() {
        return duration;
    }

}
