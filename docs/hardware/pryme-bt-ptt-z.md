# Pryme BT-PTT-Z / BLE PTT Buttons

## Important Pairing Instructions

**DO NOT pair Pryme BT-PTT-Z buttons via the main Android OS Bluetooth settings.**

Many dual-mode BLE PTT pucks (such as the Pryme BT-PTT-Z and other inexpensive clones) have a known hardware defect where they drop their Bluetooth bonding keys whenever they are powered off. 

If you pair the device in the Android Bluetooth settings:
1. Android saves a "Long Term Key" (LTK) for the device.
2. When the device is turned off and back on, it forgets the key.
3. When Android connects to it again, Android attempts to re-establish the encrypted connection. The PTT puck rejects it ("PIN or Key Missing").
4. Android aggressively deletes the bond entirely and throws an annoying "Pairing Request" prompt to the user.
5. This cycle will repeat every time you turn the puck on and off, or every time you press the button (if it tries to wake up its HID keyboard profile).

### Correct Setup
1. If you have already paired the Bluetooth button in Android, go to your Android **Settings > Connected Devices / Bluetooth**, find the Bluetooth device, and select **Forget** or **Unpair**.
2. Put the Pryme puck in pairing mode.
3. Open the TAK-XVoice plugin.
4. Go to the plugin's Bluetooth device picker.
5. Select and connect to the Pryme device directly from the app.

By avoiding standard OS-level bonding, the app connects to the button via an unencrypted GATT data channel. This channel survives app restarts and power cycles perfectly without ever triggering Android's pairing prompt loops.

### Note on Generic Media Buttons
If you are using a generic Bluetooth media/volume remote (such as inexpensive Amazon camera shutter / media buttons) rather than a dedicated PTT puck, those **should** be paired in the standard Android OS settings. They typically have stable bonding firmware and act strictly as HID input devices, which the ATAK plugin supports natively by intercepting the Volume/Media keystrokes.
