Upstream ships tlottie as a git submodule (https://github.com/dkaraush/tlottie), but the
build only ever consumes two things from it: this header and the prebuilt Rust static
library at `jni/prebuild/<abi>/libtlottie.a`, which is committed in the tree.

Novagram keeps the header vendored instead of adding a submodule, so the repo stays
self-contained and a clone needs no extra fetch step. Pinned to upstream's submodule
commit 3ce946c9ede5ece8beead2edd9beab68718d990e (Telegram 12.10.1). If the prebuilt .a is
ever refreshed from a newer tlottie, refresh this header from the same commit.
