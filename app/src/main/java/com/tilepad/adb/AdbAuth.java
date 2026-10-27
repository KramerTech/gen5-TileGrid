package com.tilepad.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;

/**
 * The ADB key handshake as the relay sees it. Some head units' adbd checks keys over TCP and some
 * (the one this was developed on, despite {@code ro.adb.secure=1}) let any client straight in.
 * The relay forwards the client's CNXN, and adbd's first reply says which kind it is:
 *
 * <ul>
 * <li>CNXN: adbd doesn't check, so the relay does ({@link #challenge}), as the device half of the
 *     handshake: send AUTH TOKEN, and check each AUTH SIGNATURE against the keys already saved. A
 *     client none of them matches eventually sends AUTH RSAPUBLICKEY, which is let in unproven:
 *     the caller saves the key, and the client proves it holds it the next time, by signature.
 * <li>AUTH: adbd checks, so the relay passes the handshake through ({@link #passThrough}), only
 *     watching it: which saved key signed adbd's token, or which key the client offers instead.
 *     The relay must not challenge the client itself here. adb signs one token per key and then
 *     only ever offers its public key, so adbd would never get a signature.
 * </ul>
 *
 * Pure stream and byte handling with no sockets, so it is unit-tested directly.
 */
final class AdbAuth {
    static final int HEADER = 24;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;
    static final int TOKEN_SIZE = 20;
    private static final int MAX_MESSAGE = 1 << 20;
    /** A client tries each of its keys once, then offers one; more rounds than this is a loop. */
    private static final int MAX_ROUNDS = 16;
    /** PKCS#1 v1.5 DigestInfo prefix for SHA-1: adb signs the token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    };

    /** An adb public key: an {@code adb_keys} line ("BASE64 user@host"), decoded. */
    static final class Key {
        final String line;
        final BigInteger modulus;
        final BigInteger exponent;
        private final byte[] blob;

        private Key(String line, byte[] blob, BigInteger modulus, BigInteger exponent) {
            this.line = line;
            this.blob = blob;
            this.modulus = modulus;
            this.exponent = exponent;
        }

        /** The comment after the key, usually user@host; "(no name)" without one. */
        String comment() {
            int sp = line.indexOf(' ');
            String c = sp < 0 ? "" : line.substring(sp + 1).trim();
            return c.isEmpty() ? "(no name)" : c;
        }

        /** MD5 of the key blob, colon-separated: what Android's "Allow USB debugging?" shows. */
        String fingerprint() {
            try {
                byte[] md5 = MessageDigest.getInstance("MD5").digest(blob);
                StringBuilder sb = new StringBuilder();
                for (byte b : md5) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(Character.toUpperCase(Character.forDigit(b >> 4 & 0xf, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                return "?";
            }
        }

        /** True if sig is this key's adb signature of token. */
        boolean verifies(byte[] token, byte[] sig) {
            int size = (modulus.bitLength() + 7) / 8;
            if (sig.length != size || token.length != TOKEN_SIZE) return false;
            BigInteger s = new BigInteger(1, sig);
            if (s.compareTo(modulus) >= 0) return false;
            byte[] expected = new byte[size];
            expected[1] = 0x01;
            int fill = size - 3 - SHA1_DIGEST_INFO.length - TOKEN_SIZE;
            for (int i = 0; i < fill; i++) expected[2 + i] = (byte) 0xff;
            System.arraycopy(SHA1_DIGEST_INFO, 0, expected, size - TOKEN_SIZE - SHA1_DIGEST_INFO.length, SHA1_DIGEST_INFO.length);
            System.arraycopy(token, 0, expected, size - TOKEN_SIZE, TOKEN_SIZE);
            return MessageDigest.isEqual(fixed(s.modPow(exponent, modulus), size), expected);
        }

        /**
         * Parses an adb_keys line, or returns null. The blob is mincrypt's RSAPublicKey: len (the
         * modulus in 32-bit words), n0inv, n[len] and rr[len] as little-endian words, exponent.
         */
        static Key parse(String line) {
            String l = line.trim();
            int sp = l.indexOf(' ');
            byte[] blob = base64(sp < 0 ? l : l.substring(0, sp));
            if (blob == null || blob.length < 8) return null;
            int words = le(blob, 0);
            if (words <= 0 || words > 1024 || blob.length < 12 + 8 * words) return null;
            byte[] n = new byte[4 * words];
            for (int i = 0; i < n.length; i++) n[i] = blob[8 + n.length - 1 - i];
            BigInteger e = BigInteger.valueOf(le(blob, 8 + 8 * words) & 0xffffffffL);
            BigInteger modulus = new BigInteger(1, n);
            if (modulus.signum() == 0 || e.signum() == 0) return null;
            return new Key(l, blob, modulus, e);
        }
    }

    /** What {@link #authenticate} let in: the client's CNXN to forward, and who it is. */
    static final class Admission {
        final byte[] connect;
        final Key key;
        /** The client signed a token with this key (else it only offered it, unproven). */
        final boolean signed;

        Admission(byte[] connect, Key key, boolean signed) {
            this.connect = connect;
            this.key = key;
            this.signed = signed;
        }
    }

    /** How a handshake that adbd checked itself ended ({@link #passThrough}). */
    static final class Checked {
        /** adbd accepted the client and its CNXN has gone to the client. */
        final boolean connected;
        /** The saved key the client signed adbd's token with, or null (none, or not one we know). */
        final Key signer;
        /** The key the client offered instead of a signature adbd accepts. Not passed on to adbd. */
        final Key offered;

        Checked(boolean connected, Key signer, Key offered) {
            this.connected = connected;
            this.signer = signer;
            this.offered = offered;
        }
    }

    private AdbAuth() { }

    /** Reads the client's CNXN, then {@link #challenge}s it. */
    static Admission authenticate(InputStream rawIn, OutputStream out, List<Key> known, Random random)
            throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        byte[] connect = read(in);
        if (le(connect, 0) != A_CNXN) throw new IOException("expected CNXN");
        return challenge(in, out, connect, known, random);
    }

    /**
     * The relay's own check, for an adbd that doesn't check keys. Challenges the client (whose
     * CNXN was connect) and returns once it has signed with a known key or offered a public key.
     * Throws on anything else (the caller drops the connection), including a client that never
     * offers a key.
     */
    static Admission challenge(DataInputStream in, OutputStream out, byte[] connect, List<Key> known, Random random)
            throws IOException {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            byte[] token = new byte[TOKEN_SIZE];
            random.nextBytes(token);
            out.write(message(A_AUTH, AUTH_TOKEN, 0, token));
            out.flush();
            byte[] reply = read(in);
            int type = authType(reply);
            if (type == AUTH_SIGNATURE) {
                for (Key k : known) if (k.verifies(token, payload(reply))) return new Admission(connect, k, true);
            } else if (type == AUTH_RSAPUBLICKEY) {
                return new Admission(connect, offeredKey(reply), false);
            } else {
                throw new IOException("unexpected AUTH type " + type);
            }
        }
        throw new IOException("no key after " + MAX_ROUNDS + " rounds");
    }

    /**
     * For an adbd that checks keys: relays its handshake with the client message by message,
     * starting from adbd's first reply (first), until adbd sends anything but AUTH TOKEN (CNXN:
     * connected) or the client offers its public key, which is held back. adbd has no dialog to
     * approve it, so the caller adds it to adb_keys and drops the connection, and the client's
     * next connection signs with it.
     */
    static Checked passThrough(DataInputStream client, OutputStream toClient, DataInputStream adbd, OutputStream toAdbd,
            byte[] first, List<Key> known) throws IOException {
        byte[] m = first;
        Key signer = null;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            toClient.write(m);
            toClient.flush();
            if (le(m, 0) != A_AUTH || le(m, 4) != AUTH_TOKEN) return new Checked(le(m, 0) == A_CNXN, signer, null);
            byte[] reply = read(client);
            int type = authType(reply);
            if (type == AUTH_RSAPUBLICKEY) return new Checked(false, null, offeredKey(reply));
            if (type != AUTH_SIGNATURE) throw new IOException("unexpected AUTH type " + type);
            signer = null;
            for (Key k : known) if (k.verifies(payload(m), payload(reply))) signer = k;
            toAdbd.write(reply);
            toAdbd.flush();
            m = read(adbd);
        }
        throw new IOException("no answer from adbd after " + MAX_ROUNDS + " rounds");
    }

    private static int authType(byte[] m) throws IOException {
        if (le(m, 0) != A_AUTH) throw new IOException("expected AUTH");
        return le(m, 4);
    }

    private static Key offeredKey(byte[] m) throws IOException {
        String line = new String(payload(m), "UTF-8");
        int nul = line.indexOf('\0');
        Key k = Key.parse(nul >= 0 ? line.substring(0, nul) : line);
        if (k == null) throw new IOException("unreadable public key");
        return k;
    }

    private static byte[] payload(byte[] m) {
        byte[] p = new byte[m.length - HEADER];
        System.arraycopy(m, HEADER, p, 0, p.length);
        return p;
    }

    /** One ADB message: header then payload. */
    static byte[] message(int command, int arg0, int arg1, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER + payload.length);
        int check = 0;
        for (byte b : payload) check += b & 0xff;
        word(out, command);
        word(out, arg0);
        word(out, arg1);
        word(out, payload.length);
        word(out, check);
        word(out, ~command);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /** Reads one whole message (header and payload). */
    static byte[] read(DataInputStream in) throws IOException {
        byte[] header = new byte[HEADER];
        in.readFully(header);
        int length = le(header, 12);
        if (length < 0 || length > MAX_MESSAGE) throw new IOException("bad message length");
        byte[] all = new byte[HEADER + length];
        System.arraycopy(header, 0, all, 0, HEADER);
        in.readFully(all, HEADER, length);
        return all;
    }

    private static void word(ByteArrayOutputStream out, int v) {
        out.write(v);
        out.write(v >>> 8);
        out.write(v >>> 16);
        out.write(v >>> 24);
    }

    static int le(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    /** v as exactly size big-endian bytes (BigInteger adds a sign byte or drops leading zeros). */
    private static byte[] fixed(BigInteger v, int size) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[size];
        int n = Math.min(raw.length, size);
        System.arraycopy(raw, raw.length - n, out, size - n, n);
        return out;
    }

    /** Standard base64 (android.util.Base64 is not there in unit tests; java.util's is API 26). */
    private static byte[] base64(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int bits = 0, have = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else if (c == '=') break;
            else return null;
            bits = bits << 6 | v;
            have += 6;
            if (have >= 8) {
                have -= 8;
                out.write(bits >> have & 0xff);
            }
        }
        return out.toByteArray();
    }
}
