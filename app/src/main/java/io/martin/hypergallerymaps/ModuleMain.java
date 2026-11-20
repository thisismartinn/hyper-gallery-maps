package io.martin.hypergallerymaps;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModuleMain extends XposedModule {
    private static final String GALLERY_PLUGIN = "com.hyper.gallery.plugin";
    private static final String GALLERY = "com.miui.gallery";
    private final Hooks hooks = new Hooks(this);
    private String process;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        process = param.getProcessName();
        hooks.info("loaded in " + process);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if (!GALLERY_PLUGIN.equals(pkg) && !GALLERY.equals(pkg)) return;

        ClassLoader loader = param.getClassLoader();
        hooks.info("hooking " + pkg + " (process=" + process + ")");

        new GalleryLocationHooks(hooks, loader).install();
        new GalleryGoogleMapLayer(hooks, loader).install();
    }
}
