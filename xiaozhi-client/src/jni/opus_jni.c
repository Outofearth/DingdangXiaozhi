/*
 * opus_jni.c - JNI bridge for libopus (encoder + decoder)
 *
 * Single shared library libopusjni.so statically links libopus.a so the
 * Android app only needs to load one native library. Built with the
 * Android NDK standalone toolchain (armeabi-v7a, API 21).
 */

#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <opus.h>

/* Helper: convert jlong handle back to a pointer */
#define J2P(j) ((void *)(intptr_t)(j))

JNIEXPORT jlong JNICALL
Java_com_altc_xiaozhi_OpusCodec_createEncoder(JNIEnv *env, jclass cls,
                                             jint sampleRate, jint channels)
{
    int err = OPUS_OK;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels,
                                           OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) {
        return 0;
    }
    return (jlong)(intptr_t)enc;
}

JNIEXPORT void JNICALL
Java_com_altc_xiaozhi_OpusCodec_destroyEncoder(JNIEnv *env, jclass cls, jlong handle)
{
    if (handle != 0) {
        opus_encoder_destroy((OpusEncoder *)J2P(handle));
    }
}

JNIEXPORT jint JNICALL
Java_com_altc_xiaozhi_OpusCodec_encode(JNIEnv *env, jclass cls, jlong handle,
                                      jshortArray pcm, jint pcmLen,
                                      jbyteArray out, jint outLen)
{
    OpusEncoder *enc = (OpusEncoder *)J2P(handle);
    if (enc == NULL || pcm == NULL || out == NULL) {
        return -1;
    }
    jshort *pcmBuf = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte  *outBuf = (*env)->GetByteArrayElements(env, out, NULL);

    opus_int32 n = opus_encode(enc, (const opus_int16 *)pcmBuf, pcmLen,
                               (unsigned char *)outBuf, outLen);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmBuf, JNI_ABORT);
    if (n < 0) {
        (*env)->ReleaseByteArrayElements(env, out, outBuf, JNI_ABORT);
        return (jint)n;
    }
    (*env)->ReleaseByteArrayElements(env, out, outBuf, 0);
    return (jint)n;
}

JNIEXPORT jlong JNICALL
Java_com_altc_xiaozhi_OpusCodec_createDecoder(JNIEnv *env, jclass cls,
                                             jint sampleRate, jint channels)
{
    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == NULL) {
        return 0;
    }
    return (jlong)(intptr_t)dec;
}

JNIEXPORT void JNICALL
Java_com_altc_xiaozhi_OpusCodec_destroyDecoder(JNIEnv *env, jclass cls, jlong handle)
{
    if (handle != 0) {
        opus_decoder_destroy((OpusDecoder *)J2P(handle));
    }
}

JNIEXPORT jint JNICALL
Java_com_altc_xiaozhi_OpusCodec_decode(JNIEnv *env, jclass cls, jlong handle,
                                      jbyteArray opus, jint opusLen,
                                      jshortArray pcm, jint pcmLen)
{
    OpusDecoder *dec = (OpusDecoder *)J2P(handle);
    if (dec == NULL || opus == NULL || pcm == NULL) {
        return -1;
    }
    jbyte  *opusBuf = (*env)->GetByteArrayElements(env, opus, NULL);
    jshort *pcmBuf  = (*env)->GetShortArrayElements(env, pcm, NULL);

    /* opusLen < 0 would trigger PLC; we only pass real packets here. */
    int n = opus_decode(dec, (const unsigned char *)opusBuf, opusLen,
                        pcmBuf, pcmLen, 0);

    (*env)->ReleaseByteArrayElements(env, opus, opusBuf, JNI_ABORT);
    if (n < 0) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcmBuf, JNI_ABORT);
        return (jint)n;
    }
    (*env)->ReleaseShortArrayElements(env, pcm, pcmBuf, 0);
    return (jint)n;
}
