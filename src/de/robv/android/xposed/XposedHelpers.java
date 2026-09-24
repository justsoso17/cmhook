package de.robv.android.xposed;
public final class XposedHelpers {
    private XposedHelpers() {}
    public static Class<?> findClass(String className, ClassLoader classLoader) { throw new UnsupportedOperationException("stub"); }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { throw new UnsupportedOperationException("stub"); }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { throw new UnsupportedOperationException("stub"); }
    public static Object getObjectField(Object obj, String fieldName) { throw new UnsupportedOperationException("stub"); }
    public static Object callMethod(Object obj, String methodName, Object... args) { throw new UnsupportedOperationException("stub"); }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { throw new UnsupportedOperationException("stub"); }
    public static Object newInstance(Class<?> clazz, Object... args) { throw new UnsupportedOperationException("stub"); }
}
