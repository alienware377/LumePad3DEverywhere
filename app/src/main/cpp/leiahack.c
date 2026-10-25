// Calls a C++ vtable slot on CNSDK's ThreadedInterlacer.
//
// SetInterlaceMode has no JNI export, but the Java `Interlacer.cxxPtr` field points at a holder
// whose first qword is the ThreadedInterlacer* (every SDK JNI stub does `ldr x0,[x0]` on it).
// Slot 7 (+0x38) is SetInterlaceMode in libleiaSDK.so 0.6.167. Passing the slot from Kotlin keeps
// the build-specific magic number in one documented place.
//
// The callee validates that it runs on the interlacer's own thread and throws a C++ exception if
// not: that would abort the process, so Kotlin dispatches this via GLSurfaceView.queueEvent().
// Everything here is defensively null-checked; a bad pointer would segfault, not throw.

#include <jni.h>
#include <stddef.h>

JNIEXPORT jint JNICALL
Java_com_lume3d_everywhere_LeiaHack_nSetInterlaceMode(JNIEnv *env, jclass clazz,
                                                     jlong cxxPtr, jint slot, jint mode) {
    (void) env; (void) clazz;
    if (cxxPtr == 0 || slot < 0 || slot > 64) return -1;

    // holder -> ThreadedInterlacer*
    void **holder = (void **) (intptr_t) cxxPtr;
    if (holder == NULL) return -2;
    void *obj = holder[0];
    if (obj == NULL) return -3;

    // obj -> vtable -> slot
    void ***vt = (void ***) obj;
    void **vtable = vt[0];
    if (vtable == NULL) return -4;
    void *fn = vtable[slot];
    if (fn == NULL) return -5;

    ((void (*)(void *, int)) fn)(obj, mode);
    return 0;
}
