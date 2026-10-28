# SOS Button Android App

Companion app for the ESP32 SOS button. It listens over Bluetooth for "SOS" from a
device named `SOS_Button`, gets the phone's GPS location, sends an SMS with a Google
Maps link to saved numbers, and repeats with a fresh location until "SAFE" is pressed.
It reconnects to the ESP32 by itself if Bluetooth drops.

Works with the ESP32 code in `sos_button.ino` (original ESP32 / ESP32-S only).

## How it behaves
1. SOS tapped on the ESP32: the first SMS goes out at once ("HELP! I am in danger.").
2. Every `UPDATE_INTERVAL_MS` (15 s for the demo, set to 120000 for 2 minutes) a new
   location is sent ("UPDATE n: still in danger.") until the alert is stopped.
3. SAFE pressed on the ESP32 (or "I AM SAFE" in the app): the repeats stop and
   "I am safe now. The alert has been stopped." is sent. If SAFE is pressed before the first
   SMS went out, nothing is sent.
4. The alert also stops by itself after 60 minutes.
5. If an SMS fails, the ESP32 is told "FAIL" and the next round tries again.

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
6. Test with "Send TEST SOS now" using your own number first (it repeats until you tap
   "I AM SAFE"), then tap the SOS button on the ESP32. Use only 1 or 2 numbers when testing:
   Android may block or ask to confirm after about 30 SMS in 30 minutes.

## Notes
- Do not put real phone numbers in the code or GitHub. They are typed in the app and stored on the phone.
- There is no cancel window: SOS sends at once. Use SAFE to stop.
- Android only (iPhones cannot send SMS silently).

## Messages between the ESP32 and the phone
- ESP32 to phone: `SOS`, `SAFE`
- phone to ESP32: `SENDING`, `SENT`, `FAIL`, `SAFE_SENDING`, `SAFE_SENT`, `ENDED`

## ESP32 wiring (`sos_button.ino`)
- SOS button: GPIO 4 to GND. SAFE button: GPIO 5 to GND.
- Green LED: GPIO 19 through 220 ohm to GND. Red LED: GPIO 22 through 220 ohm to GND.
- Red blinks slowly: not connected. Both LEDs solid 3 s: connected. Red solid: sending.
  Green 2 s: sent. Green blinking: "I am safe" sending / sent. Red blinks 6 times: failed.
