package com.appdevper.mediaplayer.mediaserver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;

import com.appdevper.mediaplayer.R;

import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.DefaultServiceManager;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

public class MediaServer {

    private InetSocketAddress inetSocket;
    // private Server server;
    private UDN udn = new UDN("Kak-MediaServer");
    private LocalDevice localDevice;

    private final static String deviceType = "MediaServer";
    private final static int version = 1;
    private final static String LOGTAG = "iKak-MediaServer";
    private final static int port = 8192;
    private InetAddress localAddress;
    private Context c;
    private HttpServer hServer;

    @SuppressWarnings("unchecked")
    public MediaServer(InetAddress localAddress, Context context, String name) throws ValidationException, InterruptedException {
        this.c = context;
        this.localAddress = localAddress;

        DeviceType type = new UDADeviceType(deviceType, version);
        DeviceDetails details = new DeviceDetails(name, new ManufacturerDetails(android.os.Build.MANUFACTURER), new ModelDetails("iKak", "MediaServer Android", "v1"));

        LocalService<ContentDirectoryService> service = new AnnotationLocalServiceBinder().read(ContentDirectoryService.class);
        service.setManager(new DefaultServiceManager<>(service, ContentDirectoryService.class));

        Icon[] ic = new Icon[]{createDefaultDeviceIcon()};

        localDevice = new LocalDevice(new DeviceIdentity(udn), type, details, ic, service);


        Log.v(LOGTAG, "MediaServer device created: ");
        Log.v(LOGTAG, "friendly name: " + details.getFriendlyName());
        Log.v(LOGTAG, "manufacturer: " + details.getManufacturerDetails().getManufacturer());
        Log.v(LOGTAG, "model: " + details.getModelDetails().getModelName());
        Log.v(LOGTAG, "URL: " + details.getBaseURL());

        try {
            hServer = new HttpServer(port);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
            System.exit(-1);
        }

        Log.v(LOGTAG, "Started Http Server on port " + port);
    }

    public LocalDevice getDevice() {
        return localDevice;
    }

    public String getAddress() {
        return localAddress.getHostAddress() + ":" + port;
    }

    public void stop() {
        hServer.stop();
    }

//    class StartServer extends AsyncTask<String, Void, Void> {
//        @Override
//        protected Void doInBackground(String... svcs) {
//            server = new Server(inetSocket);
//            try {
//                ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
//                context.setContextPath("/");
//                server.setHandler(context);
//                //context.addServlet(new ServletHolder(new HelloServlet()), "/*");
//                server.start();
//
//                server.join();
//            } catch (Exception e) {
//                e.printStackTrace();
//                System.exit(-1);
//            }
//            return null;
//        }
//    }

//    public void stop() {
//        try {
//            server.stop();
//        } catch (Exception e) {
//            e.printStackTrace();
//            server = null;
//        }
//    }

    protected Icon createDefaultDeviceIcon() {

        BitmapDrawable bitDw = ((BitmapDrawable) c.getResources().getDrawable(R.drawable.ic_launcher));
        Bitmap bitmap = bitDw.getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        byte[] imageInByte = stream.toByteArray();
        System.out.println("........length......" + imageInByte);
        ByteArrayInputStream bis = new ByteArrayInputStream(imageInByte);

        try {
            return new Icon("image/png", 120, 120, 8, URI.create("icon.png"), bis);
        } catch (IOException ex) {
            throw new RuntimeException("Could not load icon", ex);
        }
    }

}
