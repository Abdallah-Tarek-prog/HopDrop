package com.hop.drop.net;

import com.hop.drop.HopApp;
import com.hop.drop.core.Json;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = HopApp.class, sdk = 35)
public final class DiscoveryTest {
    private static final String OTHER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OWN = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String UNKNOWN = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    private static String message(String type, String id) {
        return Json.string(Json.obj("hopdrop", 2, "type", type, "id", id, "name", type.equals("bye") ? "" : "Laptop",
                "pl", "windows", "p", 7410, "rx", !type.equals("bye")));
    }

    @Test public void goodbyeRemovesAndNextAnnouncementRestores() throws Exception {
        HopApp app = (HopApp) RuntimeEnvironment.getApplication();
        AtomicInteger changes = new AtomicInteger();
        Discovery discovery = new Discovery(app, OWN, changes::incrementAndGet);
        InetAddress from = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 5});

        discovery.handle(message("announce", OTHER), from);
        assertEquals(1, discovery.list().size());
        assertEquals(1, changes.get());
        InetAddress otherAddress = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 6});
        discovery.handle(message("bye", OTHER), otherAddress);
        assertTrue(discovery.list().isEmpty());
        assertEquals(2, changes.get());
        discovery.handle(message("bye", OTHER), from);
        assertEquals(2, changes.get());
        discovery.handle(message("announce", OTHER), from);
        assertEquals(1, discovery.list().size());
        assertEquals(3, changes.get());
    }

    @Test public void queryOwnIdAndUnknownType() throws Exception {
        HopApp app = (HopApp) RuntimeEnvironment.getApplication();
        AtomicInteger changes = new AtomicInteger();
        Discovery discovery = new Discovery(app, OWN, changes::incrementAndGet);
        InetAddress from = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 5});

        discovery.handle(message("query", OTHER), from);
        assertEquals(1, discovery.list().size());
        assertEquals(1, changes.get());
        discovery.handle(message("announce", OWN), from);
        discovery.handle(message("bye", OWN), from);
        discovery.handle(message("other", UNKNOWN), from);
        assertEquals(1, discovery.list().size());
        assertEquals(1, changes.get());
    }
}
