package dev.wayfinder;

/** A saved navigation target. Coordinates are stored by world name so the file remains readable. */
public record Waypoint(
        String id,
        String name,
        boolean shared,
        String world,
        double x,
        double y,
        double z
) {
}
