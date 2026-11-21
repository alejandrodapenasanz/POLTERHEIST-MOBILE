package org.polterheist.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

/** Entry point for protocol-specific device controls. Opening the hub performs no discovery. */
public final class HomeActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DeviceUi ui = new DeviceUi(this);
        ui.heading("Tu entorno.\nBajo tu control.", "Encuentra tus dispositivos y elige qué quieres hacer con ellos.");
        LinearLayout tv = ui.card("TVs y red local");
        ui.add(tv,ui.text("Envía una imagen o un vídeo. Busca por Wi-Fi, Cast, DLNA y otras conexiones cercanas.",15,DeviceUi.MUTED),12);
        ui.add(tv,ui.button("Abrir TVs y red",true,() -> startActivity(new Intent(this,MainActivity.class))),16);
        LinearLayout audio = ui.card("Bluetooth y audio");
        ui.add(audio,ui.text("Descubre altavoces, auriculares y otros accesorios. Conecta una salida de audio y reproduce tu archivo.",15,DeviceUi.MUTED),12);
        ui.add(audio,ui.button("Abrir Bluetooth",true,() -> startActivity(new Intent(this,BluetoothAudioActivity.class))),16);
        ui.add(ui.body,ui.text("Los controles disponibles dependen de las funciones que ofrece cada dispositivo.",13,DeviceUi.MUTED),24);
    }
}
