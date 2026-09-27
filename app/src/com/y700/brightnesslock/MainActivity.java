package com.y700.brightnesslock;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private static final String TARGET = "/data/adb/brightness_lock/target";
    private static final int[] PRESETS = {5, 20, 50, 80, 100};
    private static String backlightDir = null;

    private TextView levelView;
    private TextView statusView;
    private SeekBar bar;
    private Switch lockSwitch;
    private boolean applying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        backlightDir = findBacklightDir();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(20));
        root.setBackgroundColor(Color.parseColor("#0E0E14"));

        TextView title = new TextView(this);
        title.setText("亮度锁定");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        levelView = new TextView(this);
        levelView.setText("--%");
        levelView.setTextColor(Color.WHITE);
        levelView.setTextSize(76);
        levelView.setGravity(Gravity.CENTER);
        levelView.setPadding(0, dp(28), 0, dp(4));
        levelView.setTypeface(null, Typeface.BOLD);

        TextView hint = new TextView(this);
        hint.setText("亮度（1% 精度，1~100%）");
        hint.setTextColor(Color.parseColor("#9A9AA3"));
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);

        bar = new SeekBar(this);
        bar.setMax(99); // progress 0..99 => 1%..100%
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    levelView.setText((p + 1) + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                applyLevel(s.getProgress() + 1);
            }
        });

        // 预设常用档位
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        pp.leftMargin = dp(3);
        pp.rightMargin = dp(3);
        for (int lvl : PRESETS) {
            presetRow.addView(makePresetButton(lvl), pp);
        }

        // 自定义档位输入
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("自定义 1~100");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#6A6A75"));
        input.setBackgroundColor(Color.parseColor("#1C1C26"));
        input.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(48), 2f);
        ip.rightMargin = dp(8);
        customRow.addView(input, ip);
        Button customApply = new Button(this);
        customApply.setText("应用");
        customApply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = input.getText().toString().trim();
                if (s.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入 1~100 的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    int lvl = Integer.parseInt(s);
                    if (lvl < 1 || lvl > 100) {
                        Toast.makeText(MainActivity.this, "档位需在 1~100 之间", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    setAndApply(lvl);
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "请输入有效的整数", Toast.LENGTH_SHORT).show();
                }
            }
        });
        customRow.addView(customApply, new LinearLayout.LayoutParams(0, dp(48), 1f));

        lockSwitch = new Switch(this);
        lockSwitch.setText("锁定亮度（防高负荷/温控降亮度）");
        lockSwitch.setTextColor(Color.WHITE);
        lockSwitch.setChecked(true); // 先设状态，再挂监听，避免触发回调
        lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (applying) return;
                if (checked) {
                    applyLevel(bar.getProgress() + 1);
                } else {
                    unlock();
                }
            }
        });

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9A9AA3"));
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setText("正在初始化…");

        root.addView(title);
        root.addView(levelView);
        root.addView(hint);
        root.addView(bar);
        root.addView(presetRow);
        root.addView(customRow);
        root.addView(lockSwitch);
        root.addView(statusView);

        setContentView(root);
        refreshState();
    }

    private Button makePresetButton(final int lvl) {
        Button b = new Button(this);
        b.setText(lvl + "%");
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setAndApply(lvl);
            }
        });
        return b;
    }

    /** 设置档位到进度条/显示并立即应用（不依赖用户是否拖过进度条） */
    private void setAndApply(int lvl) {
        lvl = clamp(lvl, 1, 100);
        bar.setProgress(lvl - 1);
        levelView.setText(lvl + "%");
        applyLevel(lvl);
    }

    private void applyLevel(final int level) {
        new Thread(new Runnable() {
            @Override public void run() {
                runRoot(buildSetCommand(level));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshState();
                        Toast.makeText(MainActivity.this, "已设为 " + level + "%", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void unlock() {
        new Thread(new Runnable() {
            @Override public void run() {
                runRoot("echo off > " + TARGET);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshState();
                        Toast.makeText(MainActivity.this, "已解锁，恢复系统控制", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void refreshState() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String target = runRoot("cat " + TARGET);
                final boolean locked = target != null && target.trim().matches("\\d+");
                final int percent;
                if (locked) {
                    // 锁定态：背光是 App/守护进程直写的，以背光 raw 为准
                    int[] bl = readBacklightRoot();
                    percent = clamp((int) Math.round(bl[1] * 100.0 / bl[0]), 1, 100);
                } else {
                    // 解锁态：以系统亮度为准（用户看到的系统档位），严格匹配
                    percent = readSystemBrightnessPercent();
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        applying = true;
                        bar.setProgress(percent - 1);
                        lockSwitch.setChecked(locked);
                        applying = false;
                        levelView.setText(percent + "%");
                        statusView.setText("亮度 " + percent + "%  |  " + (locked ? "已锁定" : "未锁定")
                                + "  |  Root " + (haveRoot() ? "OK" : "缺失"));
                    }
                });
            }
        }).start();
    }

    /** 读取系统亮度百分比（0~255 -> 0~100%），解锁态下与系统滑块严格一致 */
    private int readSystemBrightnessPercent() {
        String sb = runRoot("settings get system screen_brightness");
        int v = parseIntSafe(sb, 0);
        if (v <= 0) return 1;
        return clamp((int) Math.round(v * 100.0 / 255.0), 1, 100);
    }

    private String buildSetCommand(int level) {
        String max = backlightDir + "/max_brightness";
        String bright = backlightDir + "/brightness";
        return "mkdir -p /data/adb/brightness_lock && echo " + level + " > " + TARGET
                + " && M=$(cat " + max + ") && echo $(( " + level + " * M / 100 )) > " + bright
                + " && for d in /sys/class/thermal/cooling_device*; do [ \"$(cat $d/type)\" = panel0-backlight ] && echo 0 > $d/cur_state; done";
    }

    private static String findBacklightDir() {
        String p = "/sys/class/backlight/panel0-backlight";
        if (new File(p + "/brightness").exists()) return p;
        File dir = new File("/sys/class/backlight");
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (new File(f, "brightness").exists()) return f.getAbsolutePath();
            }
        }
        return p;
    }

    private int[] readBacklightRoot() {
        String maxPath = backlightDir + "/max_brightness";
        String brightPath = backlightDir + "/brightness";
        String out = runRoot("echo M=$(cat " + maxPath + " 2>/dev/null); echo R=$(cat " + brightPath + " 2>/dev/null)");
        int max = 4095, raw = 0;
        if (out != null) {
            for (String line : out.split("\n")) {
                if (line.startsWith("M=")) max = parseIntSafe(line.substring(2), 4095);
                else if (line.startsWith("R=")) raw = parseIntSafe(line.substring(2), 0);
            }
        }
        return new int[]{max, raw};
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static boolean haveRoot() {
        return new File("/system/bin/su").canExecute()
                || new File("/system/xbin/su").canExecute()
                || new File("/sbin/su").canExecute()
                || new File("/data/adb/ksu/bin/su").canExecute();
    }

    private static String runRoot(String cmd) {
        String[] candidates = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/ksu/bin/su"};
        for (String su : candidates) {
            if (!new File(su).canExecute()) continue;
            try {
                Process p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                p.waitFor();
                return sb.toString().trim();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
