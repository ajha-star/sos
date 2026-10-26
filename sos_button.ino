// SOS Button Prototype - ESP32 + phone over Bluetooth
// Works with the original ESP32 / ESP32-S boards (classic Bluetooth).
// NOT for ESP32-S3 or ESP32-C3 (they have no classic Bluetooth).
//
// Wiring:
//   SOS button    : GPIO 4  -> button -> GND
//   Cancel button : GPIO 5  -> button -> GND
//   Motor 1       : GPIO 18 -> 1k resistor -> NPN base
//   Motor 2       : GPIO 19 -> 1k resistor -> NPN base
//   Each motor    : one wire to 3V3, other wire to NPN collector
//                   NPN emitter -> GND
//                   Diode across motor (stripe/cathode to 3V3)

#include "BluetoothSerial.h"

BluetoothSerial SerialBT;

const int SOS_PIN = 4;
const int CANCEL_PIN = 5;
const int MOTOR1_PIN = 18;
const int MOTOR2_PIN = 19;

const unsigned long HOLD_TIME = 2000;  // hold SOS button for 2 seconds

unsigned long pressStart = 0;
bool sosSent = false;

void vibrate(int times, int onMs, int offMs) {
  for (int i = 0; i < times; i++) {
    digitalWrite(MOTOR1_PIN, HIGH);
    digitalWrite(MOTOR2_PIN, HIGH);
    delay(onMs);
    digitalWrite(MOTOR1_PIN, LOW);
    digitalWrite(MOTOR2_PIN, LOW);
    delay(offMs);
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(SOS_PIN, INPUT_PULLUP);
  pinMode(CANCEL_PIN, INPUT_PULLUP);
  pinMode(MOTOR1_PIN, OUTPUT);
  pinMode(MOTOR2_PIN, OUTPUT);

  SerialBT.begin("SOS_Button");  // name that shows in phone Bluetooth list
  Serial.println("Ready. Pair your phone with SOS_Button");
  vibrate(1, 200, 0);  // one buzz = powered on
}

void loop() {
  // 1. SOS button: hold for 2 seconds
  if (digitalRead(SOS_PIN) == LOW) {
    if (pressStart == 0) pressStart = millis();
    if (!sosSent && millis() - pressStart >= HOLD_TIME) {
      SerialBT.println("SOS");
      Serial.println("SOS sent to phone");
      vibrate(1, 500, 0);  // long buzz = trigger accepted
      sosSent = true;
    }
  } else {
    pressStart = 0;
    sosSent = false;
  }

  // 2. Cancel button: false alarm
  if (digitalRead(CANCEL_PIN) == LOW) {
    SerialBT.println("CANCEL");
    Serial.println("CANCEL sent to phone");
    vibrate(2, 100, 100);
    delay(500);  // simple debounce
  }

  // 3. Messages from the phone
  if (SerialBT.available()) {
    String msg = SerialBT.readStringUntil('\n');
    msg.trim();
    if (msg == "SENT") {
      vibrate(3, 150, 150);  // three buzzes = message sent
    } else if (msg == "FAIL") {
      vibrate(5, 80, 80);    // five quick buzzes = failed, try again
    }
  }
}
