package com.tilepad;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * Runs one command through NEMESIS's privileged channel ({@code nemesis root -c}), which
 * any UID may use. This is the same path NEMESIS Manager uses for its own root actions.
 */
final class RootShell {
    static final String NEMESIS = "/system/xbin/nemesis";

    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    static boolean available() { return new File(NEMESIS).exists(); }

    /** Blocking; call from a worker thread. */
    static Result run(String command) {
        if (!available()) return new Result(127, "NEMESIS is not installed (" + NEMESIS + " is missing).");
        try {
            Process process = new ProcessBuilder(NEMESIS, "root", "-c", command).redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) > 0; ) if (out.size() < 16384) out.write(buffer, 0, n);
            }
            return new Result(process.waitFor(), out.toString("UTF-8").trim());
        } catch (Exception e) {
            return new Result(1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private RootShell() { }
}
