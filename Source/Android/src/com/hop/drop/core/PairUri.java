package com.hop.drop.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PairUri {
    public final String id, name, platform, token; public final int port; public final List<String> addresses;
    /** The shower's direct link (a PC's own Wi-Fi network), so a phone that isn't on the same network can still reach it. Null when absent. */
    public final String linkSsid, linkKey, linkIp;
    public PairUri(String id, String name, String platform, int port, List<String> addresses, String token) {
        this(id, name, platform, port, addresses, token, null, null, null);
    }
    public PairUri(String id, String name, String platform, int port, List<String> addresses, String token, String linkSsid, String linkKey, String linkIp) {
        this.id = id; this.name = name; this.platform = platform; this.port = port; this.addresses = addresses; this.token = token;
        boolean link = Peer.validLink(linkSsid, linkKey, linkIp);
        this.linkSsid = link ? linkSsid : null; this.linkKey = link ? linkKey : null; this.linkIp = link ? linkIp : null;
    }
    public static String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(Protocol.random(16)); }
    public static PairUri parse(String text) {
        try {
            int query = text.indexOf('?');
            if (query < 0 || !"hopdrop://pair".equals(text.substring(0, query)) || text.indexOf('#') >= 0)
                throw new IllegalArgumentException("Invalid pair URI");
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) <= ' ') throw new IllegalArgumentException("Invalid pair URI");
            Map<String,String> q = new LinkedHashMap<>();
            for (String field : text.substring(query + 1).split("&")) { String[] kv = field.split("=",2); if (kv.length != 2 || q.put(kv[0],kv[1]) != null) throw new IllegalArgumentException("Invalid query"); }
            String id = q.get("id"), name = URLDecoder.decode(q.get("n"), "UTF-8"), pl = q.get("pl"), token = q.get("t");
            int port = Integer.parseInt(q.get("p")); List<String> addresses = new ArrayList<>();
            for (String address : q.get("a").split(",")) { if (!address.matches("[0-9.]+") || !validIpv4(address)) throw new IllegalArgumentException("Invalid address"); addresses.add(address); }
            if (!"2".equals(q.get("v")) || id == null || !id.matches("[0-9a-f]{64}") || name.length() < 1 || name.length() > 40 || !(pl.equals("android") || pl.equals("windows")) || port < 1 || port > 65535 || addresses.isEmpty() || token.length() != 22 || Base64.getUrlDecoder().decode(token).length != 16) throw new IllegalArgumentException("Invalid pair URI");
            // Optional (1.1+): the direct link. A malformed one is left out rather than failing the scan.
            String ws = q.get("ws"), wk = q.get("wk"), wa = q.get("wa");
            if (ws != null && wk != null && wa != null) {
                try { ws = URLDecoder.decode(ws, "UTF-8"); wk = URLDecoder.decode(wk, "UTF-8"); }
                catch (Exception malformed) { ws = null; wk = null; wa = null; }
            }
            return new PairUri(id,name,pl,port,addresses,token,ws,wk,wa);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid pair URI", e); }
    }
    public static boolean validIpv4(String address) {
        if (address == null) return false;
        String[] p = address.split("\\.",-1); if (p.length != 4) return false;
        try { for (String s : p) if (s.isEmpty() || s.length() > 3 || Integer.parseInt(s) > 255) return false; return true; } catch (Exception e) { return false; }
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
    @Override public String toString() {
        try {
            return "hopdrop://pair?v=2&id=" + id + "&n=" + encode(name) + "&pl=" + platform + "&p=" + port + "&a=" + String.join(",", addresses) + "&t=" + token
                + (linkSsid == null ? "" : "&ws=" + encode(linkSsid) + "&wk=" + encode(linkKey) + "&wa=" + linkIp);
        }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
