package io.martin.hypergallerymaps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws Google Maps tiles under Xiaomi HyperOS Gallery's photo pins (both the full interactive
 * {@code MapContainer} and the photo-detail {@code StaticMapContainer}).
 *
 * <p>Baidu's native map engine, clustering, pins, and gestures remain untouched while Baidu's base
 * map, POI labels, and custom style are turned off, and Baidu's bottom-left logo is replaced with
 * Google Maps' official watermark ({@code google4_hdpi.png}). Only sharp tiles are delivered to
 * Baidu's tile cache, preventing blurry chunks and full-layer refresh flashing during panning.
 */
final class GalleryGoogleMapLayer {
    private static final String MAP = "com.baidu.mapapi.map.";
    private static final String LAYER_PROP = "io.martin.hypergallerymaps.googlemap.layer";
    private static final String OFFICIAL_LOGO_URL_HDPI = "https://maps.gstatic.com/mapfiles/api-3/images/google4_hdpi.png";
    private static final String OFFICIAL_LOGO_URL_MDPI = "https://maps.gstatic.com/mapfiles/api-3/images/google4.png";
    private static final String OFFICIAL_LOGO_FILE = "google_maps_logo_v4.png";

    private static final int SIZE = 256;
    private static final int MAX_QUEUE = 64;
    private static final int MAX_ZOOM = 21;
    private static final int WORKERS = 8;
    private static final int FETCHERS = 4;
    private static final int WORKER_PRIORITY = Process.THREAD_PRIORITY_DEFAULT;
    private static final int ENGINE_CACHE_BYTES = 64 * 1024 * 1024;
    private static final long DISK_BYTES = 100L * 1024 * 1024;
    private static final long DISK_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    private static final int MAX_PLAN = 600;
    private static final int MISSES_PER_SLEEP = 16;
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final Hooks hooks;
    private final ClassLoader loader;
    private final Map<Object, Boolean> attached = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Long, Bundle> tiles = Collections.synchronizedMap(new LinkedHashMap<Long, Bundle>(384, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Bundle> eldest) {
            return size() > 384;
        }
    });
    private final Map<Long, Boolean> deliveredTiles = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(384, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 360;
        }
    });
    private final ArrayDeque<Job> preload = new ArrayDeque<>();
    private final Map<Object, Object> layers = Collections.synchronizedMap(new WeakHashMap<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> sources = Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(192, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 160;
        }
    });
    private final ConcurrentHashMap<String, Object> sourceLocks = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Map<ImageView, Drawable> originalLogos = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Bitmap> canvasBitmap = new ThreadLocal<>();
    private final ThreadLocal<Canvas> tileCanvas = new ThreadLocal<>();
    private final AtomicBoolean logoFetchStarted = new AtomicBoolean(false);
    private volatile Bitmap googleLogoBitmap;

    private volatile long failedUntil;
    private volatile boolean satellite;
    private volatile boolean fallback;
    private volatile boolean delivered;
    private int failures;
    private boolean workersStarted;
    private int misses;
    private int viewZoom;
    private int viewMinX, viewMaxX, viewMinY, viewMaxY;
    private long lastMissAt;
    private final Object viewLock = new Object();
    private final ArrayDeque<int[]> fetchQueue = new ArrayDeque<>();
    private File diskCache;

    private Class<?> optionsClass;
    private Method addTileLayer;
    private Method clearLayer;
    private Method removeLayer;
    private Method setZoomLimits;
    private Method setTileCache;
    private Method setZoomRange;
    private Method setMapType;
    private Constructor<?> geoPoint;
    private Method mc2ll;
    private Field latitude;
    private Field longitude;

    private static final class Job {
        final long key;
        final int x, y, zoom;
        final boolean photo;
        final boolean prepared;

        Job(long key, int x, int y, int zoom, boolean photo, boolean prepared) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.zoom = zoom;
            this.photo = photo;
            this.prepared = prepared;
        }
    }

    GalleryGoogleMapLayer(Hooks hooks, ClassLoader loader) {
        this.hooks = hooks;
        this.loader = loader;
    }

    void install() {
        try {
            installInternal();
            hooks.info("Gallery: Google map layer ready");
        } catch (ClassNotFoundException absent) {
            hooks.info("Gallery: Google map layer skipped (Baidu SDK not in this package)");
        } catch (Throwable failure) {
            hooks.error("Gallery: Google map layer unavailable", failure);
        }
    }

    private void installInternal() throws Throwable {
        if (System.getProperty(LAYER_PROP) != null) {
            hooks.info("Gallery: Google map already installed in this process");
            return;
        }
        System.setProperty(LAYER_PROP, "on");
        System.setProperty("http.maxConnections", "16");

        geoPoint = loader.loadClass("com.baidu.platform.comapi.basestruct.GeoPoint")
                .getConstructor(double.class, double.class);
        Class<?> latLng = loader.loadClass("com.baidu.mapapi.model.LatLng");
        latitude = latLng.getField("latitude");
        longitude = latLng.getField("longitude");
        mc2ll = loader.loadClass("com.baidu.mapapi.model.CoordUtil")
                .getMethod("mc2ll", geoPoint.getDeclaringClass());

        optionsClass = loader.loadClass(MAP + "TileOverlayOptions");
        Class<?> baiduMap = loader.loadClass(MAP + "BaiduMap");
        Class<?> tileOverlay = loader.loadClass(MAP + "TileOverlay");
        Class<?> textureMapView = loader.loadClass(MAP + "TextureMapView");

        addTileLayer = baiduMap.getMethod("addTileLayer", optionsClass);
        clearLayer = tileOverlay.getMethod("clearTileCache");
        removeLayer = tileOverlay.getMethod("removeTileOverlay");
        setZoomLimits = baiduMap.getMethod("setMaxAndMinZoomLevel", float.class, float.class);
        setTileCache = optionsClass.getMethod("setMaxTileTmp", int.class);
        setZoomRange = optionsClass.getDeclaredMethod("a", int.class, int.class);
        setZoomRange.setAccessible(true);
        setMapType = baiduMap.getMethod("setMapType", int.class);

        // Find the obfuscated BaiduMap tile callback inner class by signature: Bundle(int, int, int, Context).
        Method callback = null;
        for (char letter = 'a'; letter <= 'z'; letter++) {
            Class<?> inner;
            try {
                inner = loader.loadClass(MAP + "BaiduMap$" + letter);
            } catch (ClassNotFoundException absent) {
                continue;
            }
            for (Method method : inner.getDeclaredMethods()) {
                if (method.getReturnType() == Bundle.class && Arrays.equals(method.getParameterTypes(),
                        new Class<?>[]{int.class, int.class, int.class, Context.class})) {
                    callback = method;
                }
            }
        }
        if (callback == null) throw new NoSuchMethodException("BaiduMap tile callback unavailable");
        hooks.install(callback, chain -> request((int) chain.getArg(0), (int) chain.getArg(1), (int) chain.getArg(2)));

        // Replace Baidu's logo ImageView with the official Google Maps watermark.
        for (Constructor<?> ctor : textureMapView.getDeclaredConstructors()) {
            hooks.install(ctor, chain -> {
                Object result = chain.proceed();
                applyGoogleLogo((ViewGroup) chain.getThisObject());
                return result;
            });
        }

        // Suppress Gallery's async Baidu light/dark custom style while Google Maps is active.
        try {
            hooks.install(textureMapView.getMethod("setMapCustomStyleEnable", boolean.class), chain -> {
                if (!fallback && Boolean.TRUE.equals(chain.getArg(0))) {
                    return chain.proceed(new Object[]{Boolean.FALSE});
                }
                return chain.proceed();
            });
        } catch (NoSuchMethodException ignored) {
            // Older/newer Baidu SDK variant.
        }

        // Gallery initializes BaiduMap via TextureMapView.getMap() and waits for onMapLoaded()
        // before querying and rendering photo pins. Do NOT hide Baidu's basemap before onMapLoaded()
        // fires, or onMapLoaded() may never trigger. Schedule a delayed fallback only for maps that
        // never register an OnMapLoadedCallback.
        hooks.install(textureMapView.getMethod("getMap"), chain -> {
            Object map = chain.proceed();
            if (map != null && !fallback) {
                mainHandler.postDelayed(() -> {
                    if (!fallback && attached.get(map) == null) {
                        attachIfNeeded(map);
                    }
                }, 1200);
            }
            return map;
        });

        // Wrap OnMapLoadedCallback so original onMapLoaded() runs first (loading photo cluster pins),
        // then immediately attach the Google Maps tile layer and hide Baidu's basemap.
        try {
            Class<?> loadedCb = loader.loadClass(MAP + "BaiduMap$OnMapLoadedCallback");
            hooks.install(baiduMap.getMethod("setOnMapLoadedCallback", loadedCb), chain -> {
                Object orig = chain.getArg(0);
                Object map = chain.getThisObject();
                if (orig == null || map == null || fallback) {
                    return chain.proceed();
                }
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        loader,
                        new Class<?>[]{loadedCb},
                        (p, method, args) -> {
                            Object r = method.invoke(orig, args);
                            if ("onMapLoaded".equals(method.getName()) && !fallback) {
                                attachIfNeeded(map);
                            }
                            return r;
                        });
                return chain.proceed(new Object[]{proxy});
            });
        } catch (ReflectiveOperationException failure) {
            hooks.info("Gallery: OnMapLoadedCallback hook unavailable: " + failure.getClass().getSimpleName());
        }

        hooks.install(baiduMap.getMethod("getMapType"), chain ->
                fallback ? chain.proceed() : Integer.valueOf(satellite ? 2 : 1));

        hooks.install(setMapType, chain -> {
            if (fallback) return chain.proceed();
            Object result = chain.proceed(new Object[]{1});
            Object map = chain.getThisObject();
            boolean wanted = Integer.valueOf(2).equals(chain.getArg(0));
            if (wanted != satellite) {
                satellite = wanted;
                tiles.clear();
                deliveredTiles.clear();
                synchronized (queue) {
                    preload.clear();
                }
                Object layer = layers.get(map);
                if (layer != null) {
                    attached.remove(map);
                    layers.remove(map);
                    try {
                        clearLayer.invoke(layer);
                        removeLayer.invoke(layer);
                    } catch (Throwable failure) {
                        hooks.info("Gallery: old Google layer not removed: " + failure.getClass().getSimpleName());
                    }
                }
            }
            attachIfNeeded(map);
            return result;
        });
    }

    private void attachIfNeeded(Object map) {
        if (attached.get(map) == null) {
            try {
                Object settings = optionsClass.getConstructor().newInstance();
                setZoomRange.invoke(settings, MAX_ZOOM, 3);
                setTileCache.invoke(settings, ENGINE_CACHE_BYTES);
                Object layer = addTileLayer.invoke(map, settings);
                if (layer != null) {
                    setZoomLimits.invoke(map, (float) MAX_ZOOM, 4f);
                    attached.put(map, Boolean.TRUE);
                    layers.put(map, layer);
                    startWorkers();
                    hooks.info("Gallery: Google map tile layer added");
                } else {
                    hooks.info("Gallery: Google map tile layer refused (will retry)");
                    return;
                }
            } catch (Throwable failure) {
                hooks.info("Gallery: Google map tile layer unavailable: " + failure.getClass().getSimpleName());
                return;
            }
        }
        showBaidu(map, false);
    }

    private void showBaidu(Object map, boolean visible) {
        try {
            Object engine = fieldOfType(map, loader.loadClass("com.baidu.mapsdkplatform.comapi.map.b"));
            Object base = fieldOfType(engine, loader.loadClass("com.baidu.platform.comjni.map.basemap.AppBaseMap"));
            Method show = base.getClass().getMethod("ShowLayers", long.class, boolean.class);
            Method id = base.getClass().getMethod("getLayerIDByTag", String.class);
            for (String tag : new String[]{"basemap", "basepoi", "poiindoormarklayer"}) {
                show.invoke(base, id.invoke(base, tag), visible);
            }
        } catch (Throwable failure) {
            hooks.info("Gallery: Baidu layers toggle unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static Object fieldOfType(Object owner, Class<?> type) throws IllegalAccessException, NoSuchFieldException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        throw new NoSuchFieldException(type.getSimpleName());
    }

    private void applyGoogleLogo(ViewGroup view) {
        Context context = view.getContext();
        Bitmap logo = getCachedOfficialLogo(context);
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (child instanceof ImageView imageView) {
                synchronized (originalLogos) {
                    if (!originalLogos.containsKey(imageView)) {
                        originalLogos.put(imageView, imageView.getDrawable());
                    }
                }
                if (logo != null) {
                    setLogoBitmap(imageView, logo);
                } else {
                    imageView.setVisibility(View.INVISIBLE);
                    fetchOfficialLogoAsync(context.getApplicationContext());
                }
            }
        }
    }

    private static void setLogoBitmap(ImageView imageView, Bitmap logo) {
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(logo);
        imageView.setVisibility(View.VISIBLE);
    }

    private Bitmap getCachedOfficialLogo(Context context) {
        Bitmap existing = googleLogoBitmap;
        if (existing != null && !existing.isRecycled()) return existing;
        try {
            File file = new File(disk(), OFFICIAL_LOGO_FILE);
            if (file.isFile() && file.length() > 0) {
                byte[] data = Files.readAllBytes(file.toPath());
                Bitmap scaled = decodeOfficialLogo(data, context);
                if (scaled != null) {
                    googleLogoBitmap = scaled;
                    return scaled;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void fetchOfficialLogoAsync(Context context) {
        if (!logoFetchStarted.compareAndSet(false, true)) return;
        start(() -> {
            try {
                byte[] data;
                try {
                    data = downloadUrl(OFFICIAL_LOGO_URL_HDPI);
                } catch (IOException primaryFailed) {
                    data = downloadUrl(OFFICIAL_LOGO_URL_MDPI);
                }
                Bitmap scaled = decodeOfficialLogo(data, context);
                if (scaled == null) {
                    logoFetchStarted.set(false);
                    return;
                }
                store(new File(disk(), OFFICIAL_LOGO_FILE), data);
                googleLogoBitmap = scaled;
                mainHandler.post(() -> {
                    if (fallback) return;
                    synchronized (originalLogos) {
                        for (ImageView iv : originalLogos.keySet()) {
                            if (iv != null) setLogoBitmap(iv, scaled);
                        }
                    }
                });
            } catch (Throwable failure) {
                logoFetchStarted.set(false);
                hooks.info("Gallery: official Google logo fetch failed: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Scales Google Maps' official 66x26dp watermark cleanly for the device's screen density. */
    private static Bitmap decodeOfficialLogo(byte[] data, Context context) {
        Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0) return null;
        float density = context.getResources().getDisplayMetrics().density;
        int targetW = Math.max(1, Math.round(62f * density));
        int targetH = Math.max(1, Math.round(targetW * (raw.getHeight() / (float) raw.getWidth())));
        if (raw.getWidth() == targetW && raw.getHeight() == targetH) return raw;
        Bitmap scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true);
        if (scaled != raw) raw.recycle();
        return scaled;
    }

    /** Google is unreachable: restore Baidu's map and logo rather than showing an empty screen. */
    private void fallBack() {
        fallback = true;
        hooks.info("Gallery: Google map unreachable, falling back to Baidu");
        mainHandler.post(() -> {
            synchronized (originalLogos) {
                for (Map.Entry<ImageView, Drawable> entry : originalLogos.entrySet()) {
                    ImageView iv = entry.getKey();
                    if (iv != null) {
                        iv.setImageDrawable(entry.getValue());
                        iv.setVisibility(View.VISIBLE);
                    }
                }
            }
            synchronized (attached) {
                for (Object map : attached.keySet()) {
                    showBaidu(map, true);
                    try {
                        setMapType.invoke(map, satellite ? 2 : 1);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        });
    }

    /** Runs on the engine's render loop: fast, no logging, no blocking network I/O. */
    private Bundle request(int x, int y, int zoom) throws InterruptedException {
        if (fallback) return null;
        boolean photo = satellite;
        long key = key(x, y, zoom, photo);
        noteView(x, y, zoom);
        Bundle ready = tiles.get(key);
        if (ready != null) {
            deliveredTiles.put(key, Boolean.TRUE);
            return ready;
        }
        if (SystemClock.elapsedRealtime() >= failedUntil && pending.add(key)) {
            synchronized (queue) {
                queue.addFirst(new Job(key, x, y, zoom, photo, false));
                if (queue.size() > MAX_QUEUE) pending.remove(queue.removeLast().key);
                queue.notifyAll();
            }
        }
        if (++misses % MISSES_PER_SLEEP == 0) Thread.sleep(1);
        return null;
    }

    private static long key(int x, int y, int zoom, boolean photo) {
        return ((long) zoom << 56) | (photo ? 1L << 55 : 0) | ((x & 0x7FFFFFFL) << 27) | (y & 0x7FFFFFFL);
    }

    private void noteView(int x, int y, int zoom) {
        long now = SystemClock.elapsedRealtime();
        synchronized (viewLock) {
            if (zoom != viewZoom || now - lastMissAt > 500) {
                viewZoom = zoom;
                viewMinX = viewMaxX = x;
                viewMinY = viewMaxY = y;
            } else {
                viewMinX = Math.min(viewMinX, x);
                viewMaxX = Math.max(viewMaxX, x);
                viewMinY = Math.min(viewMinY, y);
                viewMaxY = Math.max(viewMaxY, y);
            }
            lastMissAt = now;
        }
    }

    private synchronized void startWorkers() {
        if (workersStarted) return;
        workersStarted = true;
        for (int i = 0; i < WORKERS; i++) start(this::work);
        for (int i = 0; i < FETCHERS; i++) start(this::fetch);
        start(this::plan);
    }

    private static void start(Runnable task) {
        Thread thread = new Thread(task, "HyperGalleryGoogleMap");
        thread.setDaemon(true);
        thread.start();
    }

    private void plan() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        int plannedZoom = 0, plannedX = 0, plannedY = 0;
        while (!fallback) {
            try {
                Thread.sleep(250);
                int zoom, minX, maxX, minY, maxY;
                synchronized (viewLock) {
                    zoom = viewZoom;
                    minX = viewMinX;
                    maxX = viewMaxX;
                    minY = viewMinY;
                    maxY = viewMaxY;
                }
                int centerX = (minX + maxX) / 2;
                int centerY = (minY + maxY) / 2;
                if (zoom == 0 || (zoom == plannedZoom && Math.abs(centerX - plannedX) <= (maxX - minX) / 3
                        && Math.abs(centerY - plannedY) <= (maxY - minY) / 3)) continue;
                plannedZoom = zoom;
                plannedX = centerX;
                plannedY = centerY;
                double span = SIZE * Math.pow(2, 18 - zoom);
                double[] southWest = point(minX * span, minY * span);
                double[] northEast = point((maxX + 1) * span, (maxY + 1) * span);
                boolean metered = metered();
                boolean photo = satellite;
                ArrayDeque<int[]> wanted = new ArrayDeque<>();
                // Prefetch a generous panning ring at the current zoom first, then zoom-in levels.
                double[][] steps = metered ? new double[][]{{0, 1.5}, {1, 0.7}, {-1, 1}}
                        : new double[][]{{0, 2.2}, {1, 0.9}, {2, 0.5}, {-1, 2}, {-2, 1.5}};
                for (double[] step : steps) {
                    addLevel(wanted, southWest, northEast, zoom - 1 + (int) step[0], step[1], photo);
                }
                synchronized (fetchQueue) {
                    fetchQueue.clear();
                    fetchQueue.addAll(wanted);
                    fetchQueue.notifyAll();
                }
                prepare(zoom, minX, maxX, minY, maxY, photo);
            } catch (InterruptedException stopped) {
                return;
            } catch (Throwable failure) {
                hooks.info("Gallery: Google map plan failed: " + failure.getClass().getSimpleName());
            }
        }
    }

    private void prepare(int zoom, int minX, int maxX, int minY, int maxY, boolean photo) {
        ArrayDeque<Job> jobs = new ArrayDeque<>();
        double[][] steps = {{0, 1.5}, {1, 0.7}, {-1, 1.3}, {2, 0.3}, {-2, 1.4}};
        for (double[] step : steps) {
            int level = zoom + (int) step[0];
            if (level < 4 || level > MAX_ZOOM) continue;
            double scale = Math.pow(2, step[0]);
            double middleX = (minX + maxX + 1) / 2.0 * scale;
            double middleY = (minY + maxY + 1) / 2.0 * scale;
            double halfX = (maxX - minX + 1) / 2.0 * scale * step[1];
            double halfY = (maxY - minY + 1) / 2.0 * scale * step[1];
            for (int y = (int) Math.floor(middleY - halfY); y <= (int) Math.floor(middleY + halfY); y++) {
                for (int x = (int) Math.floor(middleX - halfX); x <= (int) Math.floor(middleX + halfX); x++) {
                    long key = key(x, y, level, photo);
                    if (jobs.size() < 240 && !tiles.containsKey(key) && !deliveredTiles.containsKey(key)) {
                        jobs.add(new Job(key, x, y, level, photo, true));
                    }
                }
            }
        }
        synchronized (queue) {
            preload.clear();
            preload.addAll(jobs);
            queue.notifyAll();
        }
    }

    private void addLevel(ArrayDeque<int[]> wanted, double[] southWest, double[] northEast, int level, double share, boolean photo)
            throws ReflectiveOperationException {
        if (level < 1 || level > 19) return;
        double world = SIZE * Math.pow(2, level);
        double left = worldX(southWest[1], world) / SIZE;
        double right = worldX(northEast[1], world) / SIZE;
        double top = worldY(northEast[0], world) / SIZE;
        double bottom = worldY(southWest[0], world) / SIZE;
        double middleX = (left + right) / 2;
        double middleY = (top + bottom) / 2;
        double halfX = (right - left) / 2 * share;
        double halfY = (bottom - top) / 2 * share;
        int count = 1 << level;
        for (long y = (long) Math.floor(middleY - halfY); y <= (long) Math.floor(middleY + halfY); y++) {
            if (y < 0 || y >= count) continue;
            for (long x = (long) Math.floor(middleX - halfX); x <= (long) Math.floor(middleX + halfX); x++) {
                if (wanted.size() >= MAX_PLAN) return;
                int wrapped = (int) ((x % count + count) % count);
                if (!new File(disk(), fileName(wrapped, (int) y, level, photo)).isFile()) {
                    wanted.add(new int[]{wrapped, (int) y, level, photo ? 1 : 0});
                }
            }
        }
    }

    private void fetch() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (!fallback) {
            try {
                int[] job;
                synchronized (fetchQueue) {
                    while (fetchQueue.isEmpty()) fetchQueue.wait();
                    job = fetchQueue.removeFirst();
                }
                File file = new File(disk(), fileName(job[0], job[1], job[2], job[3] == 1));
                if (!file.isFile()) store(file, downloadTile(job[0], job[1], job[2], job[3] == 1));
            } catch (InterruptedException stopped) {
                return;
            } catch (Throwable failure) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        }
    }

    private static boolean metered() {
        try {
            Context context = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return ((android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .isActiveNetworkMetered();
        } catch (Throwable unknown) {
            return true;
        }
    }

    /**
     * Always composes and delivers the sharp tile directly (no coarse preview / full-layer clear),
     * preventing blurry zoomed-in chunks and flashing when panning.
     */
    private void work() {
        Process.setThreadPriority(WORKER_PRIORITY);
        while (true) {
            Job job;
            try {
                synchronized (queue) {
                    while (queue.isEmpty() && preload.isEmpty()) queue.wait();
                    job = !queue.isEmpty() ? queue.removeFirst() : preload.removeFirst();
                }
            } catch (InterruptedException stopped) {
                return;
            }
            if (job.prepared && !pending.add(job.key)) continue;
            try {
                if (job.photo != satellite || fallback) continue;
                int level = Math.max(1, Math.min(19, job.zoom - 1));
                byte[] pixels = compose(job.x, job.y, job.zoom, job.photo, level, false);
                if (pixels == null) {
                    pixels = compose(job.x, job.y, job.zoom, job.photo, level, true);
                }
                if (pixels != null) {
                    tiles.put(job.key, bundle(pixels));
                    delivered = true;
                }
            } catch (Throwable failure) {
                failedUntil = SystemClock.elapsedRealtime() + (delivered ? 1500 : 5000);
                hooks.info("Gallery: Google map tile failed: " + failure.getClass().getSimpleName());
                synchronized (this) {
                    if (!delivered && ++failures >= 3 && !fallback) fallBack();
                }
            } finally {
                pending.remove(job.key);
            }
        }
    }

    private static Bundle bundle(byte[] pixels) {
        Bundle tile = new Bundle(3);
        tile.putInt("image_width", SIZE);
        tile.putInt("image_height", SIZE);
        tile.putByteArray("image_data", pixels);
        return tile;
    }

    private byte[] compose(int x, int y, int zoom, boolean photo, int level, boolean network) throws Exception {
        double span = SIZE * Math.pow(2, 18 - zoom);
        double west = x * span;
        double south = y * span;
        double world = SIZE * Math.pow(2, level);
        Bitmap out = canvasBitmap.get();
        Canvas canvas = tileCanvas.get();
        if (out == null || canvas == null) {
            out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(out);
            canvasBitmap.set(out);
            tileCanvas.set(canvas);
        }
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        double[] bottomLeft = point(west, south);
        double[] topRight = point(west + span, south + span);
        double left = worldX(bottomLeft[1], world);
        double right = worldX(topRight[1], world);
        // At zoom >= 8, projection curvature across a single 256px tile is < 0.05px, so 1 band is 4x faster.
        int bands = zoom >= 8 ? 1 : 4;
        int count = 1 << level;
        Rect from = new Rect();
        RectF to = new RectF();
        for (int band = 0; band < bands; band++) {
            double top = bands == 1
                    ? worldY(topRight[0], world)
                    : worldY(point(west, south + span * (bands - band) / bands)[0], world);
            double bottom = bands == 1
                    ? worldY(bottomLeft[0], world)
                    : worldY(point(west, south + span * (bands - band - 1) / bands)[0], world);
            float bandTop = SIZE * band / (float) bands;
            float bandBottom = SIZE * (band + 1) / (float) bands;
            for (long gy = (long) Math.floor(top / SIZE); gy <= (long) Math.floor((bottom - 1e-6) / SIZE); gy++) {
                if (gy < 0 || gy >= count) continue;
                for (long gx = (long) Math.floor(left / SIZE); gx <= (long) Math.floor((right - 1e-6) / SIZE); gx++) {
                    Bitmap source = source((int) ((gx % count + count) % count), (int) gy, level, photo, network);
                    if (source == null) return null;
                    double sourceScale = source.getWidth() / (double) SIZE;
                    double fromX = Math.max(left, gx * SIZE);
                    double toX = Math.min(right, (gx + 1) * SIZE);
                    double fromY = Math.max(top, gy * SIZE);
                    double toY = Math.min(bottom, (gy + 1) * SIZE);
                    from.set((int) Math.round((fromX - gx * SIZE) * sourceScale),
                            (int) Math.round((fromY - gy * SIZE) * sourceScale),
                            (int) Math.round((toX - gx * SIZE) * sourceScale),
                            (int) Math.round((toY - gy * SIZE) * sourceScale));
                    to.set((float) ((fromX - left) / (right - left) * SIZE),
                            (float) (bandTop + (fromY - top) / (bottom - top) * (bandBottom - bandTop)),
                            (float) ((toX - left) / (right - left) * SIZE),
                            (float) (bandTop + (toY - top) / (bottom - top) * (bandBottom - bandTop)));
                    canvas.drawBitmap(source, from, to, BITMAP_PAINT);
                }
            }
        }
        byte[] raw = new byte[SIZE * SIZE * 4];
        out.copyPixelsToBuffer(ByteBuffer.wrap(raw));
        return raw;
    }

    /**
     * Converts Baidu Mercator plane coordinates to Google tile lat/lng (applying BD-09 -> GCJ-02
     * inside mainland China where Google's tiles carry the national offset).
     */
    private double[] point(double mapX, double mapY) throws ReflectiveOperationException {
        Object value = mc2ll.invoke(null, geoPoint.newInstance(mapY, mapX));
        double y = latitude.getDouble(value);
        double x = longitude.getDouble(value);
        if (!GeoMath.inMainland(y, x)) return new double[]{y, x};
        double shift = Math.PI * 3000 / 180;
        double east = x - 0.0065;
        double north = y - 0.006;
        double radius = Math.sqrt(east * east + north * north) - 0.00002 * Math.sin(north * shift);
        double angle = Math.atan2(north, east) - 0.000003 * Math.cos(east * shift);
        return new double[]{radius * Math.sin(angle), radius * Math.cos(angle)};
    }

    private static double worldX(double lng, double world) {
        return (lng + 180) / 360 * world;
    }

    private static double worldY(double lat, double world) {
        double limited = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double sin = Math.sin(Math.toRadians(limited));
        return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * world;
    }

    private Bitmap source(int x, int y, int level, boolean photo, boolean network) throws Exception {
        String key = fileName(x, y, level, photo);
        Bitmap cached = sources.get(key);
        if (cached != null) return cached;
        if (!network) {
            File file = new File(disk(), key);
            if (!file.isFile() || System.currentTimeMillis() - file.lastModified() >= DISK_AGE_MS) {
                return null;
            }
        }
        Object lock = sourceLocks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                cached = sources.get(key);
                if (cached != null) return cached;
                File file = new File(disk(), key);
                byte[] data = null;
                if (file.isFile() && System.currentTimeMillis() - file.lastModified() < DISK_AGE_MS) {
                    try {
                        data = Files.readAllBytes(file.toPath());
                    } catch (IOException ignored) {
                    }
                }
                Bitmap image = data == null ? null : decode(data);
                if (image == null) {
                    if (!network) return null;
                    data = downloadTile(x, y, level, photo);
                    image = decode(data);
                    if (image == null) throw new IOException("Bad image");
                    store(file, data);
                }
                sources.put(key, image);
                return image;
            }
        } finally {
            sourceLocks.remove(key, lock);
        }
    }

    private static String fileName(int x, int y, int level, boolean photo) {
        return level + "_" + x + "_" + y + (photo ? "_s" : "") + "_" + Locale.getDefault().getLanguage();
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static byte[] downloadTile(int x, int y, int level, boolean photo) throws IOException {
        String address = "https://mt" + ((x + y) & 3) + ".google.com/vt/lyrs=" + (photo ? "y" : "m")
                + "&hl=" + Locale.getDefault().getLanguage() + "&scale=2&x=" + x + "&y=" + y + "&z=" + level;
        return downloadUrl(address);
    }

    private static byte[] downloadUrl(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream data = new ByteArrayOutputStream(32768);
            byte[] buffer = new byte[16384];
            for (int read; (read = in.read(buffer)) > 0; ) data.write(buffer, 0, read);
            return data.toByteArray();
        }
    }

    private synchronized File disk() throws ReflectiveOperationException {
        if (diskCache != null) return diskCache;
        Context context = (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        File folder = new File(context.getCacheDir(), "hyper_gallery_google_map");
        folder.mkdirs();
        File[] files = folder.listFiles();
        if (files != null) {
            long total = 0;
            for (File file : files) total += file.length();
            if (total > DISK_BYTES) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                for (File file : files) {
                    if (total <= DISK_BYTES * 3 / 4) break;
                    if (OFFICIAL_LOGO_FILE.equals(file.getName())) continue;
                    total -= file.length();
                    file.delete();
                }
            }
        }
        diskCache = folder;
        return folder;
    }

    private static void store(File file, byte[] data) {
        File partial = new File(file.getPath() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            out.write(data);
        } catch (IOException failed) {
            partial.delete();
            return;
        }
        if (!partial.renameTo(file)) partial.delete();
    }
}
