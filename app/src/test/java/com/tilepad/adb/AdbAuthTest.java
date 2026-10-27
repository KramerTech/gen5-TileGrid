package com.tilepad.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.crypto.Cipher;

import org.junit.Test;

/** The relay's ADB handshake, against keys and signatures made the way the adb client makes them. */
public class AdbAuthTest {
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    };
    private static final KeyPair ALICE = generate();
    private static final KeyPair BOB = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The adb_keys line adb writes for a key: mincrypt's RSAPublicKey, base64, then a comment. */
    private static String line(KeyPair pair, String comment) {
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        BigInteger n = pub.getModulus();
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(12 + 8 * words).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n.mod(r32).modInverse(r32).negate().mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        BigInteger rr = BigInteger.ONE.shiftLeft(32 * words).pow(2).mod(n);
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array()) + " " + comment;
    }

    /** What adb sends for AUTH TOKEN: RSA_sign(NID_sha1, token), PKCS#1 v1.5 over the raw token. */
    private static byte[] sign(KeyPair pair, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pair.getPrivate());
        byte[] info = Arrays.copyOf(SHA1_DIGEST_INFO, SHA1_DIGEST_INFO.length + token.length);
        System.arraycopy(token, 0, info, SHA1_DIGEST_INFO.length, token.length);
        return c.doFinal(info);
    }

    /** The tokens authenticate() will send, given the same seed. */
    private static byte[][] tokens(long seed, int count) {
        Random r = new Random(seed);
        byte[][] t = new byte[count][AdbAuth.TOKEN_SIZE];
        for (byte[] one : t) r.nextBytes(one);
        return t;
    }

    private static byte[] cnxn() throws Exception {
        return AdbAuth.message(AdbAuth.A_CNXN, 0x01000000, 4096, "host::features=shell_v2".getBytes("UTF-8"));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static AdbAuth.Admission run(byte[] clientBytes, List<AdbAuth.Key> known, long seed, ByteArrayOutputStream sent)
            throws IOException {
        return AdbAuth.authenticate(new ByteArrayInputStream(clientBytes), sent, known, new Random(seed));
    }

    @Test public void parsesAdbKeyLines() {
        AdbAuth.Key k = AdbAuth.Key.parse(line(ALICE, "alice@laptop"));
        assertNotNull(k);
        assertEquals(((RSAPublicKey) ALICE.getPublic()).getModulus(), k.modulus);
        assertEquals(BigInteger.valueOf(65537), k.exponent);
        assertEquals("alice@laptop", k.comment());
        assertEquals(47, k.fingerprint().length()); // 16 hex pairs and 15 colons
        assertNull(AdbAuth.Key.parse("not!base64 x"));
        assertNull(AdbAuth.Key.parse("QUJD x"));
    }

    @Test public void knownKeyThatSignsIsAdmittedAsSigned() throws Exception {
        AdbAuth.Key alice = AdbAuth.Key.parse(line(ALICE, "alice@laptop"));
        byte[] token = tokens(7, 1)[0];
        byte[] client = concat(cnxn(), AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_SIGNATURE, 0, sign(ALICE, token)));
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        AdbAuth.Admission a = run(client, Collections.singletonList(alice), 7, sent);
        assertTrue(a.signed);
        assertEquals(alice.line, a.key.line);
        assertTrue(Arrays.equals(cnxn(), a.connect));
        // Exactly one AUTH TOKEN went out, carrying the token, with a valid checksum.
        byte[] out = sent.toByteArray();
        assertEquals(AdbAuth.HEADER + AdbAuth.TOKEN_SIZE, out.length);
        assertEquals(AdbAuth.A_AUTH, AdbAuth.le(out, 0));
        assertEquals(AdbAuth.AUTH_TOKEN, AdbAuth.le(out, 4));
        int sum = 0;
        for (byte b : token) sum += b & 0xff;
        assertEquals(sum, AdbAuth.le(out, 16));
        assertTrue(Arrays.equals(token, Arrays.copyOfRange(out, AdbAuth.HEADER, out.length)));
    }

    @Test public void unknownClientTriesItsKeysThenOffersOne() throws Exception {
        AdbAuth.Key alice = AdbAuth.Key.parse(line(ALICE, "alice@laptop"));
        byte[][] t = tokens(9, 2);
        // Bob signs the first token (no match), then offers his public key, NUL-terminated.
        byte[] client = concat(cnxn(),
            AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_SIGNATURE, 0, sign(BOB, t[0])),
            AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_RSAPUBLICKEY, 0, (line(BOB, "bob@desk") + "\0").getBytes("UTF-8")));
        AdbAuth.Admission a = run(client, Collections.singletonList(alice), 9, new ByteArrayOutputStream());
        assertFalse(a.signed);
        assertEquals("bob@desk", a.key.comment());
        assertEquals(((RSAPublicKey) BOB.getPublic()).getModulus(), a.key.modulus);
    }

    @Test public void signatureOverTheWrongTokenDoesNotMatch() throws Exception {
        AdbAuth.Key alice = AdbAuth.Key.parse(line(ALICE, "alice@laptop"));
        byte[] token = tokens(3, 1)[0];
        assertTrue(alice.verifies(token, sign(ALICE, token)));
        byte[] other = token.clone();
        other[0] ^= 1;
        assertFalse(alice.verifies(other, sign(ALICE, token)));
        assertFalse(alice.verifies(token, sign(BOB, token)));
    }

    // ---- adbd that checks keys: the relay only watches ----

    private static byte[] token(byte[] t) {
        return AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_TOKEN, 0, t);
    }

    private static AdbAuth.Checked passThrough(byte[] fromClient, byte[] fromAdbdAfterFirst, byte[] first,
            List<AdbAuth.Key> known, ByteArrayOutputStream toClient, ByteArrayOutputStream toAdbd) throws IOException {
        return AdbAuth.passThrough(new DataInputStream(new ByteArrayInputStream(fromClient)), toClient,
            new DataInputStream(new ByteArrayInputStream(fromAdbdAfterFirst)), toAdbd, first, known);
    }

    @Test public void adbdThatChecksGetsTheClientsSignature() throws Exception {
        AdbAuth.Key alice = AdbAuth.Key.parse(line(ALICE, "alice@laptop"));
        byte[] t = tokens(5, 1)[0];
        byte[] sig = AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_SIGNATURE, 0, sign(ALICE, t));
        byte[] adbdCnxn = AdbAuth.message(AdbAuth.A_CNXN, 0x01000000, 4096, "device::".getBytes("UTF-8"));
        ByteArrayOutputStream toClient = new ByteArrayOutputStream(), toAdbd = new ByteArrayOutputStream();
        AdbAuth.Checked c = passThrough(sig, adbdCnxn, token(t), Collections.singletonList(alice), toClient, toAdbd);
        assertTrue(c.connected);
        assertEquals(alice.line, c.signer.line);
        assertNull(c.offered);
        // adbd's token and CNXN reached the client unchanged, and the signature reached adbd.
        assertTrue(Arrays.equals(concat(token(t), adbdCnxn), toClient.toByteArray()));
        assertTrue(Arrays.equals(sig, toAdbd.toByteArray()));
    }

    @Test public void newKeyOfferedToAdbdThatChecksIsHeldBack() throws Exception {
        byte[][] t = tokens(11, 2);
        byte[] sig = AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_SIGNATURE, 0, sign(BOB, t[0]));
        byte[] pub = AdbAuth.message(AdbAuth.A_AUTH, AdbAuth.AUTH_RSAPUBLICKEY, 0, (line(BOB, "bob@desk") + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream toClient = new ByteArrayOutputStream(), toAdbd = new ByteArrayOutputStream();
        AdbAuth.Checked c = passThrough(concat(sig, pub), token(t[1]), token(t[0]),
            Collections.<AdbAuth.Key>emptyList(), toClient, toAdbd);
        assertFalse(c.connected);
        assertEquals("bob@desk", c.offered.comment());
        // adbd saw the (failed) signature but never the public key.
        assertTrue(Arrays.equals(sig, toAdbd.toByteArray()));
    }

    @Test public void clientThatSkipsAuthIsDropped() throws Exception {
        byte[] client = concat(cnxn(), AdbAuth.message(0x4e45504f /* OPEN */, 1, 0, "shell:\0".getBytes("UTF-8")));
        try {
            run(client, Collections.<AdbAuth.Key>emptyList(), 1, new ByteArrayOutputStream());
            fail("expected the connection to be refused");
        } catch (IOException expected) { }
        try {
            run(AdbAuth.message(0x4e45504f, 1, 0, new byte[0]), Collections.<AdbAuth.Key>emptyList(), 1, new ByteArrayOutputStream());
            fail("expected CNXN first");
        } catch (IOException expected) { }
    }
}
