#!/bin/bash
# Builds mod-claudebridge-0.1.litemod. JDK 25 can only target Java 8, but FML's ASM 4.0 rejects class
# version 52, so the class files are re-stamped as version 51 (Java 7) - the code uses no Java 8 features.
set -e
cd "$(dirname "$0")"
P="$HOME/Library/Application Support/PrismLauncher"
I="$P/instances/Lycodon-Client(1)/minecraft"
LW="$P/libraries/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar"
rm -rf build && mkdir build
"$P/java/java-runtime-epsilon/bin/javac" --release 8 -nowarn -Xlint:-options -proc:none \
  -cp "$I/bin/minecraft.jar:$I/mods/mod-macros-0.9.8.litemod:$I/mods/computer-craft-1.5.zip:$LW" -d build src/LiteModClaudeBridge.java
for f in build/*.class; do printf '\x00\x33' | dd of="$f" bs=1 seek=6 conv=notrunc 2>/dev/null; done
printf '1.4.7' > build/version.txt
rm -f mod-claudebridge-0.1.litemod
(cd build && zip -q -r ../mod-claudebridge-0.1.litemod .)
[ "$1" = install ] && cp mod-claudebridge-0.1.litemod "$I/mods/"
echo built
