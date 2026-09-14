#!/usr/bin/env python3
"""
Gramify setup — DrKLO/Telegram ke cloned source me Gramify features patch kar deta hai.

Usage:
    python3 gramify_setup.py --repo /path/to/Telegram \
        --app-id 1234567 --app-hash abcdef0123456789abcdef0123456789 \
        [--package com.gramify.messenger] [--name Gramify] [--fast] [--dry-run]

Kya karta hai (sab idempotent — dobara chalane par duplicate nahi hoga):
  1. Feature files copy: payload/TMessagesProj/... -> repo/TMessagesProj/...
  2. Voice hook:      org/webrtc/audio/WebRtcAudioRecord.java me outgoing-PCM hook
  3. Settings rows:   SettingsActivity ke fillItems() me 2 naye rows
  4. Settings click:  onClick() ke switch me case 101 / 102
  5. API keys:        BuildVars.APP_ID / APP_HASH
  6. App name/packadge/version: gradle.properties + AppName string
  7. (--fast):        ndk debugSymbolLevel FULL -> NONE (CI build tez + kam RAM)

Har patch ke baad verify bhi karta hai; kuch match na ho to saaf error deta hai
(guess karke file kharab nahi karta).
"""
import argparse
import copy
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.dirname(HERE) if os.path.basename(HERE) == "patches" else HERE

# Marker: is folder me ye path hona chahiye tabhi wo sahi "payload" hai.
PAYLOAD_MARKER = os.path.join("TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "gramify")


def find_payload(script_dir, explicit=None):
    """
    `payload/` folder kahin bhi ho (root me, gramify/ ke andar, ya 3 level upar),
    khud dhoondh leta hai. Isse GitHub pe folder nesting wali dikkat khatam ho jati hai.
    """
    candidates = []
    if explicit:
        candidates.append(explicit)
    base = os.path.dirname(script_dir) if os.path.basename(script_dir) == "patches" else script_dir
    candidates.append(os.path.join(base, "payload"))
    candidates.append(os.path.join(script_dir, "payload"))
    # upar ki taraf bhi dekho (nested upload ke case me)
    d = script_dir
    for _ in range(5):
        candidates.append(os.path.join(d, "payload"))
        d = os.path.dirname(d)
    seen = set()
    for c in candidates:
        c = os.path.abspath(c)
        if c in seen:
            continue
        seen.add(c)
        if os.path.isdir(os.path.join(c, PAYLOAD_MARKER)):
            return c
    return None


PAYLOAD = None  # main() me set hota hai

# --------------------------------------------------------------------------- #
#  FLAT MODE
#  GitHub web upload me aksar folders flatten ho jaate hain (saari files ek hi
#  folder me aa jaati hain). Tab payload/ folder milta hi nahi. Isliye file ke
#  NAAM se uska asli Telegram destination pata karte hain:
FLAT_FILES = {
    "VoiceDsp.java":              "TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceDsp.java",
    "VoiceEnhancer.java":         "TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceEnhancer.java",
    "VoiceEnhancerFragment.java": "TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceEnhancerFragment.java",
    "GramifyMusicFragment.java":  "TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyMusicFragment.java",
    "GramifyPlayer.java":         "TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyPlayer.java",
    "SaavnApi.java":              "TMessagesProj/src/main/java/org/telegram/messenger/gramify/SaavnApi.java",
    "Song.java":                  "TMessagesProj/src/main/java/org/telegram/messenger/gramify/Song.java",
    "SongAdapter.java":           "TMessagesProj/src/main/java/org/telegram/messenger/gramify/SongAdapter.java",
    "Json.java":                  "TMessagesProj/src/main/java/org/telegram/messenger/gramify/Json.java",
    "CallMixer.java":             "TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMixer.java",
    "CallMusicCapture.java":      "TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMusicCapture.java",
    "CallMusicDecoder.java":      "TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMusicDecoder.java",
    "MusicShare.java":            "TMessagesProj/src/main/java/org/telegram/messenger/gramify/MusicShare.java",
    "GramifyBubble.java":         "TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyBubble.java",
    "GramifyBubbleService.java":  "TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyBubbleService.java",
    "DevgramBotPanel.java":       "TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBotPanel.java",
    "DevgramBotFragment.java":    "TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBotFragment.java",
    "DevgramBubbleService.java":  "TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBubbleService.java",
    "gramify_music.xml":          "TMessagesProj/src/main/res/drawable/gramify_music.xml",
    "gramify_mic.xml":            "TMessagesProj/src/main/res/drawable/gramify_mic.xml",
    "gramify_strings.xml":        "TMessagesProj/src/main/res/values/gramify_strings.xml",
}
# kit ki baaki files (test/demo/docs) — inhe repo me copy karne ki zaroorat nahi
FLAT_IGNORE = {"VoiceDspTest.java", "SaavnSmokeTest.java", "EnhanceWav.java",
               "Parcel.java", "Parcelable.java", "run_tests.sh"}


def flat_sources(start_dirs, max_up=3, max_depth=2):
    """
    Flatten kiye gaye kit ki files ko naam se dhoondhta hai.
    @return dict: filename -> absolute path (pehla match jeetta hai)
    """
    found = {}
    roots = []
    for start in start_dirs:
        if not start:
            continue
        base = os.path.abspath(start)
        roots.append(base)
        d = base
        for _ in range(max_up):
            d = os.path.dirname(d)
            if d and d != os.path.dirname(d):
                roots.append(d)
    seen = set()
    for root in roots:
        if root in seen or not os.path.isdir(root):
            continue
        seen.add(root)
        for dirpath, dirnames, filenames in os.walk(root):
            rel = os.path.relpath(dirpath, root)
            depth = 0 if rel == "." else rel.count(os.sep) + 1
            if depth >= max_depth:
                dirnames[:] = []
            dirnames[:] = [d for d in dirnames
                           if d not in (".git", "telegram", "node_modules", "build")]
            for f in filenames:
                if f in FLAT_FILES and f not in found:
                    p = os.path.join(dirpath, f)
                    if os.path.isfile(p):
                        found[f] = p
    return found

MARK_HOOK = "// >>> GRAMIFY VOICE HOOK"
MARK_ROW = "// >>> GRAMIFY SETTINGS ROWS"
MARK_CASE = "// >>> GRAMIFY SETTINGS CLICK"
MARK_BOOT = ">>> GRAMIFY BOOT HOOK"
MARK_CALL = ">>> GRAMIFY CALL HOOK"
MARK_MANIFEST = "<!-- >>> GRAMIFY BUBBLE -->"


class Fail(Exception):
    pass


def same_file(a, b):
    """dono files ka content same hai? (binary-safe — icons PNG hote hain)"""
    try:
        with open(a, "rb") as fa, open(b, "rb") as fb:
            return fa.read() == fb.read()
    except Exception:
        return False


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def patch_file(path, old, new, required=True, count=1, label=""):
    """Replaces `old` with `new` exactly `count` times. Idempotent-safe."""
    if not os.path.exists(path):
        if required:
            raise Fail("file nahi mila: %s" % path)
        return False
    text = read(path)
    if new.strip() and new.strip() in text and old not in text:
        print("   = already patched: %s" % label)
        return True
    found = text.count(old)
    if found != count:
        if required:
            raise Fail("anchor mila %d baar (expected %d) — %s\n  file: %s\n  anchor: %s"
                       % (found, count, label, path, old.strip()[:120]))
        print("   ! skip (%d/%d): %s" % (found, count, label))
        return False
    write(path, text.replace(old, new, count))
    print("   + patched: %s" % label)
    return True


def copy_payload(repo):
    """
    Feature files ko repo me sahi jagah pahunchata hai.
    Do modes:
      1) payload/ folder mila        -> poora tree copy
      2) payload/ nahi mila (flat)    -> file naam se destination map karke copy
    """
    if PAYLOAD and os.path.isdir(PAYLOAD):
        copied = 0
        for root, _dirs, files in os.walk(PAYLOAD):
            for name in files:
                src = os.path.join(root, name)
                rel = os.path.relpath(src, PAYLOAD)
                dst = os.path.join(repo, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                if os.path.exists(dst) and same_file(dst, src):
                    continue
                shutil.copy2(src, dst)
                copied += 1
        print("   + payload/ se %d files copy hui" % copied)
        return

    # ---- FLAT MODE ----
    srcs = flat_sources([HERE])
    if not srcs:
        raise Fail("payload/ folder bhi nahi mila aur flat kit ki files bhi nahi mili.\n"
                   "  Kit ki ye files repo me kahin honi chahiye (root pe ya kisi folder me):\n"
                   "  " + ", ".join(sorted(FLAT_FILES.keys())))
    missing = [f for f in FLAT_FILES if f not in srcs]
    copied = 0
    for name, src in srcs.items():
        dst = os.path.join(repo, FLAT_FILES[name])
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(dst) and same_file(dst, src):
            continue
        shutil.copy2(src, dst)
        copied += 1
        print("   + %-28s -> %s" % (name, FLAT_FILES[name].split("TMessagesProj/")[-1]))
    print("   + FLAT MODE: %d/%d files copy hui" % (len(srcs), len(FLAT_FILES)))
    if missing:
        raise Fail("ye files flat kit me nahi mili: %s" % ", ".join(missing))


# --------------------------------------------------------------------------- #
#  1. Copy feature files
def step_copy(repo):
    print("\n[1/8] Feature files (DSP + music search + UI)")
    copy_payload(repo)


# --------------------------------------------------------------------------- #
#  2. Voice hook
def step_voice_hook(repo):
    print("\n[2/8] Voice hook (outgoing call audio) — 3 mic paths")

    # Telegram 3 tareeke se mic padhta hai. teeno pe hook lagta hai,
    # jo hai wahi chalta hai; baaki chup-chaap ignore ho jaate hain.
    targets = [
        # (path, anchor, buffer var, byteCount expr, sampleRate expr, channels expr, label)
        #  -- anchors aur indentation TAB/space tolerant
        ("TMessagesProj/src/main/java/org/webrtc/voiceengine/WebRtcAudioRecord.java",
         "nativeDataIsRecorded(bytesRead, nativeAudioRecord);",
         "byteBuffer", "bytesRead", "requestedSampleRate", "requestedChannels", "microphoneMute",
         "PRIMARY — voiceengine/WebRtcAudioRecord (calls + group calls, native audio_record_jni.cc)"),
        ("TMessagesProj/src/main/java/org/webrtc/audio/WebRtcAudioRecord.java",
         "nativeDataIsRecorded(nativeAudioRecord, bytesRead, captureTimeNs);",
         "byteBuffer", "bytesRead", "audioRecord.getSampleRate()", "audioRecord.getChannelCount()", "microphoneMute",
         "Java-ADM WebRtcAudioRecord (PeerConnectionFactory path)"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/voip/AudioRecordJNI.java",
         "nativeCallback(buffer);",
         "buffer", "960 * 2", "48000", "1", "false",
         "voip/AudioRecordJNI (purana tgVoip path)"),
    ]

    patched = 0
    for rel, anchor, bufn, nbytes, rate, ch, mute, label in targets:
        path = os.path.join(repo, rel)
        if not os.path.exists(path):
            print("   - skip (file nahi mila): %s" % rel)
            continue
        text = read(path)
        if MARK_HOOK in text:
            print("   = already patched: %s" % label)
            patched += 1
            continue
        if anchor not in text:
            print("   ! anchor nahi mila, skip: %s" % label)
            continue

        # anchor ki indentation utha lo (tabs vs spaces)
        idx = text.index(anchor)
        line_start = text.rfind("\n", 0, idx) + 1
        indent = text[line_start:idx]

        hook = (
            indent + MARK_HOOK + "\n"
            + indent + "// Aapki awaz native encoder ko jane se pehle yahan se guzarti hai:\n"
            + indent + "//   (1) voice enhancer  (2) call me music mix\n"
            + indent + "// Dono OFF ho to kuch bhi nahi hota (sirf ek boolean check).\n"
            + indent + "try {\n"
            + indent + "  org.telegram.messenger.gramify.VoiceEnhancer.onOutgoingPcm(\n"
            + indent + "      " + bufn + ", " + nbytes + ", " + rate + ", " + ch + ",\n"
            + indent + "      " + mute + ");   // mute = true ho to gaana bhi band\n"
            + indent + "} catch (Throwable ignore) {\n"
            + indent + "}\n"
            + indent + "// <<< GRAMIFY VOICE HOOK\n"
        )
        text = text.replace(anchor, hook + indent + anchor, 1)
        write(path, text)
        patched += 1
        print("   + hook lag gaya: %s" % label)

    if patched == 0:
        raise Fail("koi bhi mic path patch nahi ho paaya — Telegram ka audio code badal gaya hai")


# --------------------------------------------------------------------------- #
#  2b. App boot + call-state hooks (taaki enhancer/music share bina Settings
#      kholne ke bhi chale, aur capture sirf call ke dauran chale)
def step_app_hooks(repo):
    print("\n[2b/8] App boot + call-state hooks")

    # --- (a) ApplicationLoader.onCreate -> settings load + music share restore ---
    al = os.path.join(repo, "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java")
    if os.path.exists(al):
        t = read(al)
        if MARK_BOOT in t:
            print("   = already patched: ApplicationLoader")
        else:
            anchor = "        applicationLoaderInstance = this;"
            if anchor not in t:
                print("   ! ApplicationLoader anchor nahi mila (skip)")
            else:
                body = (
                    "\n"
                    "        // " + MARK_BOOT + "\n"
                    "        // Gramify: call-audio settings app start pe load ho jaati hain,\n"
                    "        // taaki Settings kholne ki zaroorat na pade.\n"
                    "        try {\n"
                    "            org.telegram.messenger.gramify.VoiceEnhancer.load(this);\n"
                    "            org.telegram.messenger.gramify.MusicShare.restore(this);\n"
                    "            // bubble ON tha to wapas dikha do (permission ho to)\n"
                    "            if (org.telegram.messenger.gramify.GramifyBubbleService.isEnabled(this)\n"
                    "                    && org.telegram.messenger.gramify.GramifyBubbleService.canShow(this)) {\n"
                    "                org.telegram.messenger.gramify.GramifyBubbleService.show(this);\n"
                    "            }\n"
                    "        } catch (Throwable ignore) {\n"
                    "        }\n"
                )
                t = t.replace(anchor, anchor + body, 1)
                write(al, t)
                print("   + boot hook: ApplicationLoader.onCreate")
    else:
        print("   ! ApplicationLoader.java nahi mila (skip)")

    # --- (b) VoIPService.onCreate / onDestroy -> call state ---
    vs = os.path.join(repo, "TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java")
    if os.path.exists(vs):
        t = read(vs)
        if MARK_CALL in t:
            print("   = already patched: VoIPService")
        else:
            start_anchor = "\tpublic void onCreate() {\n\t\tsuper.onCreate();"
            end_anchor = "\tpublic void onDestroy() {"
            done = 0
            if start_anchor in t:
                t = t.replace(
                    start_anchor,
                    start_anchor + "\n"
                    "\t\t// " + MARK_CALL + " (start)\n"
                    "\t\t// Gramify: call shuru — music share (capture/decode) chalu karo\n"
                    "\t\ttry { org.telegram.messenger.gramify.MusicShare.setInCall(this, true); }\n"
                    "\t\tcatch (Throwable ignore) {}\n",
                    1)
                done += 1
            else:
                print("   ! VoIPService.onCreate anchor nahi mila")
            if end_anchor in t:
                t = t.replace(
                    end_anchor,
                    end_anchor + "\n"
                    "\t\t// " + MARK_CALL + " (stop)\n"
                    "\t\ttry { org.telegram.messenger.gramify.MusicShare.setInCall(this, false); }\n"
                    "\t\tcatch (Throwable ignore) {}\n",
                    1)
                done += 1
            else:
                print("   ! VoIPService.onDestroy anchor nahi mila")
            if done:
                write(vs, t)
                print("   + call-state hook: VoIPService (%d/2)" % done)
    else:
        print("   ! VoIPService.java nahi mila (skip)")


# --------------------------------------------------------------------------- #
#  2c. Manifest: floating bubble ke liye permission + service
def step_manifest(repo):
    print("\n[2c/8] Manifest (floating bubble)")

    perms = [("org/telegram/messenger/ApplicationLoader.java", None)]

    path = os.path.join(repo, "TMessagesProj/src/main/AndroidManifest.xml")
    if not os.path.exists(path):
        print("   ! AndroidManifest.xml nahi mila (skip)")
        return
    t = read(path)
    if MARK_MANIFEST in t:
        print("   = already patched: AndroidManifest")
        return

    # (a) permission — <application> tag se pehle daalo
    perm = '    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n'
    if "SYSTEM_ALERT_WINDOW" not in t:
        idx = t.find("    <application")
        if idx < 0:
            print("   ! <application> tag nahi mila")
            return
        t = t[:idx] + perm + t[idx:]
        print("   + permission: SYSTEM_ALERT_WINDOW (floating bubble)")
    else:
        print("   = permission pehle se hai")

    # (b) service — </application> se pehle daalo
    svc = ('    ' + '<!-- >>> GRAMIFY BUBBLE -->\n'
           '    <service\n'
           '        android:name="org.telegram.messenger.gramify.GramifyBubbleService"\n'
           '        android:exported="false"\n'
           '        android:stopWithTask="false" />\n'
           '    <service\n'
           '        android:name="org.telegram.messenger.gramify.DevgramBubbleService"\n'
           '        android:exported="false"\n'
           '        android:stopWithTask="false" />\n')
    if "GramifyBubbleService" not in t:
        idx = t.rfind("</application>")
        if idx < 0:
            print("   ! </application> nahi mila")
            return
        t = t[:idx] + svc + t[idx:]
        print("   + service: GramifyBubbleService + DevgramBubbleService (round bubble)")
    else:
        print("   = service pehle se hai")

    write(path, t)


# --------------------------------------------------------------------------- #
#  3. + 4. Settings UI
def step_settings(repo):
    print("\n[3/8] Settings list rows + click handlers")
    path = os.path.join(repo, "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java")
    text = read(path)

    if MARK_ROW not in text:
        anchor = ('        items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, '
                  'IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, '
                  'getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));')
        if text.count(anchor) != 1:
            raise Fail("Settings rows anchor mila %d baar (expected 1)" % text.count(anchor))
        rows = anchor + "\n" + (
            "        " + MARK_ROW + "\n"
            "        items.add(SettingCell.Factory.of(101, IconBackgroundColors.CYAN.top, "
            "IconBackgroundColors.CYAN.bottom, R.drawable.gramify_music, "
            "getString(R.string.gramify_settings_music), getString(R.string.gramify_settings_music_info)));\n"
            "        items.add(SettingCell.Factory.of(102, IconBackgroundColors.PURPLE.top, "
            "IconBackgroundColors.PURPLE.bottom, R.drawable.gramify_mic, "
            "getString(R.string.gramify_settings_voice), getString(R.string.gramify_settings_voice_info)));\n"
            "        items.add(SettingCell.Factory.of(103, IconBackgroundColors.GREEN.top, "
            "IconBackgroundColors.GREEN.bottom, R.drawable.gramify_mic, "
            "getString(R.string.devgram_bot_settings), getString(R.string.devgram_bot_settings_info)));"
        )
        text = text.replace(anchor, rows, 1)
        print("   + 3 rows add hue (Gramify Music / Gramify Voice / Devgram Bot)")
    else:
        print("   = rows already present")

    if MARK_CASE not in text:
        anchor = "        switch (item.id) {"
        if text.count(anchor) != 1:
            raise Fail("switch (item.id) anchor mila %d baar (expected 1) — %s"
                       % (text.count(anchor), path))
        cases = anchor + "\n" + (
            "            " + MARK_CASE + "\n"
            "            case 101:\n"
            "                presentFragment(new org.telegram.messenger.gramify.GramifyMusicFragment());\n"
            "                break;\n"
            "            case 102:\n"
            "                presentFragment(new org.telegram.messenger.gramify.VoiceEnhancerFragment());\n"
            "                break;\n"
            "            case 103:\n"
            "                presentFragment(new org.telegram.messenger.gramify.DevgramBotFragment());\n"
            "                break;"
        )
        text = text.replace(anchor, cases, 1)
        print("   + click cases (101/102/103) add hue")
    else:
        print("   = cases already present")

    write(path, text)


# --------------------------------------------------------------------------- #
#  5. API ID / HASH
def step_api_keys(repo, app_id, app_hash):
    print("\n[4/8] Telegram API ID / HASH")
    path = os.path.join(repo, "TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java")
    text = read(path)
    if app_id:
        text = re.sub(r"(public static int APP_ID = )-?\d+;", r"\g<1>%d;" % int(app_id), text, count=1)
    if app_hash:
        text = re.sub(r'(public static String APP_HASH = ")[^"]*(";)', r'\g<1>%s\g<2>' % app_hash, text, count=1)
    write(path, text)
    got_id = re.search(r"public static int APP_ID = (-?\d+);", text)
    got_hash = re.search(r'public static String APP_HASH = "([^"]*)";', text)
    print("   APP_ID   = %s" % (got_id.group(1) if got_id else "?"))
    print("   APP_HASH = %s..." % (got_hash.group(1)[:8] if got_hash else "?"))
    if got_id and got_id.group(1) in ("4", "-1"):
        print("   !! WARNING: default test ID (4) use ho raha hai. my.telegram.org se apna")
        print("      API ID/HASH banao aur --app-id/--app-hash ke sath dobara chalao,")
        print("      warna login me 'official test key' wali dikkat aa sakti hai.")


# --------------------------------------------------------------------------- #
#  6. Name / package / version
def step_branding(repo, package, name):
    print("\n[5/8] App naam / package / version")
    gp = os.path.join(repo, "gradle.properties")
    if not os.path.exists(gp):
        raise Fail("gradle.properties nahi mila")
    text = read(gp)

    if package:
        text = re.sub(r"^APP_PACKAGE=.*$", "APP_PACKAGE=%s" % package, text, count=1, flags=re.M)
        print("   APP_PACKAGE = %s" % package)
    if "-devgram" not in text:
        text = re.sub(r"^APP_VERSION_NAME=(.*?)(-gramify)?$", r"APP_VERSION_NAME=\1-devgram",
                      text, count=1, flags=re.M)
    m = re.search(r"^APP_VERSION_CODE=(\d+)$", text, flags=re.M)
    if m and "-devgram" not in read(gp):
        text = re.sub(r"^APP_VERSION_CODE=(\d+)$",
                      lambda mm: "APP_VERSION_CODE=%d" % (int(mm.group(1)) * 10 + 1), text, count=1, flags=re.M)
    write(gp, text)
    print("   %s" % re.search(r"^APP_VERSION_NAME=.*$", text, flags=re.M).group(0))

    # App label
    strings = os.path.join(repo, "TMessagesProj/src/main/res/values/strings.xml")
    if os.path.exists(strings):
        st = read(strings)
        if re.search(r'<string name="AppName">', st) and name:
            st = re.sub(r'(<string name="AppName">)[^<]*(</string>)', r"\g<1>%s\g<2>" % name, st, count=1)
            write(strings, st)
            print("   AppName string = %s" % name)


# --------------------------------------------------------------------------- #
#  7. Fast build (CI ke liye)
def step_fast(repo):
    print("\n[6b/8] Fast build mode (--fast)")
    path = os.path.join(repo, "TMessagesProj_App/build.gradle")
    if not os.path.exists(path):
        print("   ! build.gradle nahi mila, skip")
        return
    text = read(path)
    n = text.count("ndk.debugSymbolLevel = 'FULL'")
    text = text.replace("ndk.debugSymbolLevel = 'FULL'", "ndk.debugSymbolLevel = 'NONE'")
    write(path, text)
    print("   %d debugSymbolLevel FULL -> NONE (APK size/time kam)" % n)


# --------------------------------------------------------------------------- #
#  google-services.json  (BUILD FAIL hone ka #1 karan)
#
#  Google ka `com.google.gms.google-services` plugin applicationId se match
#  karta hai. Telegram ke json me sirf org.telegram.messenger* clients hote hain,
#  isliye naya package (com.gramify.messenger) daalte hi build is error se fail
#  ho jata hai:
#      No matching client found for package name 'com.gramify.messenger'
#  Fix: usi json me naye package ke liye client entries add kar do.
def step_google_services(repo, package):
    print("\n[6/8] google-services.json (package match)")
    if not package:
        print("   ! --package khaali hai, skip")
        return
    names = [package, package + ".beta", package + ".web"]
    # repo me jitni bhi google-services.json hain (HockeyApp/Huawei sab) — sab me daalo
    candidates = []
    for root, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "build", "src")]
        if "google-services.json" in files:
            candidates.append(os.path.join(root, "google-services.json"))
    touched = 0
    for path in sorted(candidates):
        rel = os.path.relpath(path, repo)
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print("   ! %s padha nahi ja saka: %s" % (rel, e))
            continue
        clients = data.get("client") or []
        if not clients:
            continue
        existing = set()
        for c in clients:
            ci = (c.get("client_info") or {}).get("android_client_info") or {}
            if ci.get("package_name"):
                existing.add(ci["package_name"])
        template = clients[0]
        added = []
        for nm in names:
            if nm in existing:
                continue
            c = copy.deepcopy(template)
            c.setdefault("client_info", {}).setdefault("android_client_info", {})
            c["client_info"]["android_client_info"]["package_name"] = nm
            clients.append(c)
            added.append(nm)
        if added:
            data["client"] = clients
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
            print("   + %s: %d client add hua -> %s" % (rel, len(added), ", ".join(added)))
            touched += 1
        else:
            print("   = %s: already theek hai" % rel)
    if touched == 0:
        print("   = kuch change nahi chahiye tha")


def step_verify(repo):
    print("\n[7/8] Verification")
    checks = [
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceDsp.java", "class VoiceDsp"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceEnhancer.java", "class VoiceEnhancer"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/VoiceEnhancerFragment.java", "class VoiceEnhancerFragment"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMixer.java", "class CallMixer"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMusicCapture.java", "class CallMusicCapture"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/CallMusicDecoder.java", "class CallMusicDecoder"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/MusicShare.java", "class MusicShare"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyBubble.java", "class GramifyBubble"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyBubbleService.java", "class GramifyBubbleService"),
        ("TMessagesProj/src/main/res/drawable/devgram_icon_fg.xml", "#CDCDCD"),
        ("TMessagesProj/src/main/res/drawable/devgram_icon_bg.xml", "#1A1A1A"),
        ("TMessagesProj/src/main/res/drawable/devgram_icon_mono.xml", "fillColor=\"#000000\""),
        ("TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "devgram_icon"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyMusicFragment.java", "class GramifyMusicFragment"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/SaavnApi.java", "class SaavnApi"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/SongAdapter.java", "class SongAdapter"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/GramifyPlayer.java", "class GramifyPlayer"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/Song.java", "class Song"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/Json.java", "class Json"),
        ("TMessagesProj/src/main/res/values/gramify_strings.xml", "gramify_settings_music"),
        ("TMessagesProj/src/main/res/drawable/gramify_music.xml", "vector"),
        ("TMessagesProj/src/main/res/drawable/gramify_mic.xml", "vector"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBotPanel.java", "class DevgramBotPanel"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBotFragment.java", "class DevgramBotFragment"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/gramify/DevgramBubbleService.java", "class DevgramBubbleService"),
    ]
    ok = True
    for rel, needle in checks:
        p = os.path.join(repo, rel)
        if os.path.exists(p) and needle in read(p):
            print("   OK  %s" % rel.split("gramify/")[-1])
        else:
            ok = False
            print("   MISSING %s" % rel)

    hooks = [
        "org/webrtc/voiceengine/WebRtcAudioRecord.java",
        "org/webrtc/audio/WebRtcAudioRecord.java",
        "org/telegram/messenger/voip/AudioRecordJNI.java",
    ]
    hooked = 0
    for h in hooks:
        hp = os.path.join(repo, "TMessagesProj/src/main/java/" + h)
        has = os.path.exists(hp) and MARK_HOOK in read(hp)
        hooked += 1 if has else 0
        print("   %s voice hook: %s" % ("OK " if has else "MISSING", h.split("/")[-1]))
    if hooked == 0:
        raise Fail("ek bhi voice hook nahi laga — enhancer kaam nahi karega")

    for rel, mark, what in [
        ("TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java", MARK_BOOT, "boot hook"),
        ("TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java", MARK_CALL, "call-state hook"),
        ("TMessagesProj/src/main/AndroidManifest.xml", MARK_MANIFEST, "bubble (permission + service)"),
    ]:
        hp = os.path.join(repo, rel)
        has = os.path.exists(hp) and mark in read(hp)
        print("   %s %s" % ("OK " if has else "MISSING", what))

    st = read(os.path.join(repo, "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"))
    print("   %s settings rows" % ("OK " if MARK_ROW in st else "MISSING"))
    print("   %s settings click cases" % ("OK " if MARK_CASE in st else "MISSING"))
    if not ok:
        raise Fail("kuch files missing hain")
    print("\nSab patch ho gaya. Ab build karo:")
    print("   cd %s && ./gradlew TMessagesProj_App:assembleAfatRelease" % repo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="cloned Telegram repo ka path")
    ap.add_argument("--payload", default=None, help="payload/ folder ka path (auto-detect ho jata hai)")
    ap.add_argument("--app-id", default=None)
    ap.add_argument("--app-hash", default=None)
    ap.add_argument("--package", default="com.gramify.messenger",
                    help="naya applicationId (khaali chhodo to Telegram wala hi rahega)")
    ap.add_argument("--name", default="Devgram Remastered")
    ap.add_argument("--fast", action="store_true", help="CI ke liye tez build")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    repo = os.path.abspath(args.repo)
    if not os.path.isdir(os.path.join(repo, "TMessagesProj")):
        print("ERROR: %s Telegram repo nahi lagta (TMessagesProj/ missing)" % repo)
        sys.exit(1)

    global PAYLOAD
    PAYLOAD = find_payload(HERE, args.payload)
    if PAYLOAD:
        print("payload folder mil gaya: %s" % PAYLOAD)
    else:
        flat = flat_sources([HERE])
        if not flat:
            print("ERROR: na 'payload' folder mila, na flat kit ki files.")
            print("  ye script : %s" % HERE)
            print("  chahiye   : payload/TMessagesProj/... ya kit ki 12 files (VoiceDsp.java etc.)")
            sys.exit(1)
        print("payload/ folder nahi mila -> FLAT MODE (%d kit files mili, naam se pehchan kar"
              " sahi jagah daali jayengi)" % len(flat))

    print("Gramify patch chal raha hai: %s" % repo)
    print("GitHub Actions ke secrets me TG_API_ID / TG_API_HASH daalna mat bhoolna!")

    try:
        step_copy(repo)
        step_voice_hook(repo)
        step_app_hooks(repo)
        step_manifest(repo)
        step_settings(repo)
        step_api_keys(repo, args.app_id, args.app_hash)
        step_branding(repo, args.package, args.name)
        step_google_services(repo, args.package)
        if args.fast:
            step_fast(repo)
        step_verify(repo)
    except Fail as e:
        print("\nFAIL: %s" % e)
        sys.exit(1)


if __name__ == "__main__":
    main()
