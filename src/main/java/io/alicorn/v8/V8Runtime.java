package io.alicorn.v8;

import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Object;

import java.util.*;

/**
 * Represents a V8 JavaScript engine which may be injected with
 * classes and objects.
 *
 * @author Brandon Sanders [brandon@alicorn.io]
 */
public class V8Runtime {
//Protected////////////////////////////////////////////////////////////////////

    // Wrapped J2V8 instance.
    protected final V8 v8;

    // Local object cache.
    // TODO: Add ability to share cache between runtimes.
    protected final V8JavaCache cache;

    // Protected constructor to force factory construction.
    protected V8Runtime() {
        this.v8 = V8.createV8Runtime();
        this.cache = new V8JavaCache();
    }

    /**
     * @see #injectObject(String, Object)
     */
    protected String injectObject(String name, Object object, V8Object rootObject) {

        //TODO: Add special handlers for N-dimensional and primitive arrays.
        //TODO: This should inject arrays as JS arrays, not lists. Meh.
        //TODO: This will bypass interceptors in some cases.
        //TODO: This is terrible.
        if (object.getClass().isArray()) {
            Object[] rawArray = (Object[]) object;
            List<Object> injectedArray = new ArrayList<Object>(rawArray.length);
            for (Object obj : rawArray) {
                injectedArray.add(obj);
            }
            return injectObject(name, injectedArray, rootObject);
        } else {
            injectClass("".equals(object.getClass().getSimpleName()) ?
                                object.getClass().getName().replaceAll("\\.+", "_") :
                                object.getClass().getSimpleName(),
                        object.getClass(),
                        null,
                        rootObject);
        }

        if (name == null) {
            name = "TEMP" + UUID.randomUUID().toString().replaceAll("-", "");
        }

        //Build an empty object instance.
        V8JavaClassProxy proxy = cache.cachedV8JavaClasses.get(object.getClass());
        StringBuilder script = new StringBuilder();
        script.append("var ").append(name).append(" = new function() {");

        // Attach interceptor.
        if (proxy.getInterceptor() != null) {
            script.append(proxy.getInterceptor().getConstructorScriptBody());
        }

        script.append("\n}; ").append(name).append(";");

        V8Object other = V8JavaObjectUtils.getRuntimeSarcastically(rootObject).executeObjectScript(script.toString());
        String id = proxy.attachJavaObjectToJsObject(object, other);
        other.release();
        return id;
    }

    /**
     * @see #injectClass(Class)
     */
    protected void injectClass(String name, Class<?> classy, V8JavaClassInterceptor interceptor, V8Object rootObject) {
        //Calculate V8-friendly full class names.
        String v8FriendlyClassname = classy.getName().replaceAll("\\.+", "_");

        //Register the class proxy.
        V8JavaClassProxy proxy;
        if (cache.cachedV8JavaClasses.containsKey(classy)) {
            proxy = cache.cachedV8JavaClasses.get(classy);
        } else {
            proxy = new V8JavaClassProxy(classy, interceptor, cache);
            cache.cachedV8JavaClasses.put(classy, proxy);
        }

        //Check if the root object already has a constructor.
        //TODO: Is this faster or slower than checking if a specific V8Value is "undefined"?
        if (!Arrays.asList(rootObject.getKeys()).contains("v8ConstructJavaClass" + v8FriendlyClassname)) {
            rootObject.registerJavaMethod(proxy, "v8ConstructJavaClass" + v8FriendlyClassname);

            //Build up the constructor script.
            StringBuilder script = new StringBuilder();
            script.append("this.").append(name).append(" = function() {");
            script.append("v8ConstructJavaClass").append(v8FriendlyClassname).append(".apply(this, arguments);");

            // Attach interceptor.
            if (proxy.getInterceptor() != null) {
                script.append(proxy.getInterceptor().getConstructorScriptBody());
            }

            script.append("\n};");

            //Evaluate the script to create a new constructor function.
            V8JavaObjectUtils.getRuntimeSarcastically(rootObject).executeVoidScript(script.toString());

            //Build up static methods if needed.
            if (proxy.getInterceptor() == null) {
                V8Object constructorFunction = (V8Object) rootObject.get(name);
                for (V8JavaStaticMethodProxy method : proxy.getStaticMethods()) {
                    constructorFunction.registerJavaMethod(method, method.getMethodName());
                }

                //Clean up after ourselves.
                constructorFunction.release();
            }
        }
    }

//Public///////////////////////////////////////////////////////////////////////

    /**
     * Creates and returns a new {@link V8Runtime}.
     *
     * @return A new {@link V8Runtime}.
     */
    public static V8Runtime create() {
        return new V8Runtime();
    }

    /**
     * Injects an existing Java object into V8 as a variable.
     * <p>
     * If the passed object represents a primitive array (e.g., String[], Object[], int[]),
     * the array will be unwrapped and injected into the V8 context as an ArrayList. Any
     * modifications made to the injected list will not be passed back up to the Java runtime.
     * <p>
     * This method will immediately invoke {@link #injectClass(Class)}
     * before injecting the object, causing the object's class to be automatically
     * injected into V8 if it wasn't already.
     *
     * @param name Name of the variable to assign the Java object to. If this value is null,
     * a UUID will be automatically generated and used as the name of the variable.
     * @param object Java object to inject.
     *
     * @return String identifier of the injected object.
     */
    public String injectObject(String name, Object object) {
        return injectObject(name, object, v8);
    }

    /**
     * Injects a Java class into V8 as a prototype.
     * <p>
     * The injected "class" will be equivalent to a Java Script prototype with
     * a name identical to the simple name of the class.
     *
     * @param classy Java class to inject.
     */
    public void injectClass(Class<?> classy) {
        injectClass(classy.getSimpleName(), classy, null, v8);
    }

    /**
     * Releases any native resources held by this V8 runtime.
     *
     * This method must be invoked before shutdown, or else memory leaks could occur!
     */
    public void release() {
        V8JavaObjectUtils.releaseV8Resources(v8);
        v8.release(true);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     */
    public void executeScript(String script) {
        v8.executeVoidScript(script);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     *
     * @return The boolean return value of the script.
     */
    public boolean executeBooleanScript(String script) {
        return v8.executeBooleanScript(script);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     *
     * @return The integer return value of the script.
     */
    public int executeIntegerScript(String script) {
        return v8.executeIntegerScript(script);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     *
     * @return The double return value of the script.
     */
    public double executeDoubleScript(String script) {
        return v8.executeDoubleScript(script);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     *
     * @return The string return value of the script.
     */
    public String executeStringScript(String script) {
        return v8.executeStringScript(script);
    }

    /**
     * Executes a script within this V8 runtime.
     *
     * @param script Script to execute.
     *
     * @return The object return value of the script.
     */
    public Object executeObjectScript(String script) {
        return v8.executeObjectScript(script);
    }
}
