package com.tilepad;

final class VehicleClientFactory {
    static VehiclePort create() { return new OemVehicleClient(); }

    static boolean simulated() { return false; }
}
