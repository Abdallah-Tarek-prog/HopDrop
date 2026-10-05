package com.hop.drop.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON codec for protocol messages and local stores. */
public final class Json {
    private Json() { }
    public static Map<String,Object> obj(Object... pairs) {
        Map<String,Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String)pairs[i], pairs[i + 1]);
        return value;
    }
    public static String string(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("\\r");
                else if (c == '\t') b.append("\\t");
                else if (c < 32) b.append(String.format("\\u%04x", (int)c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) {
                if (b.length() > 1) b.append(',');
                b.append(string(e.getKey().toString())).append(':').append(string(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder b = new StringBuilder("[");
            for (Object v : (Iterable<?>)value) {
                if (b.length() > 1) b.append(',');
                b.append(string(v));
            }
            return b.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    public static Map<String,Object> parseObject(String text) {
        Parser p = new Parser(text);
        Object result = p.value(); p.ws();
        if (!(result instanceof Map) || p.at != text.length()) throw new IllegalArgumentException("Invalid JSON object");
        return (Map<String,Object>)result;
    }
    public static String str(Map<String,Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof String)) throw new IllegalArgumentException("Missing " + key);
        return (String)v;
    }
    public static long num(Map<String,Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof Number)) throw new IllegalArgumentException("Missing " + key);
        return ((Number)v).longValue();
    }
    public static boolean bool(Map<String,Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof Boolean)) throw new IllegalArgumentException("Missing " + key);
        return (Boolean)v;
    }
    private static final class Parser {
        final String s; int at;
        Parser(String s) { this.s = s; }
        void ws() { while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++; }
        char next() { if (at >= s.length()) throw new IllegalArgumentException("Truncated JSON"); return s.charAt(at++); }
        void want(char c) { ws(); if (next() != c) throw new IllegalArgumentException("Invalid JSON"); }
        Object value() {
            ws(); char c = next();
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                while (true) {
                    c = next(); if (c == '"') return b.toString();
                    if (c < 32) throw new IllegalArgumentException("Invalid string");
                    if (c == '\\') {
                        c = next();
                        if (c == 'u') { if (at + 4 > s.length()) throw new IllegalArgumentException("Invalid unicode"); c = (char)Integer.parseInt(s.substring(at, at + 4), 16); at += 4; }
                        else if (c == 'n') c = '\n'; else if (c == 'r') c = '\r'; else if (c == 't') c = '\t';
                        else if (c == 'b') c = '\b'; else if (c == 'f') c = '\f';
                        else if (c != '/' && c != '"' && c != '\\') throw new IllegalArgumentException("Invalid escape");
                    }
                    b.append(c);
                }
            }
            if (c == '{') {
                Map<String,Object> m = new LinkedHashMap<>(); ws(); if (at < s.length() && s.charAt(at) == '}') { at++; return m; }
                do { Object k = value(); if (!(k instanceof String)) throw new IllegalArgumentException("Invalid key"); want(':'); m.put((String)k, value()); ws(); c = next(); } while (c == ',');
                if (c != '}') throw new IllegalArgumentException("Invalid object"); return m;
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>(); ws(); if (at < s.length() && s.charAt(at) == ']') { at++; return list; }
                do { list.add(value()); ws(); c = next(); } while (c == ',');
                if (c != ']') throw new IllegalArgumentException("Invalid array"); return list;
            }
            at--;
            if (s.startsWith("true", at)) { at += 4; return true; }
            if (s.startsWith("false", at)) { at += 5; return false; }
            if (s.startsWith("null", at)) { at += 4; return null; }
            int start = at;
            if (s.charAt(at) == '-') at++;
            while (at < s.length() && Character.isDigit(s.charAt(at))) at++;
            if (at == start || at == start + 1 && s.charAt(start) == '-') throw new IllegalArgumentException("Invalid number");
            if (at < s.length() && (s.charAt(at) == '.' || s.charAt(at) == 'e' || s.charAt(at) == 'E')) throw new IllegalArgumentException("Integer required");
            return Long.parseLong(s.substring(start, at));
        }
    }
}
