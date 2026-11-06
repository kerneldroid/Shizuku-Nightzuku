package rikka.shizuku.server.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection compatibility layer for Android 15/16/17+ hidden package and permission APIs,
 * ported from Nightzuku.
 *
 * Key platform facts:
 * - {@code IPermissionManager.checkPermission(String packageName, String permissionName, String persistentDeviceId, int userId)}
 *   expects {@code packageName} first, {@code permissionName} second, and {@code "default:0"} as the persistent device ID.
 * - {@code IPermissionManager.checkUidPermission(int uid, String permissionName, int deviceId)} takes an {@code int} deviceId (0).
 * - {@code IPackageManager.getPackageInfo} and {@code getApplicationInfo} take {@code (String, long, int)} on API 33+
 *   and {@code (String, int, int)} on API <= 32.
 */
public final class Android17Compat {

    private static final String TAG = "ShizukuAndroid17Compat";
    private static final String PERSISTENT_DEVICE_ID = "default:0";
    private static final int DEVICE_ID_DEFAULT = 0;
    private static final String REVOKE_REASON = "shizuku";

    private static final Logger LOGGER = new Logger(TAG);

    private static final Class<?> INT = int.class;
    private static final Class<?> LONG = long.class;
    private static final Class<?> STRING = String.class;

    private enum Backend {
        PACKAGE("package", "android.content.pm.IPackageManager"),
        PERMISSION("permissionmgr", "android.permission.IPermissionManager");

        final String serviceName;
        final String interfaceName;

        Backend(String serviceName, String interfaceName) {
            this.serviceName = serviceName;
            this.interfaceName = interfaceName;
        }
    }

    private enum Operation {
        PACKAGE_INFO,
        APPLICATION_INFO,
        CHECK_PERMISSION_PACKAGE,
        CHECK_PERMISSION_UID,
        GRANT_RUNTIME_PERMISSION,
        REVOKE_RUNTIME_PERMISSION
    }

    private static final class ManagerHolder {
        volatile Object value;
        volatile Throwable failure;
    }

    private static final class Candidate {
        final Backend backend;
        final String methodName;
        final List<Class<?>> parameterTypes;
        final List<Object> arguments;

        Candidate(Backend backend, String methodName, List<Class<?>> parameterTypes, List<Object> arguments) {
            this.backend = backend;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.arguments = arguments;
        }
    }

    private static final class Resolution {
        final Backend backend;
        final Method method;
        final int candidateIndex;

        Resolution(Backend backend, Method method, int candidateIndex) {
            this.backend = backend;
            this.method = method;
            this.candidateIndex = candidateIndex;
        }
    }

    private static final class Outcome {
        final boolean reached;
        final Object value;
        final Throwable failure;

        Outcome(boolean reached, Object value, Throwable failure) {
            this.reached = reached;
            this.value = value;
            this.failure = failure;
        }
    }

    private static final ManagerHolder packageManagerHolder = new ManagerHolder();
    private static final ManagerHolder permissionManagerHolder = new ManagerHolder();

    private static final Map<Operation, Resolution> resolutions = new HashMap<>();
    private static final Set<Operation> unresolved = new HashSet<>();

    private Android17Compat() {
    }

    public static synchronized void invalidate(String serviceName) {
        if (Backend.PACKAGE.serviceName.equals(serviceName)) {
            packageManagerHolder.value = null;
            packageManagerHolder.failure = null;
            resolutions.entrySet().removeIf(e -> e.getValue().backend == Backend.PACKAGE);
            unresolved.clear();
        } else if (Backend.PERMISSION.serviceName.equals(serviceName)) {
            permissionManagerHolder.value = null;
            permissionManagerHolder.failure = null;
            resolutions.entrySet().removeIf(e -> e.getValue().backend == Backend.PERMISSION);
            unresolved.clear();
        }
    }

    private static synchronized ManagerHolder managerHolder(Backend backend) {
        return backend == Backend.PACKAGE ? packageManagerHolder : permissionManagerHolder;
    }

    @Nullable
    private static synchronized Object obtain(Backend backend) {
        ManagerHolder holder = managerHolder(backend);
        if (holder.value != null) {
            return holder.value;
        }
        if (holder.failure != null) {
            return null;
        }
        try {
            IBinder binder = ServiceManager.getService(backend.serviceName);
            if (binder == null) {
                throw new IllegalStateException(backend.serviceName + " service is not available");
            }
            Class<?> stub = Class.forName(backend.interfaceName + "$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            Object value = asInterface.invoke(null, binder);
            if (value == null) {
                throw new IllegalStateException("asInterface of " + backend.interfaceName + " returned null");
            }
            holder.value = value;
            return value;
        } catch (Throwable t) {
            holder.failure = t;
            LOGGER.w(t, "%s: resolving %s failed", backend.serviceName, backend.interfaceName);
            return null;
        }
    }

    @Nullable
    private static synchronized Throwable lookupFailure(Backend backend) {
        return managerHolder(backend).failure;
    }

    @Nullable
    private static synchronized Resolution resolution(Operation operation) {
        return resolutions.get(operation);
    }

    private static synchronized boolean isUnresolved(Operation operation) {
        return unresolved.contains(operation);
    }

    private static synchronized void rememberResolution(Operation operation, Resolution resolution) {
        resolutions.put(operation, resolution);
        unresolved.remove(operation);
    }

    private static synchronized void forgetResolution(Operation operation) {
        resolutions.remove(operation);
        unresolved.remove(operation);
    }

    private static synchronized void markUnresolved(Operation operation) {
        unresolved.add(operation);
    }

    @Nullable
    public static PackageInfo getPackageInfo(String packageName, long flags, int userId) {
        try {
            return getPackageInfoOrThrow(packageName, flags, userId);
        } catch (Throwable t) {
            LOGGER.w(t, "getPackageInfo(%s, %d) failed", packageName, userId);
            return null;
        }
    }

    @Nullable
    public static PackageInfo getPackageInfoOrThrow(String packageName, long flags, int userId) throws RemoteException {
        try {
            return (PackageInfo) value(
                    Operation.PACKAGE_INFO,
                    Arrays.asList(
                            new Candidate(
                                    Backend.PACKAGE,
                                    "getPackageInfo",
                                    Arrays.asList(STRING, LONG, INT),
                                    Arrays.asList(packageName, flags, userId)
                            ),
                            new Candidate(
                                    Backend.PACKAGE,
                                    "getPackageInfo",
                                    Arrays.asList(STRING, INT, INT),
                                    Arrays.asList(packageName, (int) flags, userId)
                            )
                    )
            );
        } catch (RemoteException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Nullable
    public static ApplicationInfo getApplicationInfo(String packageName, long flags, int userId) {
        try {
            return (ApplicationInfo) value(
                    Operation.APPLICATION_INFO,
                    Arrays.asList(
                            new Candidate(
                                    Backend.PACKAGE,
                                    "getApplicationInfo",
                                    Arrays.asList(STRING, LONG, INT),
                                    Arrays.asList(packageName, flags, userId)
                            ),
                            new Candidate(
                                    Backend.PACKAGE,
                                    "getApplicationInfo",
                                    Arrays.asList(STRING, INT, INT),
                                    Arrays.asList(packageName, (int) flags, userId)
                            )
                    )
            );
        } catch (Throwable t) {
            LOGGER.w(t, "getApplicationInfo(%s, %d) failed", packageName, userId);
            return null;
        }
    }

    public static int checkPermission(String permissionName, String packageName, int userId) {
        Outcome outcome = invoke(
                Operation.CHECK_PERMISSION_PACKAGE,
                Arrays.asList(
                        new Candidate(
                                Backend.PERMISSION,
                                "checkPermission",
                                Arrays.asList(STRING, STRING, STRING, INT),
                                Arrays.asList(packageName, permissionName, PERSISTENT_DEVICE_ID, userId)
                        ),
                        new Candidate(
                                Backend.PERMISSION,
                                "checkPermission",
                                Arrays.asList(STRING, STRING, INT),
                                Arrays.asList(permissionName, packageName, userId)
                        ),
                        new Candidate(
                                Backend.PACKAGE,
                                "checkPermission",
                                Arrays.asList(STRING, STRING, INT),
                                Arrays.asList(permissionName, packageName, userId)
                        )
                )
        );
        if (outcome.reached && outcome.failure == null && outcome.value instanceof Integer) {
            return (Integer) outcome.value;
        }
        return PackageManager.PERMISSION_DENIED;
    }

    public static int checkPermission(String permissionName, int uid) {
        Outcome outcome = invoke(
                Operation.CHECK_PERMISSION_UID,
                Arrays.asList(
                        new Candidate(
                                Backend.PERMISSION,
                                "checkUidPermission",
                                Arrays.asList(INT, STRING, INT),
                                Arrays.asList(uid, permissionName, DEVICE_ID_DEFAULT)
                        ),
                        new Candidate(
                                Backend.PACKAGE,
                                "checkUidPermission",
                                Arrays.asList(STRING, INT),
                                Arrays.asList(permissionName, uid)
                        )
                )
        );
        if (outcome.reached && outcome.failure == null && outcome.value instanceof Integer) {
            return (Integer) outcome.value;
        }
        return PackageManager.PERMISSION_DENIED;
    }

    public static boolean grantRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException {
        Outcome outcome = invoke(
                Operation.GRANT_RUNTIME_PERMISSION,
                Arrays.asList(
                        new Candidate(
                                Backend.PERMISSION,
                                "grantRuntimePermission",
                                Arrays.asList(STRING, STRING, STRING, INT),
                                Arrays.asList(packageName, permissionName, PERSISTENT_DEVICE_ID, userId)
                        ),
                        new Candidate(
                                Backend.PERMISSION,
                                "grantRuntimePermission",
                                Arrays.asList(STRING, STRING, INT),
                                Arrays.asList(packageName, permissionName, userId)
                        )
                )
        );
        if (outcome.failure instanceof RemoteException) {
            throw (RemoteException) outcome.failure;
        }
        return outcome.reached;
    }

    public static boolean revokeRuntimePermission(String packageName, String permissionName, int userId) throws RemoteException {
        Outcome outcome = invoke(
                Operation.REVOKE_RUNTIME_PERMISSION,
                Arrays.asList(
                        new Candidate(
                                Backend.PERMISSION,
                                "revokeRuntimePermission",
                                Arrays.asList(STRING, STRING, STRING, INT, STRING),
                                Arrays.asList(packageName, permissionName, PERSISTENT_DEVICE_ID, userId, REVOKE_REASON)
                        ),
                        new Candidate(
                                Backend.PERMISSION,
                                "revokeRuntimePermission",
                                Arrays.asList(STRING, STRING, STRING, INT),
                                Arrays.asList(packageName, permissionName, PERSISTENT_DEVICE_ID, userId)
                        ),
                        new Candidate(
                                Backend.PERMISSION,
                                "revokeRuntimePermission",
                                Arrays.asList(STRING, STRING, INT),
                                Arrays.asList(packageName, permissionName, userId)
                        )
                )
        );
        if (outcome.failure instanceof RemoteException) {
            throw (RemoteException) outcome.failure;
        }
        return outcome.reached;
    }

    @Nullable
    private static Object value(Operation operation, List<Candidate> candidates) throws Throwable {
        Outcome outcome = invoke(operation, candidates);
        if (!outcome.reached) {
            if (outcome.failure != null) {
                throw outcome.failure;
            }
            throw new IllegalStateException(operation.name() + " was not reached");
        }
        if (outcome.failure != null) {
            throw outcome.failure;
        }
        return outcome.value;
    }

    private static Outcome invoke(Operation operation, List<Candidate> candidates) {
        if (isUnresolved(operation)) {
            return new Outcome(false, null, null);
        }
        Resolution cached = resolution(operation);
        if (cached != null) {
            Outcome resolvedOutcome = invokeResolved(operation, cached, candidates);
            if (resolvedOutcome != null) {
                return resolvedOutcome;
            }
        }
        return search(operation, candidates);
    }

    @Nullable
    private static Outcome invokeResolved(Operation operation, Resolution cached, List<Candidate> candidates) {
        if (cached.candidateIndex < 0 || cached.candidateIndex >= candidates.size()) {
            forgetResolution(operation);
            return null;
        }
        Candidate candidate = candidates.get(cached.candidateIndex);
        Object target = obtain(cached.backend);
        if (target == null) {
            return new Outcome(false, null, lookupFailure(cached.backend));
        }
        Outcome outcome = attempt(operation, candidate, target, cached.method);
        if (outcome.reached) {
            return outcome;
        }
        if (outcome.failure instanceof IllegalArgumentException) {
            forgetResolution(operation);
            return null;
        }
        markUnresolved(operation);
        return outcome;
    }

    private static Outcome search(Operation operation, List<Candidate> candidates) {
        Set<List<Class<?>>> triedShapes = new LinkedHashSet<>();
        Throwable rejection = null;
        Throwable lookup = null;

        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            List<Class<?>> shapeKey = Arrays.asList(
                    candidate.backend.getClass(),
                    candidate.parameterTypes.toArray(new Class<?>[0])
            ).size() >= 0 ? candidate.parameterTypes : Collections.emptyList();

            // Allow same parameter types only if targeting a different backend service
            List<Object> backendAndShape = Arrays.asList(candidate.backend, candidate.parameterTypes);
            if (!triedShapes.add(candidate.parameterTypes) && index > 0 &&
                    candidates.get(index - 1).backend == candidate.backend) {
                LOGGER.w(
                        "%s: candidate %d rejected, parameter shape (%s) is a duplicate",
                        operation.name(),
                        index,
                        shape(candidate.parameterTypes)
                );
                continue;
            }
            if (!isBound(candidate)) {
                continue;
            }
            Object target = obtain(candidate.backend);
            if (target == null) {
                lookup = lookupFailure(candidate.backend);
                continue;
            }
            Method method = findMethod(target, candidate);
            if (method == null) {
                continue;
            }
            Outcome outcome = attempt(operation, candidate, target, method);
            if (outcome.reached) {
                rememberResolution(operation, new Resolution(candidate.backend, method, index));
                return outcome;
            }
            if (outcome.failure instanceof IllegalArgumentException) {
                rejection = outcome.failure;
                continue;
            }
            markUnresolved(operation);
            return outcome;
        }

        markUnresolved(operation);
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) shapes.append("; ");
            shapes.append(shape(candidates.get(i).parameterTypes));
        }
        LOGGER.w("%s: no candidate shape was accepted, candidates were (%s)", operation.name(), shapes.toString());
        return new Outcome(false, null, rejection != null ? rejection : lookup);
    }

    private static Outcome attempt(Operation operation, Candidate candidate, Object target, Method method) {
        try {
            Object res = method.invoke(target, candidate.arguments.toArray());
            return new Outcome(true, res, null);
        } catch (IllegalArgumentException e) {
            return new Outcome(false, null, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            LOGGER.w(cause, "%s: %s refused the request", operation.name(), candidate.methodName);
            return new Outcome(true, null, cause);
        } catch (Throwable t) {
            LOGGER.w(t, "%s: calling %s failed", operation.name(), candidate.methodName);
            return new Outcome(false, null, t);
        }
    }

    @Nullable
    private static Method findMethod(Object target, Candidate candidate) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(candidate.methodName)) {
                continue;
            }
            if (!Arrays.asList(method.getParameterTypes()).equals(candidate.parameterTypes)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static boolean isBound(Candidate candidate) {
        if (candidate.parameterTypes.size() != candidate.arguments.size()) {
            return false;
        }
        for (int i = 0; i < candidate.parameterTypes.size(); i++) {
            if (!isAssignable(candidate.parameterTypes.get(i), candidate.arguments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssignable(Class<?> parameterType, @Nullable Object argument) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        if (parameterType.isInstance(argument)) {
            return true;
        }
        if (parameterType == INT) {
            return argument instanceof Integer;
        }
        if (parameterType == LONG) {
            return argument instanceof Long;
        }
        return false;
    }

    private static String shape(List<Class<?>> parameterTypes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(parameterTypes.get(i).getSimpleName());
        }
        return sb.toString();
    }
}
