package com.ecut.campus;

import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/** 明示同意后才保存本人山交门户凭据。 */
final class SdjtuCredentialsDialog {
    private SdjtuCredentialsDialog() {}

    static void show(MainActivity activity, Runnable refresh) {
        if (SdjtuCredentials.isUpdating()) return;
        View form = activity.getLayoutInflater().inflate(R.layout.dialog_sdjtu_credentials, null);
        EditText username = form.findViewById(R.id.sdjtu_login_username);
        EditText password = form.findViewById(R.id.sdjtu_login_password);
        SdjtuCredentials.Account existing = SdjtuCredentials.load(activity);
        if (existing != null) {
            username.setText(existing.username);
            password.setHint("重新输入统一门户密码");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("统一门户自动登录")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton(existing == null ? "保存并启用" : "保存更改", null);
        if (SdjtuCredentials.hasRecord(activity)) {
            builder.setNeutralButton("忘记账号密码", (d, which) -> {
                boolean removed = SdjtuCredentials.clear(activity);
                refresh.run();
                Toast.makeText(activity, removed ? "已忘记账号密码，自动登录已关闭" : "已停用自动登录，请重试清除凭据",
                        Toast.LENGTH_SHORT).show();
            });
        }
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.setOnDismissListener(d -> password.getText().clear());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = username.getText().toString().trim();
            if (name.isEmpty()) {
                username.setError("请输入统一门户账号");
                username.requestFocus();
                return;
            }
            if (password.length() == 0) {
                password.setError("请输入统一门户密码");
                password.requestFocus();
                return;
            }
            v.setEnabled(false);
            // Keep the dialog open and prevent conflicting changes while WebView clears a prior session.
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            username.setEnabled(false);
            password.setEnabled(false);
            SdjtuCredentials.save(activity, name, password.getText().toString(), saved -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dialog.dismiss();
                refresh.run();
                Toast.makeText(activity, saved ? "已启用自动登录，可直接同步课表" : "未能在本机保存账号密码，请重试",
                        Toast.LENGTH_LONG).show();
            });
        });
    }
}
