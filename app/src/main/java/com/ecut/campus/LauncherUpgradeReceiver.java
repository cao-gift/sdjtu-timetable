package com.ecut.campus;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/** 旧版切换桌面图标分两步进行；升级时修复中途中断留下的入口。 */
public final class LauncherUpgradeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg);
        List<ResolveInfo> entries = pm.queryIntentActivities(launcher, 0);
        if (entries.size() == 1) return;

        String preferred = Prefs.legacyLauncherAlias(context);
        // 先启用原来选中的入口，再关闭同次旧切换留下的多余入口。
        pm.setComponentEnabledSetting(new ComponentName(pkg, preferred),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (ResolveInfo entry : entries) {
            if (preferred.equals(entry.activityInfo.name)) continue;
            pm.setComponentEnabledSetting(new ComponentName(pkg, entry.activityInfo.name),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
