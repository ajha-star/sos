# SOS Button Android App

Companion app for the ESP32 SOS button. It listens over Bluetooth for "SOS" from a
device named `SOS_Button`, gets the phone's GPS location, sends an SMS with a Google
Maps link to saved numbers, and replies "SENT" (or "FAIL") to the ESP32.

Works with the ESP32 code in `sos_button.ino` (original ESP32 / ESP32-S only).

## Build with Codemagic
1. Create a GitHub repo and upload ALL files from this folder (keep the folder structure).
2. On codemagic.io: Add application -> choose the repo -> choose "codemagic.yaml" workflow.
3. Start a build with the workflow `sos-android-debug`.
4. When it finishes, download the `app-debug.apk` artifact and install it on the phone
   (allow "install unknown apps" if asked).

## First run on the phone
1. Bluetooth settings: pair the ESP32 (it shows as `SOS_Button`).
2. Open the app, type family numbers (comma separated).
3. Tap "1. Allow permissions" and allow everything (Location, SMS, Nearby devices, Notifications).
4. Tap "Battery settings" and set the app to Unrestricted (on Xiaomi/Oppo/Vivo also turn on Autostart).
5. Tap "2. Save numbers and START protection". A notification "SOS Button is active" appears.
6. Test with "Send TEST SOS now" using your own number first, then hold the ESP32 button for 2 seconds.

## Notes
- Do not put real phone numbers in the code or GitHub. They are typed in the app and stored on the phone.
- There is a 3-second cancel window after SOS; the ESP32 cancel button sends "CANCEL".
- Android only (iPhones cannot send SMS silently).
