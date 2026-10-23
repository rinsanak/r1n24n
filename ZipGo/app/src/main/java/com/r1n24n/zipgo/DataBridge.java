package com.r1n24n.zipgo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.*;
import java.util.*;
import rikka.shizuku.Shizuku;

/** On-demand Shizuku bridge; only Android/data is allowed on the privileged side. */
final class DataBridge {
    interface Listener { void onConnected(); void onMessage(String message); }
    static final String ROOT = AndroidDataService.ROOT;
    static boolean protectedPath(String path) {
        return path != null && (path.equals(ROOT) || path.startsWith(ROOT+"/"));
    }
    static final class Entry {
        final String name;final boolean directory;final long length;
        Entry(String name,boolean directory,long length) {this.name=name;this.directory=directory;this.length=length;}
    }
    private Activity activity;
    private Listener listener;
    private final Shizuku.UserServiceArgs args;
    private IBinder binder;
    private boolean binding;
    private boolean closed;
    private final int PERMISSION_CODE = 7239;

    private final Shizuku.OnBinderReceivedListener received=()->{
        if (!closed && binder==null) activity.runOnUiThread(this::connect);
    };
    private final Shizuku.OnBinderDeadListener dead=()->{
        binder=null;binding=false;
        if (!closed) activity.runOnUiThread(()->listener.onMessage("Shizuku disconnected"));
    };
    private final Shizuku.OnRequestPermissionResultListener permission=(request,result)->{
        if (request==PERMISSION_CODE && !closed) {
            activity.runOnUiThread(()->{
                if(result==PackageManager.PERMISSION_GRANTED)connect();
                else listener.onMessage("Shizuku permission denied");
            });
        }
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName component, IBinder remote) {
            binder=remote;binding=false;
            if(!closed) activity.runOnUiThread(listener::onConnected);
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            binder=null;binding=false;
            if(!closed)activity.runOnUiThread(()->listener.onMessage("Shizuku service disconnected"));
        }
    };
    DataBridge(Activity activity, Listener listener) {
        this.activity=activity;this.listener=listener;
        args=new Shizuku.UserServiceArgs(new ComponentName(activity,AndroidDataService.class))
            .daemon(false).processNameSuffix("unified_files").tag("zipgo_data_v2").version(2);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    boolean connected() {return binder!=null && binder.isBinderAlive();}
    void connect(){
        if(closed||connected()||binding)return;
        try {
            if(!Shizuku.pingBinder()) {
                listener.onMessage("Start Shizuku, then tap retry");
                return;
            }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){
                listener.onMessage("Grant Shizuku access to ZipGo");
                Shizuku.requestPermission(PERMISSION_CODE);
                return;
            }
            binding=true;listener.onMessage("Connecting to protected storage…");
            Shizuku.bindUserService(args,serviceConnection);
        }catch(Throwable e){
            binding=false;listener.onMessage("Shizuku: "+e.getMessage());
        }
    }
    private interface Writer {void write(Parcel parcel);}
    private interface Reader<T>{T read(Parcel parcel)throws Exception;}
    private <T> T call(int action,Writer writer,Reader<T> reader)throws Exception{
        IBinder remote=binder;
        if(remote==null || !remote.isBinderAlive())throw new IOException("Shizuku is not connected");
        Parcel req=Parcel.obtain(),reply=Parcel.obtain();
        try{
            writer.write(req);
            if(!remote.transact(action,req,reply,0))throw new IOException("Protected file service unavailable");
            reply.setDataPosition(0);
            if(reply.readInt()!=0)throw new IOException(reply.readString());
            return reader.read(reply);
        }finally{req.recycle();reply.recycle();}
    }
    List<Entry> list(String folder)throws Exception {
        List<Entry> all=new ArrayList<>();
        for(int offset=0;offset<=100000;offset+=80){
            final int pos=offset;
            Object[] page=call(AndroidDataService.LIST,p->{p.writeString(folder);p.writeInt(pos);},p->{
                int total=p.readInt(),size=p.readInt();
                ArrayList<Entry> items=new ArrayList<>();
                for(int i=0;i<size;i++) items.add(new Entry(p.readString(),p.readInt()!=0,p.readLong()));
                return new Object[]{total,items};
            });
            all.addAll((List<Entry>)page[1]);
            if(all.size()>=(Integer)page[0])break;
            if(((List<?>)page[1]).isEmpty())break;
        }
        return all;
    }
    void mkdir(String parent,String name)throws Exception{
        call(AndroidDataService.MKDIR,p->{p.writeString(parent);p.writeString(name);},p->null);
    }
    void rename(String path,String name)throws Exception{
        call(AndroidDataService.RENAME,p->{p.writeString(path);p.writeString(name);},p->null);
    }
    void deleteEmpty(String path)throws Exception{
        call(AndroidDataService.DELETE,p->p.writeString(path),p->null);
    }
    void export(String path, OutputStream out) throws Exception {
        long position=0;
        while(true) {
            final long pos=position;
            byte[] bytes=call(AndroidDataService.READ,p->{p.writeString(path);p.writeLong(pos);},Parcel::createByteArray);
            if(bytes==null || bytes.length==0)break;
            out.write(bytes);
            position+=bytes.length;
        }
    }
    void importFile(String path,InputStream source)throws Exception {
        byte[] buffer=new byte[AndroidDataService.MAX_CHUNK];long position=0;int n;
        boolean any=false;
        while((n=source.read(buffer))!=-1){
            any=true;final byte[] chunk=Arrays.copyOf(buffer,n);
            final long at=position;
            call(AndroidDataService.WRITE,p->{p.writeString(path);p.writeLong(at);p.writeByteArray(chunk);},p->null);
            position+=n;
        }
        if(!any)call(AndroidDataService.WRITE,p->{p.writeString(path);p.writeLong(0);p.writeByteArray(new byte[0]);},p->null);
    }
    void close(){
        closed=true;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        try{if(binding||connected())Shizuku.unbindUserService(args,serviceConnection,true);}catch(Throwable ignored){}
        binder=null;
    }
}
