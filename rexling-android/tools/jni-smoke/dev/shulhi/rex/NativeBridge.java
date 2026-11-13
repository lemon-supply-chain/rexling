package dev.shulhi.rex;

/** Host JVM smoke test of the same JNI entry points used by Kotlin. */
public final class NativeBridge {
    static { System.load(System.getProperty("rex.native.library")); }
    public static native long create();
    public static native String command(long handle, String request);
    public static native String poll(long handle, long after);
    public static native long awaitUpdate(long handle,long after,long timeoutMs);
    public static native byte[] readUpdate(long handle,long after);
    public static native void destroy(long handle);

    public static void main(String[] args) throws Exception {
        long handle = create();
        if (handle <= 0) throw new AssertionError("No native handle");
        try {
            if (!command(handle, "{\"type\":\"preview\"}").isEmpty()) throw new AssertionError("Preview command failed");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                String state = poll(handle, 0);
                if (state.contains("\"phase\":\"preview\"")) {
                    if (!state.contains("λ") || !state.contains("🦀")) throw new AssertionError("JNI Unicode conversion failed");
                    if (!state.contains("\"columns\":93") || !state.contains("\"rows\":31")) throw new AssertionError("Unexpected preview size");
                    if (state.contains("reconnect_token") || state.contains("\"ticket\"")) throw new AssertionError("Protocol secret exposed to UI");
                    break;
                }
                if (System.nanoTime() > deadline) throw new AssertionError("Preview timed out");
                Thread.sleep(20);
            }
            long revision=awaitUpdate(handle,0,1000);
            byte[] packet=readUpdate(handle,0);
            if (revision<=0 || packet.length<17 || packet[0]!='R' || packet[1]!='X' || packet[2]!='T' || packet[3]!='5') throw new AssertionError("Binary update missing");
            String bytes=new String(packet,java.nio.charset.StandardCharsets.UTF_8);
            if (!bytes.contains("λ") || !bytes.contains("🦀")) throw new AssertionError("Binary UTF8 lost");
            final long waitingHandle=handle;
            java.util.concurrent.atomic.AtomicReference<Throwable> waitError=new java.util.concurrent.atomic.AtomicReference<>();
            Thread waiter=new Thread(()-> {try {if(awaitUpdate(waitingHandle,Long.MAX_VALUE,1000)!=-1) throw new AssertionError("Destroy did not close notifier");} catch(Throwable error) {waitError.set(error);}});
            waiter.start();
            long other=create();
            if(!command(other,"{\"type\":\"preview\"}").isEmpty()) throw new AssertionError("Other handle blocked");
            destroy(other);destroy(handle);waiter.join(500);
            if(waiter.isAlive()) throw new AssertionError("Waiter held registry lock");
            if(waitError.get()!=null) throw new AssertionError("Notification reader failed",waitError.get());
            if (command(handle, "not-json").isEmpty()) throw new AssertionError("Invalid request accepted");
        } finally { destroy(handle); }
        destroy(handle); // Closed handles remain safe.
        if (!poll(handle, 0).isEmpty()) throw new AssertionError("Closed handle returned state");
        System.out.println("JNI smoke passed: handles, worker, snapshot, Unicode, secret redaction, and cleanup");
    }
}
