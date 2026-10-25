/* Replacement for sanei_debug.h: routes pixma's DBG() to Android logcat. */
#ifndef CANONSCAN_SANEI_DEBUG_H
#define CANONSCAN_SANEI_DEBUG_H
#ifdef __cplusplus
extern "C" {
#endif
void canonscan_dbg(int level, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
#ifdef __cplusplus
}
#endif
#ifndef DBG
#define DBG canonscan_dbg
#endif
#endif
