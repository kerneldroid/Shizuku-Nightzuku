package rikka.shizuku.server;

import static android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_CACHED;
import static android.app.ActivityManagerHidden.UID_OBSERVER_GONE;

import android.app.ActivityManagerHidden;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.collections.ArraysKt;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.shizuku.server.util.Android17Compat;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.UserHandleCompat;

public class BinderSender {

    private static final Logger LOGGER = new Logger("BinderSender");

    private static final String PERMISSION_MANAGER = "moe.shizuku.manager.permission.MANAGER";
    private static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";

    private static volatile ShizukuService sShizukuService;

    private static final ExecutorService DISPATCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shizuku-binder-sender");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    private static class ProcessObserver extends ProcessObserverAdapter {

        private static final Set<Integer> PID_SET = new HashSet<>();

        static void clear() {
            synchronized (PID_SET) {
                PID_SET.clear();
            }
        }

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
            LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, foregroundActivities ? "true" : "false");

            synchronized (PID_SET) {
                if (!foregroundActivities || !PID_SET.add(pid)) {
                    return;
                }
            }

            dispatchAsync(uid, pid);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid);

            synchronized (PID_SET) {
                PID_SET.remove(pid);
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState);

            synchronized (PID_SET) {
                if (!PID_SET.add(pid)) {
                    return;
                }
            }

            dispatchAsync(uid, pid);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private static class UidObserver extends UidObserverAdapter {

        private static final Set<Integer> UID_SET = new HashSet<>();

        static void clear() {
            synchronized (UID_SET) {
                UID_SET.clear();
            }
        }

        @Override
        public void onUidActive(int uid) throws RemoteException {
            LOGGER.d("onUidActive: uid=%d", uid);

            uidStarts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, Boolean.toString(cached));

            if (!cached) {
                uidStarts(uid);
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) throws RemoteException {
            LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, Boolean.toString(disabled));

            uidGone(uid);
        }

        private void uidStarts(int uid) {
            synchronized (UID_SET) {
                if (!UID_SET.add(uid)) {
                    LOGGER.v("Uid %d already starts", uid);
                    return;
                }
                LOGGER.v("Uid %d starts", uid);
            }

            dispatchAsync(uid, -1);
        }

        private void uidGone(int uid) {
            synchronized (UID_SET) {
                if (UID_SET.remove(uid)) {
                    LOGGER.v("Uid %d dead", uid);
                }
            }
        }
    }

    public static void dispatchTask(Runnable task) {
        DISPATCH_EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.w(t, "Background binder dispatch task failed");
            }
        });
    }

    private static void dispatchAsync(int uid, int pid) {
        DISPATCH_EXECUTOR.execute(() -> {
            try {
                sendBinder(uid, pid);
            } catch (Throwable t) {
                LOGGER.w(t, "Failed to dispatch binder to uid=%d pid=%d", uid, pid);
            }
        });
    }

    private static void sendBinder(int uid, int pid) throws RemoteException {
        ShizukuService service = sShizukuService;
        if (service == null) {
            return;
        }

        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        if (packages.isEmpty())
            return;

        LOGGER.d("sendBinder to uid %d: packages=%s", uid, TextUtils.join(", ", packages));

        int userId = UserHandleCompat.getUserId(uid);
        for (String packageName : packages) {
            PackageInfo pi = Android17Compat.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId);
            if (pi == null || pi.requestedPermissions == null)
                continue;

            if (ArraysKt.contains(pi.requestedPermissions, PERMISSION_MANAGER)) {
                boolean granted;
                if (pid == -1)
                    granted = Android17Compat.checkPermission(PERMISSION_MANAGER, packageName, userId) == PackageManager.PERMISSION_GRANTED
                            || Android17Compat.checkPermission(PERMISSION_MANAGER, uid) == PackageManager.PERMISSION_GRANTED;
                else
                    granted = ActivityManagerApis.checkPermission(PERMISSION_MANAGER, pid, uid) == PackageManager.PERMISSION_GRANTED;

                if (granted) {
                    ShizukuService.sendBinderToManager(service, userId);
                    return;
                }
            } else if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) {
                ShizukuService.sendBinderToUserApp(service, packageName, userId);
                return;
            }
        }
    }

    public static void register(ShizukuService shizukuService) {
        sShizukuService = shizukuService;
        ProcessObserver.clear();
        if (Build.VERSION.SDK_INT >= 26) {
            UidObserver.clear();
        }

        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserver());
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerProcessObserver");
        }

        if (Build.VERSION.SDK_INT >= 26) {
            int flags = UID_OBSERVER_GONE | UID_OBSERVER_ACTIVE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags |= UID_OBSERVER_CACHED;
            }
            try {
                ActivityManagerApis.registerUidObserver(new UidObserver(), flags,
                        ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                        null);
            } catch (Throwable tr) {
                LOGGER.e(tr, "registerUidObserver");
            }
        }
    }
}
