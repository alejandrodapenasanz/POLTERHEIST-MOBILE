package org.polterheist.mobile;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(16,20,18), CARD = Color.rgb(26,32,28), BORDER = Color.rgb(48,58,49),
            TEXT = Color.rgb(239,244,233), MUTED = Color.rgb(161,174,161), ACCENT = Color.rgb(197,242,119);
    private AppController app;
    private LinearLayout devices, body;
    private TextView network, fileName, fileDetails, scanNote, state, destination, browserAddress, viewerState, count;
    private ImageView thumbnail;
    private Button pick, search, send, pause, stop, preview, browser;
    private CheckBox repeat;
    private LinearLayout browserPanel;
    private String previewed = "";
    private boolean rendering;
    private final Runnable listener = this::render;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable viewerTick = new Runnable() {
        @Override public void run() {
            if (app.sharing && app.target == AppController.Target.BROWSER) {
                viewerState.setText(app.viewerConnected() ? "Navegador conectado · controles disponibles" : "Esperando al navegador de la TV…");
            }
            timer.postDelayed(this, 1500);
        }
    };
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) app.importMedia(uri);
    });
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> app.send());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = ((PolterApp) getApplication()).controller;
        app.initializeCast();
        build();
        if (savedInstanceState == null && app.devices.isEmpty() && !app.scanning && !app.sharing) app.scan(null);
    }
    @Override protected void onStart() {
        super.onStart(); app.addListener(listener); app.refreshNetwork(); timer.post(viewerTick);
    }
    @Override protected void onStop() {
        app.removeListener(listener); timer.removeCallbacks(viewerTick);
        super.onStop();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG); scroll.setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(system.left, system.top, system.right, system.bottom); return insets;
        });
        body = column(); body.setPadding(dp(22), dp(20), dp(22), dp(32));
        scroll.addView(body); setContentView(scroll);
        LinearLayout brand = row();
        ImageView ghost = new ImageView(this); ghost.setImageResource(R.drawable.ic_ghost); ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(ghost, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView wordmark = text("POLTERHEIST", 18, TEXT); wordmark.setTypeface(Typeface.DEFAULT, Typeface.BOLD); wordmark.setLetterSpacing(.13f);
        LinearLayout.LayoutParams wordLayout = new LinearLayout.LayoutParams(0, -2, 1); wordLayout.leftMargin=dp(12); brand.addView(wordmark, wordLayout);
        TextView version = text("MOBILE / 01", 10, MUTED); version.setLetterSpacing(.05f); brand.addView(version);
        body.addView(brand);
        TextView heading = text("Tu contenido.\nEn tu pantalla.", 34, TEXT); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setLineSpacing(0, 1.02f); margin(body, heading, 28);
        TextView intro = text("Elige algo, conecta tu TV y dale al play.", 15, MUTED); margin(body, intro, 12);
        network = text("", 13, ACCENT); network.setPadding(dp(12),dp(9),dp(12),dp(9)); network.setBackground(shape(Color.rgb(33,46,30), 12, 0)); margin(body,network,18);

        LinearLayout mediaCard = card("01", "Elige tu contenido");
        thumbnail = new ImageView(this); thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER); thumbnail.setImageResource(R.drawable.ic_ghost);
        thumbnail.setContentDescription("Vista previa del archivo seleccionado"); thumbnail.setPadding(dp(25),dp(25),dp(25),dp(25)); thumbnail.setBackground(shape(BG,14,0));
        margin(mediaCard, thumbnail, 16, dp(150));
        fileName = text("Una foto. Un vídeo. Lo que quieras.", 16, TEXT); fileName.setTypeface(Typeface.DEFAULT, Typeface.BOLD); margin(mediaCard,fileName,16);
        fileDetails = text("Selecciona un archivo de tu móvil", 13, MUTED); margin(mediaCard,fileDetails,7);
        pick = button("Elegir imagen o vídeo", true, () -> picker.launch(new String[]{"image/*", "video/*"})); margin(mediaCard,pick,16);
        preview = button("Ver a pantalla completa", false, () -> startActivity(new Intent(this, PreviewActivity.class))); margin(mediaCard,preview,6);

        LinearLayout tvCard = card("02", "Conecta tu TV");
        LinearLayout searchRow = row();
        count = text("En tu red", 14, MUTED); searchRow.addView(count, new LinearLayout.LayoutParams(0,-2,1));
        search = button("Buscar TVs", false, () -> app.scan(null)); searchRow.addView(search);
        margin(tvCard,searchRow,14);
        devices = column(); margin(tvCard,devices,10);
        scanNote = text("", 12, MUTED); margin(tvCard,scanNote,12);
        Button manual = button("Buscar DLNA por IP", false, this::manualIp); margin(tvCard,manual,8);

        LinearLayout controls = card("03", "Listo para emitir");
        destination = text("", 16, TEXT); destination.setTypeface(Typeface.DEFAULT,Typeface.BOLD); margin(controls,destination,16);
        state = text("", 14, MUTED); state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); margin(controls,state,10);
        repeat = new CheckBox(this); repeat.setText("Repetir vídeo"); repeat.setTextColor(TEXT); repeat.setTextSize(14); repeat.setMinHeight(dp(48));
        repeat.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        repeat.setOnCheckedChangeListener((button, checked) -> { if (!rendering) app.setLoop(checked); }); margin(controls,repeat,10);
        send = button("Enviar a la TV", true, this::send); margin(controls,send,10);
        LinearLayout playback = row();
        pause = button("Pausar", false, app::togglePause); stop = button("Detener", false, app::stop);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,-2,1); half.rightMargin=dp(5); playback.addView(pause,half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0,-2,1); other.leftMargin=dp(5); playback.addView(stop,other); margin(controls,playback,8);
        browserPanel = column();
        TextView instructions = text("En la TV, abre su navegador y escribe:", 14, TEXT); margin(browserPanel,instructions,12);
        browserAddress = text("", 19, ACCENT); browserAddress.setTypeface(Typeface.MONOSPACE); browserAddress.setTextIsSelectable(true); margin(browserPanel,browserAddress,10);
        viewerState = text("", 13, MUTED); margin(browserPanel,viewerState,8);
        Button copy = button("Copiar enlace", false, () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("POLTERHEIST TV",app.viewerUrl()));
            Toast.makeText(this,"Enlace copiado",Toast.LENGTH_SHORT).show();
        }); margin(browserPanel,copy,8); controls.addView(browserPanel);

        LinearLayout alternatives = card("+", "Otras formas de conectar");
        TextView explanation = text("Si tu TV no aparece, prueba su navegador o las opciones de duplicación de tu móvil.", 14, MUTED); margin(alternatives,explanation,14);
        browser = button("Usar el navegador de la TV", false, app::chooseBrowser); margin(alternatives,browser,12);
        margin(alternatives,button("Abrir duplicación de pantalla", false, this::mirroring),6);
        margin(alternatives,button("Compatibilidad y ayuda", false, this::help),6);
        TextView footer = text("HECHO PARA TU RED, DESDE TU MÓVIL\nMantén el móvil conectado durante la emisión.", 11, MUTED); footer.setGravity(Gravity.CENTER); footer.setLineSpacing(dp(7),1); margin(body,footer,24);
    }

    private void render() {
        if (network == null) return;
        rendering = true;
        network.setText("●  " + app.networkLabel);
        destination.setText(app.targetName);
        state.setText(app.status);
        search.setEnabled(!app.scanning); search.setText(app.scanning ? "Buscando…" : "Buscar TVs");
        pick.setEnabled(!app.busy && !app.sharing); preview.setVisibility(app.media == null ? View.GONE : View.VISIBLE);
        send.setEnabled(app.media != null && app.target != AppController.Target.NONE && !app.sharing && !app.busy);
        send.setText(app.busy ? "Preparando…" : app.sharing ? "Emisión activa" : "Enviar a la TV");
        pause.setEnabled(app.sharing && !app.busy && app.media != null && !app.media.isImage() &&
                (app.target != AppController.Target.DLNA || app.dlna.actions.contains("Pause")));
        pause.setText(app.paused ? "Reanudar" : "Pausar"); stop.setEnabled(app.sharing);
        repeat.setChecked(app.loop); repeat.setEnabled(!app.sharing && !app.busy && app.media != null && !app.media.isImage());
        browser.setEnabled(!app.sharing && !app.busy);
        for (View control : new View[]{pick, search, send, pause, stop, repeat, browser}) control.setAlpha(control.isEnabled() ? 1f : .42f);
        if (app.media != null) {
            fileName.setText(app.media.name);
            fileDetails.setText((app.media.isImage() ? "Imagen · JPEG preparado" : "Vídeo · " + app.media.mime) + " · " +
                    String.format(Locale.getDefault(), "%.1f MB", app.media.file.length() / 1048576.0));
            if (!previewed.equals(app.media.file.getPath())) {
                previewed = app.media.file.getPath();
                if (app.media.isImage()) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
                    thumbnail.setImageBitmap(BitmapFactory.decodeFile(previewed,options)); thumbnail.setPadding(0,0,0,0);
                } else { thumbnail.setImageResource(android.R.drawable.ic_media_play); thumbnail.setPadding(dp(40),dp(40),dp(40),dp(40)); }
            }
        }
        browserPanel.setVisibility(app.sharing && app.target == AppController.Target.BROWSER && !app.viewerUrl().isEmpty() ? View.VISIBLE : View.GONE);
        browserAddress.setText(app.viewerUrl());
        devices.removeAllViews();
        int total = app.devices.size() + (app.cast == null ? 0 : app.cast.routes.size());
        count.setText(total == 0 ? "En tu red" : total + " receptores");
        if (app.cast != null) for (var route : app.cast.routes) {
            deviceRow(route.getName(), "Google Cast · vídeo e imágenes", app.target == AppController.Target.CAST && app.targetName.equals(route.getName()), () -> app.chooseCast(route));
        }
        for (DlnaDevice device : app.devices) deviceRow(device.name.isEmpty() ? "Receptor DLNA" : device.name,
                "DLNA · " + device.host + (device.playable() ? "" : " · solo detectado"), app.target == AppController.Target.DLNA && app.dlna == device, () -> app.chooseDlna(device));
        if (total == 0) {
            TextView empty = text(app.scanning ? "Buscando pantallas en tu red…" : "Todavía no hay TVs compatibles en la lista.\nEnciende la TV y comprueba su conexión.", 14, MUTED);
            empty.setPadding(dp(12),dp(20),dp(12),dp(20)); devices.addView(empty);
        }
        String castNote = app.cast != null && !app.cast.unavailable.isEmpty() ? "\n" + app.cast.unavailable : "";
        scanNote.setText(app.scanNote + castNote);
        rendering = false;
    }

    private void deviceRow(String name, String detail, boolean selected, Runnable action) {
        LinearLayout row = column(); row.setPadding(dp(14),dp(12),dp(14),dp(12)); row.setBackground(shape(selected ? Color.rgb(40,54,33) : BG,12, selected ? ACCENT : BORDER));
        TextView title = text((selected ? "●  " : "▣  ") + name,16,selected?ACCENT:TEXT); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); row.addView(title);
        TextView sub = text(detail,12,MUTED); margin(row,sub,6); row.setFocusable(true); row.setClickable(true);
        row.setContentDescription(name + ", " + detail + (selected ? ", seleccionado" : ", seleccionar"));
        row.setOnClickListener(v -> action.run()); margin(devices,row,8);
    }
    private void send() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notification_asked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked",true).apply();
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else app.send();
    }
    private void manualIp() {
        EditText input = new EditText(this); input.setHint("192.168.1.50"); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setInputType(InputType.TYPE_CLASS_PHONE); input.setPadding(dp(24),dp(16),dp(24),dp(16));
        new AlertDialog.Builder(this).setTitle("Buscar DLNA por IP").setMessage("Introduce la IP que muestra tu TV en sus ajustes de red. Se consultará solo ese dispositivo.")
                .setView(input).setPositiveButton("Buscar",(dialog,which) -> app.scan(input.getText().toString().trim())).setNegativeButton("Cancelar",null).show();
    }
    private void mirroring() {
        new AlertDialog.Builder(this).setTitle("Duplicar desde Android").setMessage("Activa Screen Mirroring / Miracast en la TV si lo admite. En los ajustes del móvil, conecta con ella. Después vuelve aquí y abre tu archivo a pantalla completa.\n\nEsta función depende del fabricante del móvil y de la TV.")
                .setPositiveButton("Abrir ajustes",(dialog,which) -> {
                    try { startActivity(new Intent(Settings.ACTION_CAST_SETTINGS)); }
                    catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                }).setNegativeButton("Cerrar",null).show();
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Una red. Varias formas de conectar.").setMessage(
                "GOOGLE CAST\nChromecast y TVs con Google Cast activado. El móvil necesita Google Play Services. La primera conexión puede necesitar Internet.\n\n" +
                "DLNA\nTVs que anuncien MediaRenderer y controles de reproducción. En algunas Samsung, LG, Sony y otras marcas hay que activar compartir contenido o permitir el móvil en la TV.\n\n" +
                "NAVEGADOR\nAbre el enlace local en el navegador de la TV. El vídeo arranca sin sonido; actívalo con el mando. Algunas TVs no tienen navegador compatible.\n\n" +
                "SI NO APARECE\nUsa la misma red local; la TV puede estar por Ethernet. Evita Wi-Fi de invitados y aislamiento de clientes. Revisa VPN y permisos de la TV. Buscar por IP solo prueba DLNA.\n\n" +
                "FORMATOS\nLas imágenes se preparan como JPEG. MP4 con vídeo H.264 y audio AAC suele ofrecer mayor compatibilidad; los vídeos no se convierten automáticamente.\n\n" +
                "LÍMITES\nUna TV por sesión, sin sincronización entre pantallas. Bluetooth no transmite vídeo de forma universal. TVs sin receptor, navegador o duplicación compatible necesitan un receptor externo, por ejemplo por HDMI. Mantén el móvil conectado: esta versión no instala contenido en la TV.")
                .setPositiveButton("Entendido",null).show();
    }

    private LinearLayout card(String number,String title) {
        LinearLayout card=column(); card.setPadding(dp(18),dp(18),dp(18),dp(18));card.setBackground(shape(CARD,20,BORDER));
        LinearLayout top=row();TextView index=text(number,12,ACCENT); index.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); top.addView(index);
        TextView label=text(title,19,TEXT);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD); LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.leftMargin=dp(14);top.addView(label,params);
        card.addView(top); margin(body,card,18);return card;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(2),1);return v;}
    private Button button(String title,boolean primary,Runnable action){
        Button b=new androidx.appcompat.widget.AppCompatButton(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?BG:TEXT);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));b.setPadding(dp(16),dp(8),dp(16),dp(8));
        b.setBackground(shape(primary?ACCENT:Color.rgb(35,43,37),13,primary?0:BORDER));b.setOnClickListener(v->action.run());
        b.setStateListAnimator(null); return b;
    }
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private void margin(LinearLayout parent,View child,int top){margin(parent,child,top,-2);}
    private void margin(LinearLayout parent,View child,int top,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height);p.topMargin=dp(top);parent.addView(child,p);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
