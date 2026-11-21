package org.polterheist.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Small native UI vocabulary shared by the device hub and audio screen. */
final class DeviceUi {
    static final int BG = Color.rgb(16,20,18), CARD = Color.rgb(26,32,28),
            TEXT = Color.rgb(239,244,233), MUTED = Color.rgb(161,174,161), ACCENT = Color.rgb(197,242,119);
    final Activity activity;
    final LinearLayout body;
    DeviceUi(Activity activity) {
        this.activity = activity;
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        body = column(); body.setPadding(dp(22),dp(20),dp(22),dp(32));
        scroll.addView(body); activity.setContentView(scroll);
    }
    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    TextView text(String label, int size, int color) {
        TextView v = new TextView(activity); v.setText(label); v.setTextSize(size); v.setTextColor(color); return v;
    }
    void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(top); parent.addView(child,p);
    }
    void heading(String title, String subtitle) {
        add(body,text("POLTERHEIST / DISPOSITIVOS",11,ACCENT),4);
        TextView h = text(title,32,TEXT); h.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(body,h,24);
        add(body,text(subtitle,15,MUTED),12);
    }
    LinearLayout card(String title) {
        LinearLayout c = column(); c.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(18)); c.setBackground(bg);
        TextView t = text(title,19,TEXT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(c,t,0); add(body,c,20); return c;
    }
    Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setMinHeight(dp(48));
        b.setTextColor(primary ? BG : ACCENT);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? ACCENT : Color.rgb(38,48,39)));
        b.setOnClickListener(v -> action.run()); return b;
    }
}
