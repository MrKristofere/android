ogg, opus and opusfile vendored from the xiph repositories at exactly the commits upstream
Telegram 12.10.1 pins as submodules:

  ogg       https://github.com/xiph/ogg.git       be05b13e98b048f0b5a0f5fa8ce514d56db5f822
  opus      https://github.com/xiph/opus.git      22244de5a79bd1d6d623c32e72bf1954b56235be
  opusfile  https://github.com/xiph/opusfile.git  a55c164e9891a9326188b7d4d216ec9a88373739

Through 12.9.x these three directories were empty submodule mount points and the app built
opus from its own copy at `jni/opus`. 12.10's CMakeLists compiles the sources straight out of
third_party/xiph, so the real trees have to be here.

Novagram vendors them rather than adding submodules, so the repo stays self-contained.
To refresh, re-archive whatever commits upstream pins.
