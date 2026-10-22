package com.r1n24n.zipgo;
import android.content.*;import android.content.pm.PackageInstaller;import android.os.Build;
public final class ResultReceiver extends BroadcastReceiver {
public void onReceive(Context c,Intent i){int s=i.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);if(s==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=Build.VERSION.SDK_INT>=33?i.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class):(Intent)i.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(confirm);}}}
}