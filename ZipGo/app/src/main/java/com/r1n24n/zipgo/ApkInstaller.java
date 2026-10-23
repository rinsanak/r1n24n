package com.r1n24n.zipgo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Package installation belongs to the file-open action, not the home screen. */
final class ApkInstaller {
    private ApkInstaller(){}
    interface Status {void say(String message);}
    static boolean isInstallPackage(String filename) {
        String s=filename.toLowerCase(Locale.ROOT);
        return s.endsWith(".apk")||s.endsWith(".apks")||s.endsWith(".xapk")||s.endsWith(".sapk");
    }
    static void install(Activity activity,File source,Status status){
        new Thread(()->installNow(activity,source,status),"ZipGo-package-install").start();
    }
    private static void installNow(Activity activity,File file,Status status){
        PackageInstaller installer=activity.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session=null;int id=-1;
        try{
            if(!file.isFile())throw new IOException("Not a file");
            status.say("Reading "+file.getName());
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            id=installer.createSession(params);
            session=installer.openSession(id);
            int count=0;long total=0;
            String lower=file.getName().toLowerCase(Locale.ROOT);
            if(lower.endsWith(".apk")){
                try(InputStream stream=new BufferedInputStream(new FileInputStream(file))){
                    put(session,"base.apk",stream,-1);
                }
                count=1;
            }else{
                try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))){
                    ZipEntry entry;
                    while((entry=zip.getNextEntry())!=null){
                        String entryName=entry.getName();
                        if(!entry.isDirectory()&&entryName.toLowerCase(Locale.ROOT).endsWith(".apk")){
                            String name=entryName.substring(entryName.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9_.-]","_");
                            if(name.length()==0)throw new IOException("Invalid package split");
                            if(!name.endsWith(".apk"))name+=".apk";
                            put(session,count==0?"base.apk":"split"+count+".apk",zip,entry.getSize());
                            count++;
                            if(count>100)throw new IOException("Too many package splits");
                        }
                        zip.closeEntry();
                    }
                }
            }
            if(count==0)throw new IOException("No APK found in package");
            Intent result=new Intent(activity,ResultReceiver.class);
            result.setAction("com.r1n24n.zipgo.PACKAGE_RESULT");
            PendingIntent pending=PendingIntent.getBroadcast(activity,id,result,
                PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            session.commit(pending.getIntentSender());
            session.close();session=null;
            status.say("Confirm installation in Android");
        }catch(Exception error){
            if(session!=null)session.abandon();
            else if(id!=-1)try{installer.abandonSession(id);}catch(Exception ignore){}
            status.say("Install error: "+error.getMessage());
        }
    }
    private static void put(PackageInstaller.Session session,String name,InputStream in,long length)throws IOException{
        try(OutputStream out=session.openWrite(name,0,length>=0?length:-1)){
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            session.fsync(out);
        }
    }
}
