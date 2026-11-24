# AI DEVELOPMENT PROMPT: planeMode — Live In-Flight Plane Tracker for Android

## 1. Project Overview

Act as a senior Android application architect, Kotlin developer, geospatial engineer, and UI/UX designer.

Build a complete, functional, production-quality native Android smartphone application named **planeMode**.

The application is a live in-flight aircraft tracker that uses the smartphone's GPS/GNSS receiver, location services, and onboard sensors to track and visualize the passenger's current flight.

The central experience should resemble a sophisticated passenger flight-tracking display, with an overhead graphical representation of an aircraft moving across a live vector map.

The application should feel like a beautiful, modern aviation instrument rather than a generic GPS application.

### Primary objectives

1. Track the user's actual geographic position during a flight.
2. Display a graphical overhead aircraft representation on a moving vector map.
3. Calculate and display ground speed, heading, altitude, climb rate, flight duration, and distance traveled.
4. Maintain a historical flight path.
5. Provide a graphical artificial horizon and simplified aviation HUD.
6. Display GPS satellite reception and signal status.
7. Provide estimated destination distance and arrival time when departure and arrival airports are configured.
8. Support offline operation with locally cached vector maps.
9. Use onboard motion sensors to estimate device orientation and provide limited short-term position prediction during temporary GPS interruptions.
10. Provide sound effects for significant flight events.
11. Include a realistic simulated flight GPS stream for development and testing.

**Important:** This application tracks the smartphone's location during the user's own flight. It is not an ADS-B receiver, airline flight-information aggregator, or aircraft tracking service. Do not introduce unnecessary external flight-tracking APIs.

---

# 2. Technology Stack and Architecture

Develop a fully native Android application.

| Component | Required technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Design system | Material 3 |
| IDE | Android Studio |
| Minimum Android version | Android 8.0 / API 26 |
| Target SDK | Latest stable SDK available |
| Architecture | MVVM |
| Async operations | Kotlin Coroutines and Flow |
| Dependency injection | Hilt |
| Database | Room / SQLite |
| Preferences | Jetpack DataStore |
| Location | Google Play Services Fused Location Provider, with appropriate Android location fallback |
| Satellite information | Android GnssStatus API |
| Sensors | Android SensorManager |
| Vector maps | MapLibre Native Android |
| Charts | Compose Canvas or lightweight chart implementation |
| Background tracking | Android Foreground Service |
| Build system | Gradle Kotlin DSL |

Use a clean, modular architecture with separation between:

- UI and presentation.
- Flight tracking and calculations.
- Location and sensor management.
- Map rendering and offline caching.
- Flight event detection.
- Data persistence.
- Mock flight simulation.

Use StateFlow and reactive state updates throughout the application.

Avoid unnecessary third-party dependencies. Prefer lightweight, maintained libraries.

Do not over-engineer the project.

The application must compile and run as a complete project, not as a collection of disconnected code examples.

---

# 3. Application Design and Navigation

Create an attractive, cohesive aviation-inspired visual design.

### Visual direction

Use a clean, modern aviation instrumentation aesthetic.

Suggested palette:

- Deep navy: primary background.
- Dark charcoal: secondary panels.
- Aviation blue: map and flight information.
- Cyan or turquoise: active flight indicators.
- White: primary typography.
- Muted grey: secondary information.
- Green: GPS lock and normal operating status.
- Amber: warnings and reduced GPS accuracy.
- Red: critical status.
- Blue and brown: artificial horizon.

Use restrained shadows, subtle transitions, rounded panels, and excellent typography.

Avoid excessive gradients, unnecessary animations, excessive card nesting, and clutter.

The map should be visually dominant during flight.

### Navigation

Provide the following main pages:

1. Home / Flight Start
2. Flight Map
3. Altitude Profile
4. Cockpit HUD
5. Flight Information Summary
6. GPS Satellite Monitor
7. Settings

Use a compact, intuitive bottom navigation system for the primary flight screens. Settings and satellite details may be accessed through an additional navigation item or menu.

Maintain consistent information hierarchy and styling across all pages.

Support portrait and landscape orientations where practical.

---

# 4. Home Page — Flight Start

Create an attractive landing screen.

### Main elements

- planeMode branding.
- Large graphical airplane illustration.
- Animated or smoothly moving vector map background.
- Prominent START FLIGHT button.
- Current GPS status.
- Current GPS coordinates.
- Current altitude.
- Current groundspeed.
- Selected aircraft type.
- Optional departure airport.
- Optional arrival airport.

### Aircraft selection

Provide an optional aircraft selector with the following aircraft families:

- Boeing 737.
- Boeing 737 MAX.
- Boeing 777.
- Boeing 787.
- Airbus A320.
- Airbus A321.
- Airbus A330.
- Airbus A350.
- Embraer ERJ.

Aircraft selection should be optional. The user can start tracking without choosing an aircraft.

When an aircraft is selected, retrieve and cache its basic performance information when online.

Include:

- Aircraft manufacturer.
- Aircraft family.
- Typical cruising speed.
- Maximum cruise speed.
- Typical cruising altitude.
- Maximum operating altitude.
- Approximate range.

Use an embedded local aircraft dataset as the immediate fallback. Online downloads should update the cached information when available.

Performance values must be identified as approximate family-level reference values, not specifications for the exact aircraft variant.

Provide a simple aircraft silhouette or icon corresponding to the selected family where available.

### Departure and arrival airports

Allow the user to optionally specify:

- Departure airport (IATA or ICAO code).
- Arrival airport (IATA or ICAO code).

Provide an autocomplete airport selector backed by a locally cached airport database.

When both airports are selected:

1. Calculate approximate great-circle distance.
2. Display estimated overall flight distance.
3. Calculate estimated flight duration.
4. Display estimated remaining distance during the flight.
5. Display estimated time remaining.

Use the Haversine formula or a suitable geodesic library.

The route estimate must be described as approximate because actual commercial airline routes vary.

Draw an optional direct route line between the airports.

As the flight progresses, calculate remaining straight-line distance using the current GPS position and destination coordinates.

### Starting the flight

When START FLIGHT is pressed:

- Validate required permissions.
- Acquire the best available GPS fix.
- Initialize the flight session.
- Record the starting position.
- Start the flight timer.
- Begin calculating distance traveled.
- Start collecting location and sensor information.
- Initialize the flight route.
- Navigate to the Flight Map page.

The timer and tracking must continue if the user navigates between pages or turns off the display.

Persist the flight session so it can be recovered after an unexpected application shutdown.

---

# 5. Vector Mapping System — Critical Requirement

The mapping system is a central part of planeMode.

**Use genuine vector map data. Do not use raster map tiles or bitmap map backgrounds.**

Use MapLibre Native Android with vector tile rendering.

### Map appearance

Create a simplified aviation-friendly map style.

Display:

- Continents and coastlines.
- Major land boundaries.
- Major cities.
- Major metropolitan areas.
- Major rivers and lakes.
- Significant geographical landmarks.
- Country names.
- Major water bodies.

Do not display unnecessary street-level detail, minor roads, individual buildings, or excessive points of interest.

The purpose is to provide geographical context during flight, not street navigation.

### Map colors

Use an attractive, understated aviation palette.

Suggested appearance:

- Land: muted light blue-grey or pale green.
- Water: muted blue.
- Major cities: subtle labels.
- Country boundaries: thin grey lines.
- Selected destination: contrasting cyan.
- Flight route: colored speed gradient.

Provide a dark map style as an optional theme.

### Map performance

The map must:

- Render vector features efficiently.
- Maintain smooth movement.
- Support zooming and panning.
- Rotate or remain north-up based on settings.
- Keep the aircraft marker visible.
- Animate map movement smoothly.
- Avoid excessive redraws.
- Limit unnecessary network activity.

### Offline vector map caching

Implement a complete offline map management system.

Use MapLibre offline-region functionality.

Requirements:

1. Initially download a limited geographical region surrounding the first valid GPS fix.
2. Cache recently viewed map regions automatically.
3. Allow users to download specific regions manually.
4. Support pre-caching regions along a planned departure-to-arrival route when both airports are configured and connectivity is available.
5. Allow users to manage, inspect, and delete downloaded regions.
6. Display download progress and estimated storage use.
7. Provide configurable cache limits.
8. Prioritize Wi-Fi downloads, with optional cellular downloading.
9. Store map data persistently on device.
10. Avoid repeatedly downloading existing cached data.

Where a destination is selected, optionally pre-cache a corridor around the estimated route rather than downloading an entire continent at high zoom levels.

Map resources and vector tile providers must respect their licensing, attribution, and usage restrictions. Make the vector tile source configurable rather than hardcoding an unauthorized public tile endpoint.

If online map services are unavailable, display whatever locally cached vector data is available. Continue showing the flight path over a neutral background when map coverage is missing.

Never silently substitute raster tiles.

---

# 6. Flight Map Page — Primary Application Screen

This is the most important page in planeMode.

Create a visually impressive, interactive overhead flight-tracking display.

### Main map display

Show:

- Moving vector map.
- Graphical overhead airplane icon.
- Aircraft heading indicator.
- Current flight location.
- Historical route trace.
- Optional destination marker.
- Optional estimated destination route.
- GPS status indicator.

The aircraft icon should be centered or slightly below the center of the screen, with the map moving underneath it.

Provide smooth location interpolation so the plane does not jump every time a GPS update arrives.

Use a configurable map-follow mode.

Default mode: aircraft centered, map oriented to the current GNSS course over ground.

Provide a north-up alternative.

### Flight information header

Display a compact header containing:

- Aircraft type.
- Current ground speed.
- Current altitude.
- Current heading.
- GPS reception status.

### Flight information footer

Display:

- Elapsed time.
- Distance traveled.
- Climb or descent rate.
- Estimated distance remaining, if destination is configured.
- Estimated time remaining, if destination is configured.

Use large, readable typography.

Allow the user to select a compact or expanded information layout.

### Historical route trace

Continuously draw the route followed by the aircraft.

Implement color-coded route segments.

Use a speed-based color gradient, for example:

| Ground speed | Route color |
|---|---|
| Below 150 knots | Cyan |
| 150–250 knots | Green |
| 250–350 knots | Yellow |
| Above 350 knots | Orange |

These thresholds should be configurable.

When GPS is unavailable and the displayed position is estimated, draw the corresponding route segment in dark grey.

Clearly distinguish measured GPS positions from estimated positions.

Provide a small map legend.

Allow the user to zoom into and out of the map.

---

# 7. GPS, Location Tracking and Sensor Integration

Implement accurate location monitoring using Android's location APIs.

### GPS tracking

Collect, where available:

- Latitude.
- Longitude.
- Altitude.
- Ground speed.
- Course over ground.
- Horizontal accuracy.
- Vertical accuracy.
- Speed accuracy.
- Location timestamp.

Use high-accuracy location requests during an active flight, with sensible battery and processing optimizations.

Filter invalid and anomalous GPS readings.

Use timestamps to reject stale data and avoid duplicate position samples.

Smooth location updates without introducing excessive latency.

### Important measurement distinctions

Clearly identify ground speed as GPS-derived ground speed, not true airspeed or indicated airspeed.

Heading should use GNSS course over ground while the aircraft is moving and the fix is reliable. A phone compass only provides the phone's magnetic orientation.

Do not claim that a smartphone magnetometer independently knows the aircraft's heading when the phone is not aligned with the aircraft.

### GPS loss and estimated position

Implement a GPS status monitor.

Detect when GPS signal quality deteriorates or location updates become unavailable.

Use the following display states:

- GPS LOCKED.
- GPS WEAK.
- GPS LOST.
- ESTIMATED POSITION.
- GPS REACQUIRED.

When GPS is lost:

1. Preserve the last known valid location.
2. Use the last reliable ground speed and course over ground to extrapolate position briefly.
3. Mark extrapolated points as estimated.
4. Draw those route segments in dark grey.
5. Increase position uncertainty as time passes.
6. Show the time since the last valid GPS fix.
7. Resume accurate GPS tracking when a valid fix returns.
8. Reconcile the estimated track with the new GPS position without creating an artificial teleporting route segment.

**Important sensor limitation:** Accelerometers and gyroscopes measure the smartphone's own movement, not the aircraft's absolute movement. A phone resting on a passenger's lap or tray table is not rigidly aligned with the aircraft. Do not pretend that integrating phone acceleration can accurately reconstruct the airplane's flight path. Use short-duration velocity/course extrapolation as the primary fallback. IMU information can support orientation and experimental, explicitly low-confidence estimation when properly calibrated.

Set a short configurable prediction window, initially 30 seconds. Beyond that, freeze the marker at its last estimated position and communicate that location is stale rather than inventing an increasingly inaccurate route.

### Sensor integration

Use available:

- Accelerometer.
- Gyroscope.
- Magnetometer.
- Rotation vector.
- Gravity sensor.
- Barometric pressure sensor, where available.

Use sensor fusion or Android's rotation-vector facilities.

These sensors should support:

- Device orientation.
- Pitch estimation.
- Roll estimation.
- HUD visualization.
- Compass display.
- Sensor diagnostics.

If barometric pressure is used to estimate relative altitude, clearly label the measurement as a phone-derived pressure altitude estimate and allow calibration.

---

# 8. Altitude Profile Page

Create a graphical side-profile representation of the flight.

This page should display a side silhouette of an aircraft moving along an altitude profile.

### Main visualization

Display:

- Current aircraft position.
- Historical altitude trace.
- Current altitude.
- Flight distance.
- Expected route profile, if an airport destination is configured.
- Climb and descent sections.

The vertical axis represents altitude.

The horizontal axis represents distance traveled.

### Color-coded altitude profile

Use different colors for altitude bands:

- Below 10,000 ft: cyan.
- 10,000–20,000 ft: green.
- 20,000–30,000 ft: blue.
- 30,000–40,000 ft: purple.
- Above 40,000 ft: amber.

Make these thresholds configurable.

### Information header

Display:

- Current speed.
- Current altitude.
- Current heading.

### Information footer

Display:

- Distance traveled.
- Elapsed time.
- Climb/descent rate.

Use a smooth scrolling chart.

The aircraft icon should remain near the center while the historical altitude graph scrolls horizontally.

Implement a visually attractive, lightweight chart using Compose Canvas rather than introducing a heavyweight charting dependency.

---

# 9. Cockpit HUD Page

Create a simplified cockpit-style instrument display inspired by an aviation Attitude Director Indicator (ADI).

This should be a particularly attractive page.

### Layout

**Top center: Compass**

- Compass heading.
- Cardinal directions.
- Current heading emphasized.
- Smooth rotation.

**Left side: Speed tape**

- Vertical scrolling scale.
- Current GPS ground speed highlighted.
- Knots, mph, or km/h according to settings.
- Clear numerical speed indicator.

**Center: Artificial horizon**

- Blue sky.
- Brown ground.
- Pitch reference markings.
- Bank angle markings.
- Visible aircraft reference symbol.
- Numerical pitch and roll.

**Right side: Altitude tape**

- Vertical altitude scale.
- Current altitude highlighted.
- Feet or meters according to settings.
- Climb/descent trend indication.

**Additional information**

- Climb/descent rate.
- GPS signal indicator.
- Aircraft heading.

### Sensor-based attitude

Use the smartphone's rotation vector, gyroscope, accelerometer, and magnetometer to estimate pitch and roll.

Provide a calibration procedure that lets users align the phone with an assumed aircraft reference orientation.

Clearly label these values as device-derived attitude estimates unless a properly aligned, fixed mounting arrangement is established.

A passenger holding a phone cannot reliably obtain the aircraft's actual pitch and roll.

Use smooth filtering and avoid jitter.

The artificial horizon should remain visually stable and responsive.

Provide a recalibration option.

---

# 10. Flight Information Summary Page

Create an attractive flight dashboard with large information panels.

### Primary flight metrics

Display:

- Current ground speed.
- Current altitude.
- Current heading.
- Climb/descent rate.
- Distance traveled.
- Flight elapsed time.
- Estimated remaining distance.
- Estimated time remaining.

### Additional information

Display:

- Selected aircraft.
- Departure airport.
- Arrival airport.
- Flight phase.
- Estimated pitch.
- Estimated roll.
- GPS status.
- Location accuracy.
- Time since last valid GPS update.
- Current position coordinates.

### Estimated arrival

When a destination is configured:

Calculate estimated remaining distance using the current position and destination coordinates.

Calculate estimated arrival time using current reliable ground speed.

Use an appropriate smoothed speed calculation to reduce ETA fluctuations.

If current speed is unavailable or unreliable, fall back to the aircraft's approximate typical cruise speed as an explicitly identified estimate.

Do not present calculated ETA as official airline flight information.

Update the dashboard reactively.

---

# 11. GPS Satellite Monitor Page

Build a dedicated GPS monitoring screen.

Use Android GnssStatus callbacks where supported.

Show a graphical sky-plot representing available satellites.

### Satellite sky plot

Represent each satellite as an icon or point based on:

- Azimuth.
- Elevation.
- Constellation.
- Signal strength.
- Used-in-fix status.

Distinguish between:

- Satellites in view.
- Satellites currently used in the location fix.
- Satellites with weak or unavailable signals.

Where supported, identify GNSS constellations:

- GPS.
- GLONASS.
- Galileo.
- BeiDou.
- QZSS.
- SBAS.

Use recognizable colors for different constellations.

### GPS statistics

Display:

- Number of satellites in view.
- Number used for positioning.
- Average signal strength.
- Current horizontal accuracy.
- Current altitude accuracy, where available.
- Last successful fix.
- GPS lock duration.
- GPS loss duration.
- Estimated tracking duration.

### Time statistics

Provide separate timers for:

- Total time with a valid GPS fix.
- Total time with estimated position tracking.
- Total time without usable positioning.

When GNSS satellite data is unavailable on the device, clearly show that the detailed satellite information is unsupported or unavailable rather than fabricating values.

---

# 12. Flight Phase Detection and Sound Effects

Implement a lightweight flight-phase detection engine.

Possible flight states:

1. Idle.
2. Armed.
3. Takeoff.
4. Initial climb.
5. Climb.
6. Cruise.
7. Top of descent.
8. Descent.
9. Landing.
10. Completed.

Use filtered GPS speed, altitude trend, vertical rate, duration, and historical observations to infer transitions.

Use hysteresis and minimum duration thresholds to prevent repeated or false events.

### Sound effects

Sound effects should be enabled by default.

Create original, subtle aviation-inspired chimes. Do not copy proprietary airline announcements or copyrighted aircraft sounds.

Allow the user to enable or disable each event individually.

Implement these sound events:

| Event | Trigger |
|---|---|
| GPS acquired | Valid GPS lock established |
| GPS lost | GPS unavailable beyond threshold |
| Takeoff-speed threshold | Ground speed crosses configured threshold during takeoff |
| 10,000 feet | Climbing through 10,000 ft |
| Cruise altitude | Climb rate remains near zero after climb |
| Maximum cruise speed | Crosses selected aircraft's reference speed |
| Top of descent | Sustained negative vertical rate following cruise |
| Landing | Probable landing and deceleration detected |
| Flight complete | Tracking session ends |

Important: True V1 is aircraft-specific and cannot be calculated from phone GPS speed alone. The default 140-knot threshold is an approximate takeoff-speed alert, not a real V1 determination. Label it accordingly in settings and notifications.

### Landing detection

Do not stop tracking immediately when speed briefly decreases.

Implement a landing confirmation mechanism using:

- Sustained ground-speed reduction.
- Altitude and vertical-rate trends where available.
- Flight duration.
- State transition history.

A configurable taxi-speed threshold should default to approximately 35 knots.

Require the aircraft to remain below the threshold for a configurable period, initially 90 seconds, before suggesting that the flight has landed.

Allow the user to manually end tracking.

---

# 13. Automatic Flight Start and Stop

Provide an Auto Flight Tracking setting.

When enabled:

### Automatic start

Monitor GPS-derived ground speed.

When speed exceeds the configured threshold for a sustained period:

- Validate GPS accuracy.
- Check that the location stream is current.
- Confirm the threshold is sustained.
- Start the flight session.
- Begin recording flight data.

Default threshold: 140 knots.

Make the threshold configurable.

This is a practical takeoff-speed trigger, not an exact aircraft V1 detector.

### Automatic stop

After a flight has started, detect sustained deceleration to taxiing speed.

Default threshold: 35 knots.

Use a sustained low-speed period and flight-state confirmation before automatically finishing the session.

Provide a user override to prevent unexpected automatic termination.

Do not rely on noisy or extrapolated position data to initiate or terminate an automatic session.

---

# 14. Settings Page

Create a clean, categorized settings interface.

### Flight tracking

- Auto-start flight: ON/OFF.
- Auto-stop flight: ON/OFF.
- Automatic start speed threshold.
- Automatic stop speed threshold.
- Minimum GPS accuracy.
- GPS update interval.
- Position prediction duration.

### Aircraft

- Default aircraft.
- Aircraft performance data.
- Refresh aircraft reference data.
- Cached performance information.

### Sound

- Sound effects master toggle (default ON).
- Individual event toggles.
- Sound volume.
- Preview sound effects.

### Units

Speed:

- Knots.
- Miles per hour (mph).
- Kilometers per hour (km/h).

Altitude:

- Feet.
- Meters.

Climb rate should follow the configured altitude system.

### Mapping

- Map style.
- North-up or flight-heading-up.
- Automatic map centering.
- Automatic region caching.
- Maximum cache storage.
- Wi-Fi-only downloads.
- Manual region downloads.
- Downloaded regions management.
- Clear map cache.

### Display

- Light theme.
- Dark theme.
- Follow system theme.
- Compact/expanded flight information.
- Screen-on option during tracking.
- HUD calibration.

### Simulation / Developer settings

Provide a test-mode section.

Options:

- Enable mock flight.
- Select mock route.
- Simulation playback speed.
- Pause/resume simulation.
- Simulate GPS loss.
- Simulate GPS recovery.
- Reset simulation.

Clearly distinguish simulation data from actual GPS tracking.

---

# 15. Background Tracking and Persistence

The flight-tracking engine must continue working when the application is not visible.

Implement a proper Android Foreground Service with location service type.

Requirements:

- Request appropriate runtime location permissions.
- Request notification permission where required.
- Display an ongoing flight-tracking notification.
- Continue receiving location updates when the screen turns off.
- Preserve flight state when the app is recreated.
- Persist location samples and flight events using Room.
- Avoid unnecessary background processing.
- Handle location-service disablement gracefully.
- Handle application process recreation.
- Release sensor listeners and resources appropriately.

Use Android platform-compliant foreground-service declarations and permission handling.

Do not request unrestricted background location permission unless genuinely required by the chosen implementation.

Provide a persistent notification with flight duration, current speed, and an action to reopen planeMode.

---

# 16. Mock Flight GPS Data Stream — Mandatory

Implement an actual simulated GPS data source.

This is essential for testing the application without flying.

Create a deterministic simulated flight from Newark Liberty International Airport (EWR) to Los Angeles International Airport (LAX).

The simulation should use a realistic flight profile.

### Sample flight phases

| Phase | Approximate altitude | Approximate speed |
|---|---:|---:|
| Ground/taxi | 0–100 ft | 0–25 knots |
| Takeoff | 100–1,500 ft | 140–180 knots |
| Initial climb | 1,500–10,000 ft | 180–280 knots |
| Climb | 10,000–30,000 ft | 250–350 knots |
| Cruise | 30,000–39,000 ft | 400–480 knots |
| Descent | 39,000–10,000 ft | 250–400 knots |
| Approach | 10,000–1,000 ft | 140–250 knots |
| Landing/taxi | 0–100 ft | 0–35 knots |

Use these figures as illustrative test parameters, not actual performance guarantees.

### Mock data structure

Create a structured location stream containing:

- Timestamp.
- Latitude.
- Longitude.
- Altitude.
- Ground speed.
- Course over ground.
- Vertical speed.
- Horizontal accuracy.
- Vertical accuracy.
- Satellite count.
- GPS validity.
- Simulated GPS signal strength.

Provide a sample JSONL dataset in the application assets:

`app/src/main/assets/mock_flight_ewr_lax.jsonl`

Also implement a Kotlin-based procedural flight generator that can produce a continuous stream at a configurable interval.

The generated flight should:

1. Start at EWR.
2. Simulate taxiing.
3. Simulate acceleration and takeoff.
4. Follow a realistic curved geographical route toward LAX.
5. Gradually climb to cruising altitude.
6. Maintain a relatively stable cruise altitude.
7. Simulate a descent.
8. Simulate landing and taxiing.

Use great-circle interpolation or appropriate waypoint interpolation to calculate coordinates.

Add minor realistic variations in altitude, speed, and heading without introducing excessive random noise.

### Mock GPS failure scenarios

Include the ability to:

- Drop GPS updates for 30 seconds.
- Simulate weak GPS accuracy.
- Simulate GPS recovery.
- Simulate missing satellite information.
- Simulate location inaccuracies.
- Test estimated position rendering.

The mock provider must implement the same interface as the real GPS provider.

All application pages, tracking calculations, flight-phase transitions, map traces, sound events, ETA calculations, and GPS status indicators must work with simulated data exactly as they do with real location updates.

### Simulation controls

Include:

- Start.
- Pause.
- Resume.
- Reset.
- 1x speed.
- 5x speed.
- 20x speed.
- 60x speed.

The simulation should use virtual timestamps consistently so that flight duration, elapsed time, phase detection, and historical route calculations remain coherent at faster playback rates.

---

# 17. Data Models and Calculations

Create well-defined Kotlin data models.

Suggested core models:

- FlightSession.
- FlightSample.
- AircraftProfile.
- Airport.
- FlightEvent.
- GpsStatus.
- SatelliteInfo.
- SensorAttitude.
- MapRegion.
- FlightSettings.

Persist historical track samples and flight summaries using Room.

### Core calculations

Implement:

**Distance traveled**

Calculate cumulative geographic distance between accepted position samples.

Reject unrealistic jumps and invalid fixes.

**Flight time**

Use a monotonic time source for active timing, with appropriate persistence and recovery.

**Ground speed**

Use GPS speed when available, with unit conversion.

**Heading**

Use reliable GNSS course over ground while moving.

**Climb rate**

Calculate smoothed vertical speed using valid altitude observations and elapsed time.

Use an available barometer as supplemental information where appropriate.

**Remaining distance**

Calculate the current straight-line geographical distance from the aircraft's position to the destination airport.

**Estimated arrival time**

Calculate:

Remaining distance / filtered ground speed.

Do not show a misleading ETA when position or speed is unreliable.

**Route history**

Store timestamped GPS and estimated samples with their source and quality indicators.

Maintain separate metadata for measured and estimated positions.

---

# 18. Performance and Reliability

Performance is a major design requirement.

The application should run smoothly on ordinary modern Android smartphones.

Prioritize:

- Low CPU utilization.
- Sensible GPS update rates.
- Efficient sensor sampling.
- Smooth map rendering.
- Limited memory consumption.
- Efficient Room database writes.
- Batched route-history persistence.
- Efficient vector map caching.
- Minimal unnecessary network requests.
- Battery-conscious operation.

Do not continuously redraw every page when it is not visible.

Use appropriately filtered and throttled UI updates.

Keep the location collection and flight calculation engine independent of the current UI screen.

Handle unavailable hardware and missing permissions gracefully.

The application should remain useful even when some sensors are unavailable.

---

# 19. Essential Testing and Validation

Keep testing focused and practical. Do not create an unnecessarily extensive testing framework.

Implement essential unit tests for:

1. Great-circle distance calculations.
2. Distance traveled accumulation.
3. Speed unit conversions.
4. Altitude unit conversions.
5. Climb-rate calculation.
6. GPS loss detection.
7. Estimated position prediction.
8. GPS recovery and route reconciliation.
9. Flight-phase detection.
10. Automatic start/stop logic.
11. Destination ETA calculation.

Perform a basic UI smoke test.

Run the mock EWR–LAX flight through the entire application.

Confirm:

- The aircraft moves across the map.
- The route trace is drawn correctly.
- Route colors respond to speed.
- Estimated GPS segments turn dark grey.
- GPS recovery restores accurate tracking.
- The altitude profile updates.
- The HUD updates.
- Satellite information updates where available.
- Flight summary statistics are correct.
- Sound events occur at the appropriate transitions.
- The application continues tracking with the screen off.

Build the application successfully using Gradle.

Resolve compilation errors and significant runtime issues before declaring the project complete.

---

# 20. Deliverables

Produce the complete Android Studio project.

Expected structure:

- Kotlin source files.
- Jetpack Compose UI.
- Resource files.
- Android manifest.
- Gradle configuration.
- Room database implementation.
- Flight tracking service.
- Location and sensor providers.
- MapLibre integration.
- Vector map style.
- Offline map management.
- Airport dataset.
- Aircraft performance dataset.
- Mock GPS generator.
- Sample mock JSONL flight data.
- Essential unit tests.
- Original sound effect assets.

Provide:

1. A fully buildable Android Studio project.
2. A working APK, preferably debug APK.
3. The mock flight simulator.
4. All necessary application assets.
5. A concise README containing only essential setup and run instructions.

Do not spend time writing extensive architecture documentation, lengthy developer tutorials, or unnecessary explanations.

Do not leave major features as TODOs, placeholders, or pseudocode.

Do not substitute static screenshots for functioning UI.

If a third-party map service requires an API key, provide a configurable integration rather than hardcoding credentials. Ensure the project still compiles and can run in simulation mode without external API credentials.

---

# 21. Final Development Priorities

Implement features in this order:

**Priority 1 — Core tracking**
- Project setup.
- GPS location engine.
- Flight session management.
- Live vector map.
- Aircraft representation.
- Flight route history.

**Priority 2 — Flight instrumentation**
- Speed, heading, altitude, climb rate.
- Altitude profile.
- Cockpit HUD.
- Flight summary.

**Priority 3 — Reliability**
- GPS status.
- Sensor integration.
- GPS-loss estimation.
- Background tracking.
- Data persistence.

**Priority 4 — Enhanced functionality**
- Airport selection.
- Aircraft profiles.
- Destination ETA.
- Flight phase detection.
- Sound effects.
- Automatic flight start/stop.

**Priority 5 — Offline and testing**
- Offline vector map downloads.
- Map cache management.
- Mock GPS generator.
- Simulation controls.
- Essential tests.
- Final APK.

Build and validate incrementally.

Make sensible engineering decisions independently. Do not interrupt development with unnecessary questions or requests for approval.

The final result should be a polished, reliable, visually impressive native Android application that makes a passenger feel like they have their own personal aircraft flight instrumentation system.

**Project name: planeMode**

**Application tagline: Your flight. Your instruments. Your view.**