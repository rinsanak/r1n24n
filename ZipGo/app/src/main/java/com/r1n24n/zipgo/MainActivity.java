package com.r1n24n.zipgo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.text.*;
import java.util.*;

/** Single unified file manager home: APK install and archive actions are contextual. */
public final class MainActivity extends Activity {
    static final int ACCESS=101,INSTALL_PERMISSION=102,IMPORT_FROM_SAF=103,OPEN_EXTERNAL=104;
    final int BG=0xff0b1019, PANEL=0xff151d2c, ACCENT=0xff5ce5c4, TEXT=0xfff0f5fd, MUTED=0xff9baec4;
    String rootPath,current,sortBy="Name",filter="",pendingInstall;
    boolean showHidden=false,busy=false,cut=false,searchVisible=false;
    final LinkedHashSet<String> selected=new LinkedHashSet<>();
    final ArrayList<String> clipboard=new ArrayList<>();
    final ArrayList<Entry> visible=new ArrayList<>(),all=new ArrayList<>();
    DataBridge bridge;
    TextView header,pathBar,foot,empty;
    EditText search;
    LinearLayout selectionBar;
    ListView list;
    ArrayAdapter<Entry> adapter;
    File pendingExternal;
    boolean lastAccess=false;
    static final class Entry{
        final String path,name;
        final boolean folder,protectedFile;
        final long length,modified;
        Entry(String path,String name,boolean folder,long length,long modified,boolean protectedFile){
            this.path=path;this.name=name;this.folder=folder;this.length=length;this.modified=modified;this.protectedFile=protectedFile;
        }
    }
    interface Task { void work()throws Exception; }
    int d(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    GradientDrawable background(int color,int r){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(d(r));return g;
    }
    TextView text(String value,int size,int color,boolean strong){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);
        if(strong)v.setTypeface(null,Typeface.BOLD);
        return v;
    }
    TextView control(String value,Runnable action){
        TextView v=text(value,17,ACCENT,true);
        v.setGravity(Gravity.CENTER);v.setMinWidth(d(42));v.setMinHeight(d(46));
        v.setOnClickListener(w->action.run());return v;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        rootPath=Environment.getExternalStorageDirectory().getAbsolutePath();
        String previous=getPreferences(MODE_PRIVATE).getString("last",rootPath);
        current=previous!=null&&(previous.equals(rootPath)||previous.startsWith(rootPath+"/"))?previous:rootPath;
        buildUI();
        bridge=new DataBridge(this,new DataBridge.Listener(){
            @Override public void onConnected(){message("Protected storage connected");refresh();}
            @Override public void onMessage(String s){message(s);if(DataBridge.protectedPath(current))showProtectedHelp(s);}
        });
        refresh();
        if(!hasStorage())askStorage();
        Intent action=getIntent();
        if(action!=null&&Intent.ACTION_VIEW.equals(action.getAction())&&action.getData()!=null){
            external(action.getData());
        }
    }
    void buildUI(){
        LinearLayout base=new LinearLayout(this);base.setOrientation(1);base.setBackgroundColor(BG);
        base.setPadding(d(12),d(8),d(12),d(8));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        header=text("ZipGo",23,TEXT,true);header.setPadding(d(8),0,0,0);
        toolbar.addView(header,new LinearLayout.LayoutParams(0,d(50),1));
        toolbar.addView(control("⌕",()->toggleSearch()));
        toolbar.addView(control("↕",()->sortMenu()));
        toolbar.addView(control("⋮",()->options()));
        base.addView(toolbar);
        pathBar=text("Internal storage",13,MUTED,false);pathBar.setSingleLine(true);
        pathBar.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathBar.setPadding(d(10),d(12),d(10),d(12));pathBar.setBackground(background(PANEL,12));
        base.addView(pathBar,new LinearLayout.LayoutParams(-1,d(43)));
        pathBar.setOnClickListener(v->breadcrumbs());
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);search.setHint("Search this folder…");search.setTextSize(14);
        search.setVisibility(View.GONE);base.addView(search);
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){filter=s.toString();filterList();}
            public void afterTextChanged(android.text.Editable e){}
        });
        LinearLayout shortcuts=new LinearLayout(this);shortcuts.setPadding(0,d(10),0,d(8));
        addChip(shortcuts,"Home",()->open(rootPath));
        addChip(shortcuts,"Download",()->open(rootPath+"/Download"));
        addChip(shortcuts,"Android",()->open(rootPath+"/Android"));
        base.addView(shortcuts);
        empty=text("",14,MUTED,false);empty.setGravity(Gravity.CENTER);
        list=new ListView(this);list.setDividerHeight(0);list.setCacheColorHint(BG);
        adapter=new ArrayAdapter<Entry>(this,android.R.layout.simple_list_item_1,visible){
            @Override public View getView(int position,View convert,android.view.ViewGroup parent){
                LinearLayout row;
                TextView icon,name,detail,check;
                if(convert instanceof LinearLayout){
                    row=(LinearLayout)convert;icon=(TextView)row.getChildAt(0);
                    LinearLayout middle=(LinearLayout)row.getChildAt(1);
                    name=(TextView)middle.getChildAt(0);detail=(TextView)middle.getChildAt(1);
                    check=(TextView)row.getChildAt(2);
                }else{
                    row=new LinearLayout(MainActivity.this);row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(d(8),d(8),d(8),d(8));
                    icon=text("",22,ACCENT,false);icon.setGravity(Gravity.CENTER);
                    row.addView(icon,new LinearLayout.LayoutParams(d(47),d(53)));
                    LinearLayout middle=new LinearLayout(MainActivity.this);middle.setOrientation(LinearLayout.VERTICAL);
                    middle.setGravity(Gravity.CENTER_VERTICAL);row.addView(middle,new LinearLayout.LayoutParams(0,d(59),1));
                    name=text("",15,TEXT,false);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);middle.addView(name);
                    detail=text("",11,MUTED,false);detail.setSingleLine(true);middle.addView(detail);
                    check=text("",20,ACCENT,true);check.setGravity(Gravity.CENTER);
                    row.addView(check,new LinearLayout.LayoutParams(d(31),d(44)));
                }
                Entry item=getItem(position);
                boolean marked=selected.contains(item.path);
                row.setBackground(background(marked?0xff17382f:PANEL,10));
                name.setText(item.name);
                icon.setText(item.folder?"📁":iconFor(item.name));
                detail.setText(item.folder?(item.protectedFile?"Protected folder":"Folder"):
                    FileTasks.readable(item.length)+(item.modified>0?"  ·  "+date(item.modified):""));
                check.setText(marked?"✓":"");
                return row;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,position,id)->{
            Entry e=visible.get(position);
            if(!selected.isEmpty())toggleSelected(e.path);
            else if(e.folder)open(e.path);
            else fileMenu(e);
        });
        list.setOnItemLongClickListener((p,v,pos,id)->{toggleSelected(visible.get(pos).path);return true;});
        base.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        empty.setVisibility(View.GONE);base.addView(empty,new LinearLayout.LayoutParams(-1,d(24)));
        selectionBar=new LinearLayout(this);selectionBar.setGravity(Gravity.CENTER);
        selectionBar.setVisibility(View.GONE);
        action(selectionBar,"Copy",()->copySelected(false));
        action(selectionBar,"Move",()->copySelected(true));
        action(selectionBar,"ZIP",()->compressSelected());
        action(selectionBar,"Delete",()->deleteSelected());
        action(selectionBar,"More",()->selectedMore());
        base.addView(selectionBar);
        LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);
        action(bottom,"＋ New",()->createMenu());
        action(bottom,"Paste",()->paste());
        action(bottom,"↻",()->refresh());
        action(bottom,"↑ Up",()->up());
        base.addView(bottom);
        foot=text("Offline • No background scanning",12,MUTED,false);
        foot.setPadding(d(7),d(9),0,d(3));base.addView(foot);
        setContentView(base);
    }
    String date(long ts){return new SimpleDateFormat("dd MMM",Locale.getDefault()).format(new Date(ts));}
    String iconFor(String name){
        String n=name.toLowerCase(Locale.ROOT);
        if(ApkInstaller.isInstallPackage(n))return "📦";
        if(archive(n))return "🗜";
        if(n.matches(".*\\.(jpg|jpeg|png|webp|gif|heic)$"))return "▧";
        if(n.matches(".*\\.(mp4|mkv|avi|mov|webm)$"))return "▶";
        if(n.matches(".*\\.(mp3|flac|wav|m4a|opus)$"))return "♫";
        return "▤";
    }
    boolean archive(String n){
        n=n.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip")||n.endsWith(".7z")||n.endsWith(".rar")||
        n.endsWith(".tar")||n.endsWith(".tar.gz")||n.endsWith(".tgz")||
        n.endsWith(".gz")||n.endsWith(".apk")||n.endsWith(".xapk")||n.endsWith(".apks")||n.endsWith(".sapk");
    }
    void addChip(LinearLayout bar,String caption,Runnable action){
        TextView chip=text(caption,12,ACCENT,true);chip.setGravity(Gravity.CENTER);
        chip.setBackground(background(PANEL,10));chip.setPadding(d(10),0,d(10),0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,d(36),1);p.setMargins(d(2),0,d(2),0);
        bar.addView(chip,p);chip.setOnClickListener(v->action.run());
    }
    void action(LinearLayout parent,String title,Runnable action){
        TextView label=text(title,12,ACCENT,true);label.setGravity(Gravity.CENTER);label.setMinHeight(d(45));
        parent.addView(label,new LinearLayout.LayoutParams(0,d(47),1));label.setOnClickListener(v->action.run());
    }
    void message(String value){runOnUiThread(()->{if(!isFinishing())foot.setText(value);});}
    void runWork(String label,Task task){
        if(busy){message("Finish the current operation first");return;}
        busy=true;message(label);
        new Thread(()->{
            try{task.work();message("Finished");}
            catch(Exception e){message("Error: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
            finally{busy=false;runOnUiThread(this::refresh);}
        },"ZipGo-foreground-work").start();
    }
    void open(String to){
        if(to==null)return;
        if(!to.equals(rootPath)&&!to.startsWith(rootPath+"/")){message("Outside shared storage");return;}
        current=to;selected.clear();
        getPreferences(MODE_PRIVATE).edit().putString("last",current).apply();refresh();
    }
    void refresh(){
        if(isFinishing())return;
        header.setText(selected.isEmpty()?"ZipGo":selected.size()+" selected");
        selectionBar.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);
        pathBar.setText(current.equals(rootPath)?"Internal storage":current.substring(rootPath.length()));
        if(DataBridge.protectedPath(current)&&!bridge.connected()){
            all.clear();filterList();bridge.connect();
            showProtectedHelp("Protected folder · Shizuku access required");return;
        }
        final String target=current;
        new Thread(()->{
            try{
                ArrayList<Entry> result=new ArrayList<>();
                if(DataBridge.protectedPath(target)){
                    for(DataBridge.Entry file:bridge.list(target)){
                        result.add(new Entry(target+"/"+file.name,file.name,file.directory,file.length,0,true));
                    }
                }else{
                    File dir=new File(target);File[] files=dir.listFiles();
                    if(files==null)throw new IOException("Cannot list directory. Grant All files access.");
                    for(File file:files){
                        if(!showHidden&&file.getName().startsWith("."))continue;
                        result.add(new Entry(file.getAbsolutePath(),file.getName(),file.isDirectory(),file.length(),file.lastModified(),false));
                    }
                    if(target.equals(rootPath+"/Android")){
                        boolean data=false;
                        for(Entry file:result)if(file.name.equals("data"))data=true;
                        if(!data)result.add(new Entry(DataBridge.ROOT,"data",true,0,0,true));
                    }
                }
                runOnUiThread(()->{
                    if(!current.equals(target)||isFinishing())return;
                    all.clear();all.addAll(result);filterList();message(result.size()+" items");
                });
            }catch(Exception e){runOnUiThread(()->{
                if(!current.equals(target))return;
                all.clear();filterList();
                if(DataBridge.protectedPath(target))showProtectedHelp(e.getMessage());
                else message("Cannot open: "+e.getMessage());
            });}
        },"ZipGo-directory").start();
    }
    void filterList(){
        visible.clear();String q=filter.toLowerCase(Locale.ROOT);
        for(Entry e:all)if(e.name.toLowerCase(Locale.ROOT).contains(q))visible.add(e);
        Collections.sort(visible,(a,b)->{
            if(a.folder!=b.folder)return a.folder?-1:1;
            int compare=0;
            if(sortBy.equals("Size"))compare=Long.compare(b.length,a.length);
            else if(sortBy.equals("Newest"))compare=Long.compare(b.modified,a.modified);
            else if(sortBy.equals("Type"))compare=extension(a.name).compareTo(extension(b.name));
            if(compare==0)compare=a.name.compareToIgnoreCase(b.name);
            return compare;
        });
        adapter.notifyDataSetChanged();
        android.view.ViewGroup.LayoutParams eparams=empty.getLayoutParams();if(eparams!=null){eparams.height=d(24);empty.setLayoutParams(eparams);}
        empty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);
        empty.setText(visible.isEmpty()?"No files to show":"");
    }
    String extension(String name){int n=name.lastIndexOf('.');return n<0?"":name.substring(n).toLowerCase(Locale.ROOT);}
    void showProtectedHelp(String message){
        foot.setText(message==null?"Shizuku access unavailable":message);
        empty.setVisibility(View.VISIBLE);
        android.view.ViewGroup.LayoutParams eparams=empty.getLayoutParams();if(eparams!=null){eparams.height=d(90);empty.setLayoutParams(eparams);}
        empty.setText("Android/data needs Shizuku.\nStart Shizuku and tap here to connect.");
        empty.setOnClickListener(v->{bridge.connect();refresh();});
    }
    void up(){
        if(!selected.isEmpty()){selected.clear();refresh();return;}
        if(current.equals(rootPath))return;
        File par=new File(current).getParentFile();open(par==null?rootPath:par.getAbsolutePath());
    }
    @Override public void onBackPressed(){
        if(!selected.isEmpty()){selected.clear();refresh();}
        else if(!current.equals(rootPath))up();
        else super.onBackPressed();
    }
    void toggleSearch(){
        searchVisible=!searchVisible;search.setVisibility(searchVisible?View.VISIBLE:View.GONE);
        if(searchVisible){search.requestFocus();}
        else{search.setText("");((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
    }
    void sortMenu(){
        new AlertDialog.Builder(this).setTitle("Sort files").setItems(new String[]{"Name","Newest","Size","Type","Show / hide hidden files"},(d,which)->{
            if(which==4){showHidden=!showHidden;refresh();}
            else{sortBy=new String[]{"Name","Newest","Size","Type"}[which];filterList();}
        }).show();
    }
    void breadcrumbs(){
        ArrayList<String> dirs=new ArrayList<>(),labels=new ArrayList<>();
        String path=current;
        while(path!=null&&path.startsWith(rootPath)){
            dirs.add(path);labels.add(path.equals(rootPath)?"Internal storage":new File(path).getName());
            if(path.equals(rootPath))break;
            path=new File(path).getParent();
        }
        new AlertDialog.Builder(this).setTitle("Go to folder").setItems(labels.toArray(new String[0]),(d,i)->open(dirs.get(i))).show();
    }
    void options(){
        new AlertDialog.Builder(this).setTitle("ZipGo")
            .setItems(new String[]{"Select all","Storage permission","Connect Shizuku","New folder","Show hidden files","Refresh","About"},
                (d,which)->{
                    switch(which){
                        case 0:for(Entry e:visible)selected.add(e.path);refresh();break;
                        case 1:askStorage();break;
                        case 2:bridge.connect();break;
                        case 3:createFolder();break;
                        case 4:showHidden=!showHidden;refresh();break;
                        case 5:refresh();break;
                        case 6:new AlertDialog.Builder(this).setTitle("ZipGo v2")
                             .setMessage("Unified offline file manager. APK installation is part of file actions. Android/data uses Shizuku if granted. No ads or background scanner.")
                             .setPositiveButton("OK",null).show();break;
                    }
                }).show();
    }
    void toggleSelected(String p){if(!selected.add(p))selected.remove(p);refresh();}
    void selectedMore(){
        if(selected.isEmpty())return;
        new AlertDialog.Builder(this).setItems(new String[]{"Rename (one file)","Properties","Share (one file)","Clear selection"},(d,which)->{
            if(which==0&&selected.size()==1)rename(selected.iterator().next());
            if(which==1)properties(selected.iterator().next());
            if(which==2&&selected.size()==1)share(selected.iterator().next());
            if(which==3){selected.clear();refresh();}
            if((which==0||which==2)&&selected.size()>1)message("Select one file first");
        }).show();
    }
    void copySelected(boolean move){
        if(selected.isEmpty())return;
        clipboard.clear();clipboard.addAll(selected);cut=move;selected.clear();refresh();
        message(move?"Move: open destination and tap Paste":"Copy: open destination and tap Paste");
    }
    void paste(){
        if(clipboard.isEmpty()){message("Select files and tap Copy or Move first");return;}
        final String destination=current;
        final ArrayList<String> sources=new ArrayList<>(clipboard);
        final boolean moving=cut;
        new AlertDialog.Builder(this).setTitle(moving?"Move files here?":"Copy files here?")
            .setMessage(sources.size()+" items → "+destination)
            .setNegativeButton("Cancel",null).setPositiveButton("Paste",(d,w)->
            runWork("Pasting "+sources.size()+" items…",()->{
                for(String path:sources){
                    File src=new File(path);String name=src.getName();
                    String dst=destination+"/"+name;
                    if(path.equals(dst))throw new IOException("Source and destination are the same");
                    boolean fromProtected=DataBridge.protectedPath(path),toProtected=DataBridge.protectedPath(dst);
                    if(fromProtected&&toProtected)throw new IOException("Protected-to-protected copy not supported yet");
                    if(fromProtected){
                        if(src.isDirectory())throw new IOException("Protected folder copy unavailable");
                        File out=new File(dst);
                        if(out.exists())throw new IOException("Already exists: "+name);
                        try(OutputStream stream=new BufferedOutputStream(new FileOutputStream(out))){bridge.export(path,stream);}
                        if(moving)bridge.deleteEmpty(path);
                    }else if(toProtected){
                        if(src.isDirectory())throw new IOException("Import a file, not folder");
                        try(InputStream stream=new BufferedInputStream(new FileInputStream(src))){bridge.importFile(dst,stream);}
                        if(moving)FileTasks.delete(src);
                    }else{
                        if(moving)FileTasks.move(src,new File(dst));else FileTasks.copy(src,new File(dst));
                    }
                }
                clipboard.clear();
            })).show();
    }
    void deleteSelected(){
        if(selected.isEmpty())return;
        ArrayList<String> paths=new ArrayList<>(selected);
        new AlertDialog.Builder(this).setTitle("Permanently delete "+paths.size()+" items?")
            .setMessage("This cannot be undone. Check the selection carefully.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->
            runWork("Deleting files…",()->{
                for(String p:paths){
                    if(DataBridge.protectedPath(p))bridge.deleteEmpty(p);
                    else FileTasks.delete(new File(p));
                }
                selected.clear();
            })).show();
    }
    void rename(String path){
        EditText e=new EditText(this);e.setSingleLine(true);e.setText(new File(path).getName());e.selectAll();
        new AlertDialog.Builder(this).setTitle("Rename").setView(e).setNegativeButton("Cancel",null)
            .setPositiveButton("Rename",(d,w)->{
                String name=e.getText().toString().trim();
                runWork("Renaming…",()->{
                    FileTasks.validateName(name);
                    if(DataBridge.protectedPath(path))bridge.rename(path,name);
                    else{
                        File from=FileTasks.checked(new File(path));
                        File dest=FileTasks.checked(new File(from.getParent(),name));
                        if(dest.exists()||!from.renameTo(dest))throw new IOException("Rename failed");
                    }
                    selected.clear();
                });
            }).show();
    }
    void createMenu(){
        new AlertDialog.Builder(this).setTitle("New")
          .setItems(new String[]{"Folder","ZIP from selected files"},(d,n)->{
             if(n==0)createFolder();else compressSelected();
          }).show();
    }
    void createFolder(){
        EditText e=new EditText(this);e.setSingleLine(true);e.setText("New folder");e.selectAll();
        String target=current;
        new AlertDialog.Builder(this).setTitle("New folder").setView(e).setNegativeButton("Cancel",null)
            .setPositiveButton("Create",(d,w)->{
                String name=e.getText().toString().trim();
                runWork("Creating folder…",()->{
                    FileTasks.validateName(name);
                    if(DataBridge.protectedPath(target))bridge.mkdir(target,name);
                    else if(!FileTasks.checked(new File(target,name)).mkdir())throw new IOException("Cannot create folder");
                });
            }).show();
    }
    void compressSelected(){
        if(selected.isEmpty()){message("Long-press files to select, then tap ZIP");return;}
        ArrayList<File> files=new ArrayList<>();
        for(String p:selected){
            if(DataBridge.protectedPath(p)){message("Export protected files before compression");return;}
            files.add(new File(p));
        }
        EditText e=new EditText(this);e.setSingleLine(true);e.setText("Archive.zip");
        new AlertDialog.Builder(this).setTitle("Create ZIP archive").setView(e)
            .setNegativeButton("Cancel",null).setPositiveButton("Compress",(d,w)->{
                String entered=e.getText().toString().trim();
                final String zipName=entered.toLowerCase(Locale.ROOT).endsWith(".zip")?entered:entered+".zip";
                runWork("Compressing…",()->{
                    FileTasks.validateName(zipName);
                    FileTasks.zip(files,new File(current,zipName),this::message);selected.clear();
                });
            }).show();
    }
    void properties(String path){
        File file=new File(path);
        String msg="Path: "+path+"\n"+
                   "Type: "+(file.isDirectory()?"Folder":"File")+"\n"+
                   "Size: "+(DataBridge.protectedPath(path)?"Protected":FileTasks.readable(file.length()))+"\n"+
                   "Modified: "+date(file.lastModified());
        new AlertDialog.Builder(this).setTitle(file.getName()).setMessage(msg)
            .setPositiveButton("Close",null).show();
    }
    void fileMenu(Entry e){
        ArrayList<String> choices=new ArrayList<>();
        boolean apk=ApkInstaller.isInstallPackage(e.name),archive=archive(e.name);
        if(apk)choices.add("Install package");
        if(archive)choices.add("Open archive");
        if(archive)choices.add("Extract here");
        if(e.protectedFile)choices.add("Copy to Downloads");
        choices.add("Open with…");choices.add("Copy");choices.add("Move");choices.add("Rename");choices.add("Share");choices.add("Properties");choices.add("Delete");
        new AlertDialog.Builder(this).setTitle(e.name).setItems(choices.toArray(new String[0]),(dialog,which)->{
            String c=choices.get(which);
            switch(c){
                case "Install package":install(e.path);break;
                case "Open archive":showArchive(e.path);break;
                case "Extract here":extract(e.path);break;
                case "Copy to Downloads":exportProtected(e.path);break;
                case "Open with…":view(e.path,false);break;
                case "Copy":selected.clear();selected.add(e.path);copySelected(false);break;
                case "Move":selected.clear();selected.add(e.path);copySelected(true);break;
                case "Rename":rename(e.path);break;
                case "Share":share(e.path);break;
                case "Properties":properties(e.path);break;
                case "Delete":selected.clear();selected.add(e.path);deleteSelected();break;
            }
        }).show();
    }
    void exportProtected(String path){
        if(!DataBridge.protectedPath(path)){message("Select a protected file");return;}
        File destination=new File(rootPath+"/Download",new File(path).getName());
        if(destination.exists()){message("Download already has a file with this name");return;}
        runWork("Exporting from Android/data…",()->{
            FileTasks.checked(destination);
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(destination))){bridge.export(path,output);}
            catch(Exception e){destination.delete();throw e;}
        });
    }
    void install(String path){
        if(DataBridge.protectedPath(path)){message("Export this APK from Android/data first");return;}
        if(android.os.Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            pendingInstall=path;Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));
            startActivityForResult(i,INSTALL_PERMISSION);return;
        }
        new AlertDialog.Builder(this).setTitle("Install package?")
          .setMessage(new File(path).getName()+"\nAndroid will ask you to confirm.")
          .setNegativeButton("Cancel",null).setPositiveButton("Install",(d,w)->
              ApkInstaller.install(this,new File(path),this::message)).show();
    }
    void showArchive(String path){
        if(DataBridge.protectedPath(path)){message("Export protected archive first");return;}
        Intent i=new Intent(this,ArchiveActivity.class);
        i.putExtra("archive_file",path);startActivity(i);
    }
    void extract(String path){
        if(DataBridge.protectedPath(path)){message("Export protected archive first");return;}
        File from=new File(path);
        new AlertDialog.Builder(this).setTitle("Extract here?")
          .setMessage(from.getName()).setNegativeButton("Cancel",null)
          .setPositiveButton("Extract",(d,w)->runWork("Extracting…",()->
                ArchiveEngine.extract(from,new File(current),null,this::message)
          )).show();
    }
    void view(String path,boolean send){
        if(DataBridge.protectedPath(path)){message("Export protected file first");return;}
        File file=new File(path);
        try{
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);
            Intent i=new Intent(send?Intent.ACTION_SEND:Intent.ACTION_VIEW);
            String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(file.getName()).replace(".",""));
            if(mime==null)mime="application/octet-stream";
            if(send){i.setType(mime);i.putExtra(Intent.EXTRA_STREAM,uri);}
            else i.setDataAndType(uri,mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,send?"Share file":"Open with"));
        }catch(Exception ex){message("Cannot open: "+ex.getMessage());}
    }
    void share(String path){view(path,true);}
    boolean hasStorage(){
        if(Build.VERSION.SDK_INT>=30)return Environment.isExternalStorageManager();
        if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
        return true;
    }
    void askStorage(){
        try{
            if(Build.VERSION.SDK_INT>=30){
                Intent i=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:"+getPackageName()));startActivityForResult(i,ACCESS);
            }else if(Build.VERSION.SDK_INT>=23){
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE},ACCESS);
            }
        }catch(Exception ex){message("Open Settings → Apps → ZipGo → All files access");}
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grant){
        super.onRequestPermissionsResult(code,perms,grant);
        if(code==ACCESS)refresh();
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==ACCESS)refresh();
        if(code==INSTALL_PERMISSION&&pendingInstall!=null){
            String file=pendingInstall;pendingInstall=null;
            if(getPackageManager().canRequestPackageInstalls())install(file);
            else message("Grant install permission for ZipGo");
        }
    }
    void external(Uri uri){message("Open this file from the file manager or import it to Downloads");}
    @Override protected void onResume(){super.onResume();if(bridge!=null)refresh();}
    @Override protected void onDestroy(){if(bridge!=null)bridge.close();super.onDestroy();}
}
