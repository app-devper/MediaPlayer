package com.dlna.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import android.util.Log;

import com.google.android.gms.ads.AdRequest;

public class Utils {
	
	final static String TAG = Utils.class.getSimpleName();

    public static byte byteOfInt(int value, int which) {
        int shift = which * 8;
        return (byte) (value >> shift);
    }

    public static String ipToString(int addr, String sep) {
        // myLog.l(Log.DEBUG, "IP as int: " + addr);
        if (addr > 0) {
            StringBuffer buf = new StringBuffer();
            buf.append(byteOfInt(addr, 0)).append(sep).append(byteOfInt(addr, 1))
                    .append(sep).append(byteOfInt(addr, 2)).append(sep)
                    .append(byteOfInt(addr, 3));
            Log.d(TAG, "ipToString returning: " + buf.toString());
            return buf.toString();
        } else {
            return null;
        }
    }

    public static InetAddress intToInet(int value) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = byteOfInt(value, i);
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            // This only happens if the byte array has a bad length
            return null;
        }
    }
    public static String milliSecondsToTimer(long milliseconds){
		String finalTimerString = "";
		String secondsString = "";
		
		// Convert total duration into time
		   int hours = (int)( milliseconds / (1000*60*60));
		   int minutes = (int)(milliseconds % (1000*60*60)) / (1000*60);
		   int seconds = (int) ((milliseconds % (1000*60*60)) % (1000*60) / 1000);
		   // Add hours if there
		   if(hours > 0){
			   finalTimerString = hours + ":";
		   }
		   
		   // Prepending 0 to seconds if it is one digit
		   if(seconds < 10){ 
			   secondsString = "0" + seconds;
		   }else{
			   secondsString = "" + seconds;}
		   
		   finalTimerString = finalTimerString + minutes + ":" + secondsString;
		
		// return timer string
		return finalTimerString;
	}
	
	public static String secondsToTimer(long second){
        String finalTimerString = "";
        String secondsString = "";
        
        // Convert total duration into time
           int hours = (int)( second / (60*60));
           int minutes = (int)(second % (60*60)) / (60);
           int seconds = (int) ((second % (60*60)) % (60) );
           // Add hours if there
           if(hours > 0){
               finalTimerString = hours + ":";
           }
           
           // Prepending 0 to seconds if it is one digit
           if(seconds < 10){ 
               secondsString = "0" + seconds;
           }else{
               secondsString = "" + seconds;}
           
           finalTimerString = finalTimerString + minutes + ":" + secondsString;
        
        // return timer string
        return finalTimerString;
    }
	
	public static String secondsToTimer(int second){
        String finalTimerString = "";
        String secondsString = "";
        String minutessString = "";
        String hoursString = "";
        
        // Convert total duration into time
           int hours = (int)( second / (60*60));
           int minutes = (int)(second % (60*60)) / (60);
           int seconds = (int) ((second % (60*60)) % (60) );
           
           // Prepending 0 if it is one digit
           
           if(hours < 10){ 
               hoursString = "0" + hours;
           }else{
               hoursString = "" + hours;}           
           if(minutes < 10){ 
               minutessString = "0" + minutes;
           }else{
               minutessString = "" + minutes;}
           if(seconds < 10){ 
               secondsString = "0" + seconds;
           }else{
               secondsString = "" + seconds;}
           
           finalTimerString = hoursString + ":" + minutessString + ":" + secondsString;
        
        // return timer string
        return finalTimerString;
    }
	
	/**
	 * Function to get Progress percentage
	 * @param currentDuration
	 * @param totalDuration
	 * */
	public static int getProgressPercentage(long currentDuration, long totalDuration){
		Double percentage = (double) 0;
		
		long currentSeconds = (int) (currentDuration / 1000);
		long totalSeconds = (int) (totalDuration / 1000);
		
		// calculating percentage
		percentage =(((double)currentSeconds)/totalSeconds)*100;
		
		// return percentage
		return percentage.intValue();
	}

	/**
	 * Function to change progress to timer
	 * @param progress - 
	 * @param totalDuration
	 * returns current duration in milliseconds
	 * */
	public static int progressToTimer(int progress, int totalDuration) {
		int currentDuration = 0;
		totalDuration = (int) (totalDuration / 1000);
		currentDuration = (int) ((((double)progress) / 100) * totalDuration);
		
		// return current duration in milliseconds
		return currentDuration * 1000;
	}
	
	public static int progressToTimerSeconds(int progress, long totalDuration) {
        int currentDuration = 0;
        totalDuration = (int) (totalDuration);
        currentDuration = (int) ((((double)progress) / 100) * totalDuration);
        
        // return current duration in milliseconds
        return currentDuration;
    }
    
	public static void CopyStream(InputStream is, OutputStream os) {
		final int buffer_size = 1024;
		try {
			byte[] bytes = new byte[buffer_size];
			for (;;) {
				int count = is.read(bytes, 0, buffer_size);
				if (count == -1)
					break;
				os.write(bytes, 0, count);
			}
		} catch (Exception ex) {
		}
	}
	
	public static String getErrorReason(int errorCode) {
		String errorReason = "";
		switch (errorCode) {
		case AdRequest.ERROR_CODE_INTERNAL_ERROR:
			errorReason = "Internal error";
			break;
		case AdRequest.ERROR_CODE_INVALID_REQUEST:
			errorReason = "Invalid request";
			break;
		case AdRequest.ERROR_CODE_NETWORK_ERROR:
			errorReason = "Network Error";
			break;
		case AdRequest.ERROR_CODE_NO_FILL:
			errorReason = "No fill";
			break;
		}
		return errorReason;
	}
}