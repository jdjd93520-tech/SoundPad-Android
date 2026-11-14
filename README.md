# Display Launcher

Android display-scaling controls using Shizuku.

## Modes
- STRETCHED selects Android's automatic display-scaling mode (wm scaling auto).
- CENTER selects scaling-off mode (wm scaling off).

The exact visual result depends on the Android version and device display implementation. These commands do not provide a universal OEM-independent guarantee of non-uniform stretching or pixel-perfect centering.

## Safety
- Before applying a mode, the app saves the current display-size override, density override, and scaling mode.
- A restore is scheduled for 10 minutes after applying.
- A restore is attempted when the screen turns off and after boot.
- The app has a manual restore button.
- Restore commands are verified by reading the display settings again.
- Shizuku must be running and this app must be authorized. If Shizuku is unavailable during a screen-off or boot event, automatic restoration cannot execute at that moment; start Shizuku and use the manual restore button.

The release APK is signed with the debug signing key for testing. Use a private release keystore before public distribution.
