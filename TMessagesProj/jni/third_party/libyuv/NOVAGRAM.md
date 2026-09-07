Vendored from https://chromium.googlesource.com/libyuv/libyuv at commit
28ce69c2744a6aafdb58564e7b884aec3f66be5f -- the commit upstream Telegram 12.10.1 pins as
its `TMessagesProj/jni/third_party/libyuv` submodule.

Why the full tree and not the handful of sources we used to carry: through 12.9.x the app's
own CMakeLists listed libyuv's .cc files one by one, so a partial copy was enough. 12.10
switched to `add_subdirectory(third_party/libyuv)` and links libyuv's own `yuv` target, so
its CMakeLists.txt and the whole source tree have to be present.

Novagram keeps this vendored rather than adding a submodule, so the repo stays
self-contained. To refresh, re-archive the commit upstream pins.
