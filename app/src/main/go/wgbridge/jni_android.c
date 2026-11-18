//go:build android

// JNI yapıştırıcısı: net.atom.dpibypass.wg.WgNative -> Go dışa aktarımları.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "_cgo_export.h"

static char *dup_utf(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) return NULL;
    size_t n = strlen(c);
    char *out = malloc(n + 1);
    if (out) memcpy(out, c, n + 1);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

JNIEXPORT jstring JNICALL
Java_net_atom_dpibypass_wg_WgNative_nativeStart(JNIEnv *env, jclass cls, jstring uapi, jstring addresses,
                                                 jstring dns, jint mtu, jstring listen) {
    char *u = dup_utf(env, uapi), *a = dup_utf(env, addresses), *d = dup_utf(env, dns), *l = dup_utf(env, listen);
    char *err = NULL;
    if (u && a && d && l) err = wgbridgeStart(u, a, d, (int)mtu, l);
    else err = NULL;
    jstring result = (*env)->NewStringUTF(env, (u && a && d && l) ? (err ? err : "") : "out of memory");
    if (err) wgbridgeFree(err);
    free(u); free(a); free(d); free(l);
    return result;
}

JNIEXPORT void JNICALL
Java_net_atom_dpibypass_wg_WgNative_nativeStop(JNIEnv *env, jclass cls) {
    wgbridgeStop();
}

JNIEXPORT jstring JNICALL
Java_net_atom_dpibypass_wg_WgNative_nativeStatus(JNIEnv *env, jclass cls) {
    char *s = wgbridgeStatus();
    jstring result = (*env)->NewStringUTF(env, s ? s : "");
    if (s) wgbridgeFree(s);
    return result;
}
