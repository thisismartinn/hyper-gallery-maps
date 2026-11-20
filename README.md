# HyperGallery Maps

Lightweight headless LSPosed module (libxposed API 101/102) that replaces Baidu Maps tiles and hardcoded Chinese reverse geocoding in Xiaomi HyperOS Gallery (`com.hyper.gallery.plugin` / `com.miui.gallery`) with Google Maps tiles and Android's global `Geocoder` in your device's system language.

Based on the Google Maps tile injection approach from XApps (`XSecurity` / `FindDeviceHooks` + `GoogleMapLayer`).

## Features

- **Google Maps Tile Injection** (`GalleryGoogleMapLayer`):
  - Keeps Gallery's native Baidu `TextureMapView`, photo clustering, pins, and gestures intact while hiding Baidu's base map, POI labels, custom style, and logo.
  - Works in both the full interactive Map Album (`MapContainer`) and Photo Details mini-map (`StaticMapContainer`).
  - Reprojects Google Maps `scale=2` tiles in your device's language (`Locale.getDefault().getLanguage()`) onto Baidu's tile grid, with memory + 100 MB disk caching, coarse-to-sharp preview rendering, and automatic fallback to Baidu if Google is unreachable.
- **Coordinate Drift Fix** (`GalleryLocationHooks`):
  - Hooks `LocationConverter` (`convertToBdLatLng` / `convertToMapLatLng`) and `CoordinateConverter` so photo pins outside mainland China stay at their exact WGS-84 coordinates instead of shifting by hundreds of meters.
- **Global Location Naming** (`GalleryLocationHooks`):
  - Hooks `GalleryGeoClientImpl` (`batchParseFromLatLngList` and `parseFromLatLng`), which backs both `com.hyper.gallery.geocoder.provider` (`GeoCoderProvider` for HyperOS Gallery's Rust backend) and `GalleryGeoClientManager`.
  - Replaces Baidu's hardcoded `LanguageTypeChinese` reverse geocoding with Android's `Geocoder(context, Locale.getDefault())` with a 512-entry spatial cache.

## Installation

1. Install the APK (download from GitHub Actions artifacts or Releases).
2. Enable **HyperGallery Maps** in LSPosed.
3. Make sure scope includes **`com.hyper.gallery.plugin`** (and `com.miui.gallery`).
4. Force-stop **Gallery** (`com.miui.gallery`) and **Gallery Components** (`com.hyper.gallery.plugin`).

> **Refreshing existing Chinese location names:** Photos that were already geocoded before enabling this module have their old Chinese addresses cached in Gallery's SQLite database. New photos will immediately get global names; to re-index old photos, clear Gallery's app data (after ensuring your photos are safe — clearing app data does not delete local photos, only Gallery's index/cache) and let it re-scan.
