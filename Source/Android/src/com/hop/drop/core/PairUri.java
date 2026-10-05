package com.hop.drop.core;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PairUri {
    public final String id, name, platform, token; public final int port; public final List<String> addresses;
    public PairUri(String id, String name, String platform, int port, List<String> addresses, String token) {
        this.id = id; this.name = name; this.platform = platform; this.port = port; this.addresses = addresses; this.token = token;
    }
    public static String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(Protocol.random(16)); }
    public static PairUri parse(String text) {
        try {
            URI uri = URI.create(text);
            if (!"hopdrop".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) throw new IllegalArgumentException("Invalid pair URI");
            Map<String,String> q = new LinkedHashMap<>();
            for (String field : uri.getRawQuery().split("&")) { String[] kv = field.split("=",2); if (kv.length != 2 || q.put(kv[0],kv[1]) != null) throw new IllegalArgumentException("Invalid query"); }
            String id = q.get("id"), name = URLDecoder.decode(q.get("n"), "UTF-8"), pl = q.get("pl"), token = q.get("t");
            int port = Integer.parseInt(q.get("p")); List<String> addresses = new ArrayList<>();
            for (String address : q.get("a").split(",")) { if (!address.matches("[0-9.]+") || !validIpv4(address)) throw new IllegalArgumentException("Invalid address"); addresses.add(address); }
            if (!"2".equals(q.get("v")) || id == null || !id.matches("[0-9a-f]{64}") || name.length() < 1 || name.length() > 40 || !(pl.equals("android") || pl.equals("windows")) || port < 1 || port > 65535 || addresses.isEmpty() || token.length() != 22 || Base64.getUrlDecoder().decode(token).length != 16) throw new IllegalArgumentException("Invalid pair URI");
            return new PairUri(id,name,pl,port,addresses,token);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid pair URI", e); }
    }
    public static boolean validIpv4(String address) {
        String[] p = address.split("\\.",-1); if (p.length != 4) return false;
        try { for (String s : p) if (s.isEmpty() || s.length() > 3 || Integer.parseInt(s) > 255) return false; return true; } catch (Exception e) { return false; }
    }
    @Override public String toString() {
        try { return "hopdrop://pair?v=2&id=" + id + "&n=" + URLEncoder.encode(name,"UTF-8").replace("+","%20") + "&pl=" + platform + "&p=" + port + "&a=" + String.join(",", addresses) + "&t=" + token; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
