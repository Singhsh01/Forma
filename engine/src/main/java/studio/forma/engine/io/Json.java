package studio.forma.engine.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free JSON: a compact streaming writer and a strict recursive-descent parser
 * (objects become {@code LinkedHashMap}, arrays {@code ArrayList}, numbers {@code Double}).
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- writer

    public static final class Writer {
        private final StringBuilder sb;
        private final boolean[] first = new boolean[256];
        private int depth;
        private boolean afterKey;

        public Writer() {
            this(4096);
        }

        public Writer(int capacity) {
            sb = new StringBuilder(capacity);
        }

        private void comma() {
            if (afterKey) {
                afterKey = false;
                return;
            }
            if (depth > 0) {
                if (!first[depth]) sb.append(',');
                first[depth] = false;
            }
        }

        public Writer end() {
            sb.append(stack[depth] == '[' ? ']' : '}');
            depth--;
            return this;
        }

        private final char[] stack = new char[256];

        public Writer object() {
            comma();
            sb.append('{');
            depth++;
            stack[depth] = '{';
            first[depth] = true;
            return this;
        }

        public Writer array() {
            comma();
            sb.append('[');
            depth++;
            stack[depth] = '[';
            first[depth] = true;
            return this;
        }

        public Writer key(String k) {
            comma();
            str(k);
            sb.append(':');
            afterKey = true;
            return this;
        }

        public Writer value(String v) {
            comma();
            if (v == null) sb.append("null");
            else str(v);
            return this;
        }

        public Writer value(double v) {
            comma();
            if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
            else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v);
            else sb.append(Math.round(v * 1e6) / 1e6);
            return this;
        }

        public Writer value(long v) {
            comma();
            sb.append(v);
            return this;
        }

        public Writer value(boolean v) {
            comma();
            sb.append(v);
            return this;
        }

        public Writer nul() {
            comma();
            sb.append("null");
            return this;
        }

        public Writer field(String k, String v) { return key(k).value(v); }
        public Writer field(String k, double v) { return key(k).value(v); }
        public Writer field(String k, long v) { return key(k).value(v); }
        public Writer field(String k, int v) { return key(k).value((long) v); }
        public Writer field(String k, boolean v) { return key(k).value(v); }

        /** Writes a pre-serialised JSON fragment as a value. */
        public Writer raw(String json) {
            comma();
            sb.append(json);
            return this;
        }

        private void str(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- binary helpers

    public static String b64(float[] a) {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : a) bb.putFloat(f);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    public static String b64u16(int[] a) {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : a) bb.putShort((short) v);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    public static String b64i32(int[] a) {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : a) bb.putInt(v);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    public static String b64(byte[] a) {
        return Base64.getEncoder().encodeToString(a);
    }

    // ---------------------------------------------------------------- parser

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw p.err("unexpected trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException("invalid JSON at position " + i + ": " + m);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value(int depth) {
            if (depth > 64) throw err("nesting too deep");
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> number();
            };
        }

        Object lit(String w, Object v) {
            if (!s.startsWith(w, i)) throw err("unexpected token");
            i += w.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else b.append(c);
            }
        }

        Double number() {
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected character '" + s.charAt(i) + "'");
            try {
                return Double.parseDouble(s.substring(st, i));
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }
    }
}
