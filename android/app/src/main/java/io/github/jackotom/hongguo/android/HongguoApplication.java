package io.github.jackotom.hongguo.android;

import android.app.Application;

/** Installs account-scoped history before either activities or cast services start. */
public final class HongguoApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AccountSync.init(this);
    }
}
