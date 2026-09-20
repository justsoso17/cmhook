package de.robv.android.xposed;
public final class XposedHelpers {
    private XposedHelpers() {}
    public static Class<?> findClass(String className, ClassLoader classLoader) { throw new UnsupportedOperationException("stub"); }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { throw new UnsupportedOperationException("stub"); }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { throw new UnsupportedOperationException("stub"); }
}
