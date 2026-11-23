package tw.edu.socket.client;

import android.app.Application;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import android.os.Bundle;

public class ClientApplication extends Application {
    private ClientSession clientSession;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int startedActivityCount;
    private final Runnable leaveOnBackground = () -> {
        if (startedActivityCount == 0 && clientSession != null) clientSession.leave();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        clientSession = new ClientSession();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedActivityCount++;
                mainHandler.removeCallbacks(leaveOnBackground);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivityCount = Math.max(0, startedActivityCount - 1);
                if (startedActivityCount == 0) mainHandler.postDelayed(leaveOnBackground, 600);
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    public ClientSession getClientSession() {
        return clientSession;
    }

    @Override
    public void onTerminate() {
        if (clientSession != null) clientSession.close();
        super.onTerminate();
    }
}
