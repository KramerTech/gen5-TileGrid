package com.tilepad;

/** Single-writer controller. Time updates the display; it never implies activation. */
final class PreconditioningController {
    interface Journal { void requested(boolean value); }
    private final VehiclePort vehicle;
    private final Journal journal;
    private VehiclePort.Reading reading;
    private boolean requested, activeSeen, stopping, released, commandFailed;
    private long startedAt, endedAt, nextMaintenance;
    private PreconditioningSnapshot.State state = PreconditioningSnapshot.State.CHECKING;
    private String detail = "Checking vehicle services";
    private String commandError = "";

    PreconditioningController(VehiclePort vehicle, Journal journal) {
        this.vehicle = vehicle; this.journal = journal;
    }
    void recover(boolean pending, long now) {
        if (pending) {
            requested = true; startedAt = now;
            // A recovered process must cancel uncertain ownership, never send Start.
            stop(now);
        }
    }
    void refresh(long now) {
        try { reading = vehicle.read(); }
        catch (Exception e) {
            reading = null;
            state = PreconditioningSnapshot.State.UNKNOWN;
            detail = "Vehicle status unavailable. " + message(e);
            return;
        }
        if (reading.indicator != 0 && reading.indicator != 1) {
            state = PreconditioningSnapshot.State.UNKNOWN;
            detail = "The car has not supplied a recognized conditioning indicator.";
            return;
        }
        if (stopping) {
            if (released && reading.indicator == 0) {
                try { journal.requested(false); }
                catch (RuntimeException e) { fail("Cancel sent, but session completion could not be recorded. ", e); return; }
                requested = false; stopping = false;
                endedAt = now;
                state = PreconditioningSnapshot.State.ENDED;
                detail = "Cancel sent; the car reports conditioning Off.";
            } else if (released) {
                state = PreconditioningSnapshot.State.STOPPING;
                detail = "Cancel sent; waiting for the car’s Off indicator.";
            }
            return;
        }
        if (requested) {
            if (reading.ready == 0 || reading.enabled == 1 || (activeSeen && reading.indicator == 0)) {
                stop(now);
                return;
            }
            if (commandFailed) {
                state = PreconditioningSnapshot.State.ERROR; detail = commandError; return;
            }
            if (reading.ready != 1 || reading.enabled != 2) {
                state = PreconditioningSnapshot.State.UNKNOWN;
                detail = "Vehicle operating status is unknown. Stop remains available.";
                return;
            }
            if (reading.indicator == 1) {
                activeSeen = true; state = PreconditioningSnapshot.State.ACTIVE;
                detail = "The car reports battery conditioning active.";
            } else {
                state = PreconditioningSnapshot.State.WAITING;
                detail = "Waiting for BMS — activation may take about 1–2 minutes.";
                if (now - startedAt > 120000) detail += " Activation is still unconfirmed.";
            }
            // Refresh the OEM request metadata. Never resend the Start pulse.
            long interval = vehicle.maintenanceIntervalMs();
            if (interval > 0 && now >= nextMaintenance) {
                try { vehicle.maintainManual(); nextMaintenance = now + interval; }
                catch (Exception e) { fail("Manual request maintenance failed. ", e); }
            }
            return;
        }
        if (state == PreconditioningSnapshot.State.ENDED) return;
        String unavailable = vehicle.unavailableReason();
        if (!unavailable.isEmpty()) {
            state = PreconditioningSnapshot.State.UNAVAILABLE; detail = unavailable;
        } else if (reading.enabled != 2) {
            state = PreconditioningSnapshot.State.UNAVAILABLE;
            detail = "Enable battery conditioning in Hyundai’s settings first.";
        } else if (reading.ready != 1) {
            state = PreconditioningSnapshot.State.UNAVAILABLE;
            detail = "The car has not reported READY.";
        } else {
            state = PreconditioningSnapshot.State.IDLE;
            detail = reading.indicator == 1 ? "Conditioning is controlled by the car."
                : "Start a manual battery-conditioning request.";
        }
    }
    boolean start(long now) {
        if (!snapshot(now).canStart) return false;
        requested = true; stopping = false; released = false; activeSeen = false; commandFailed = false;
        startedAt = now; endedAt = 0;
        try {
            journal.requested(true); // Record uncertain ownership before any transmission.
        } catch (RuntimeException e) {
            requested = false; fail("Start was not sent because session ownership could not be recorded. ", e);
            return false;
        }
        try {
            vehicle.beginManual();
            nextMaintenance = now + vehicle.maintenanceIntervalMs();
            state = PreconditioningSnapshot.State.WAITING;
            detail = "Waiting for BMS — activation may take about 1–2 minutes.";
            return true;
        } catch (Exception e) { fail("Start could not be confirmed. Stop remains available. ", e); return false; }
    }
    void stop(long now) {
        if (!requested || (stopping && released)) return;
        stopping = true;
        state = PreconditioningSnapshot.State.STOPPING;
        detail = "Releasing the manual request; waiting for the car’s Off indicator.";
        try { vehicle.stopAndRelease(); released = true; }
        catch (Exception e) { fail("Cancellation is unconfirmed. Stop can be tried again. ", e); }
    }
    PreconditioningSnapshot snapshot(long now) {
        int indicator = reading == null ? -1 : reading.indicator;
        boolean known = reading != null && (indicator == 0 || indicator == 1);
        boolean canStart = !requested && known && indicator == 0 && reading.enabled == 2
            && reading.ready == 1 && vehicle.unavailableReason().isEmpty();
        return new PreconditioningSnapshot(state, requested, canStart,
            requested && !(stopping && released), indicator,
            reading == null ? -1 : reading.failureRaw,
            requested ? Math.max(0, now - startedAt) : endedAt > 0 ? Math.max(0, endedAt - startedAt) : 0,
            detail);
    }
    private void fail(String prefix, Exception e) {
        commandFailed = true;
        state = PreconditioningSnapshot.State.ERROR; detail = prefix + message(e); commandError = detail;
    }
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
