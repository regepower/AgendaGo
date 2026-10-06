#!/usr/bin/env bash
# Size lab: builds the release APK in several variants and reports the sizes.
# Each variant starts from a clean checkout; usage: size-lab.sh <phase>
set -u
PHASE="$1"
OUT="$RUNNER_TEMP/size-lab.txt"
touch "$OUT"

measure() { # name
  local apk; apk=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
  if [ -z "$apk" ]; then echo "$1 FAILED" >> "$OUT"; return; fi
  local total dex arsc res meta
  total=$(stat -c %s "$apk")
  dex=$(unzip -lv "$apk" | awk '$8 ~ /\.dex$/ {s+=$3} END {print s+0}')
  arsc=$(unzip -lv "$apk" | awk '$8=="resources.arsc" {print $3}')
  res=$(unzip -lv "$apk" | awk '$8 ~ /^res\// {s+=$3} END {print s+0}')
  meta=$(unzip -lv "$apk" | awk '$8 ~ /^META-INF\// {s+=$3} END {print s+0}')
  # Signing block = file size minus all zip entries (local headers etc. are small and constant).
  printf '%-14s total=%7d dex=%6d arsc=%6d res=%6d meta=%5d\n' "$1" "$total" "$dex" "$arsc" "$res" "$meta" >> "$OUT"
}

build() { # name
  rm -rf app/build
  if gradle --no-daemon -q assembleRelease > "$RUNNER_TEMP/$1.log" 2>&1; then measure "$1"; else
    echo "$1 FAILED: $(grep -m2 -E '^e: |What went wrong' -A2 "$RUNNER_TEMP/$1.log" | tr '\n' ' ' | cut -c1-200)" >> "$OUT"; fi
  git checkout -q -- . ; git clean -fdq -- app gradle.properties 2>/dev/null || true
}

rules() { # append keep/optimisation rules and register the file
  printf '%s\n' "$@" >> app/proguard-rules.pro
  python3 - <<'PY'
p='app/build.gradle.kts'; s=open(p).read()
s=s.replace('proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))','proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")')
open(p,'w').write(s)
PY
}

kotlinArgs() {
  python3 - "$@" <<'PY'
import sys
p='app/build.gradle.kts'; s=open(p).read()
a='jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)'
args=', '.join('"%s"' % x for x in sys.argv[1:])
s=s.replace(a, a + '\n            freeCompilerArgs.addAll(' + args + ')')
open(p,'w').write(s)
PY
}

agp() { sed -i "s/id(\"com.android.application\") version \"[0-9.]*\"/id(\"com.android.application\") version \"$1\"/" build.gradle.kts; }

if [ "$PHASE" = 1-base-only ]; then
  build base
elif [ "$PHASE" = 1 ]; then
  build base
  printf '\nandroid.signingConfigs.all { enableV3Signing = false }\n' >> app/build.gradle.kts; build no-v3-sign
  rules '-assumenosideeffects class android.util.Log { public static int w(...); public static int d(...); public static int i(...); public static int v(...); public static int e(...); }'; build no-log
  rules '-overloadaggressively'; build overload
  rules '-allowaccessmodification' '-mergeinterfacesaggressively'; build aggressive
  kotlinArgs -Xstring-concat=inline; build str-inline
  kotlinArgs -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions; build no-assert
elif [ "$PHASE" = 3 ]; then
  # Combination of what helped in phase 1, then aapt2 post-processing of that APK.
  rules '-assumenosideeffects class android.util.Log { public static int w(...); public static int d(...); public static int i(...); public static int v(...); public static int e(...); }'
  kotlinArgs -Xstring-concat=inline -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions
  rm -rf app/build
  gradle --no-daemon -q assembleRelease > "$RUNNER_TEMP/combined.log" 2>&1 || { echo "combined FAILED" >> "$OUT"; cat "$OUT"; exit 0; }
  measure combined
  APK=$(ls app/build/outputs/apk/release/*.apk | head -1); cp "$APK" "$RUNNER_TEMP/combined.apk"
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  # dex content by package (defined classes only), top entries
  AA=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/apkanalyzer | head -1)
  "$AA" dex packages --defined-only "$RUNNER_TEMP/combined.apk" > "$RUNNER_TEMP/dex.txt" 2>/dev/null || true
  echo "--- dex packages (defined bytes) ---" >> "$OUT"
  awk '$1=="P" {print $4, $NF}' "$RUNNER_TEMP/dex.txt" | awk -F' ' '{n=split($2,a,"."); k=a[1]; if(n>1)k=k"."a[2]; if(n>2)k=k"."a[3]; s[k]+=$1} END {for(k in s) print s[k], k}' | sort -rn | head -12 >> "$OUT"
  for opt in "--shorten-resource-paths" "--collapse-resource-names" "--enable-sparse-encoding" "--collapse-resource-names --shorten-resource-paths --enable-sparse-encoding"; do
    name="aapt2[$(echo $opt | sed 's/--//g; s/resource-//g; s/enable-//g; s/ /+/g')]"
    rm -f "$RUNNER_TEMP/opt.apk" "$RUNNER_TEMP/al.apk" "$RUNNER_TEMP/signed.apk"
    if "$BT/aapt2" optimize $opt -o "$RUNNER_TEMP/opt.apk" "$RUNNER_TEMP/combined.apk" > "$RUNNER_TEMP/aapt.log" 2>&1 \
      && "$BT/zipalign" -P 16 -f 4 "$RUNNER_TEMP/opt.apk" "$RUNNER_TEMP/al.apk" \
      && "$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-pass env:KEYSTORE_PASSWORD --out "$RUNNER_TEMP/signed.apk" "$RUNNER_TEMP/al.apk" \
      && "$BT/apksigner" verify "$RUNNER_TEMP/signed.apk"; then
      t=$(stat -c %s "$RUNNER_TEMP/signed.apk"); a=$(unzip -lv "$RUNNER_TEMP/signed.apk" | awk '$8=="resources.arsc" {print $3}')
      printf '%-14s total=%7d arsc=%6d\n' "$name" "$t" "$a" >> "$OUT"
    else
      echo "$name FAILED: $(head -c 200 "$RUNNER_TEMP/aapt.log")" >> "$OUT"
    fi
  done
  git checkout -q -- . ; git clean -fdq -- app 2>/dev/null || true
else
  agp 8.13.0; build agp-8.13
  agp 8.13.0; echo 'android.r8.optimizedResourceShrinking=true' >> gradle.properties; build agp813-optres
fi
cat "$OUT"
