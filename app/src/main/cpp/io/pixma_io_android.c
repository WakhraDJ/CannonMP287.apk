/*
 * Android USB transport for the SANE pixma core.
 *
 * Replaces backend/pixma/pixma_io_sanei.c (which used sanei_usb/libusb).
 * All USB traffic is delegated to a Kotlin object (UsbTransport) through JNI:
 *   int bulkWrite(byte[] buf, int len, int timeoutMs)
 *   int bulkRead (byte[] buf, int len, int timeoutMs)
 *   int interruptRead(byte[] buf, int len, int timeoutMs)
 * Each returns the number of bytes transferred, or a negative value on
 * failure/timeout (UsbDeviceConnection.bulkTransfer semantics).
 *
 * Based on SANE's pixma_io_sanei.c; SANE is GPL-2.0-or-later with the SANE
 * linking exception. This file is therefore distributed under the same terms.
 */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <jni.h>

#include "../include/sane/config.h"
#include "../pixma/pixma_rename.h"
#include "../pixma/pixma_common.h"
#include "../pixma/pixma_io.h"
#include "../include/sane/sane.h"
#include "pixma_io_android.h"

#define UNUSED(v) (void) (v)

struct pixma_io_t
{
  int open;
};

static JavaVM *g_vm = NULL;
static jobject g_transport = NULL;      /* global ref to UsbTransport */
static jmethodID m_bulk_write, m_bulk_read, m_int_read;
static unsigned g_vid, g_pid;
static char g_serial[PIXMA_MAX_ID_LEN + 1];
static const pixma_config_t *g_cfg = NULL;
static unsigned g_nscanners = 0;
static pixma_io_t g_io;

int
pixma_android_set_transport (JNIEnv * env, jobject transport,
                             unsigned vid, unsigned pid, const char *serial)
{
  jclass cls;

  pixma_android_clear_transport (env);
  if ((*env)->GetJavaVM (env, &g_vm) != 0)
    return PIXMA_EIO;
  g_transport = (*env)->NewGlobalRef (env, transport);
  if (!g_transport)
    return PIXMA_ENOMEM;
  cls = (*env)->GetObjectClass (env, transport);
  m_bulk_write = (*env)->GetMethodID (env, cls, "bulkWrite", "([BII)I");
  m_bulk_read = (*env)->GetMethodID (env, cls, "bulkRead", "([BII)I");
  m_int_read = (*env)->GetMethodID (env, cls, "interruptRead", "([BII)I");
  (*env)->DeleteLocalRef (env, cls);
  if (!m_bulk_write || !m_bulk_read || !m_int_read)
    {
      (*env)->ExceptionClear (env);
      pixma_android_clear_transport (env);
      return PIXMA_EINVAL;
    }
  g_vid = vid;
  g_pid = pid;
  snprintf (g_serial, sizeof (g_serial), "%04X%04X_%s", vid & 0xffff,
            pid & 0xffff, (serial && *serial) ? serial : "0");
  return 0;
}

void
pixma_android_clear_transport (JNIEnv * env)
{
  if (g_transport && env)
    (*env)->DeleteGlobalRef (env, g_transport);
  g_transport = NULL;
  g_cfg = NULL;
  g_nscanners = 0;
}

static JNIEnv *
get_env (void)
{
  JNIEnv *env = NULL;
  if (!g_vm)
    return NULL;
  if ((*g_vm)->GetEnv (g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
    return NULL;                /* calls must come from a thread attached by Java */
  return env;
}

/* Shared helper for the three transfer kinds. dir_write: 1 = host->device. */
static int
transfer (jmethodID mid, void *buf, unsigned len, int timeout_ms, int dir_write)
{
  JNIEnv *env = get_env ();
  jbyteArray arr;
  jint n;

  if (!env || !g_transport)
    return PIXMA_EIO;
  arr = (*env)->NewByteArray (env, (jsize) len);
  if (!arr)
    return PIXMA_ENOMEM;
  if (dir_write)
    (*env)->SetByteArrayRegion (env, arr, 0, (jsize) len, (const jbyte *) buf);
  n = (*env)->CallIntMethod (env, g_transport, mid, arr, (jint) len,
                             (jint) timeout_ms);
  if ((*env)->ExceptionCheck (env))
    {
      (*env)->ExceptionClear (env);
      n = -1;
    }
  if (!dir_write && n > 0)
    (*env)->GetByteArrayRegion (env, arr, 0, n, (jbyte *) buf);
  (*env)->DeleteLocalRef (env, arr);
  return (int) n;
}

int
pixma_io_init (void)
{
  g_nscanners = 0;
  return 0;
}

void
pixma_io_cleanup (void)
{
  g_io.open = 0;
  g_nscanners = 0;
}

unsigned
pixma_collect_devices (const char **conf_devices,
                       const struct pixma_config_t *const pixma_devices[],
                       SANE_Bool local_only)
{
  unsigned i;
  const struct pixma_config_t *cfg;

  UNUSED (conf_devices);
  UNUSED (local_only);
  g_cfg = NULL;
  g_nscanners = 0;
  if (!g_transport)
    return 0;
  for (i = 0; pixma_devices[i] && !g_cfg; i++)
    for (cfg = pixma_devices[i]; cfg->name; cfg++)
      if (cfg->vid == g_vid && cfg->pid == g_pid)
        {
          g_cfg = cfg;
          g_nscanners = 1;
          break;
        }
  return g_nscanners;
}

const pixma_config_t *
pixma_get_device_config (unsigned devnr)
{
  return (devnr == 0 && g_nscanners) ? g_cfg : NULL;
}

const char *
pixma_get_device_id (unsigned devnr)
{
  return (devnr == 0 && g_nscanners) ? g_serial : NULL;
}

int
pixma_connect (unsigned devnr, pixma_io_t ** handle)
{
  *handle = NULL;
  if (devnr != 0 || !g_nscanners || !g_transport)
    return PIXMA_EINVAL;
  g_io.open = 1;
  *handle = &g_io;
  return 0;
}

void
pixma_disconnect (pixma_io_t * io)
{
  if (io)
    io->open = 0;
}

int
pixma_activate (pixma_io_t * io)
{
  UNUSED (io);
  return 0;
}

int
pixma_deactivate (pixma_io_t * io)
{
  UNUSED (io);
  return 0;
}

int
pixma_reset_device (pixma_io_t * io)
{
  UNUSED (io);
  return PIXMA_ENOTSUP;
}

int
pixma_write (pixma_io_t * io, const void *cmd, unsigned len)
{
  int n;
  UNUSED (io);
  n = transfer (m_bulk_write, (void *) cmd, len, PIXMA_BULKOUT_TIMEOUT, 1);
  if (n < 0)
    n = (n == PIXMA_ENOMEM) ? n : PIXMA_ETIMEDOUT;
  else if ((unsigned) n != len)
    {
      PDBG (pixma_dbg (1, "WARNING:pixma_write(): count(%d) != len(%u)\n", n, len));
      n = PIXMA_EIO;
    }
  PDBG (pixma_dump (10, "OUT ", cmd, n, len, 128));
  return n;
}

int
pixma_read (pixma_io_t * io, void *buf, unsigned size)
{
  int n;
  UNUSED (io);
  n = transfer (m_bulk_read, buf, size, PIXMA_BULKIN_TIMEOUT, 0);
  if (n < 0)
    n = (n == PIXMA_ENOMEM) ? n : PIXMA_ETIMEDOUT;
  PDBG (pixma_dump (10, "IN  ", buf, n, -1, 128));
  return n;
}

int
pixma_wait_interrupt (pixma_io_t * io, void *buf, unsigned size, int timeout)
{
  int n;
  UNUSED (io);
  if (timeout < 0)
    timeout = INT_MAX;
  else if (timeout < 100)
    timeout = 100;
  n = transfer (m_int_read, buf, size, timeout, 0);
  if (n < 0)
    n = PIXMA_ETIMEDOUT;
  if (n != PIXMA_ETIMEDOUT)
    PDBG (pixma_dump (10, "INTR", buf, n, -1, -1));
  return n;
}

int
pixma_set_interrupt_mode (pixma_io_t * s, int background)
{
  UNUSED (s);
  return (background) ? PIXMA_ENOTSUP : 0;
}
