/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class Main {
    static final String DEX_FILE = System.getenv("DEX_LOCATION") + "/141-class-unload-ex.jar";
    static String nativeLibraryName;

    public static void main(String[] args) throws Exception {
        nativeLibraryName = args[0];
        Class pathClassLoader = Class.forName("dalvik.system.PathClassLoader");
        if (pathClassLoader == null) {
            throw new AssertionError("Couldn't find path class loader class");
        }
        Constructor constructor =
            pathClassLoader.getDeclaredConstructor(String.class, ClassLoader.class);
        try {
            testUnloadClass(constructor);
            testUnloadLoader(constructor);
            // Test that we don't unload if we have a Method keeping the class live.
            testNoUnloadInvoke(constructor);
            // Test that we don't unload if we have an instance.
            testNoUnloadInstance(constructor);
            // Test JNI_OnLoad and JNI_OnUnload.
            testLoadAndUnloadLibrary(constructor);
            // Stress test to make sure we dont leak memory.
            stressTest(constructor);
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    private static void stressTest(Constructor constructor) throws Exception {
        for (int i = 0; i <= 100; ++i) {
            setUpUnloadLoader(constructor, false);
            if (i % 10 == 0) {
                Runtime.getRuntime().gc();
            }
        }
    }

    private static void testUnloadClass(Constructor constructor) throws Exception {
        WeakReference<Class> klass = setUpUnloadClass(constructor);
        // No strong refernces to class loader, should get unloaded.
        Runtime.getRuntime().gc();
        WeakReference<Class> klass2 = setUpUnloadClass(constructor);
        Runtime.getRuntime().gc();
        // If the weak reference is cleared, then it was unloaded.
        System.out.println(klass.get());
        System.out.println(klass2.get());
    }

    private static void testUnloadLoader(Constructor constructor)
        throws Exception {
      WeakReference<ClassLoader> loader = setUpUnloadLoader(constructor, true);
      // No strong refernces to class loader, should get unloaded.
      Runtime.getRuntime().gc();
      // If the weak reference is cleared, then it was unloaded.
      System.out.println(loader.get());
    }

    private static void testLoadAndUnloadLibrary(Constructor constructor) throws Exception {
        WeakReference<ClassLoader> loader = setUpLoadLibrary(constructor);
        // No strong refernces to class loader, should get unloaded.
        Runtime.getRuntime().gc();
        // If the weak reference is cleared, then it was unloaded.
        System.out.println(loader.get());
    }

    private static void testNoUnloadInvoke(Constructor constructor) throws Exception {
        WeakReference<ClassLoader> loader =
            new WeakReference((ClassLoader) constructor.newInstance(
                DEX_FILE, ClassLoader.getSystemClassLoader()));
        WeakReference<Class> intHolder = new WeakReference(loader.get().loadClass("IntHolder"));
        intHolder.get().getDeclaredMethod("runGC").invoke(intHolder.get());
        boolean isNull = loader.get() == null;
        System.out.println("loader null " + isNull);
    }

    private static void testNoUnloadInstance(Constructor constructor) throws Exception {
        WeakReference<ClassLoader> loader =
            new WeakReference((ClassLoader) constructor.newInstance(
                DEX_FILE, ClassLoader.getSystemClassLoader()));
        WeakReference<Class> intHolder = new WeakReference(loader.get().loadClass("IntHolder"));
        Object o = intHolder.get().newInstance();
        Runtime.getRuntime().gc();
        boolean isNull = loader.get() == null;
        System.out.println("loader null " + isNull);
    }

    private static WeakReference<Class> setUpUnloadClass(Constructor constructor) throws Exception {
        ClassLoader loader = (ClassLoader) constructor.newInstance(
            DEX_FILE, ClassLoader.getSystemClassLoader());
        Class intHolder = loader.loadClass("IntHolder");
        Method getValue = intHolder.getDeclaredMethod("getValue");
        Method setValue = intHolder.getDeclaredMethod("setValue", Integer.TYPE);
        // Make sure we don't accidentally preserve the value in the int holder, the class
        // initializer should be re-run.
        System.out.println((int) getValue.invoke(intHolder));
        setValue.invoke(intHolder, 2);
        System.out.println((int) getValue.invoke(intHolder));
        waitForCompilation(intHolder);
        return new WeakReference(intHolder);
    }

    private static WeakReference<ClassLoader> setUpUnloadLoader(Constructor constructor,
                                                                boolean waitForCompilation)
        throws Exception {
        ClassLoader loader = (ClassLoader) constructor.newInstance(
            DEX_FILE, ClassLoader.getSystemClassLoader());
        Class intHolder = loader.loadClass("IntHolder");
        Method setValue = intHolder.getDeclaredMethod("setValue", Integer.TYPE);
        setValue.invoke(intHolder, 2);
        if (waitForCompilation) {
            waitForCompilation(intHolder);
        }
        return new WeakReference(loader);
    }

    private static void waitForCompilation(Class intHolder) throws Exception {
      // Load the native library so that we can call waitForCompilation.
      Method loadLibrary = intHolder.getDeclaredMethod("loadLibrary", String.class);
      loadLibrary.invoke(intHolder, nativeLibraryName);
      // Wait for JIT compilation to finish since the async threads may prevent unloading.
      Method waitForCompilation = intHolder.getDeclaredMethod("waitForCompilation");
      waitForCompilation.invoke(intHolder);
    }

    private static WeakReference<ClassLoader> setUpLoadLibrary(Constructor constructor)
        throws Exception {
        ClassLoader loader = (ClassLoader) constructor.newInstance(
            DEX_FILE, ClassLoader.getSystemClassLoader());
        Class intHolder = loader.loadClass("IntHolder");
        Method loadLibrary = intHolder.getDeclaredMethod("loadLibrary", String.class);
        loadLibrary.invoke(intHolder, nativeLibraryName);
        return new WeakReference(loader);
    }
}
