package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    private XposedBridge() {}
    public static void log(String text) { throw new UnsupportedOperationException("stub"); }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook hook) { throw new UnsupportedOperationException("stub"); }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook hook) { throw new UnsupportedOperationException("stub"); }
}
