// SOS Button - ESP32 + Android phone over classic Bluetooth
// Works with the original ESP32 / ESP32-S boards. NOT ESP32-S3 / C3 (no classic Bluetooth).
//
// Wiring (each LED: GPIO -> 220 ohm resistor -> LED long leg; LED short leg -> GND):
//   SOS button  : GPIO 4  -> button -> GND
//   SAFE button : GPIO 5  -> button -> GND
//   Red LED     : GPIO 22
//   Green LED   : GPIO 19
//
// ESP32 -> phone : SOS, SAFE
// phone -> ESP32 : SENDING, SENT, FAIL, SAFE_SENDING, SAFE_SENT, ENDED
//
// LED language:
//   not connected to phone      : red blinks slowly and continuously
//   phone app connects          : red + green solid for 3 seconds
//   SOS / update sending        : red solid
//   SOS / update sent           : red off, green solid for 2 seconds
//   "I am safe" sending         : green blinks
//   "I am safe" sent            : green blinks for 3 seconds
//   any failure / no answer     : red blinks fast 6 times

#include "BluetoothSerial.h"

BluetoothSerial SerialBT;

const int SOS_PIN = 4;
const int SAFE_PIN = 5;
const int RED_LED = 22;
const int GREEN_LED = 19;

const unsigned long GREEN_HOLD_MS = 2000;      // green stays on this long after "sent"
const unsigned long CONNECT_HOLD_MS = 3000;    // both LEDs solid after Bluetooth connects
const unsigned long SAFE_DONE_MS = 3000;       // green blinks this long after "I am safe" is sent
const unsigned long RESPONSE_TIMEOUT = 30000;  // give up if the phone never answers
const int FAIL_BLINKS = 6;                     // red blinks on failure (5 to 7)
const unsigned long DEBOUNCE_MS = 40;

enum Mode { M_OFF, M_RED_ON, M_GREEN_2S, M_SAFE_SENDING, M_FAIL, M_CONNECTED, M_DISCONNECTED, M_SAFE_DONE };
Mode mode = M_DISCONNECTED;
unsigned long modeStart = 0;

bool wasConnected = false;
bool alertActive = false;  // true after SOS is sent until Safe / end / disconnect
String rx = "";

// One debounced button: pressed() returns true once per physical press.
struct Button {
  int pin;
  int lastRaw;
  unsigned long change;
  bool latched;
  bool pressed() {
    int raw = digitalRead(pin);
    if (raw != lastRaw) {
      lastRaw = raw;
      change = millis();
    }
    if (millis() - change >= DEBOUNCE_MS) {
      if (raw == LOW && !latched) {
        latched = true;
        return true;
      }
      if (raw == HIGH) latched = false;
    }
    return false;
  }
};

Button sosButton = {SOS_PIN, HIGH, 0, false};
Button safeButton = {SAFE_PIN, HIGH, 0, false};

void setMode(Mode m) {
  mode = m;
  modeStart = millis();
}

void leds(bool red, bool green) {
  digitalWrite(RED_LED, red ? HIGH : LOW);
  digitalWrite(GREEN_LED, green ? HIGH : LOW);
}

void updateLeds() {
  unsigned long t = millis() - modeStart;
  switch (mode) {
    case M_OFF:
      leds(false, false);
      break;
    case M_RED_ON:
      leds(true, false);
      if (t > RESPONSE_TIMEOUT) {
        alertActive = false;
        setMode(M_FAIL);
      }
      break;
    case M_GREEN_2S:
      leds(false, true);
      if (t >= GREEN_HOLD_MS) setMode(M_OFF);
      break;
    case M_SAFE_SENDING:
      leds(false, (t / 200) % 2 == 0);
      if (t > RESPONSE_TIMEOUT) setMode(M_FAIL);
      break;
    case M_FAIL:
      leds((t % 400) < 200, false);
      if (t >= (unsigned long)FAIL_BLINKS * 400) setMode(M_OFF);
      break;
    case M_CONNECTED:
      leds(true, true);
      if (t >= CONNECT_HOLD_MS) setMode(M_OFF);
      break;
    case M_DISCONNECTED:
      leds((t % 1000) < 500, false);
      break;
    case M_SAFE_DONE:
      leds(false, (t / 200) % 2 == 0);
      if (t >= SAFE_DONE_MS) setMode(M_OFF);
      break;
  }
}

void handleMessage(const String &m) {
  Serial.print("From phone: ");
  Serial.println(m);
  if (m == "SENDING") setMode(M_RED_ON);
  else if (m == "SENT") setMode(M_GREEN_2S);
  else if (m == "FAIL") setMode(M_FAIL);
  else if (m == "SAFE_SENDING") setMode(M_SAFE_SENDING);
  else if (m == "SAFE_SENT") {
    alertActive = false;
    setMode(M_SAFE_DONE);
  } else if (m == "ENDED") {
    alertActive = false;
    setMode(M_OFF);
  }
}

void readBluetooth() {
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\n') {
      rx.trim();
      if (rx.length() > 0) handleMessage(rx);
      rx = "";
    } else if (c != '\r') {
      if (rx.length() < 32) rx += c;
    }
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(SOS_PIN, INPUT_PULLUP);
  pinMode(SAFE_PIN, INPUT_PULLUP);
  pinMode(RED_LED, OUTPUT);
  pinMode(GREEN_LED, OUTPUT);
  leds(false, false);

  SerialBT.begin("SOS_Button");  // name that shows in phone Bluetooth list
  setMode(M_DISCONNECTED);
  Serial.println("Ready. Pair your phone with SOS_Button");
}

void loop() {
  bool connected = SerialBT.hasClient();
  if (connected != wasConnected) {
    wasConnected = connected;
    rx = "";
    alertActive = false;
    setMode(connected ? M_CONNECTED : M_DISCONNECTED);
    Serial.println(connected ? "Phone connected" : "Phone disconnected");
  }

  bool sosPressed = sosButton.pressed();
  bool safePressed = safeButton.pressed();

  if (connected) {
    if (sosPressed && !alertActive) {  // one tap starts the alert
      SerialBT.println("SOS");
      Serial.println("SOS sent to phone");
      alertActive = true;
      setMode(M_RED_ON);
    }
    if (safePressed) {  // the phone ignores SAFE when no alert is running
      SerialBT.println("SAFE");
      Serial.println("SAFE sent to phone");
    }
    readBluetooth();
  }

  updateLeds();
  delay(2);
}
