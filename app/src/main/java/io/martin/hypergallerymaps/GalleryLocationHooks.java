package io.martin.hypergallerymaps;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.util.Pair;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replaces Baidu's China-only coordinate offset (outside mainland China) and Baidu's hardcoded
 * Chinese reverse geocoder ({@code GalleryGeoClientImpl}) with Android's global {@link Geocoder}
 * in the device's system language ({@link Locale#getDefault()}).
 *
 * <p>Because both {@code GeoCoderProvider} (serving {@code com.miui.gallery}'s Rust backend via
 * {@code com.hyper.gallery.geocoder.provider}) and {@code GalleryGeoClientManager} funnel through
 * {@code GalleryGeoClientImpl}, hooking it updates location naming across the entire Gallery.
 */
final class GalleryLocationHooks {
    private static final int CACHE_SIZE = 512;
    private static final long GEOCODE_TIMEOUT_SECONDS = 5;

    private final Hooks hooks;
    private final ClassLoader loader;
    private final Map<Object, Object> converterInput = Collections.synchronizedMap(new WeakHashMap<>());

    /** Spatial LRU cache keyed by quantized (~111 m) lat/lng + language tag. */
    private final Map<String, Address> geocodeCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Address>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Address> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    GalleryLocationHooks(Hooks hooks, ClassLoader loader) {
        this.hooks = hooks;
        this.loader = loader;
    }

    void install() {
        step("location converter", this::hookLocationConverter);
        step("coordinate converter", this::hookCoordinateConverter);
        step("reverse geocoder", this::hookReverseGeocoder);
    }

    private interface Step {
        void run() throws Throwable;
    }

    private void step(String name, Step step) {
        try {
            step.run();
            hooks.info("Gallery: " + name + " ready");
        } catch (ClassNotFoundException absent) {
            hooks.info("Gallery: " + name + " skipped (class not in this package)");
        } catch (Throwable failure) {
            hooks.error("Gallery: " + name + " unavailable", failure);
        }
    }

    /**
     * Thread-safe bypass of Baidu's China-only GPS->BD09 shift for photo pins outside mainland
     * China. Inside mainland China, BD09 conversion is preserved so pins align with GoogleMapLayer.
     */
    private void hookLocationConverter() throws Throwable {
        Class<?> converter = loader.loadClass("com.miui.gallery.map.utils.LocationConverter");
        Class<?> latLng = loader.loadClass("com.baidu.mapapi.model.LatLng");
        Constructor<?> latLngCtor = latLng.getConstructor(double.class, double.class);
        Class<?> mapLatLng = loader.loadClass("com.miui.gallery.map.cluster.MapLatLng");
        Constructor<?> mapLatLngCtor = mapLatLng.getConstructor(double.class, double.class);

        hooks.install(converter.getDeclaredMethod("convertToBdLatLng", double.class, double.class), chain -> {
            double lat = (Double) chain.getArg(0);
            double lng = (Double) chain.getArg(1);
            if (GeoMath.isValidCoordinate(lat, lng) && !GeoMath.inMainland(lat, lng)) {
                return latLngCtor.newInstance(lat, lng);
            }
            return chain.proceed();
        });

        hooks.install(converter.getDeclaredMethod("convertToMapLatLng", double.class, double.class), chain -> {
            double lat = (Double) chain.getArg(0);
            double lng = (Double) chain.getArg(1);
            if (GeoMath.isValidCoordinate(lat, lng) && !GeoMath.inMainland(lat, lng)) {
                return mapLatLngCtor.newInstance(lat, lng);
            }
            return chain.proceed();
        });
    }

    /** Fallback for any other caller of Baidu's CoordinateConverter outside LocationConverter. */
    private void hookCoordinateConverter() throws Throwable {
        Class<?> converter = loader.loadClass("com.baidu.mapapi.utils.CoordinateConverter");
        Class<?> latLng = loader.loadClass("com.baidu.mapapi.model.LatLng");
        Field latitude = latLng.getField("latitude");
        Field longitude = latLng.getField("longitude");

        hooks.install(converter.getMethod("coord", latLng), chain -> {
            converterInput.put(chain.getThisObject(), chain.getArg(0));
            return chain.proceed();
        });

        hooks.install(converter.getMethod("convert"), chain -> {
            Object input = converterInput.get(chain.getThisObject());
            if (input != null) {
                double lat = latitude.getDouble(input);
                double lng = longitude.getDouble(input);
                if (GeoMath.isValidCoordinate(lat, lng) && !GeoMath.inMainland(lat, lng)) {
                    return input;
                }
            }
            return chain.proceed();
        });
    }

    /**
     * Replaces Baidu's hardcoded Chinese reverse geocoding in {@code GalleryGeoClientImpl} with
     * Android's {@link Geocoder} using the device's system language ({@link Locale#getDefault()}).
     */
    private void hookReverseGeocoder() throws Throwable {
        Class<?> geoClient = loader.loadClass("com.miui.gallery.map.location.GalleryGeoClientImpl");

        hooks.install(geoClient.getDeclaredMethod("parseFromLatLng", double.class, double.class), chain -> {
            if (!Geocoder.isPresent()) {
                hooks.info("Gallery: Geocoder not present, falling back to Baidu");
                return chain.proceed();
            }
            double lat = (Double) chain.getArg(0);
            double lng = (Double) chain.getArg(1);
            Address result = geocode(lat, lng);
            hooks.info("Gallery: parseFromLatLng resolved=" + (result != null));
            return result;
        });

        hooks.install(geoClient.getDeclaredMethod("batchParseFromLatLngList", List.class), chain -> {
            if (!Geocoder.isPresent()) {
                hooks.info("Gallery: Geocoder not present, falling back to Baidu");
                return chain.proceed();
            }
            List<?> inputs = (List<?>) chain.getArg(0);
            if (inputs == null) return null;
            List<Address> results = new ArrayList<>(inputs.size());
            int resolved = 0;
            for (Object item : inputs) {
                if (item instanceof Pair<?, ?> pair
                        && pair.first instanceof Number latNum
                        && pair.second instanceof Number lngNum) {
                    Address addr = geocode(latNum.doubleValue(), lngNum.doubleValue());
                    if (addr != null) resolved++;
                    results.add(addr);
                } else {
                    results.add(null);
                }
            }
            hooks.info("Gallery: batchParseFromLatLngList count=" + inputs.size() + " resolved=" + resolved);
            return results;
        });
    }

    private Address geocode(double lat, double lng) {
        if (!GeoMath.isValidCoordinate(lat, lng)) return null;
        Locale locale = Locale.getDefault();
        String cacheKey = Math.round(lat * 1000.0) + ":" + Math.round(lng * 1000.0) + ":" + locale.toLanguageTag();
        Address cached = geocodeCache.get(cacheKey);
        if (cached != null) {
            return copyWithCoords(cached, lat, lng);
        }

        try {
            Context context = application();
            if (context == null || !Geocoder.isPresent()) return null;
            Geocoder geocoder = new Geocoder(context, locale);
            List<Address> addresses = queryGeocoder(geocoder, lat, lng);
            if (addresses == null || addresses.isEmpty()) return null;

            Address address = addresses.get(0);
            if (address == null) return null;
            address.setLatitude(lat);
            address.setLongitude(lng);
            geocodeCache.put(cacheKey, address);
            return copyWithCoords(address, lat, lng);
        } catch (Throwable failure) {
            hooks.info("Gallery: geocode failed: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Address> queryGeocoder(Geocoder geocoder, double lat, double lng) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AtomicReference<List<Address>> ref = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            geocoder.getFromLocation(lat, lng, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    ref.set(addresses);
                    latch.countDown();
                }

                @Override
                public void onError(String errorMessage) {
                    latch.countDown();
                }
            });
            if (!latch.await(GEOCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null;
            return ref.get();
        }
        return geocoder.getFromLocation(lat, lng, 1);
    }

    /** Returns an independent Address copy stamped with the exact photo coordinates. */
    private static Address copyWithCoords(Address src, double lat, double lng) {
        Address dst = new Address(src.getLocale());
        dst.setLatitude(lat);
        dst.setLongitude(lng);
        dst.setCountryName(src.getCountryName());
        dst.setCountryCode(src.getCountryCode());
        dst.setAdminArea(src.getAdminArea());
        dst.setSubAdminArea(src.getSubAdminArea());
        dst.setLocality(src.getLocality());
        dst.setSubLocality(src.getSubLocality());
        dst.setThoroughfare(src.getThoroughfare());
        dst.setSubThoroughfare(src.getSubThoroughfare());
        dst.setFeatureName(src.getFeatureName());
        dst.setPostalCode(src.getPostalCode());
        int maxLine = src.getMaxAddressLineIndex();
        for (int i = 0; i <= maxLine; i++) {
            dst.setAddressLine(i, src.getAddressLine(i));
        }
        if (src.getExtras() != null) {
            dst.setExtras(src.getExtras());
        }
        return dst;
    }

    private static Context application() throws ReflectiveOperationException {
        return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
    }
}
