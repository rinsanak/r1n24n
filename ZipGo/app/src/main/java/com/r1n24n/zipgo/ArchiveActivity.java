package com.r1n24n.zipgo;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** File-manager archive view, not a separate installer application. */
public final class ArchiveActivity extends Activity {
    final int BG=0xff0b1019, SURFACE=0xff172133, ACCENT=0xff5ce5c4;
    File file;
    String prefix="";
    char[] password;
    ArrayList<ArchiveEngine.Item> entries=new ArrayList<>();
    ArrayList<String> folders=new ArrayList<>();
    ArrayList<ArchiveEngine.Item> children=new ArrayList<>();
    TextView subtitle,status;
    LinearLayout list;
    boolean busy=false;
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView label(String value,int size,int color){
        TextView v=new TextView(this);v.setText(value);v.setTextColor(color);v.setTextSize(size);return v;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        String path=getIntent().getStringExtra("archive_file");
        if(path==null){finish();return;}
        file=new File(path);
        LinearLayout root=new LinearLayout(this);root.setPadding(dp(15),dp(25),dp(15),dp(12));
        root.setOrientation(1);root.setBackgroundColor(BG);
        TextView title=label(file.getName(),22,0xffffffff);title.setTypeface(null,Typeface.BOLD);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);root.addView(title);
        subtitle=label("",13,0xffaabbd0);subtitle.setPadding(0,dp(10),0,dp(9));root.addView(subtitle);
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);root.addView(actions);
        button(actions,"Extract here",()->extract(false));
        button(actions,"To folder",()->extract(true));
        button(actions,"Password",()->password());
        status=label("Reading archive…",12,0xffaabbd0);status.setPadding(0,dp(10),0,dp(7));root.addView(status);
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView warning=label("Extraction never silently overwrites existing files.",11,0xff8da5ba);
        root.addView(warning);setContentView(root);
        readArchive();
    }
    void button(LinearLayout bar,String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setTextSize(12);
        bar.addView(b,new LinearLayout.LayoutParams(0,dp(49),1));b.setOnClickListener(v->action.run());
    }
    void report(String t){runOnUiThread(()->{if(!isFinishing())status.setText(t);});}
    void readArchive(){
        if(busy)return;busy=true;report("Reading…");
        new Thread(()->{
            try{
                List<ArchiveEngine.Item> result=ArchiveEngine.list(file,password);
                runOnUiThread(()->{
                    entries.clear();entries.addAll(result);render();report(entries.size()+" entries");
                });
            }catch(Exception error){report("Cannot open archive: "+error.getMessage()+". Enter password if encrypted.");}
            finally{busy=false;}
        },"ZipGo-archive-read").start();
    }
    void render(){
        if(isFinishing())return;
        list.removeAllViews();folders.clear();children.clear();
        subtitle.setText((prefix.isEmpty()?"/":"/"+prefix)+"  ·  "+entries.size()+" total entries");
        TreeSet<String> dirs=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(ArchiveEngine.Item e:entries){
            String path=e.name.replace('\\','/');
            if(!path.startsWith(prefix)||path.equals(prefix))continue;
            String remain=path.substring(prefix.length());
            if(remain.isEmpty())continue;
            int split=remain.indexOf('/');
            if(split>=0){if(split>0)dirs.add(remain.substring(0,split));}
            else if(!e.folder){children.add(e);}
        }
        for(String dir:dirs)folders.add(dir);
        if(!prefix.isEmpty())item("⬆  ..","Back",()->up());
        for(String dir:folders)item("📁  "+dir,"Folder",()->{prefix+=dir+"/";render();});
        for(ArchiveEngine.Item entry:children){
            item("▤  "+entry.name.substring(prefix.length()),FileTasks.readable(Math.max(0,entry.size)),()->
                 new AlertDialog.Builder(this).setTitle(entry.name)
                 .setMessage("Size: "+FileTasks.readable(Math.max(0,entry.size))+"\nUse Extract to unpack this archive.")
                 .setPositiveButton("OK",null).show());
        }
        if(folders.isEmpty()&&children.isEmpty()&&prefix.isEmpty())
            item("No visible entries","Try a password if the archive is encrypted",()->password());
    }
    void item(String name,String description,Runnable callback){
        LinearLayout line=new LinearLayout(this);line.setOrientation(1);line.setPadding(dp(13),dp(9),dp(12),dp(8));
        GradientDrawableCompat.apply(line,SURFACE,dp(10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(5);list.addView(line,p);
        line.addView(label(name,15,0xffe6eff9));
        line.addView(label(description,11,0xff9caec0));
        line.setOnClickListener(v->callback.run());
    }
    void up(){if(prefix.isEmpty()){finish();return;}
        String remaining=prefix.substring(0,prefix.length()-1);
        int i=remaining.lastIndexOf('/');
        prefix=i<0?"":remaining.substring(0,i+1);render();
    }
    @Override public void onBackPressed(){if(prefix.isEmpty())super.onBackPressed();else up();}
    void password(){
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("Archive password")
            .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->{
                if(password!=null)Arrays.fill(password,'\u0000');
                password=input.getText().toString().toCharArray();
                readArchive();
            }).show();
    }
    void extract(boolean separateFolder){
        if(busy)return;
        String base=file.getName().replaceFirst("(?i)\\.(zip|7z|rar|tar|gz|tgz|apks|xapk|sapk)$","");
        File folder=separateFolder?new File(file.getParentFile(),base):file.getParentFile();
        String label=separateFolder?"Extract to "+folder.getName()+"?":"Extract files here?";
        new AlertDialog.Builder(this).setTitle(label)
            .setMessage("Existing files will not be overwritten.")
            .setNegativeButton("Cancel",null).setPositiveButton("Extract",(d,w)->{
                busy=true;report("Extracting "+file.getName()+"…");
                new Thread(()->{
                    try{
                        if(separateFolder&&!folder.exists()&&!folder.mkdirs())throw new IOException("Cannot create destination");
                        ArchiveEngine.extract(file,folder,password,this::report);
                        report("Extract completed");
                    }catch(Exception e){report("Extraction error: "+e.getMessage()+". Check storage and password.");}
                    finally{busy=false;}
                },"ZipGo-extract").start();
            }).show();
    }
    static final class GradientDrawableCompat{
        static void apply(View v,int color,int radius){
            android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
            bg.setColor(color);bg.setCornerRadius(radius);v.setBackground(bg);
        }
    }
    @Override protected void onDestroy(){if(password!=null)Arrays.fill(password,'\u0000');super.onDestroy();}
}
