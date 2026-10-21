# Wayfinder Compass — Paper 26.3 + Geyser

A navigation-only waypoint plugin designed around ordinary Minecraft items and inventory menus so Java and Bedrock players can use the same interaction flow through Geyser.

## First-version feature set

- **Wayfinder Compass**: right-click it to open a destination menu.
- **Personal waypoints**: players can save and remove their own locations.
- **Shared waypoints**: server admins can publish locations everyone can navigate to.
- **Navigation-only**: no teleporting, no client mod, and no required resource pack.
- **Distance + direction**: an action-bar status updates while a target is active.
- **Nearby marker**: a small pillar and ring of particles appear for the navigating player when they are within the configured range.
- **Persistent storage**: destinations are saved in `plugins/WayfinderCompass/waypoints.yml`.
- **Paginated menu**: uses standard inventory slots and vanilla items.

The menu uses ordinary inventory clicks and vanilla item types rather than client-side custom assets. Geyser compatibility is the intended design, but it still needs to be verified on your server with a Java player and a Bedrock player.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/wp item` | Give yourself a Wayfinder Compass | `wayfinder.use` |
| `/wp set <name>` | Save your current location as a private waypoint | `wayfinder.use` |
| `/wp remove <name>` | Remove one of your private waypoints | `wayfinder.use` |
| `/wp list` | List private and shared waypoints | `wayfinder.use` |
| `/wp stop` | Stop following the current target | `wayfinder.use` |
| `/wp shared set <name>` | Save a shared waypoint at your current location | `wayfinder.admin` |
| `/wp shared remove <name>` | Remove a shared waypoint | `wayfinder.admin` |
| `/wp shared list` | List shared waypoints | `wayfinder.admin` |
| `/wp item <player>` | Give a compass to an online player | `wayfinder.admin` |

Waypoint names can contain letters, numbers, spaces, hyphens and underscores (1–32 characters). Use quotes only if your server command parser supports them; joining command arguments means spaces work normally, e.g. `/wp set Base Camp`.

## Install after building

1. Build the project to produce `build/libs/WayfinderCompass-1.0.0.jar`.
2. Stop your Paper server.
3. Copy the JAR into the server's `plugins` folder.
4. Start the server and check the console for `Wayfinder Compass enabled`.
5. In-game, try `/wp item`, then `/wp set Home`, right-click the compass, and select `Home`.
6. To create a server-wide location, an operator can stand at it and run `/wp shared set Spawn`.

## Build requirements

Paper's current 26.3 developer setup calls for **JDK 25** and the Paper API repository. This project uses that API and targets Java 21 bytecode for the plugin class itself. Build with Gradle:

```shell
gradle clean build
```

A GitHub Actions workflow is included at `.github/workflows/build.yml`. If you put this project in a GitHub repository and run the workflow, it builds the JAR with JDK 25 and uploads `WayfinderCompass` as a workflow artifact.

## Configuration

Edit `plugins/WayfinderCompass/config.yml` after the first startup:

- `max-personal-waypoints`: default 20
- `max-shared-waypoints`: default 50
- `marker-visible-range`: default 80 blocks
- `update-interval-ticks`: default 10 (half a second)

Restart the server after changing configuration.

## Compatibility/testing note

The project source is prepared for Paper 26.3's published API coordinates. This work environment has JDK 21 and no outbound connection to Paper's Maven repository, so I could not build and run a server-tested JAR here. The source and CI build workflow are included; please test the built JAR on a copy of your server before installing it on your live world. In particular, verify menu clicks and particle rendering with the Bedrock versions your friends actually use.
