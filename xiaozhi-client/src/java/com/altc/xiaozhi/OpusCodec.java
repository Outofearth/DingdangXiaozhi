package com.altc.xiaozhi;

/**
 * JNI wrapper around libopus. Both encoder and decoder are exposed as static
 * native methods that take a native pointer (jlong) handle.
 *
 * The native library (libopusjni.so) statically bundles libopus, so loading
 * this one library is enough.
 */
public final class OpusCodec {
    static {
        System.loadLibrary("opusjni");
    }

    public static native long createEncoder(int sampleRate, int channels);
    public static native void destroyEncoder(long handle);

    /**
     * Encode one PCM frame.
     * @param handle  encoder handle from {@link #createEncoder}
     * @param pcm     PCM samples (16-bit), length must equal the frame size
     * @param pcmLen  number of samples in {@code pcm}
     * @param out     output buffer for the Opus packet
     * @param outLen  capacity of {@code out}
     * @return number of Opus bytes written (positive), or a negative Opus error
     */
    public static native int encode(long handle, short[] pcm, int pcmLen, byte[] out, int outLen);

    public static native long createDecoder(int sampleRate, int channels);
    public static native void destroyDecoder(long handle);

    /**
     * Decode one Opus packet.
     * @return number of PCM samples produced (positive), or a negative Opus error
     */
    public static native int decode(long handle, byte[] opus, int opusLen, short[] pcm, int pcmLen);

    private OpusCodec() {}
}
