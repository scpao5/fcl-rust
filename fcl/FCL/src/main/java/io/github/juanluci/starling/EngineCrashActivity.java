package io.github.juanluci.starling;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The engine's crash, on its own screen: native View code only, no layout to inflate and no theme
 * to resolve, since a crash is exactly when those may be the thing that is broken.
 */
public final class EngineCrashActivity extends Activity {

    public static final String EXTRA_TEXT = "io.github.juanluci.starling.CRASH_TEXT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TEXT);
        if (report == null || report.isEmpty()) {
            report = readNativeCrashFile();
        }
        if (report == null) {
            report = "The engine stopped, and wrote no report.";
        }
        final String text = report;

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF16171B);
        pad(root, pad);

        TextView title = new TextView(this);
        title.setText("游戏已停止运行");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("下面是引擎留下的信息，可以长按选择复制。");
        hint.setTextColor(0xFF9AA0A6);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pad(hint, 0, pad / 2, 0, pad / 2);
        root.addView(hint);

        ScrollView vertical = new ScrollView(this);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFFE8EAED);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setHorizontallyScrolling(true);
        horizontal.addView(body, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vertical.addView(horizontal, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(vertical, weight);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        pad(buttons, 0, pad, 0, 0);

        Button copy = new Button(this);
        copy.setText("复制");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(this);
        close.setText("退出");
        close.setOnClickListener(v -> finish());
        buttons.addView(close, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);
        setContentView(root);
    }

    private String readNativeCrashFile() {
        try {
            // The app's own files: a native crash report cannot go anywhere a plain app may not
            // write (/data/local/tmp belongs to the shell user).
            File file = new File(getFilesDirPath(), "engine_crash.txt");
            if (!file.isFile()) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private String getFilesDirPath() {
        return getFilesDir().getAbsolutePath();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pad(android.view.View view, int all) {
        pad(view, all, all, all, all);
    }

    private void pad(android.view.View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }
}
