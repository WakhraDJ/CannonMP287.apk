#ifndef PIXMA_IO_ANDROID_H
#define PIXMA_IO_ANDROID_H
#include <jni.h>
/* Register the Kotlin UsbTransport and the device's USB ids. Returns 0 or a PIXMA_E* code. */
int pixma_android_set_transport (JNIEnv *env, jobject transport,
                                 unsigned vid, unsigned pid, const char *serial);
void pixma_android_clear_transport (JNIEnv *env);
#endif
