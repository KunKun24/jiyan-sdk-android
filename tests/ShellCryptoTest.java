package com.jiyan.cloud.shell;

/** 纯 JVM 自测：不联网、不需要设备。 */
public final class ShellCryptoTest {
    private static int pass;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        pass++;
        System.out.println("[PASS] " + name);
    }

    public static void main(String[] args) {
        String expected = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
        check(expected.equals(Signer.hmacHex("key",
                "The quick brown fox jumps over the lazy dog")), "HMAC-SHA256 RFC 4231 vector");

        String canonical = Signer.canonical("manifest", "JiYan_1", "10001", "dev-abc",
                "1", "0", "1700000000", "nonce123", "", "", "");
        check(canonical.split("\n", -1).length == 11, "canonical has 11 fields");
        check(canonical.startsWith("manifest\nJiYan_1\n10001\ndev-abc\n1\n0\n1700000000\n"),
                "canonical field order");

        check(Signer.hmacHex("k", "a").length() == 64, "hex length 64");
        System.out.println("\n" + pass + " passed");
    }
}
