package com.r1n24n.zipgo;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.XZInputStream;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import net.lingala.zip4j.model.LocalFileHeader;

/** Standalone, offline archive reader with path-traversal and decompression limits. */
final class ArchiveEngine {
    static final class Item{
        final String name;final boolean folder;final long size;
        Item(String n,boolean d,long s){name=n;folder=d;size=s;}
    }
    private static final int MAX_ENTRIES=25000;
    private static final long MAX_OUTPUT=24L*1024*1024*1024;
    private ArchiveEngine(){}
    static String type(File file){
        String name=file.getName().toLowerCase(Locale.ROOT);
        if(name.endsWith(".7z"))return "7z";
        if(name.endsWith(".rar")||name.matches(".*\\.part[0-9]+\\.rar"))return "rar";
        if(name.endsWith(".tar.gz")||name.endsWith(".tgz"))return "tgz";
        if(name.endsWith(".tar.bz2")||name.endsWith(".tbz")||name.endsWith(".tbz2"))return "tbz";
        if(name.endsWith(".tar.xz")||name.endsWith(".txz"))return "txz";
        if(name.endsWith(".tar"))return "tar";
        if(name.endsWith(".gz"))return "gzip";
        if(name.endsWith(".zip")||name.endsWith(".apk")||name.endsWith(".apks")||name.endsWith(".xapk")||name.endsWith(".sapk"))return "zip";
        return "unknown";
    }
    static List<Item> list(File input,char[] password)throws Exception{
        String type=type(input);List<Item> result=new ArrayList<>();
        switch(type){
            case "zip":
                try(net.lingala.zip4j.ZipFile zip=password==null?new net.lingala.zip4j.ZipFile(input):new net.lingala.zip4j.ZipFile(input,password)){
                    for(net.lingala.zip4j.model.FileHeader h:zip.getFileHeaders()){
                        result.add(new Item(h.getFileName(),h.isDirectory(),h.getUncompressedSize()));
                        if(result.size()>MAX_ENTRIES)throw new IOException("Too many archive entries");
                    }
                }break;
            case "7z":
                try(SevenZFile seven=password==null?new SevenZFile(input):new SevenZFile(input,password)){
                    for(SevenZArchiveEntry entry:seven.getEntries()){
                        result.add(new Item(entry.getName()==null?"unnamed":entry.getName(),entry.isDirectory(),entry.getSize()));
                        if(result.size()>MAX_ENTRIES)throw new IOException("Too many archive entries");
                    }
                }break;
            case "rar":
                try(Archive rar=password==null?new Archive(input):new Archive(input,new String(password))){
                    for(FileHeader entry:rar.getFileHeaders()){
                        result.add(new Item(entry.getFileName(),entry.isDirectory(),entry.getFullUnpackSize()));
                        if(result.size()>MAX_ENTRIES)throw new IOException("Too many archive entries");
                    }
                }break;
            case "tar":case "tgz":case "tbz":case "txz":
                try(TarArchiveInputStream tar=openTar(input,type)){
                    TarArchiveEntry entry;while((entry=tar.getNextTarEntry())!=null){
                        result.add(new Item(entry.getName(),entry.isDirectory(),entry.getSize()));
                        if(result.size()>MAX_ENTRIES)throw new IOException("Too many archive entries");
                    }
                }break;
            case "gzip":
                result.add(new Item(input.getName().replaceFirst("\\.gz$",""),false,-1));break;
            default:throw new IOException("Unsupported archive format");
        }
        return result;
    }
    private static TarArchiveInputStream openTar(File input,String type)throws IOException{
        InputStream stream=new BufferedInputStream(new FileInputStream(input));
        try{
            if(type.equals("tgz"))stream=new GzipCompressorInputStream(stream);
            else if(type.equals("tbz"))stream=new BZip2CompressorInputStream(stream);
            else if(type.equals("txz"))stream=new XZInputStream(stream);
            return new TarArchiveInputStream(stream);
        }catch(IOException|RuntimeException e){try{stream.close();}catch(IOException ignored){}throw e;}
    }
    private static File checkedOutput(File root,String entry)throws IOException{
        if(entry==null||entry.length()==0||entry.charAt(0)=='/'||entry.matches("^[A-Za-z]:.*"))
            throw new IOException("Invalid archive entry name");
        String normalized=entry.replace('\\','/');
        File home=root.getCanonicalFile();
        File dest=new File(home,normalized).getCanonicalFile();
        if(!dest.getPath().startsWith(home.getPath()+File.separator))
            throw new IOException("Archive traversal blocked: "+entry);
        return dest;
    }
    private static final class Budget{
        long written=0;int entries=0;
        void next()throws IOException{if(++entries>MAX_ENTRIES)throw new IOException("Archive contains too many files");}
        void added(int n)throws IOException{
            written+=n;
            if(written>MAX_OUTPUT)throw new IOException("Archive exceeds 24 GB extraction limit");
        }
    }
    private static File begin(File root,String name,boolean dir,Budget budget)throws IOException{
        budget.next();File dest=checkedOutput(root,name);
        if(dir){if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create "+dest.getName());return dest;}
        File parent=dest.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create folder");
        if(dest.exists())throw new IOException("Won't overwrite existing: "+name);
        return dest;
    }
    private static void copy(InputStream in,OutputStream out,Budget budget)throws IOException{
        byte[] buffer=new byte[65536];int n;
        while((n=in.read(buffer))!=-1){budget.added(n);out.write(buffer,0,n);}
    }
    static void extract(File input,File target,char[] password,FileTasks.Progress progress)throws Exception{
        FileTasks.checked(input);FileTasks.checked(target);
        if(!target.isDirectory())throw new IOException("Choose an existing destination folder");
        Budget budget=new Budget();
        switch(type(input)){
            case "zip":
                try(InputStream source=new BufferedInputStream(new FileInputStream(input));
                    net.lingala.zip4j.io.inputstream.ZipInputStream zip=
                        password==null?new net.lingala.zip4j.io.inputstream.ZipInputStream(source):
                                       new net.lingala.zip4j.io.inputstream.ZipInputStream(source,password)){
                    LocalFileHeader header;
                    while((header=zip.getNextEntry())!=null){
                        String name=header.getFileName();if(progress!=null)progress.message("Extracting "+name);
                        File dest=begin(target,name,header.isDirectory(),budget);
                        if(!header.isDirectory()){
                            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                                copy(zip,out,budget);
                            }catch(Exception e){dest.delete();throw e;}
                        }
                    }
                }break;
            case "7z":
                try(SevenZFile seven=password==null?new SevenZFile(input):new SevenZFile(input,password)){
                    SevenZArchiveEntry entry;
                    while((entry=seven.getNextEntry())!=null){
                        String name=entry.getName();if(name==null)throw new IOException("Unnamed 7z entry");
                        if(progress!=null)progress.message("Extracting "+name);
                        File dest=begin(target,name,entry.isDirectory(),budget);
                        if(!entry.isDirectory()){
                            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                                byte[] buffer=new byte[65536];int n;
                                while((n=seven.read(buffer))!=-1){budget.added(n);out.write(buffer,0,n);}
                            }catch(Exception e){dest.delete();throw e;}
                        }
                    }
                }break;
            case "rar":
                try(Archive rar=password==null?new Archive(input):new Archive(input,new String(password))){
                    for(FileHeader entry:rar.getFileHeaders()){
                        String name=entry.getFileName();if(progress!=null)progress.message("Extracting "+name);
                        File dest=begin(target,name,entry.isDirectory(),budget);
                        if(!entry.isDirectory()){
                            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                                OutputStream limit=new FilterOutputStream(out){
                                    @Override public void write(int b)throws IOException{budget.added(1);out.write(b);}
                                    @Override public void write(byte[] b,int off,int len)throws IOException{budget.added(len);out.write(b,off,len);}
                                };
                                rar.extractFile(entry,limit);limit.flush();
                            }catch(Exception e){dest.delete();throw e;}
                        }
                    }
                }break;
            case "tar":case "tgz":case "tbz":case "txz":
                try(TarArchiveInputStream tar=openTar(input,type(input))){
                    TarArchiveEntry entry;
                    while((entry=tar.getNextTarEntry())!=null){
                        if(entry.isSymbolicLink()||entry.isLink())continue;
                        String name=entry.getName();if(progress!=null)progress.message("Extracting "+name);
                        File dest=begin(target,name,entry.isDirectory(),budget);
                        if(!entry.isDirectory()){
                            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                                copy(tar,out,budget);
                            }catch(Exception e){dest.delete();throw e;}
                        }
                    }
                }break;
            case "gzip":
                String name=input.getName().replaceFirst("\\.gz$","");
                File dest=begin(target,name,false,budget);
                try(InputStream in=new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(input)));
                    OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                    copy(in,out,budget);
                }catch(Exception e){dest.delete();throw e;}
                break;
            default:throw new IOException("Unsupported format");
        }
        if(progress!=null)progress.message("Extracted "+budget.entries+" entries");
    }
}
