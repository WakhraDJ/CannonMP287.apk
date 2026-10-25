# Printing — plan and what is needed

Why it is separate: SANE only covers *scanning*. Printing on the MP280 family uses Canon's own USB print
protocol (the printer-class interface, shown to Windows as `USBPRINT\CanonMP280_series…`). No open
implementation is included in the SANE source, so it has to be learned from Gutenprint's Canon code and/or
from a real capture of the Windows driver talking to your printer.

## Target design
An Android `PrintService` so printing works from any app (Chrome, Photos, Docs):
1. Android hands the service a PDF for each job.
2. Render pages with `PdfRenderer` to bitmaps at the printer's resolution.
3. Convert rows to Canon's raster format (colour planes, compression, page start/end commands).
4. Send over the printer-class USB bulk-OUT endpoint, read status over bulk-IN.
Steps 3–4 are the unknown part.

## What I need from you (after the E05 error is fixed and a Windows test page prints)
**Option A — Gutenprint source:** attach the Gutenprint source tarball; I will check its Canon support for the MP280 family.

**Option B — USB capture of a real print job (true reverse engineering):**
1. Install Wireshark and tick **USBPcap** in the installer (reboot if asked).
2. Open Wireshark, choose the **USBPcap** interface that the printer is on (the one that shows traffic when you print).
3. Start capture, print one Windows test page, stop capture.
4. Repeat for a page with solid black and solid colour blocks (e.g. a small test image).
5. Save each as `.pcapng` and attach them.
I will decode the commands from those files. Do not capture while printing documents with private content.
