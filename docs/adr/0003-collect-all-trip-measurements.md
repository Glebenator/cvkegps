# Collect all trip measurements, and fit the location request to each feature

The owner wants to play back a trip later and find, for example, the highest point, the fastest speed, and the strongest
acceleration. The visual review comes later. A trip therefore stores more than its route now:

- Each track sample stores its fix source (GPS or network position) and the number of satellites in the fix.
- A sensor record stores the raw accelerometer, the game rotation vector, and the barometer while the trip records.
  It stores raw readings, not results, so a later analysis can change without new recordings. The readings are kept
  in chunks of about 2 seconds, one database row for each sensor and chunk, as little-endian binary data.
- Each segment stores the recording choices that applied to it: recording accuracy and network positions.

The Speed tab and a recording trip have different needs. The Speed tab needs a current speed with a short delay.
A trip needs a complete route and a long battery life, or many fixes when the user chooses **High accuracy**.
One GPS request still supplies both. It uses the strictest need of the active features.

When a trip has no GPS signal for 10 seconds, it can ask for network positions until the next GPS fix. A network position
never gives a speed, a direction, or a height. The map draws it as a dashed line in another colour.

## Consequences

- Storage grows by about 7 MB for each hour of sensor record at 50 readings each second. Fixes at 10 each second in
  High accuracy add about 36,000 track samples each hour.
- The app is not published, so the database version 4 deletes older trips instead of a migration. Add migrations before
  the first release.
- Network positions come from Android's network location service, which can send Wi-Fi and mobile network data to Google.
  The setting explains this. The Play Data safety form and the privacy policy must declare it before release.
- Full GNSS tracking applies to all apps on the phone while the trip asks for it.
- The sensor hub can lose readings while the screen is off, if its queue fills before the processor wakes. The reading
  times show such gaps. This and the battery use need a physical phone to measure.
