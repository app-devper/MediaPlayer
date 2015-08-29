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
    public int DefaultResource;
    private String ClassName;
    private String type = "";
    private String ResourceUri;
    private String MimeType;
    private String Subtitle="";
    private long Size;
    private String ArtworkUri;
    private MusicTrack MusicItem;
    private String Duration=""; 
	
	public ContentItem(Container container, Service service) {
		
		this.service = service;
		this.content = container;
		this.id = container.getId();
		this.isContainer = true;
		this.Subtitle ="Folder";
		this.DefaultResource = R.drawable.ic_folder;
		
	}
	
	public ContentItem(Item item, Service service) {
		
		this.service = service;
		this.content = item;
		this.ClassName = content.getClass().getName();
		this.id = item.getId();
		this.isContainer = false;
		populateMoreInfoFromItem();
	}
	
	public Container getContainer() {
		if(isContainer)
			return (Container) content;
		else {
			return null;
		}
	}
	
	public Item getItem() {
		if(isContainer)
			return null;
		else
			return (Item)content;
	}
	
	public  List<Res> getRes() {
	    return content.getResources();
    }
    
	public Service getService() {
		return service;
	}
	
	public String getAlbumArtworkUri()
	  {
	    try
	    {
	      URI localURI = (URI)this.content.getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
	      if (localURI == null){
	        //this._log.info("No artwork found for music track: " + this.Title);
	        return null;
	      }
	      String str2;
	      if (localURI.isAbsolute())
	        str2 = localURI.toString();
	      else
	        str2 = normalizeUri(localURI);
	      return str2;
	    }
	    catch (Exception localException)
	    {
	        return null;
	    }
	  }
	
	public String getIconArtworkUri(){
      try
      {
        URI localURI = (URI)this.content.getFirstPropertyValue(DIDLObject.Property.UPNP.ICON.class);
        if (localURI == null){
          //this._log.info("No artwork found for music track: " + this.Title);
          return null;
        }
        String str2;
        if (localURI.isAbsolute())
          str2 = localURI.toString();
        else
          str2 = normalizeUri(localURI);
        return str2;
      }
      catch (Exception localException)
      {
          return null;
      }
    }
	
	private String normalizeUri(URI paramURI)
	  {
	    if (device == null)
	    {
	     // this._log.error("No active device...");
	      return null;
	    }
	    //this._log.info("Uri value to normalize : " + paramURI.toString());
	    if ((device instanceof RemoteDevice))
	      return ((RemoteDevice)device).normalizeURI(paramURI).toString();
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
        return MimeType;
    }

    public void setMimeType(String mimeType) {
        MimeType = mimeType;
    }

    private void populateMoreInfoFromItem(){
        Res firstResource = content.getFirstResource();
        this.ResourceUri = firstResource.getValue();
        this.MimeType = firstResource.getProtocolInfo().getContentFormatMimeType().toString();
        this.Subtitle = this.MimeType;
        setDuration(firstResource);
        if (isMusicItem())
        {
          this.ArtworkUri = getAlbumArtworkUri();
          this.MusicItem = new MusicTrack((Item)this.content); 
          this.Subtitle = this.MusicItem.getFirstArtist().getName();
          this.DefaultResource = R.drawable.nocover_audio;
          this.type ="audio";
          return;
        }
        
        if (isAudioItemOrDerivative()){
          this.DefaultResource = R.drawable.nocover_audio; 
          this.type ="audio";
          return;
        }
        if (isImageItemOrDerivative()){
          this.DefaultResource = R.drawable.ic_image_item;
          this.type ="image";
          return;
        }
        if (isVideoItemOrDerivative()){
          this.DefaultResource = R.drawable.nocover_audio;
          this.type ="video";
          return;
        }
    }
    
    private void setDuration(Res firstResource) {
        if (isImageItemOrDerivative()){
          return;
        } 
        String[]  time = firstResource.getDuration().split(".000");
        this.Duration = time[0];
            
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private boolean isMusicItem()
    {
      String str = MusicTrack.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    } 
    
    
    public String getResourceUri() {
        return ResourceUri;
    }

    public boolean isAudioItemOrDerivative()
    {
      return (isAudioItem()) || (isAudioBookItem()) || (isAudioBroadcastItem()) || (isMusicItem());
    }
    private boolean isAudioItem()
    {
      String str = AudioItem.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isAudioBookItem()
    {
      String str = AudioBook.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isAudioBroadcastItem()
    {
      String str = AudioBroadcast.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isPhotoItem()
    {
      String str = Photo.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    public boolean isImageItemOrDerivative()
    {
      return (isImageItem()) || (isPhotoItem());
    }
    private boolean isImageItem()
    {
      String str = ImageItem.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    public boolean isVideoItemOrDerivative()
    {
      return (isVideoItem()) || (isVideoBroadcastItem()) || (isMusicVideoClipItem()) || (isMovieItem());
    }
    private boolean isVideoItem()
    {
      String str = VideoItem.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isVideoBroadcastItem()
    {
      String str = VideoBroadcast.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isMusicVideoClipItem()
    {
      String str = MusicVideoClip.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }
    private boolean isMovieItem()
    {
      String str = Movie.class.getName();
      return (str != null) && (str.equalsIgnoreCase(this.ClassName));
    }

    public String getSubtitle() {
        return Subtitle;
    }

    public void setSubtitle(String subtitle) {
        Subtitle = subtitle;
    }

    public String getDuration() {
        return Duration;
    }

    public void setDuration(String duration) {
        Duration = duration;
    }

}
