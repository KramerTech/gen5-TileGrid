package com.tilepad;

/** Firmware-specific OEM command profile. Evidence: verification/firmware-profile.md. */
final class PreconditioningProfile {
    static final String DEVICE = "wp_daudioplus_neev23_us";
    static final String FRAMEWORK_SHA256 = "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338";
    static final String SERVICE_SHA256 = "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222";
    static final long REFRESH_MS = 5000;
    interface Commands {
        void write(int transaction, int... values) throws Exception;
        void pause(long milliseconds);
    }
    static void start(Commands commands) throws Exception {
        metadata(commands, 9, 18);
        // Stock FrameEthControl sends route metadata before the station event.
        commands.pause(10);
        // HEVService schedules Invalid (7,7) at 120 ms. Serialize a fast Stop
        // behind that pulse so the old timer cannot shorten the Cancel pulse.
        try { commands.write(62, 2, 3); } finally { commands.pause(130); }
    }
    static void maintain(Commands commands) throws Exception {
        metadata(commands, 9, 18); // Refresh metadata only; never repeat Start.
    }
    static void cancel(Commands commands) throws Exception {
        Exception failure = null;
        // Attempt every cancellation component even when an earlier call fails.
        try { commands.write(65, 2047); } catch (Exception e) { failure = e; }
        try { commands.write(66, 1023); } catch (Exception e) { if (failure == null) failure = e; }
        try { commands.write(62, 0, 0); } catch (Exception e) { if (failure == null) failure = e; }
        commands.pause(130);
        if (failure != null) throw failure;
    }
    private static void metadata(Commands commands, int distanceKm, int remainingMinutes) throws Exception {
        commands.write(65, distanceKm); commands.write(66, remainingMinutes);
    }
    private PreconditioningProfile() { }
}
