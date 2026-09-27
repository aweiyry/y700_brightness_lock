package com.y700.brightnesslock;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
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
        hint.setText("亮度档位（每 5% 一档，最高 100%）");
        hint.setTextColor(Color.parseColor("#9A9AA3"));
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);

        bar = new SeekBar(this);
        bar.setMax(19); // 0..19 => 5%..100%
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int lvl = (p + 1) * 5;
                levelView.setText(lvl + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                applyLevel((s.getProgress() + 1) * 5);
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        Button minus = new Button(this);
        minus.setText("− 5%");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int p = bar.getProgress() - 1;
                if (p < 0) p = 0;
                bar.setProgress(p);
                applyLevel((p + 1) * 5);
            }
        });
        Button plus = new Button(this);
        plus.setText("＋ 5%");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int p = bar.getProgress() + 1;
                if (p > bar.getMax()) p = bar.getMax();
                bar.setProgress(p);
                applyLevel((p + 1) * 5);
            }
        });
        row.addView(minus, bp);
        row.addView(plus, bp);

        lockSwitch = new Switch(this);
        lockSwitch.setText("锁定亮度（防高负荷/温控降亮度）");
        lockSwitch.setTextColor(Color.WHITE);
        lockSwitch.setChecked(true); // 先设状态，再挂监听，避免触发回调
        lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (applying) return;
                if (checked) {
                    applyLevel((bar.getProgress() + 1) * 5);
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
        root.addView(row);
        root.addView(lockSwitch);
        root.addView(statusView);

        setContentView(root);
        refreshState();
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
                int[] bl = readBacklightRoot();
                final int percent = (int) Math.round(bl[1] * 100.0 / bl[0]);
                final String target = runRoot("cat " + TARGET);
                final boolean locked = target != null && target.trim().matches("\\d+");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        int snap = snapLevel(percent);
                        applying = true;
                        bar.setProgress(snap / 5 - 1);
                        lockSwitch.setChecked(locked);
                        applying = false;
                        levelView.setText(snap + "%");
                        statusView.setText("背光 " + percent + "%  |  节点 " + backlightDir
                                + "  |  Root " + (haveRoot() ? "OK" : "缺失"));
                    }
                });
            }
        }).start();
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

    private static int snapLevel(int percent) {
        if (percent < 5) percent = 5;
        if (percent > 100) percent = 100;
        return ((percent + 2) / 5) * 5;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
