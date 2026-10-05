package com.hop.drop.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class Sas {
    private Sas() { }
    private static byte[] combine(String label, byte[]... parts) {
        int n = label.length(); for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; byte[] prefix = label.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, out, 0, prefix.length); int at = prefix.length;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }
    public static String commit(byte[] fpR, byte[] fpI, byte[] nR) { return Protocol.hex(Protocol.sha(combine("HopDrop-SAS-commit", fpR, fpI, nR))); }
    public static String code(byte[] fpI, byte[] fpR, byte[] nI, byte[] nR) {
        byte[] hash = Protocol.sha(combine("HopDrop-SAS-code", fpI, fpR, nI, nR));
        long n = ((long)(hash[0]&255)<<24) | ((long)(hash[1]&255)<<16) | ((long)(hash[2]&255)<<8) | (hash[3]&255);
        return String.format(Locale.ROOT, "%06d", n % 1000000);
    }
}
