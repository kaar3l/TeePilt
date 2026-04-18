# TeePilt

Android application that captures photos and stamps them with Estonian road location data (road number, kilometre marker) and L-EST97 coordinates. Only works in Estonia.

## Features

- **Road location stamping** — fetches the nearest road, kilometre marker and location name from the Estonian road registry and overlays it on the photo
- **L-EST97 coordinates** — converts GPS (WGS84) coordinates to the Estonian national coordinate system (EPSG:3301) automatically
- **Pinch-to-zoom** — standard two-finger pinch gesture on the camera preview
- **Tap-to-focus** — tap anywhere on the preview to set the focus point
- **Gallery shortcut** — button to instantly view the last taken photo
- **Saves to DCIM/TeePilt** — photos appear in the system gallery

## Tech stack

- CameraX 1.4.0
- jsoup 1.18.1
- Min SDK: 21 (Android 5.0)
- Target SDK: 35 (Android 15)
- L-EST97 projection: Lambert Conformal Conic 2SP (GRS80 ellipsoid)

## Requirements

- Android 5.0 or newer
- GPS
- Internet connection
- Location must be in Estonia
