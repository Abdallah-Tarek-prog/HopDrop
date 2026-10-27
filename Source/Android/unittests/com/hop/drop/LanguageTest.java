package com.hop.drop;

import android.content.Context;
import android.app.NotificationManager;
import android.view.View;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = HopApp.class, sdk = 35)
public final class LanguageTest {
    private Context app;
    private Locale original;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        original = Locale.getDefault();
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().remove("language").commit();
    }

    @After public void restore() { Locale.setDefault(original); }

    @Test public void englishIsExplicitDefaultEvenOnArabicPhone() {
        Locale.setDefault(Locale.forLanguageTag("ar"));
        assertEquals("en", Language.selected(app));
        assertEquals("en", app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).getString("language", null));
        assertEquals("Language", Language.text(app, R.string.language));
        assertEquals("en", Language.wrap(app).getResources().getConfiguration().getLocales().get(0).getLanguage());
        assertEquals(View.LAYOUT_DIRECTION_LTR, Language.wrap(app).getResources().getConfiguration().getLayoutDirection());
    }

    @Test public void savedArabicSelectsResourcesAndRtl() {
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().putString("language", "ar").commit();
        assertEquals("ar", Language.selected(app));
        assertEquals("اللغة", Language.text(app, R.string.language));
        assertEquals(View.LAYOUT_DIRECTION_RTL, Language.wrap(app).getResources().getConfiguration().getLayoutDirection());
        String path = "Download/HopDrop/report.pdf";
        String wrapped = Language.ltr(app, path);
        assertTrue(wrapped.contains(path));
        assertNotEquals(path, wrapped);
        String history = Language.text(app, R.string.history_from, "LOQ Laptop");
        assertTrue(history.contains("LOQ Laptop"));
        assertNotEquals("من LOQ Laptop", history);
    }

    @Test public void unsupportedSavedLanguageFallsBackToEnglish() {
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().putString("language", "fr").commit();
        assertEquals("en", Language.selected(app));
        assertEquals("en", app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).getString("language", null));
        assertEquals("Language", Language.text(app, R.string.language));
    }

    @Test public void arabicFileCasesAndFormattedMessages() {
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().putString("language", "ar").commit();
        assertEquals("ملفان", Language.files(app, 2));
        assertEquals("ملفين", Language.fileObjects(app, 2));
        assertTrue(Language.text(app, R.string.offer_files, "حاسوب", Language.fileObjects(app, 2)).contains("إرسال ملفين"));
        assertTrue(Language.text(app, R.string.notification_partial_saved, 1, Language.fileObjects(app, 2), "a.txt")
                .contains("من أصل ملفين"));
    }

    @Test public void languageSwitchUpdatesNotificationChannelName() {
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().putString("language", "ar").commit();
        ((HopApp) app).refreshLanguage();
        assertEquals(Language.text(app, R.string.channel_transfers),
                app.getSystemService(NotificationManager.class).getNotificationChannel("transfers").getName().toString());
        app.getSharedPreferences("settings_v2", Context.MODE_PRIVATE).edit().putString("language", "en").commit();
        ((HopApp) app).refreshLanguage();
        assertEquals(Language.text(app, R.string.channel_transfers),
                app.getSystemService(NotificationManager.class).getNotificationChannel("transfers").getName().toString());
    }
}
