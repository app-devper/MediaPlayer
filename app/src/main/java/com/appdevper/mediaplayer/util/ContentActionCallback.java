package com.appdevper.mediaplayer.util;

import java.util.logging.Logger;

import org.fourthline.cling.model.action.ActionException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ErrorCode;
import org.fourthline.cling.support.contentdirectory.callback.Browse;
import org.fourthline.cling.support.model.BrowseFlag;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.SortCriterion;
import org.fourthline.cling.support.model.container.Container;
import org.fourthline.cling.support.model.item.Item;

import android.app.Activity;
import android.app.ProgressDialog;
import android.widget.ArrayAdapter;
import android.widget.Toast;

public class ContentActionCallback extends Browse {

	private static Logger log = Logger.getLogger(ContentActionCallback.class.getName());

	private Service service;
	private Container container;
	private ArrayAdapter<ContentItem> listAdapter;
	private Activity activity;
	private ProgressDialog dialog;

	public ContentActionCallback(Activity activity, Service service, Container container, ArrayAdapter<ContentItem> listadapter) {
		super(service, container.getId(), BrowseFlag.DIRECT_CHILDREN, "*", 0, null, new SortCriterion(true, "dc:title"));
		this.activity = activity;
		this.service = service;
		this.container = container;
		this.listAdapter = listadapter;
		this.dialog = new ProgressDialog(activity);
		dialog.setMessage("Please Wait Loading");
		dialog.show();
	}

	public void received(final ActionInvocation actionInvocation, final DIDLContent didl) {

		log.fine("Received browse action DIDL descriptor, creating tree nodes");

		activity.runOnUiThread(new Runnable() {
			public void run() {

				try {
					listAdapter.clear();
					// Containers first
					for (Container childContainer : didl.getContainers()) {
						log.fine("add child container " + childContainer.getTitle());
						listAdapter.add(new ContentItem(childContainer, service));
					}
					// Now items
					for (Item childItem : didl.getItems()) {
						log.fine("add child item" + childItem.getTitle());
						listAdapter.add(new ContentItem(childItem, service));
					}

				} catch (Exception ex) {
					log.fine("Creating DIDL tree nodes failed: " + ex);
					actionInvocation.setFailure(new ActionException(ErrorCode.ACTION_FAILED, "Can't create list childs: " + ex, ex));
					failure(actionInvocation, null);
				}
				dialog.hide();
				dialog.dismiss();
			}
		});
	}
	
	@SuppressWarnings("rawtypes")
	@Override
    public void failure(ActionInvocation actionInvocation, UpnpResponse upnpResponse, String s) {
        //log.fine("Creating DIDL tree nodes failed: " + actionInvocation);
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    dialog.hide();
                    dialog.dismiss();
                    Toast.makeText(activity, "Can't create list childs.", Toast.LENGTH_SHORT).show();
                }catch (Exception e){
                    Toast.makeText(activity, "Can't create list childs.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

	@Override
	public void updateStatus(Status arg0) {
	
	}

}
