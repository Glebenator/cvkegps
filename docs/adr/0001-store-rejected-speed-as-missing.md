# Store a speed as missing when its reported speed accuracy is not valid

A track sample stores a speed accuracy that is not valid (NaN or negative) as missing, and missing speed accuracy is usable.
To make a stored sample give the same usable-speed result as the live reading, the app also stores the speed as missing when this occurs.
We did not add a database column for "speed accuracy reported but not valid", because no part of the app reads the raw speed of a rejected sample:
GPX export writes only coordinates and time, and all other code reads speed through the usable-speed rule.

## Consequences

- New trips do not keep the raw speed of these fixes. Other rejected speeds (poor position, speed accuracy worse than 3 m/s) keep their raw values, because the stored values are sufficient to reject them again.
- Trips saved before this decision can still contain such a speed. Their stored data cannot show the difference, so the app does not correct them.
