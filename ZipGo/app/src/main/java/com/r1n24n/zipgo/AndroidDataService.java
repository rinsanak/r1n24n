package com.r1n24n.zipgo;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.*;
import java.util.*;

/** Runs ONLY in the opt-in Shizuku UserService (shell/root identity). */
public final class AndroidDataService extends Binder {
    static final String ROOT = "/storage/emulated/0/Android/data";
    static final int LIST = 1, READ = 2, WRITE = 3, MKDIR = 4, RENAME = 5, DELETE = 6;
    static final int MAX_CHUNK = 32768;

    public AndroidDataService() { super(); }

    private static File checked(String path) throws IOException {
        File base = new File(ROOT).getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        String b = base.getPath(), p = file.getPath();
        if (!p.equals(b) && !p.startsWith(b + File.separator)) {
            throw new IOException("Outside Android/data");
        }
        return file;
    }
    private static String safeName(String name) throws IOException {
        if (name == null || name.length() == 0 || name.equals(".") || name.equals("..")
              || name.contains("/") || name.contains("\\") || name.indexOf('\u0000') >= 0)
            throw new IOException("Invalid file name");
        return name;
    }
    private static void list(Parcel in, Parcel out) throws Exception {
        File folder = checked(in.readString());
        int offset = in.readInt();
        if (offset < 0 || offset > 1000000) throw new IOException("Invalid page");
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Cannot read this folder. Shizuku shell access may be blocked by Android.");
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        int n = Math.min(80, Math.max(0, files.length - offset));
        out.writeInt(0);
        out.writeInt(files.length);
        out.writeInt(n);
        for (int k = 0; k < n; ++k) {
            File f = files[offset + k];
            out.writeString(f.getName());
            out.writeInt(f.isDirectory() ? 1 : 0);
            out.writeLong(f.length());
        }
    }
    private static void read(Parcel in, Parcel out) throws Exception {
        File file = checked(in.readString());
        long at = in.readLong();
        if (at < 0 || !file.isFile()) throw new IOException("Not a readable file");
        byte[] buf = new byte[MAX_CHUNK];
        int n;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            f.seek(at);
            n = f.read(buf);
        }
        out.writeInt(0);
        if (n < 0) out.writeByteArray(new byte[0]);
        else out.writeByteArray(Arrays.copyOf(buf, n));
    }
    private static void write(Parcel in, Parcel out) throws Exception {
        File target = checked(in.readString());
        long at = in.readLong();
        byte[] bytes = in.createByteArray();
        if (at < 0 || bytes == null || bytes.length > MAX_CHUNK || target.isDirectory() || !target.getParentFile().isDirectory())
            throw new IOException("Invalid write");
        try (RandomAccessFile f = new RandomAccessFile(target, "rw")) {
            if (at == 0) f.setLength(0);
            f.seek(at);
            f.write(bytes);
        }
        out.writeInt(0);
    }
    private static void mkdir(Parcel in, Parcel out) throws Exception {
        File parent = checked(in.readString());
        String name = safeName(in.readString());
        if (!parent.isDirectory()) throw new IOException("Parent folder missing");
        File dir = checked(new File(parent, name).getPath());
        if (!dir.mkdir()) throw new IOException("Could not create folder");
        out.writeInt(0);
    }
    private static void rename(Parcel in, Parcel out) throws Exception {
        File original = checked(in.readString());
        if (original.getPath().equals(checked(ROOT).getPath())) throw new IOException("Root cannot be renamed");
        File dest = checked(new File(original.getParentFile(), safeName(in.readString())).getPath());
        if (!original.exists() || dest.exists() || !original.renameTo(dest)) throw new IOException("Rename failed or name already exists");
        out.writeInt(0);
    }
    private static void delete(Parcel in, Parcel out) throws Exception {
        File file = checked(in.readString());
        if (file.getPath().equals(checked(ROOT).getPath())) throw new IOException("Cannot delete Android/data root");
        if (!file.delete()) throw new IOException("Cannot delete (folders must be empty)");
        out.writeInt(0);
    }

    @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
        if (code == 16777115) {
            // Shizuku UserService destroy transaction, no persistent service.
            System.exit(0);
            return true;
        }
        if (code < LIST || code > DELETE) return super.onTransact(code, in, out, flags);
        try {
            switch (code) {
                case LIST: list(in, out); break;
                case READ: read(in, out); break;
                case WRITE: write(in, out); break;
                case MKDIR: mkdir(in, out); break;
                case RENAME: rename(in, out); break;
                case DELETE: delete(in, out); break;
            }
        } catch (Throwable e) {
            // Each successful reply starts with 0. On errors the reply starts with 1.
            out.setDataPosition(0);
            out.setDataSize(0);
            out.writeInt(1);
            out.writeString(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return true;
    }
}
