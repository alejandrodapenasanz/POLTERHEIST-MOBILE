package org.polterheist.mobile;

import android.widget.Button;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ActivitySmokeTest {
    private TextView find(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().equals(text))return (TextView)view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextView result=find(group.getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
    @Test public void launchesWithoutPlayServicesAndKeepsBrowserSelectionAcrossRecreation() {
        try(var activity=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity main=activity.get();
            View root=main.getWindow().getDecorView();
            assertNotNull(find(root,"POLTERHEIST"));
            Button send=(Button)find(root,"Enviar a la TV");assertNotNull(send);assertFalse(send.isEnabled());
            TextView browser=find(root,"Usar el navegador de la TV");assertNotNull(browser);browser.performClick();
            AppController app=((PolterApp)main.getApplication()).controller;
            assertEquals(AppController.Target.BROWSER,app.target);
            activity.recreate();
            assertNotNull(find(activity.get().getWindow().getDecorView(),"Navegador de la TV"));
            assertFalse(app.sharing);
        }
    }
}
