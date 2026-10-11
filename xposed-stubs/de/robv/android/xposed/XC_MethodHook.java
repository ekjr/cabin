package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;

        public void setResult(Object result) {
            throw new UnsupportedOperationException("stub");
        }
    }

    public class Unhook {
    }
}
