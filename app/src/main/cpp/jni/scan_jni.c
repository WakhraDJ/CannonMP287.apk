/*
 * JNI entry points for com.canonscan.NativeScanner.
 * Drives the SANE pixma core (flatbed scan) over the Kotlin UsbTransport.
 * Output is written as raw interleaved 8-bit rows (RGB or gray) to a file so
 * large scans never have to fit in the Java heap.
 *
 * GPL-2.0-or-later (links SANE pixma code).
 */
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "../include/sane/config.h"
#include "../include/sane/sane.h"
#include "../pixma/pixma_rename.h"
#include "../pixma/pixma.h"
#include "../pixma/pixma_common.h"
#include "../io/pixma_io_android.h"

#define TAG "CanonScan"

void
canonscan_dbg (int level, const char *fmt, ...)
{
  va_list ap;
  va_start (ap, fmt);
#ifdef __ANDROID__
  __android_log_vprint (level <= 1 ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, TAG, fmt, ap);
#else
  vfprintf (stderr, fmt, ap);
#endif
  va_end (ap);
}

static pixma_t *volatile g_scanner = NULL;

/* out[0..5] = width, height(rows actually received), channels, lineSize, errorCode, expectedHeight */
static void
set_out (JNIEnv * env, jintArray out, int w, int h, int ch, int line, int err, int exp_h)
{
  jint v[6];
  v[0] = w; v[1] = h; v[2] = ch; v[3] = line; v[4] = err; v[5] = exp_h;
  (*env)->SetIntArrayRegion (env, out, 0, 6, v);
}

JNIEXPORT jstring JNICALL
Java_com_canonscan_NativeScanner_strerror (JNIEnv * env, jobject thiz, jint code)
{
  (void) thiz;
  return (*env)->NewStringUTF (env, pixma_strerror (code));
}

JNIEXPORT void JNICALL
Java_com_canonscan_NativeScanner_cancel (JNIEnv * env, jobject thiz)
{
  pixma_t *s = g_scanner;
  (void) env; (void) thiz;
  if (s)
    pixma_cancel (s);
}

/*
 * Returns 0 on success, otherwise a PIXMA_E* (negative) error code.
 * mode: 0 = color, 1 = gray.  Area in millimetres; w_mm/h_mm <= 0 means full bed.
 */
JNIEXPORT jint JNICALL
Java_com_canonscan_NativeScanner_scan (JNIEnv * env, jobject thiz,
                                       jobject transport, jint vid, jint pid,
                                       jstring jserial, jint dpi, jint mode,
                                       jdouble x_mm, jdouble y_mm,
                                       jdouble w_mm, jdouble h_mm,
                                       jstring jpath, jint debug_level,
                                       jintArray out)
{
  const char *serial = jserial ? (*env)->GetStringUTFChars (env, jserial, NULL) : NULL;
  const char *path = (*env)->GetStringUTFChars (env, jpath, NULL);
  pixma_t *s = NULL;
  pixma_scan_param_t sp;
  const pixma_config_t *cfg;
  uint8_t *gamma_tbl = NULL, *buf = NULL;
  FILE *fp = NULL;
  int err, n, rc = 0;
  uint64_t got = 0;
  unsigned x1, y1, x2, y2;
  unsigned table_size;
  (void) thiz;

  set_out (env, out, 0, 0, 0, 0, 0, 0);
  pixma_set_debug_level (debug_level);

  err = pixma_android_set_transport (env, transport, (unsigned) vid, (unsigned) pid, serial);
  if (err < 0) { rc = err; goto done; }
  err = pixma_init ();
  if (err < 0) { rc = err; goto done; }
  if (pixma_find_scanners (NULL, SANE_TRUE) <= 0) { rc = PIXMA_ENODEV; goto cleanup; }
  err = pixma_open (0, &s);
  if (err < 0) { rc = err; goto cleanup; }
  g_scanner = s;
  cfg = pixma_get_config (s);

  memset (&sp, 0, sizeof (sp));
  sp.channels = (mode == 0) ? 3 : 1;
  sp.depth = 8;
  sp.xdpi = sp.ydpi = (unsigned) dpi;

  if (w_mm <= 0) { x_mm = 0; w_mm = cfg->width / 75.0 * 25.4; }
  if (h_mm <= 0) { y_mm = 0; h_mm = cfg->height / 75.0 * 25.4; }
#define PIXEL(mm, d) ((unsigned) (((mm) / 25.4 * (d)) + 0.5))
  x1 = PIXEL (x_mm, sp.xdpi);          x2 = PIXEL (x_mm + w_mm, sp.xdpi);
  y1 = PIXEL (y_mm, sp.ydpi);          y2 = PIXEL (y_mm + h_mm, sp.ydpi);
#undef PIXEL
  sp.x = x1; sp.y = y1;
  sp.w = (x2 > x1) ? x2 - x1 : 1;
  sp.h = (y2 > y1) ? y2 - y1 : 1;

  /* Same defaults the SANE frontend layer uses (pixma.c). */
  sp.gamma = 2.2;
  table_size = (cfg->cap & PIXMA_CAP_GT_4096) ? 4096 : 1024;
  gamma_tbl = (uint8_t *) calloc (1, 4096);
  if (!gamma_tbl) { rc = PIXMA_ENOMEM; goto closeit; }
  pixma_fill_gamma_table (sp.gamma, gamma_tbl, table_size);
  sp.gamma_table = gamma_tbl;
  sp.source = PIXMA_SOURCE_FLATBED;
  sp.mode = (mode == 0) ? PIXMA_SCAN_MODE_COLOR : PIXMA_SCAN_MODE_GRAY;
  sp.threshold = (unsigned) (2.55 * 50);
  sp.threshold_curve = 0;
  sp.calibrate = PIXMA_CALIBRATE_ONCE;

  err = pixma_check_scan_param (s, &sp);
  if (err < 0) { rc = err; goto closeit; }

  buf = (uint8_t *) malloc (64 * 1024);
  fp = fopen (path, "wb");
  if (!buf || !fp) { rc = PIXMA_ENOMEM; goto closeit; }

  err = pixma_scan (s, &sp);
  if (err < 0) { rc = err; goto closeit; }
  while ((n = pixma_read_image (s, buf, 64 * 1024)) > 0)
    {
      if (fwrite (buf, 1, (size_t) n, fp) != (size_t) n) { rc = PIXMA_EIO; pixma_cancel (s); break; }
      got += (uint64_t) n;
    }
  if (n < 0 && rc == 0)
    rc = n;                     /* includes PIXMA_ECANCELED */
  set_out (env, out, (int) sp.w, (int) (sp.line_size ? got / sp.line_size : 0),
           (int) sp.channels, (int) sp.line_size, rc, (int) sp.h);

closeit:
  g_scanner = NULL;
  pixma_close (s);
cleanup:
  pixma_cleanup ();
done:
  pixma_android_clear_transport (env);
  if (fp) fclose (fp);
  free (buf);
  free (gamma_tbl);
  if (serial) (*env)->ReleaseStringUTFChars (env, jserial, serial);
  (*env)->ReleaseStringUTFChars (env, jpath, path);
  {
    /* make sure error is visible even if we bailed before set_out */
    jint cur[6];
    (*env)->GetIntArrayRegion (env, out, 0, 6, cur);
    if (rc != 0 && cur[4] == 0)
      { cur[4] = rc; (*env)->SetIntArrayRegion (env, out, 0, 6, cur); }
  }
  return rc;
}
