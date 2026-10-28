# CVKE GPS

A speedometer and trip recorder. These terms describe GPS measurements and the rules that decide which measurements the app can use.
The Android and iOS apps use the same rules.

## Measurements

**Fix**:
One position report from the GPS receiver, with the time at which the receiver measured it.
_Avoid_: Location, update, point

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
One fix as a trip stores it, with its coordinates.
_Avoid_: Point, location, reading

## Usability rules

**Usable position**:
A fix with a known horizontal accuracy of 50 m or better.
_Avoid_: Good fix, valid position

**Usable speed**:
A finite, non-negative speed from a fix with a usable position. The speed accuracy is not reported, or it is 3 m/s or better.
A reported speed accuracy that is not a valid number makes the speed unusable.
_Avoid_: Valid speed, current speed

**Speed issue**:
The reason that a fix has no usable speed: poor position, missing speed, invalid speed, or uncertain speed.
_Avoid_: Speed error

## Trip recording

**No GPS signal**:
The state of a recording trip that has had no fix for 10 seconds. The start of the recording segment counts as a fix, so a new segment waits 10 seconds before it reports this state. The next fix ends it.
_Avoid_: Signal lost, GPS lost, no location

**Resume after Stop**:
A trip that the user saved less than 2 minutes ago can continue as a new segment of the same trip. This undoes an accidental Stop and save.
_Avoid_: Undo stop, reopen
