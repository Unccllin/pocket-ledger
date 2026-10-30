package com.pocketledger;

/**
 * The only Java class the native library knows about.
 *
 * <p>The five {@code nativeStart..nativeVersion} methods are registered by
 * Rivium's {@code rivium_jni::export!}. {@code nativeEnqueue} and
 * {@code nativeStats} are resolved from the shared library by symbol name and
 * return JSON.
 */
public final class Bridge {
    static {
        System.loadLibrary("ledger_jni");
    }

    private Bridge() {}

    // Rivium's fixed five-method ABI.
    public static native int nativeStart(String[] args);

    public static native int nativeStop(long timeoutMillis);

    public static native int nativeStatus();

    public static native String nativeLastError();

    public static native String nativeVersion();

    // pocket-ledger additions.
    public static native String nativeEnqueue(String rawText, long receivedAtUnixSeconds);

    public static native String nativeStats();
}
