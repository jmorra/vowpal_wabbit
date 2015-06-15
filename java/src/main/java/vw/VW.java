package vw;

import sun.misc.Unsafe;
import vw.jni.NativeUtils;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A JNI layer for submitting examples to VW and getting predictions back.  It should be noted
 * that at this time VW is NOT thread safe, and therefore neither is the JNI layer.  It should be noted
 * that this was originally written with a bulk interface that was later removed because of benchmarking
 * data found <a href="https://microbenchmarks.appspot.com/runs/817d246a-5f90-478a-bc27-d5912d2ff874#r:scenario.benchmarkSpec.methodName,scenario.benchmarkSpec.parameters.loss,scenario.benchmarkSpec.parameters.mutabilityPolicy,scenario.benchmarkSpec.parameters.nExamples">here</a>.
 */
public class VW implements Closeable {

    private static final String DOUBLE_LINK_ERROR_MSG = "option '--link' cannot be specified more than once";
    private static final String LINK_FUNC_REGEX = "--link\\s+(identity|logistic|glf1)";

    // This main method only exists to test the library implementation.  To test it just run
    // java -cp target/vw-jni-*-SNAPSHOT.jar vw.VW
    public static void main(String[] args) {
        new VW("").close();
        new VW("--quiet").close();
    }

    static {
        try {
            NativeUtils.loadOSDependentLibrary("/vw_jni", ".lib");
        }
        catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicBoolean isOpen;
    private final long nativePointer;

    /**
     * Create a new VW instance that is ready to either create predictions or learn based on examples
     * @param command The same string that is passed to VW, see
     *                <a href="https://github.com/JohnLangford/vowpal_wabbit/wiki/Command-line-arguments">here</a>
     *                for more information
     */
    public VW(final String command) {
        isOpen = new AtomicBoolean(true);
        nativePointer = initializeAndGetNativePointer(command);
    }

    /**
     * Runs prediction on <code>example</code> and returns the prediction output.
     *
     * @param example a single vw example string
     * @return A prediction
     */
    public float predict(final String example) {
        if (isOpen.get()) {
            return predict(example, false, nativePointer);
        }
        throw new IllegalStateException("Already closed.");
    }

    /**
     * Runs learning on <code>example</code> and returns the prediction output.
     *
     * @param example a single vw example string
     * @return A prediction
     */
    public float learn(final String example) {
        if (isOpen.get()) {
            return predict(example, true, nativePointer);
        }
        throw new IllegalStateException("Already closed.");
    }

    /**
     * Close the VW instance.  This MUST be called in order to free up the native memory.
     * After this is called no future calls to this object are permitted.
     */
    public void close() {
        if (isOpen.getAndSet(false)) {
            closeInstance(nativePointer);
        }
    }

    private native long initialize(String command);
    private native float predict(String example, boolean learn, long nativePointer);
    private native void closeInstance(long nativePointer);

    /**
     * If a model is created with <em>-f</em> and specifies a link function via <em>--link</em>, then is then
     * subsequently imported as an initial regressor via <em>-i</em>, an error occurs.  This is inconvenient,
     * but can be subverted by attempting to remove the <em>--link</em> argument and retrying.
     * @param command the VW parameters.
     * @return the native pointer address.
     */
    private long initializeAndGetNativePointer(final String command) {
        try {
            return initialize(command);
        }
        catch(Throwable t) {
            if (!t.getMessage().equals(DOUBLE_LINK_ERROR_MSG)) {
                getUnsafe().throwException(t);
            }

            // Space so parameters don't run into each other.
            return initialize(command.replaceFirst(LINK_FUNC_REGEX, " "));
        }
    }

    /**
     * Throw checked exceptions without declaring in the signature.  This is done because we are only
     * rethrowing whatever would be thrown anyway so the logic is that it shouldn't make a difference
     * in the caller's error handling. For more information, see
     * <a href="http://fahdshariff.blogspot.com/2010/12/throw-checked-exception-from-method.html">Fahd Shariff's Blog Article</a>.
     * @return an Unsafe instance
     */
    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
