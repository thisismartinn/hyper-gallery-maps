package io.martin.hypergallerymaps;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;

/** Installs hooks in protective mode and logs to both logcat and the LSPosed log. */
final class Hooks {
    static final String TAG = "HyperGalleryMaps";
    private final XposedModule module;

    Hooks(XposedModule module) {
        this.module = module;
    }

    void install(Executable target, XposedInterface.Hooker hooker) {
        target.setAccessible(true);
        module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    void info(String message) {
        Log.i(TAG, message);
        module.log(Log.INFO, TAG, message);
    }

    void error(String message, Throwable error) {
        Log.e(TAG, message, error);
        module.log(Log.ERROR, TAG, message, error);
    }
}
