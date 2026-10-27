package com.hop.drop;

import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = HopApp.class, sdk = 35)
public final class TransportHistoryTest {
    private HopApp app;

    @Before public void setup() {
        app = (HopApp) RuntimeEnvironment.getApplication();
        app.clearHistory();
    }

    @Test public void textHistoryKeepsTheActualRoute() {
        app.saveText(true, "PC", "hello", "direct_link");
        assertEquals("direct_link", first().get("transport"));
        assertEquals("hello", first().get("text"));
    }

    @Test public void resumedFileHistoryKeepsMixedRoute() throws Exception {
        Peer.Result result = new Peer.Result("transfer", false, "PC", List.of("report.pdf"), 1,
                null, null, 12, 100, "device", "mixed");
        Method save = HopApp.class.getDeclaredMethod("saveHistory", Peer.Result.class);
        save.setAccessible(true);
        save.invoke(app, result);
        assertEquals("mixed", first().get("transport"));
        assertEquals(List.of("report.pdf"), first().get("files"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> first() {
        String data = app.getSharedPreferences("history_v2", 0).getString("data", "");
        return (Map<String, Object>) ((List<?>) Json.parseObject(data).get("items")).get(0);
    }
}
