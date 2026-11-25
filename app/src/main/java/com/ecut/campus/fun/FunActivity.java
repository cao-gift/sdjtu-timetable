package com.ecut.campus.fun;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.G;
import com.ecut.campus.Prefs;
import com.ecut.campus.R;
import com.ecut.campus.Ui;
import com.ecut.campus.data.ClassPrice;
import com.ecut.campus.data.TimetableStore;

/** 二级页「小玩意」：翻牌批语开关、学费（这节课值多少钱）、缺勤安全气囊。全部本地。 */
public class FunActivity extends AppCompatActivity {

    /** 从课程详情点「填一下学费」进来：直接弹学费框。 */
    public static final String EXTRA_EDIT_TUITION = "edit_tuition";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_EDIT_TUITION, false)) {
            getWindow().getDecorView().post(this::editTuition);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout col = Ui.page(this, "设置", "小玩意", "纯本地计算，不联网");

        Ui.label(this, col, "GRADES · 成绩");
        LinearLayout grades = Ui.card(this, col);
        boolean on = FunPrefs.verdictOn(this);
        Ui.row(this, grades, "01", "翻牌批语", "新成绩先看批语，再翻分数", on ? "开" : "关", v -> {
            FunPrefs.setVerdictOn(this, !FunPrefs.verdictOn(this));
            render();
        });

        Ui.label(this, col, "TIMETABLE · 课表");
        LinearLayout tt = Ui.card(this, col);
        double tuition = FunPrefs.tuition(this);
        String priceSub;
        if (tuition <= 0) {
            priceSub = "填学期学费，算每节课成本";
        } else {
            int total = ClassPrice.totalSections(TimetableStore.load(this), Prefs.totalWeeks(this));
            double per = ClassPrice.perSection(tuition, total);
            priceSub = "¥" + ClassPrice.yuan(tuition) + "/学期"
                    + (per > 0 ? " · 约 ¥" + ClassPrice.yuan(per) + "/节" : " · 课表为空");
        }
        Ui.row(this, tt, "01", "这节课值多少钱", priceSub, tuition > 0 ? "改" : "填", v -> editTuition());
        Ui.row(this, tt, "02", "缺勤安全气囊", "记录请假缺勤，近 1/3 描红提醒", "", null);
    }

    private void editTuition() {
        EditText et = new EditText(this);
        et.setText(FunPrefs.tuitionRaw(this));
        et.setSingleLine(true);
        et.setHint("例如 5500");
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        LinearLayout box = Ui.dialogBox(this, et, Ui.dp(this, 46));
        new AlertDialog.Builder(this).setTitle("每学期学费（元）").setView(box)
                .setMessage("只用来算「这节课值多少钱」，只存在本机。清空就不再显示。")
                .setNegativeButton("取消", null)
                .setNeutralButton("清空", (d, w) -> {
                    FunPrefs.setTuitionRaw(this, "");
                    render();
                })
                .setPositiveButton("保存", (d, w) -> {
                    String raw = et.getText().toString().trim();
                    if (!raw.isEmpty() && ClassPrice.parseTuition(raw) <= 0) {
                        Toast.makeText(this, "没看懂这个数，填个整数就行", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FunPrefs.setTuitionRaw(this, raw);
                    render();
                }).show();
    }
}
