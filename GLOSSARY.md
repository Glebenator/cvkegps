# CVKE GPS

A speedometer and trip recorder. These terms describe GPS measurements and the rules that decide which measurements the app can use.
The Android and iOS apps use the same rules.

## Measurements

**Fix**:
One position report, with the time at which it was measured. Without a qualifier, a fix is a GPS fix.
_Avoid_: Location, update, point

**Fix source**:
The receiver that measured a fix. A **GPS fix** comes from the GNSS receiver. A **network position** comes from Android's
network location service, which uses nearby Wi-Fi access points and mobile network cells. A network position has a position
and an accuracy only. The app stores no speed, direction, or height for it.
_Avoid_: Provider, Wi-Fi fix, cell fix

**Raw fix**:
A fix with its values as the receiver reported them, before the usability rules. A raw fix keeps a value that is not reported apart from a value that is reported but not valid.
_Avoid_: Location, unfiltered fix

**Reading**:
The values of the newest fix that the app shows as current: speed, altitude, accuracies, and direction of travel. A reading has no coordinates.
_Avoid_: Measurement, fix (for the displayed values)

**GPS height**:
The altitude that the GPS receiver reports, above the WGS 84 ellipsoid. It can differ from sea level by up to about 100 m.
_Avoid_: GPS altitude, ellipsoid altitude

**Sea-level altitude**:
The altitude above mean sea level. Android 14 and later calculate it from the GPS height and a geoid model. Trips do not store it.
_Avoid_: MSL, elevation, real altitude

**Track sample**:
One fix as a trip stores it, with its coordinates, its fix source, and the number of satellites in the fix when the receiver reports it.
_Avoid_: Point, location, reading

**Sensor record**:
The readings of the phone's motion and air pressure sensors that a recording trip stores: acceleration along the phone's axes
(with gravity, in m/s²), the phone's orientation without magnetic north, and the air pressure in hPa. Each reading has the same
clock as a fix. The app does not use the sensor record yet. It is stored for later analysis and playback.
_Avoid_: Telemetry, motion data, accelerometer log

## Usability rules

**Usable position**:
A fix with a known horizontal accuracy of 50 m or better.
_Avoid_: Good fix, valid position

**Usable speed**:
A finite, non-negative speed from a GPS fix with a usable position. The speed accuracy is not reported, or it is 3 m/s or better.
A reported speed accuracy that is not a valid number makes the speed unusable.
_Avoid_: Valid speed, current speed

**Speed issue**:
The reason that a fix has no usable speed: poor position, missing speed, invalid speed, or uncertain speed.
_Avoid_: Speed error

## Trip recording

**No GPS signal**:
The state of a recording trip that has had no GPS fix for 10 seconds. The start of the recording segment counts as a fix, so a new segment waits 10 seconds before it reports this state. The next GPS fix ends it. A network position does not end it.
_Avoid_: Signal lost, GPS lost, no location

**Approximate position**:
A network position that a recording trip stores while it has no GPS signal. The trip asks for network positions only in that state,
and only when the user allows it in Settings. The map draws a line of approximate positions dashed and in another colour.
A change between GPS fixes and network positions starts a new line and adds no distance.
_Avoid_: Fallback fix, estimated position

**Recording accuracy**:
The user's choice of how a trip uses the GPS receiver. **Standard** asks for one fix each second and lets Android deliver them
in groups while no screen shows. **High accuracy** asks for up to 10 fixes each second, delivered at once, and turns off the
receiver's duty cycling. It uses much more battery. A segment stores the choice that applied to it.
_Avoid_: Precision mode, performance mode

**Resume after Stop**:
A trip that the user saved less than 2 minutes ago can continue as a new segment of the same trip. This undoes an accidental Stop and save.
_Avoid_: Undo stop, reopen

## Trip replay

**Replay clock**:
The time in a trip replay. It joins the recording segments of the trip without the pauses between them. A mark shows where a segment starts.
_Avoid_: Trip time, playback time

**Playhead**:
The current time on the replay clock. The replay shows the values of the newest fix at or before the playhead, for at most 10 seconds after that fix, as the live screen does.
_Avoid_: Cursor (for the map and the profile), position

**Moment**:
A notable time in a trip that the replay finds: the strongest acceleration, the strongest braking, the fastest speed, and the highest point. Each moment has a number in time order. The map, the speed profile, and the chips show the same number.
_Avoid_: Event, highlight, peak

**Along-track acceleration**:
The change of speed along the direction of travel, in m/s². A positive value is acceleration. A negative value is braking. The replay calculates it from the sensor record: the game rotation vector turns each accelerometer reading into a frame with a vertical axis, and the GPS change of velocity gives the direction of that frame in 30 second windows. Without the sensor record, it comes from the change of GPS speed.
_Avoid_: G-force, longitudinal force

**Barometric height**:
A replay height from the air pressure. The pressure gives the changes of height. The median difference to the GPS height, over fixes with a vertical accuracy of 10 m or better, gives the level. Plane trips use the GPS height only, because the cabin pressure is not the outside pressure.
_Avoid_: Pressure altitude, barometer altitude


## Trip maps

**Speed colours**:
A saved trip or replay route line that shows speed as colour, from dark (slow) to yellow (fast). The colours use the speed range of that trip: the 5th and the 95th percentile of the moving speeds, averaged over 5 seconds. A legend on the map shows the speed at each end. A part without a usable speed is grey. The current trip map always uses one colour.
_Avoid_: Heat map, speed heat line
