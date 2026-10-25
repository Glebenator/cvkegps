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
