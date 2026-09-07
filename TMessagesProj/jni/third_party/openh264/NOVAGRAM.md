Vendored from https://github.com/cisco/openh264 at commit
652bdb7719f30b52b08e506645a7322ff1b2cc6f -- the commit upstream Telegram 12.10.1 pins.

12.10 links openh264 as a prebuilt static library (jni/prebuild/<abi>/libopenh264.a), so
nothing here is compiled; the tree is present for its headers. WebRTC's h264_encoder_impl.h
now includes "third_party/openh264/codec/api/wels/codec_app_def.h", and the copy we carried
through 12.9 had the older layout (an extra src/ level, and api/svc instead of api/wels).

Novagram vendors this rather than adding a submodule, so the repo stays self-contained.
