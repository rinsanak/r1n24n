package com.r1n24n.zipgo;
import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.os.*;import android.net.Uri;import android.database.Cursor;import android.provider.OpenableColumns;import android.graphics.Typeface;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import rikka.shizuku.Shizuku;

public final class AndroidDataActivity extends Activity {
 static final int PERM=601, IMPORT=602, EXPORT=603;
 static final String ROOT=AndroidDataService.ROOT;
 String folder=ROOT, chosenFile; Uri fileToImport; boolean busy=false, binding=false;
 int offset=0;IBinder remote; Shizuku.UserServiceArgs args;TextView status,subtitle;LinearLayout fileList;
 final Shizuku.OnBinderReceivedListener ready=()->{if(!isFinishing()&&remote==null)connect();};
 final Shizuku.OnBinderDeadListener died=()->{remote=null;binding=false;if(!isFinishing())status.setText("Shizuku stopped. Restart it and reconnect.");};
 final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{
  if(code==PERM){if(result==PackageManager.PERMISSION_GRANTED)connect();else status.setText("Shizuku permission denied.");}
 };
 final ServiceConnection connection=new ServiceConnection(){
  public void onServiceConnected(ComponentName n,IBinder binder){remote=binder;binding=false;status.setText("Connected to Shizuku.");browse(0);}
  public void onServiceDisconnected(ComponentName n){remote=null;binding=false;status.setText("Shizuku disconnected.");}
 };
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 public void onCreate(Bundle b){super.onCreate(b);
  getWindow().setStatusBarColor(0xff0d1322);getWindow().setNavigationBarColor(0xff0d1322);
  LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(14),dp(25),dp(14),dp(10));page.setBackgroundColor(0xff0d1322);
  TextView title=new TextView(this);title.setText("Android / data");title.setTextColor(0xff5ce5c4);title.setTypeface(null,Typeface.BOLD);title.setTextSize(26);page.addView(title);
  status=new TextView(this);status.setTextColor(-1);status.setTextSize(13);status.setPadding(0,dp(12),0,dp(8));status.setText("Shizuku required. Some devices may block shell file access.");page.addView(status);
  Button connectBtn=new Button(this);connectBtn.setText("Connect Shizuku");page.addView(connectBtn);connectBtn.setOnClickListener(v->connect());
  LinearLayout tools=new LinearLayout(this);page.addView(tools);
  tool(tools,"Up",()->up());tool(tools,"Reload",()->browse(offset));tool(tools,"+ Folder",()->createFolder());tool(tools,"Import",()->pickImport());
  subtitle=new TextView(this);subtitle.setTextColor(0xffaabbd0);subtitle.setTextSize(12);subtitle.setPadding(0,dp(10),0,dp(8));page.addView(subtitle);
  ScrollView scroll=new ScrollView(this);fileList=new LinearLayout(this);fileList.setOrientation(1);scroll.addView(fileList);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  TextView tip=new TextView(this);tip.setTextColor(0xff849bb0);tip.setTextSize(11);tip.setText("Tap file to export, rename or delete. Long-press a folder for more options. No automatic deletion.");page.addView(tip);
  setContentView(page);
  args=new Shizuku.UserServiceArgs(new ComponentName(this,AndroidDataService.class)).daemon(false).processNameSuffix("data").tag("zipgo-android-data").version(1);
  Shizuku.addBinderReceivedListenerSticky(ready);Shizuku.addBinderDeadListener(died);Shizuku.addRequestPermissionResultListener(permission);
  if(!Shizuku.pingBinder())status.setText("Open Shizuku and start its service, then tap Connect.");
 }
 void tool(LinearLayout row,String name,Runnable action){Button b=new Button(this);b.setText(name);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->action.run());}
 void connect(){if(remote!=null||binding)return;try{
   if(!Shizuku.pingBinder()){status.setText("Shizuku is not running.");return;}
   if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED){status.setText("Grant Shizuku permission…");Shizuku.requestPermission(PERM);return;}
   binding=true;status.setText("Connecting to Android/data…");Shizuku.bindUserService(args,connection);
  }catch(Throwable e){binding=false;status.setText("Connection error: "+e.getMessage());}}
 interface Put{void fill(Parcel p)throws Exception;}
 interface Get<T>{T read(Parcel p)throws Exception;}
 <T> T rpc(int code,Put put,Get<T> get)throws Exception{
  if(remote==null||!remote.isBinderAlive())throw new IOException("Shizuku not connected");
  Parcel input=Parcel.obtain(),result=Parcel.obtain();try{put.fill(input);if(!remote.transact(code,input,result,0))throw new IOException("Remote request failed");
   result.setDataPosition(0);if(result.readInt()!=0)throw new IOException(result.readString());return get.read(result);
  }finally{input.recycle();result.recycle();}
 }
 interface Work{void go()throws Exception;}
 void work(String message,Work job){if(busy){status.setText("An operation is already running.");return;}if(remote==null){status.setText("Connect Shizuku first.");return;}
  busy=true;status.setText(message);new Thread(()->{try{job.go();}catch(Exception e){update("Error: "+e.getMessage());}finally{busy=false;}},"ZipGo-data").start();}
 void update(String s){runOnUiThread(()->{if(!isFinishing())status.setText(s);});}
 static class Entry{String name;boolean dir;long size;Entry(String a,boolean b,long c){name=a;dir=b;size=c;}}
 void browse(int start){
  String at=folder;
  work("Reading "+at+"…",()->{
    Object[] found=rpc(AndroidDataService.LIST,p->{p.writeString(at);p.writeInt(start);},p->{
      int total=p.readInt(),count=p.readInt();ArrayList<Entry> list=new ArrayList<>();
      for(int i=0;i<count;i++)list.add(new Entry(p.readString(),p.readInt()!=0,p.readLong()));return new Object[]{total,list};});
    int total=(Integer)found[0];ArrayList<Entry> list=(ArrayList<Entry>)found[1];
    runOnUiThread(()->render(at,start,total,list));
  });
 }
 void render(String at,int start,int total,ArrayList<Entry> entries){if(isFinishing()||!folder.equals(at))return;
  offset=start;subtitle.setText(folder+" · "+total+" items");fileList.removeAllViews();
  for(Entry e:entries){TextView row=new TextView(this);row.setText((e.dir?"📁  ":"📄  ")+e.name+(e.dir?"":"  ·  "+(e.size/1024)+" KB"));
   row.setTextColor(-1);row.setTextSize(15);row.setPadding(dp(12),dp(14),dp(12),dp(14));
   fileList.addView(row);row.setOnClickListener(v->{if(e.dir){folder=at+"/"+e.name;browse(0);}else menu(at,e);});
   row.setOnLongClickListener(v->{menu(at,e);return true;});}
  LinearLayout paging=new LinearLayout(this);fileList.addView(paging);
  if(start>0)tool(paging,"Previous",()->browse(Math.max(0,start-80)));
  if(start+entries.size()<total)tool(paging,"Next",()->browse(start+entries.size()));
  status.setText(total+" items · Shizuku connected");
 }
 void up(){if(folder.equals(ROOT)){finish();return;}int i=folder.lastIndexOf('/');folder=i<=ROOT.length()?ROOT:folder.substring(0,i);browse(0);}
 @Override public void onBackPressed(){if(folder.equals(ROOT))super.onBackPressed();else up();}
 interface RenameDone{void go(String s);}
 void textDialog(String title,String initial,RenameDone action){EditText input=new EditText(this);input.setSingleLine(true);input.setText(initial);input.selectAll();
  new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("OK",(d,w)->action.go(input.getText().toString().trim())).show();}
 void createFolder(){String target=folder;textDialog("New folder","New folder",name->work("Creating folder…",()->{
  rpc(AndroidDataService.MKDIR,p->{p.writeString(target);p.writeString(name);},p->null);update("Folder created.");runOnUiThread(()->browse(0));}));}
 void menu(String parent,Entry e){
  String absolute=parent+"/"+e.name;String[] options=e.dir?new String[]{"Open","Rename","Delete empty folder"}:new String[]{"Export","Rename","Delete"};
  new AlertDialog.Builder(this).setTitle(e.name).setItems(options,(d,choice)->{
   if(choice==0){if(e.dir){folder=absolute;browse(0);}else{chosenFile=absolute;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,e.name);startActivityForResult(i,EXPORT);}}
   if(choice==1)textDialog("Rename",e.name,name->work("Renaming…",()->{rpc(AndroidDataService.RENAME,p->{p.writeString(absolute);p.writeString(name);},p->null);update("Renamed");runOnUiThread(()->browse(0));}));
   if(choice==2)new AlertDialog.Builder(this).setTitle("Delete "+e.name+"?")
    .setMessage("Permanent deletion. Non-empty folders cannot be deleted.")
    .setNegativeButton("Cancel",null).setPositiveButton("Delete",(x,w)->work("Deleting…",()->{
     rpc(AndroidDataService.DELETE,p->p.writeString(absolute),p->null);update("Deleted");runOnUiThread(()->browse(0));})).show();
  }).show();
 }
 void pickImport(){if(remote==null){status.setText("Connect Shizuku first.");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);}
 String name(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "imported_file";}
 @Override protected void onActivityResult(int code,int outcome,Intent data){super.onActivityResult(code,outcome,data);
  if(outcome!=RESULT_OK||data==null||data.getData()==null)return;
  if(code==EXPORT){String from=chosenFile;Uri to=data.getData();work("Exporting file…",()->{
   long position=0;try(OutputStream stream=getContentResolver().openOutputStream(to,"wt")){if(stream==null)throw new IOException("Output not available");
    while(true){final long offset=position;byte[] chunk=rpc(AndroidDataService.READ,p->{p.writeString(from);p.writeLong(offset);},Parcel::createByteArray);
     if(chunk==null||chunk.length==0)break;stream.write(chunk);position+=chunk.length;}
   }update("Exported "+(position/1024)+" KB");});}
  if(code==IMPORT){Uri from=data.getData();String filename=name(from);String to=folder+"/"+filename;
   new AlertDialog.Builder(this).setTitle("Import "+filename)
    .setMessage("Copy into Android/data? A matching filename will be replaced.")
    .setNegativeButton("Cancel",null).setPositiveButton("Import",(d,w)->work("Importing…",()->{
     long position=0;try(InputStream stream=getContentResolver().openInputStream(from)){if(stream==null)throw new IOException("Input unavailable");
      byte[] buffer=new byte[AndroidDataService.MAX_CHUNK];int n=stream.read(buffer);
      if(n==-1)rpc(AndroidDataService.WRITE,p->{p.writeString(to);p.writeLong(0);p.writeByteArray(new byte[0]);},p->null);
      while(n!=-1){final long start=position;final byte[] chunk=Arrays.copyOf(buffer,n);
       rpc(AndroidDataService.WRITE,p->{p.writeString(to);p.writeLong(start);p.writeByteArray(chunk);},p->null);
       position+=n;n=stream.read(buffer);}
     }update("Imported "+(position/1024)+" KB");runOnUiThread(()->browse(0));
    })).show();}
 }
 @Override protected void onDestroy(){Shizuku.removeBinderReceivedListener(ready);Shizuku.removeBinderDeadListener(died);Shizuku.removeRequestPermissionResultListener(permission);
  if(remote!=null&&args!=null)try{Shizuku.unbindUserService(args,connection,true);}catch(Throwable ignored){}remote=null;super.onDestroy();}
}