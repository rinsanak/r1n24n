package com.r1n24n.zipgo;
import android.app.*;import android.os.*;import android.content.*;import android.content.pm.*;import android.net.Uri;import android.provider.OpenableColumns;import android.database.Cursor;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import java.util.zip.*;
public final class MainActivity extends Activity {
static final int PICK=12,ALLOW=13;volatile boolean stop=false,busy=false;TextView status;Button choose;Uri selected;
int d(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
GradientDrawable bg(int color){GradientDrawable x=new GradientDrawable();x.setColor(color);x.setCornerRadius(d(18));return x;}
public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff0d1322);getWindow().setNavigationBarColor(0xff0d1322);
LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(d(26),d(55),d(26),d(30));root.setBackgroundColor(0xff0d1322);
TextView logo=new TextView(this);logo.setText("⬡");logo.setTextSize(68);logo.setTextColor(0xff5ce5c4);root.addView(logo);
TextView title=new TextView(this);title.setText("ZipGo");title.setTextSize(38);title.setTypeface(null,Typeface.BOLD);title.setTextColor(-1);root.addView(title);
TextView sub=new TextView(this);sub.setText("APK  ·  APKS  ·  XAPK  ·  SAPK");sub.setTextColor(0xff9aaac4);sub.setTextSize(14);root.addView(sub);
choose=new Button(this);choose.setText("Choose package");choose.setTextSize(17);choose.setTextColor(0xff0d1322);choose.setBackground(bg(0xff5ce5c4));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,d(58));bp.topMargin=d(45);root.addView(choose,bp);
Button files=new Button(this);files.setText("Files & ZIP tools");files.setTextSize(16);root.addView(files);files.setOnClickListener(v->startActivity(new Intent(this,ArchiveActivity.class)));
status=new TextView(this);status.setText("Ready. No background services.");status.setTextColor(0xffb9c7db);status.setTextSize(14);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=d(24);root.addView(status,sp);setContentView(root);
choose.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});
Intent in=getIntent();if(Intent.ACTION_VIEW.equals(in.getAction())&&in.getData()!=null)install(in.getData());
}
protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK&&result==RESULT_OK&&data!=null)install(data.getData());if(request==ALLOW&&result==RESULT_OK&&selected!=null)install(selected);}
void message(String s){runOnUiThread(()->{status.setText(s);choose.setEnabled(!busy);});}
void install(Uri uri){if(busy||uri==null)return;selected=uri;if(android.os.Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),ALLOW);return;}
busy=true;stop=false;choose.setEnabled(false);message("Preparing package…");
new Thread(()->{PackageInstaller.Session session=null;int id=-1;try{
PackageInstaller installer=getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);id=installer.createSession(params);session=installer.openSession(id);
String filename=name(uri).toLowerCase(Locale.ROOT);boolean archive=filename.endsWith(".xapk")||filename.endsWith(".apks")||filename.endsWith(".sapk")||filename.endsWith(".zip");
int count=0;long total=0;
try(InputStream raw=getContentResolver().openInputStream(uri)){if(raw==null)throw new IOException("File cannot be opened");
if(archive){ZipInputStream zin=new ZipInputStream(new BufferedInputStream(raw));ZipEntry entry;while((entry=zin.getNextEntry())!=null){if(stop)throw new IOException("Cancelled");String path=entry.getName();if(entry.isDirectory()||!path.toLowerCase(Locale.ROOT).endsWith(".apk"))continue;
String clean="split"+count+".apk";long len=entry.getSize();try(OutputStream out=session.openWrite(clean,0,len>=0?len:-1)){byte[] buf=new byte[65536];int n;while((n=zin.read(buf))!=-1){if(stop)throw new IOException("Cancelled");out.write(buf,0,n);total+=n;if(total>8589934592L)throw new IOException("Package too large");}}count++;if(count>100)throw new IOException("Too many APK splits");zin.closeEntry();}
}else{try(OutputStream out=session.openWrite("base.apk",0,-1)){byte[] buf=new byte[65536];int n;while((n=raw.read(buf))!=-1){if(stop)throw new IOException("Cancelled");out.write(buf,0,n);total+=n;if(total>8589934592L)throw new IOException("Package too large");}}count=1;}}
if(count==0)throw new IOException("No APK files found");if(stop)throw new IOException("Cancelled");

Intent callback=new Intent(this,ResultReceiver.class);callback.setAction("com.r1n24n.zipgo.INSTALL_RESULT");PendingIntent pi=PendingIntent.getBroadcast(this,id,callback,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);session.commit(pi.getIntentSender());session.close();session=null;message("Confirm installation in Android.");}
catch(Exception e){if(session!=null)session.abandon();else if(id!=-1)try{getPackageManager().getPackageInstaller().abandonSession(id);}catch(Exception ignored){}message("Failed: "+e.getMessage());}finally{busy=false;runOnUiThread(()->choose.setEnabled(true));}}).start();
}
String name(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}String p=u.getLastPathSegment();return p==null?"package.apk":p;}
protected void onStop(){super.onStop();if(busy)stop=true;}
}