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
    private TextView network, fileName, fileDetails, scanNote, state, destination, browserAddress, viewerState, count, p2pState, hotspotState;
    private Button p2pDisconnect, hotspotButton;
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
    private final ActivityResultLauncher<String[]> nearbyPermission = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
        if (pendingNearby != null) { Runnable next = pendingNearby; pendingNearby = null; next.run(); }
    });
    private Runnable pendingNearby;
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
        count = text("Cerca de ti", 14, MUTED); searchRow.addView(count, new LinearLayout.LayoutParams(0,-2,1));
        search = button("Buscar TVs", false, () -> withNearby(false, () -> app.scan(null))); searchRow.addView(search);
        margin(tvCard,searchRow,14);
        devices = column(); margin(tvCard,devices,10);
        scanNote = text("", 12, MUTED); margin(tvCard,scanNote,12);
        Button manual = button("Buscar por IP o en otra red", false, this::manualIp); margin(tvCard,manual,8);
        p2pState = text("", 13, ACCENT); margin(tvCard,p2pState,12);
        p2pDisconnect = button("Desconectar Wi-Fi Direct", false, app::disconnectWifiDirect); margin(tvCard,p2pDisconnect,6);

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
        TextView explanation = text("Si tu TV no aparece, crea una red desde el móvil, prueba su navegador o las opciones de duplicación de tu móvil.", 14, MUTED); margin(alternatives,explanation,14);
        browser = button("Usar el navegador de la TV", false, app::chooseBrowser); margin(alternatives,browser,12);
        hotspotButton = button("Crear red del móvil para la TV", false, this::hotspot); margin(alternatives,hotspotButton,6);
        hotspotState = text("", 15, ACCENT); hotspotState.setTypeface(Typeface.MONOSPACE); hotspotState.setTextIsSelectable(true); margin(alternatives,hotspotState,8);
        margin(alternatives,button("Abrir duplicación de pantalla", false, this::mirroring),6);
        margin(alternatives,button("Compatibilidad y ayuda", false, this::help),6);
        TextView footer = text("TU RED, OTRA RED O SIN RED, DESDE TU MÓVIL\nMantén el móvil conectado durante la emisión.", 11, MUTED); footer.setGravity(Gravity.CENTER); footer.setLineSpacing(dp(7),1); margin(body,footer,24);
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
        java.util.List<NearbyDevice> nearby = app.visibleNearby();
        int total = app.devices.size() + nearby.size() + (app.cast == null ? 0 : app.cast.routes.size());
        count.setText(total == 0 ? "Cerca de ti" : total + " receptores");
        if (app.cast != null) for (var route : app.cast.routes) {
            deviceRow(route.getName(), "Google Cast · vídeo e imágenes", app.target == AppController.Target.CAST && app.targetName.equals(route.getName()), () -> app.chooseCast(route));
        }
        for (DlnaDevice device : app.devices) deviceRow(device.name.isEmpty() ? "Receptor DLNA" : device.name,
                "DLNA · " + device.host + (device.via.isEmpty() ? "" : " · " + device.via) + (device.playable() ? "" : " · solo detectado"), app.target == AppController.Target.DLNA && app.dlna == device, () -> app.chooseDlna(device));
        for (NearbyDevice device : nearby) deviceRow(device.name, device.sourceLabel() + " · " + device.detail + " · " + nearbyHint(device),
                false, () -> chooseNearby(device));
        if (total == 0) {
            TextView empty = text(app.scanning || app.nearbyScanning ? "Buscando pantallas en tus redes y cerca del móvil…" : "Todavía no hay TVs en la lista.\nEnciende la TV, activa Bluetooth/Wi-Fi Direct en ella o prueba otra red.", 14, MUTED);
            empty.setPadding(dp(12),dp(20),dp(12),dp(20)); devices.addView(empty);
        }
        String castNote = app.cast != null && !app.cast.unavailable.isEmpty() ? "\n" + app.cast.unavailable : "";
        scanNote.setText(app.scanNote + castNote);
        boolean p2p = app.wifiDirectConnected();
        p2pState.setVisibility(p2p ? View.VISIBLE : View.GONE); p2pDisconnect.setVisibility(p2p ? View.VISIBLE : View.GONE);
        p2pState.setText("●  Wi-Fi Direct conectado con " + app.wifiDirectName());
        boolean hotspot = app.hotspotActive();
        hotspotButton.setText(hotspot ? "Apagar la red del móvil" : "Crear red del móvil para la TV");
        hotspotState.setVisibility(hotspot && !app.hotspotInfo.isEmpty() ? View.VISIBLE : View.GONE); hotspotState.setText(app.hotspotInfo);
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
        EditText input = new EditText(this); input.setHint("192.168.1.50 · 192.168.2.0/24"); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); input.setPadding(dp(24),dp(16),dp(24),dp(16));
        new AlertDialog.Builder(this).setTitle("Buscar por IP o en otra red").setMessage("Escribe la IP que muestra tu TV en sus ajustes de red, aunque esté en otra red de tu casa u oficina (otra subred, otra banda, Ethernet en otra VLAN). También puedes buscar en una red completa (192.168.2.0/24) o un rango (192.168.2.10-60).\n\nEl router debe permitir el tráfico entre ambas redes.")
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
                "OTRA RED\nSi la TV está en otra subred enrutada, usa Buscar por IP o en otra red. Se prueba SSDP directo y las direcciones DLNA habituales de cada marca.\n\n" +
                "SIN RED COMÚN\nWi-Fi Direct conecta el móvil directamente con la TV (acepta en la TV). Bluetooth detecta la TV y POLTERHEIST busca la misma TV por Wi-Fi Direct: Bluetooth no transmite vídeo. También puedes crear la red del móvil y conectar la TV a ella.\n\n" +
                "SI NO APARECE\nEvita Wi-Fi de invitados y aislamiento de clientes. Revisa VPN y permisos de la TV. Activa en la TV Wi-Fi Direct, Screen Mirroring o Bluetooth.\n\n" +
                "FORMATOS\nLas imágenes se preparan como JPEG. MP4 con vídeo H.264 y audio AAC suele ofrecer mayor compatibilidad; los vídeos no se convierten automáticamente.\n\n" +
                "LÍMITES\nUna TV por sesión, sin sincronización entre pantallas. Bluetooth solo sirve para detectar y emparejar. TVs sin receptor, navegador o duplicación compatible necesitan un receptor externo, por ejemplo por HDMI. Mantén el móvil conectado: esta versión no instala contenido en la TV.")
                .setPositiveButton("Entendido",null).show();
    }

    /** Asks once for nearby-device permissions (Wi-Fi Direct, Bluetooth, hotspot) and then runs the action. */
    private void withNearby(boolean always, Runnable action) {
        String[] missing = NearbyPermissions.missing(this);
        boolean asked = getPreferences(MODE_PRIVATE).getBoolean("nearby_asked", false);
        if (missing.length == 0 || (asked && !always)) { action.run(); return; }
        getPreferences(MODE_PRIVATE).edit().putBoolean("nearby_asked", true).apply();
        pendingNearby = action;
        nearbyPermission.launch(missing);
    }
    private String nearbyHint(NearbyDevice device) {
        switch (device.source) {
            case WIFI_DIRECT: return "toca para conectar directo";
            case BLUETOOTH: return "toca para conectar por Wi-Fi Direct";
            default: return "detectada, sin DLNA";
        }
    }
    private void chooseNearby(NearbyDevice device) {
        if (!app.canChangeTarget()) return;
        if (device.source == NearbyDevice.Source.WIFI_DIRECT) {
            new AlertDialog.Builder(this).setTitle("Conectar por Wi-Fi Direct").setMessage(
                    "Se conectará el móvil directamente con «" + device.name + "», sin router. La TV puede pedir que aceptes la conexión.\n\n" +
                    "Después aparecerá como DLNA si la TV lo ofrece por Wi-Fi Direct; si no, usa el navegador de la TV. En algunos móviles el Wi-Fi normal se pausa mientras dura.")
                    .setPositiveButton("Conectar", (d, w) -> withNearby(true, () -> app.connectWifiDirect((android.net.wifi.p2p.WifiP2pDevice) device.handle)))
                    .setNegativeButton("Cancelar", null).show();
        } else if (device.source == NearbyDevice.Source.BLUETOOTH) {
            new AlertDialog.Builder(this).setTitle("TV detectada por Bluetooth").setMessage(
                    "Bluetooth no puede transmitir imagen ni vídeo. POLTERHEIST buscará «" + device.name + "» por Wi-Fi Direct y se conectará por ahí.\n\n" +
                    "Si no la encuentra, crea la red del móvil y conecta la TV a ella, o usa duplicar pantalla.")
                    .setPositiveButton("Conectar por Wi-Fi Direct", (d, w) -> withNearby(true, () -> app.bridgeBluetooth(device)))
                    .setNeutralButton("Emparejar Bluetooth", (d, w) -> withNearby(true, () -> app.pairBluetooth(device)))
                    .setNegativeButton("Cancelar", null).show();
        } else {
            new AlertDialog.Builder(this).setTitle("TV detectada (" + device.detail + ")").setMessage(
                    "La TV anuncia " + device.detail + " pero no ha respondido como DLNA. Si es Google Cast aparecerá como tal cuando Play Services la reconozca.\n\n" +
                    "Puedes volver a comprobar DLNA o usar su navegador.")
                    .setPositiveButton("Comprobar DLNA", (d, w) -> app.reprobe(device))
                    .setNeutralButton("Usar navegador", (d, w) -> app.chooseBrowser())
                    .setNegativeButton("Cancelar", null).show();
        }
    }
    private void hotspot() {
        if (app.hotspotActive()) { app.stopHotspot(); return; }
        new AlertDialog.Builder(this).setTitle("Crear red del móvil").setMessage(
                "El móvil creará una red Wi-Fi local, sin Internet, para conectar una TV que esté en otra red o sin red.\n\n" +
                "1. Conecta la TV a la red que se mostrará (nombre y clave).\n2. Pulsa Buscar TVs o usa el navegador de la TV.\n\n" +
                "En algunos móviles el Wi-Fi normal se desconecta mientras la red está activa.")
                .setPositiveButton("Crear red", (d, w) -> withNearby(true, app::startHotspot))
                .setNegativeButton("Cancelar", null).show();
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
