package com.hop.drop.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Protocol {
    public static final int MAX_DATA = 1048576, MAX_JSON = 65536;
    public static final String APP = "1.0.0";
    /** Protocol extensions this build understands; a peer uses one only when both list it in their hello (absent = baseline v2). */
    public static final List<String> FEATURES = Collections.unmodifiableList(Arrays.asList("consent", "folders", "pages", "resume"));
    private Protocol() { }
    public static final class Frame {
        public final int kind; public final byte[] body;
        public Frame(int kind, byte[] body) { this.kind = kind; this.body = body; }
    }
    /** Header and payload go out in one write, so they travel in one TLS record. */
    public static void write(OutputStream out, int kind, byte[] body) throws IOException {
        check(kind, body.length);
        byte[] frame = new byte[5 + body.length];
        System.arraycopy(body, 0, frame, 5, body.length);
        header(frame, kind, body.length);
        out.write(frame); out.flush();
    }
    /** Sends a data frame whose {@code count} payload bytes already sit at offset 5 of {@code frame}: no copy, one write. */
    public static void writeData(OutputStream out, byte[] frame, int count) throws IOException {
        check(2, count); header(frame, 2, count);
        out.write(frame, 0, 5 + count); out.flush();
    }
    private static void header(byte[] frame, int kind, int length) {
        frame[0] = (byte) kind; frame[1] = (byte) (length >>> 24); frame[2] = (byte) (length >>> 16); frame[3] = (byte) (length >>> 8); frame[4] = (byte) length;
    }
    /** Reads frames into one reusable buffer, so receiving a file doesn't allocate a new array for every data frame. */
    public static final class Reader {
        private final DataInputStream in; private final byte[] buffer;
        private int kind, length;
        public Reader(InputStream in, int maxData) { this.in = new DataInputStream(in); buffer = new byte[maxData]; }
        /** Reads the next frame; its payload is {@link #data()}[0, {@link #length()}) until the next call. */
        public int next() throws IOException {
            kind = in.readUnsignedByte(); long size = Integer.toUnsignedLong(in.readInt());
            if (size > buffer.length) throw new IOException("protocol_error: invalid frame");
            check(kind, (int) size); length = (int) size; in.readFully(buffer, 0, length); return kind;
        }
        public byte[] data() { return buffer; }
        public int length() { return length; }
        public Map<String,Object> message() throws IOException { return Protocol.message(new Frame(kind, Arrays.copyOf(buffer, length))); }
    }
    public static Frame read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int kind = d.readUnsignedByte(); long size = Integer.toUnsignedLong(d.readInt());
        if (size > Integer.MAX_VALUE) throw new IOException("Invalid frame length");
        check(kind, (int)size); byte[] body = new byte[(int)size]; d.readFully(body);
        return new Frame(kind, body);
    }
    private static void check(int kind, int size) throws IOException {
        if ((kind != 1 && kind != 2) || size < 1 || size > (kind == 1 ? MAX_JSON : MAX_DATA)) throw new IOException("protocol_error: invalid frame");
    }
    public static void send(OutputStream out, Object... fields) throws IOException { write(out, 1, Json.string(Json.obj(fields)).getBytes(StandardCharsets.UTF_8)); }
    public static void sendObject(OutputStream out, Map<String,Object> m) throws IOException { write(out, 1, Json.string(m).getBytes(StandardCharsets.UTF_8)); }
    public static Map<String,Object> message(InputStream in) throws IOException { Frame f = read(in); return message(f); }
    public static Map<String,Object> message(Frame f) throws IOException {
        if (f.kind != 1) throw new IOException("protocol_error: expected control frame");
        try { Map<String,Object> m = Json.parseObject(new String(f.body, StandardCharsets.UTF_8)); Json.str(m, "type"); return m; }
        catch (RuntimeException e) { throw new IOException("protocol_error: invalid JSON", e); }
    }
    public static String type(Map<String,Object> m) throws IOException {
        String t = Json.str(m, "type");
        if (t.equals("error")) throw new IOException(Json.str(m, "code") + ": " + Json.str(m, "message"));
        return t;
    }
    public static void expect(Map<String,Object> m, String type) throws IOException {
        if (!type(m).equals(type)) throw new IOException("protocol_error: expected " + type);
    }
    public static byte[] sha(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hex(byte[] data) {
        char[] c = new char[data.length * 2]; char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) { c[i * 2] = digits[(data[i] & 255) >>> 4]; c[i * 2 + 1] = digits[data[i] & 15]; }
        return new String(c);
    }
    public static byte[] unhex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException("Invalid hex");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) { int a = Character.digit(text.charAt(i * 2), 16), b = Character.digit(text.charAt(i * 2 + 1), 16); if (a < 0 || b < 0) throw new IllegalArgumentException("Invalid hex"); out[i] = (byte)(a * 16 + b); }
        return out;
    }
    public static String id(X509Certificate cert) {
        try { return hex(sha(cert.getEncoded())); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static byte[] random(int length) { byte[] b = new byte[length]; new SecureRandom().nextBytes(b); return b; }
    public static boolean equal(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }
    public static void hello(OutputStream out, String id, String name, String platform, boolean paired) throws IOException {
        if (name.length() < 1 || name.length() > 40) throw new IOException("Invalid device name");
        send(out, "type","hello","proto",2,"id",id,"name",name,"platform",platform,"app",APP,"paired",paired,"features",FEATURES);
    }
    /** The extensions a peer's hello lists that this build also supports. */
    public static Set<String> features(Map<String,Object> hello) {
        Set<String> result = new HashSet<>(); Object list = hello.get("features");
        if (list instanceof List) for (Object item : (List<?>) list) if (item instanceof String && FEATURES.contains(item)) result.add((String) item);
        return result;
    }
    public static Map<String,Object> checkHello(Map<String,Object> m, String certificateId) throws IOException {
        expect(m, "hello");
        if (Json.num(m,"proto") != 2) throw new IOException("unsupported_version: protocol 2 required");
        if (!Json.str(m,"id").equalsIgnoreCase(certificateId)) throw new IOException("identity_mismatch: hello differs from certificate");
        String name = Json.str(m,"name"), platform = Json.str(m,"platform");
        if (name.length() < 1 || name.length() > 40 || !(platform.equals("android") || platform.equals("windows"))) throw new IOException("protocol_error: invalid hello");
        return m;
    }
}
