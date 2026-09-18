package com.termux.api.apis;

import org.json.JSONException;
import org.json.JSONObject;

/** ARM64 availability from the API app process, never proof of accelerated dispatch. */
final class CapabilitiesCpu {
    private CapabilitiesCpu() {}

    private static native long[] nativeProbe();

    static JSONObject collect() throws JSONException {
        try {
            System.loadLibrary("termux-capabilities");
            return decode(nativeProbe());
        } catch (UnsatisfiedLinkError error) {
            return new JSONObject().put("status", "unavailable").put("reason", "native_probe_not_loaded");
        }
    }

    static JSONObject decode(long[] raw) throws JSONException {
        if (raw == null) return new JSONObject().put("status", "unsupported").put("reason", "arm64_only");
        if (raw.length != 9) throw new IllegalArgumentException("Invalid native probe result");
        JSONObject features = new JSONObject();
        String[] names = {"asimd", "aes", "sha2", "crc32", "asimdhp", "sha3", "asimddp", "sha512", "sve", "asimdfhm"};
        int[] bits = {1, 3, 6, 7, 10, 17, 20, 21, 22, 23};
        for (int i = 0; i < names.length; i++) {
            features.put(names[i], raw[2] == 0 ? (raw[0] & (1L << bits[i])) != 0 : JSONObject.NULL);
        }
        String[] names2 = {"sve2", "svei8mm", "svebf16", "i8mm", "bf16", "sme", "sme2"};
        int[] bits2 = {1, 9, 12, 13, 14, 23, 37};
        for (int i = 0; i < names2.length; i++) {
            features.put(names2[i], raw[3] == 0 ? (raw[1] & (1L << bits2[i])) != 0 : JSONObject.NULL);
        }
        return new JSONObject().put("status", raw[2] == 0 && raw[3] == 0 ? "ok" : "partial")
                .put("source", "getauxval in the Termux:API process")
                .put("architecture", "aarch64").put("hwcap", "0x" + Long.toHexString(raw[0]))
                .put("hwcap2", "0x" + Long.toHexString(raw[1]))
                .put("hwcap_errno", raw[2]).put("hwcap2_errno", raw[3]).put("features", features)
                .put("page_size", raw[8] > 0 ? raw[8] : JSONObject.NULL)
                .put("sve", vector(raw[2], (raw[0] & (1L << 22)) != 0, raw[4], raw[5]))
                .put("sme", vector(raw[3], (raw[1] & (1L << 23)) != 0, raw[6], raw[7]))
                .put("note", "Feature availability and current API worker-thread vector lengths only; no SIMD instructions are exercised.");
    }

    private static JSONObject vector(long capabilityError, boolean advertised, long value, long error) throws JSONException {
        String status = capabilityError != 0 ? "unknown" : !advertised ? "unsupported" : value < 0 ? "unavailable" : "ok";
        return new JSONObject().put("status", status).put("errno", error)
                .put("vector_length_bytes", "ok".equals(status) ? value & 0xffff : JSONObject.NULL);
    }
}
