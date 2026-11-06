package rikka.shizuku.server.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import rikka.hidden.compat.UserManagerApis;
import rikka.hidden.compat.util.SystemServiceBinder;

/**
 * Multi-user enumeration and foreground user resolution helpers compatible with Android 17 QPR1+,
 * ported from Nightzuku.
 *
 * Android 17 QPR1 narrowed {@code IUserManager.getUsers} to the single-boolean signature
 * {@code getUsers(boolean excludeDying)}, causing 3-boolean hidden-compat calls to throw {@code NoSuchMethodError}.
 */
public final class UsersCompat {

    private static final Logger LOGGER = new Logger("UsersCompat");

    private static final SystemServiceBinder<IUserManager> userManagerBinder =
            new SystemServiceBinder<>(Context.USER_SERVICE, IUserManager.Stub::asInterface);

    private static volatile Object cachedActivityManager;
    private static volatile Method cachedGetCurrentUserMethod;

    private UsersCompat() {
    }

    public static List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated) throws RemoteException {
        try {
            List<UserInfo> list = UserManagerApis.getUsers(excludePartial, excludeDying, excludePreCreated);
            return list != null ? list : Collections.emptyList();
        } catch (NoSuchMethodError e) {
            IUserManager um = userManagerBinder.get();
            if (um == null) {
                return Collections.emptyList();
            }
            List<UserInfo> list = um.getUsers(excludeDying);
            return list != null ? list : Collections.emptyList();
        }
    }

    public static List<Integer> getUserIdsNoThrow() {
        try {
            List<UserInfo> users = getUsers(true, true, true);
            Set<Integer> ids = new LinkedHashSet<>(users.size());
            for (UserInfo user : users) {
                if (user != null) {
                    ids.add(user.id);
                }
            }
            if (ids.isEmpty()) {
                return Collections.singletonList(0);
            }
            return new ArrayList<>(ids);
        } catch (Throwable t) {
            LOGGER.w(t, "Failed to enumerate users, falling back to user 0");
            return Collections.singletonList(0);
        }
    }

    @Nullable
    public static UserInfo getUserInfo(int userId) {
        try {
            return UserManagerApis.getUserInfo(userId);
        } catch (Throwable t) {
            LOGGER.w(t, "getUserInfo(%d) failed", userId);
            return null;
        }
    }

    public static synchronized void invalidateActivityManager() {
        cachedActivityManager = null;
    }

    @SuppressLint({"SoonBlockedPrivateApi", "PrivateApi"})
    private static synchronized Object resolveActivityManager() throws Exception {
        if (cachedActivityManager != null) {
            return cachedActivityManager;
        }
        Object resolved;
        try {
            IBinder binder = ServiceManager.getService(Context.ACTIVITY_SERVICE);
            if (binder == null) {
                throw new IllegalStateException("activity service unavailable");
            }
            Class<?> stubClass = Class.forName("android.app.IActivityManager$Stub");
            resolved = stubClass.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ClassNotFoundException e) {
            resolved = Class.forName("android.app.ActivityManagerNative")
                    .getDeclaredMethod("getDefault")
                    .invoke(null);
        }
        if (resolved == null) {
            throw new IllegalStateException("IActivityManager proxy is null");
        }
        cachedActivityManager = resolved;
        return resolved;
    }

    private static synchronized Method resolveGetCurrentUserMethod() throws Exception {
        if (cachedGetCurrentUserMethod != null) {
            return cachedGetCurrentUserMethod;
        }
        Method method = Class.forName("android.app.IActivityManager").getMethod("getCurrentUser");
        cachedGetCurrentUserMethod = method;
        return method;
    }

    @Nullable
    public static Integer getForegroundUserId() {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Object am = resolveActivityManager();
                Object res = resolveGetCurrentUserMethod().invoke(am);
                if (res instanceof UserInfo) {
                    return ((UserInfo) res).id;
                }
                return null;
            } catch (Throwable t) {
                Throwable cause = (t instanceof InvocationTargetException && ((InvocationTargetException) t).getTargetException() != null)
                        ? ((InvocationTargetException) t).getTargetException()
                        : t;
                if (attempt == 0) {
                    invalidateActivityManager();
                } else {
                    LOGGER.w(cause, "Unable to read foreground user ID");
                }
            }
        }
        return null;
    }

    public static boolean isUserForeground(int userId) {
        Integer fg = getForegroundUserId();
        if (fg == null) {
            return userId == 0;
        }
        return fg == userId;
    }
}
