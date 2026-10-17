# M11 prep: Android emulator (spike_pixel)

Installed: emulator 37.2.12 (1.2 GB), system-images;android-37.0;google_apis;arm64-v8a (4.3 GB). AVD spike_pixel: pixel_8, 4 GB RAM, 8 GB data, 1080x2400, API 37.
Note: `avdmanager create avd` printed "Could not load devices.xml" but created the AVD; hw.ramSize/disk size were appended to ~/.android/avd/spike_pixel.avd/config.ini.
Hypervisor.framework: kern.hv_support=1. Cold boot headless (-no-snapshot): ~23 s to sys.boot_completed.

    export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
    export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH

## Boot / shutdown
    nohup emulator -avd spike_pixel -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect > emu.log 2>&1 &
    until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done
    adb exec-out screencap -p > shot.png
    adb emu kill        # clean shutdown

## Install / deep link
    adb install -r app.apk
    adb shell am start -a android.intent.action.VIEW -d 'openid4vp://authorize?...' <pkg>
    adb shell am start -n <pkg>/<activity>

## Kill variants (verified on API 37)
- `am kill <pkg>` ONLY kills BACKGROUND processes: app must be backgrounded first (`adb shell input keyevent KEYCODE_HOME`), else it is a no-op (verified: foreground survives, after HOME it dies). This is the "process death while in background" drill.
- `adb shell am send-trim-memory <pkg> RUNNING_CRITICAL` : accepted (rc 0), delivers onTrimMemory; does NOT kill the process.
- `adb shell cmd activity kill-all` : kills cached/background processes (worked on backgrounded Settings).
- `adb shell am force-stop <pkg>` : kills always (but clears task/state; not a faithful process-death restore).
- `adb root; adb shell kill -9 $(adb shell pidof <pkg>)` : works (google_apis image is rootable). Task survives, so system restores the activity.
- Memory pressure simulation: `adb shell cmd activity memory-factor set CRITICAL` ... `memory-factor reset` (set works; the factor is reported by `show`).
- Dont-keep-activities: `adb shell settings put global always_finish_activities 1` (reset to 0).

## TalkBack
Present on image: com.google.android.marvin.talkback (/product/app/talkback/talkback.apk) plus an emulator overlay. No sideload needed.

    SVC=com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
    adb shell settings put secure enabled_accessibility_services $SVC
    adb shell settings put secure accessibility_enabled 1
    # off:
    adb shell settings delete secure enabled_accessibility_services
    adb shell settings put secure accessibility_enabled 0
Caveat: 6 s after enabling, dumpsys accessibility showed it in Enabled/Binding but "Bound services" empty; allow longer or confirm with a screenshot. Headless has no audio (-no-audio), so speech cannot be heard; use the Accessibility focus ring in screenshots and `dumpsys accessibility`/uiautomator for assertions.

## Back / predictive back
    adb shell settings put global enable_back_animation 1    # predictive back animation dev option (was unset)
    adb shell input keyevent KEYCODE_BACK
    adb shell input swipe 2 1200 600 1200 300                 # edge swipe from left edge (x=2, 1080x2400)
Both commands succeed (rc 0); gesture-nav mode is the default on this image. Real predictive-back visuals need a screenshot mid-swipe; app must set android:enableOnBackInvokedCallback="true". Also toggled via Settings > Developer options > Predictive back animations.
