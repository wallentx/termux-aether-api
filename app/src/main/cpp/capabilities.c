#include <errno.h>
#include <jni.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <unistd.h>
#ifdef __aarch64__
#include <asm/hwcap.h>
#endif

JNIEXPORT jlongArray JNICALL Java_com_termux_api_apis_CapabilitiesCpu_nativeProbe(
        JNIEnv *env, jclass clazz) {
    (void) clazz;
#ifdef __aarch64__
    // Layout: HWCAP, HWCAP2, errno1, errno2, SVE VL, SVE errno, SME VL, SME errno, page size.
    jlong values[9] = {0, 0, 0, 0, -1, 0, -1, 0, 0};
    errno = 0;
    values[0] = (jlong) getauxval(AT_HWCAP);
    values[2] = errno;
    errno = 0;
    values[1] = (jlong) getauxval(AT_HWCAP2);
    values[3] = errno;
    if (!values[2] && (values[0] & HWCAP_SVE)) {
        errno = 0;
        values[4] = prctl(PR_SVE_GET_VL, 0L, 0L, 0L, 0L);
        values[5] = values[4] < 0 ? errno : 0;
    }
    if (!values[3] && (values[1] & HWCAP2_SME)) {
        errno = 0;
        values[6] = prctl(PR_SME_GET_VL, 0L, 0L, 0L, 0L);
        values[7] = values[6] < 0 ? errno : 0;
    }
    values[8] = sysconf(_SC_PAGESIZE);
    jlongArray result = (*env)->NewLongArray(env, 9);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 9, values);
    return result;
#else
    (void) env;
    return NULL;
#endif
}
