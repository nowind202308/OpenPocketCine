/*
 * 頭追韌體 F2 v0.5（絕對角度模式，配合 fork 版 OpenPocketCine 分支 feat/headtrack-f2）
 * 硬體：Seeed XIAO nRF52840 Sense Plus / Sense（板載 LSM6DS3TR-C）
 *
 * 做什麼：
 *   用自訂藍牙服務，每秒 50 次送出頭部角度給手機 App：
 *   - 水平（yaw）：積分頭的轉角，向右為正；頭不動時不積分，靜止 3 秒自動校正陀螺儀零點
 *   - 俯仰（pitch）：用重力算出絕對仰角，抬頭為正
 *   App 收到後換算成雲台角度，加上安全限位，再用相對移動指令驅動 Pocket 雲台
 *   本韌體不是藍牙手把，不需要在手機藍牙設定裡配對；App 會自動掃描連線
 *
 * Arduino：開發板選「Seeed XIAO nRF52840 Sense Plus」（非 mbed 版），不需要額外程式庫
 *
 * 按鍵（選配，接 D1 與 GND）：
 *   短按 = 回正（目前頭的方向當正前方，雲台轉回正前方水平）
 *   雙擊 = 暫停／恢復頭追
 *   三連按 = 切換速度 1→2→3→4（預設 3；App 會跳出提示）
 *   長按 2 秒 = 關機
 * 沒有按鍵時，序列埠輸入：r 回正、e 暫停／恢復、v 1~4 速度、s 即時數據、p 參數
 *
 * 燈號：紫燈閃 = 開機校正中，請放著不動；藍燈閃 = 等待 App 連線；綠燈每 2 秒閃一下 = 頭追中；黃燈（紅＋綠）閃 = 已暫停
 *
 * 藍牙格式（App 端 HeadPose.parse）：i16 yaw（0.1°）、i16 pitch（0.1°）、u8 回正次數、u8 旗標（bit0 啟用）
 */

#include <bluefruit.h>
#include <Wire.h>
#include <math.h>

// ===== 可調參數 =====
float STILL_SEC      = 3.0f;     // 靜止多久開始校正零點
bool  INVERT_YAW     = false;
bool  INVERT_PITCH   = false;

// 模組安裝方向：板子上哪一軸朝「臉的正前方」（0=X, 1=Y, 2=Z），正負號
const int   FORWARD_AXIS = 0;
const float FORWARD_SIGN = 1.0f;

// ===== 固定常數 =====
const float GRAV_TAU_S      = 0.5f;
const float PITCH_TAU_S     = 1.5f;
const float YAW_GATE_DPS    = 1.0f;
const float BIAS_ALPHA      = 0.02f;
const float CAL_RANGE_DPS   = 3.0f;   // 開機校正：1 秒內每軸讀值最大變化
const float STILL_JITTER_DPS = 1.5f;  // 執行中判斷靜止：讀值相對短期平均的抖動
const float STILL_MAX_DPS   = 10.0f;  // 執行中可自動修正的最大零點誤差
const float RAW_AVG_TAU_S   = 0.5f;
const uint32_t SAMPLE_US    = 5000;   // 200 Hz 取樣
const uint32_t NOTIFY_MS    = 20;     // 50 Hz 送出
const int   BUTTON_PIN      = D1;
const uint32_t LONG_PRESS_MS = 2000;
const uint32_t DOUBLE_CLICK_MS = 350;

// 自訂服務 UUID 6e8a0001-4b1e-4a3c-9d5f-2f0c8a7b4e10（Bluefruit 用低位元組在前）
const uint8_t SERVICE_UUID[16] = {0x10,0x4e,0x7b,0x8a,0x0c,0x2f,0x5f,0x9d,0x3c,0x4a,0x1e,0x4b,0x01,0x00,0x8a,0x6e};
const uint8_t POSE_UUID[16]    = {0x10,0x4e,0x7b,0x8a,0x0c,0x2f,0x5f,0x9d,0x3c,0x4a,0x1e,0x4b,0x02,0x00,0x8a,0x6e};

BLEService htService(SERVICE_UUID);
BLECharacteristic poseChar(POSE_UUID);
BLEDis bledis;

float bias[3] = {0, 0, 0};
float up[3]   = {0, 0, 1};
float headYaw = 0;
float headPitch = 0;
uint8_t recenterSeq = 0;
bool trackingEnabled = true;
uint8_t speedLevel = 3;   // 1~4，App 對應 45/80/120/180 °/s；三連按切換
uint32_t stillSinceMs = 0;
bool streaming = false;

// ---------- 小工具 ----------
static void normalize(float v[3]) {
  float n = sqrtf(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
  if (n > 1e-6f) { v[0] /= n; v[1] /= n; v[2] /= n; }
}
static void cross(const float a[3], const float b[3], float o[3]) {
  o[0] = a[1]*b[2] - a[2]*b[1];
  o[1] = a[2]*b[0] - a[0]*b[2];
  o[2] = a[0]*b[1] - a[1]*b[0];
}
static float dot(const float a[3], const float b[3]) {
  return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
}
static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

static void setLed(bool r, bool g, bool b) {  // XIAO RGB LED 低電位點亮
  digitalWrite(LED_RED,   r ? LOW : HIGH);
  digitalWrite(LED_GREEN, g ? LOW : HIGH);
  digitalWrite(LED_BLUE,  b ? LOW : HIGH);
}

// ---------- 板載 LSM6DS3TR-C 直接驅動（板內 I2C = Wire1）----------
// Seeed 的 LSM6DS3 程式庫在非 mbed 核心會誤用外接 Wire（D4/D5），所以不用它
uint8_t imuAddr = 0x6A;
TwoWire* bus = &Wire1;   // 板載 IMU 預設在 Wire1；找不到時會改試 Wire
const float GYRO_DPS_PER_LSB = 0.070f;     // ±2000 dps
const float ACC_G_PER_LSB    = 0.000122f;  // ±4 g

static bool imuWrite(uint8_t reg, uint8_t val) {
  bus->beginTransmission(imuAddr);
  bus->write(reg); bus->write(val);
  return bus->endTransmission() == 0;
}
static int imuReadByte(uint8_t reg) {
  bus->beginTransmission(imuAddr);
  bus->write(reg);
  if (bus->endTransmission(false) != 0) return -1;
  if (bus->requestFrom(imuAddr, (uint8_t)1) != 1) return -1;
  return bus->read();
}
static bool imuRead6(uint8_t reg, int16_t out[3]) {
  bus->beginTransmission(imuAddr);
  bus->write(reg);
  if (bus->endTransmission(false) != 0) return false;
  if (bus->requestFrom(imuAddr, (uint8_t)6) != 6) return false;
  for (int i = 0; i < 3; i++) {
    uint8_t lo = bus->read(), hi = bus->read();
    out[i] = (int16_t)((hi << 8) | lo);
  }
  return true;
}

// 掃描一組 I2C，印出有回應的位址（診斷用）
static void scanBus(TwoWire& w, const char* name) {
  Serial.print(name); Serial.print(" 掃描結果：");
  int n = 0;
  for (uint8_t a = 1; a < 127; a++) {
    w.beginTransmission(a);
    if (w.endTransmission() == 0) { Serial.print(" 0x"); Serial.print(a, HEX); n++; }
  }
  if (n == 0) Serial.print(" 沒有任何裝置");
  Serial.println();
}

// 回傳 0 = 成功；1 = 找不到晶片；2 = 晶片 ID 不符；3 = 寫入設定失敗
static int imuBegin() {
#ifdef PIN_LSM6DS3TR_C_POWER
  pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
  NRF_P1->PIN_CNF[8] = ((uint32_t)NRF_GPIO_PIN_DIR_OUTPUT << GPIO_PIN_CNF_DIR_Pos)
                     | ((uint32_t)NRF_GPIO_PIN_INPUT_DISCONNECT << GPIO_PIN_CNF_INPUT_Pos)
                     | ((uint32_t)NRF_GPIO_PIN_NOPULL << GPIO_PIN_CNF_PULL_Pos)
                     | ((uint32_t)NRF_GPIO_PIN_H0H1 << GPIO_PIN_CNF_DRIVE_Pos)
                     | ((uint32_t)NRF_GPIO_PIN_NOSENSE << GPIO_PIN_CNF_SENSE_Pos);
  digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);   // 板載 IMU 電源（P1.08，高驅動）
  delay(150);   // 等 IMU 上電開機
#else
#error "沒有 IMU 電源腳位定義：開發板請選「Seeed XIAO nRF52840 Sense Plus」或「Sense」（非 mbed 版）"
#endif
  Wire1.begin(); Wire1.setClock(100000);
  Wire.begin();  Wire.setClock(100000);
  int who = -1;
  TwoWire* buses[2] = {&Wire1, &Wire};
  const uint8_t addrs[2] = {0x6A, 0x6B};
  for (int b = 0; b < 2 && who < 0; b++)
    for (int i = 0; i < 2 && who < 0; i++) { bus = buses[b]; imuAddr = addrs[i]; who = imuReadByte(0x0F); }
  if (who < 0) return 1;
  bus->setClock(400000);
  if (who != 0x6A && who != 0x69) return 2;    // LSM6DS3TR-C = 0x6A
  if (!imuWrite(0x12, 0x44)) return 3;         // CTRL3_C：BDU + 位址自動遞增
  if (!imuWrite(0x10, 0x68)) return 3;         // CTRL1_XL：416 Hz，±4 g
  if (!imuWrite(0x11, 0x6C)) return 3;         // CTRL2_G：416 Hz，±2000 dps
  delay(50);
  return 0;
}

static void readGyro(float g[3]) {
  int16_t r[3];
  if (!imuRead6(0x22, r)) { g[0] = g[1] = g[2] = 0; return; }
  for (int i = 0; i < 3; i++) g[i] = r[i] * GYRO_DPS_PER_LSB;
}
static void readAccel(float a[3]) {
  int16_t r[3];
  if (!imuRead6(0x28, r)) { a[0] = a[1] = 0; a[2] = 1; return; }
  for (int i = 0; i < 3; i++) a[i] = r[i] * ACC_G_PER_LSB;
}

// 開機校正：每次開機都重新量陀螺儀零點與重力方向。
// 必須真的靜止才算數：1 秒內三軸讀值的變化都很小、加速度約 1g；
// 有在動就紫燈閃並重新量，直到放穩為止（避免把「拿在手上的轉動」當成零點，造成一直漂）。
static void initialCalibrate() {
  const int N = 200;
  for (uint32_t attempt = 0;; attempt++) {
    float gs[3] = {0, 0, 0}, as[3] = {0, 0, 0}, g[3], a[3];
    float gmin[3] = {1e9f, 1e9f, 1e9f}, gmax[3] = {-1e9f, -1e9f, -1e9f};
    bool accBad = false;
    for (int i = 0; i < N; i++) {
      readGyro(g); readAccel(a);
      for (int k = 0; k < 3; k++) {
        gs[k] += g[k]; as[k] += a[k];
        if (g[k] < gmin[k]) gmin[k] = g[k];
        if (g[k] > gmax[k]) gmax[k] = g[k];
      }
      float m = sqrtf(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
      if (m < 0.9f || m > 1.1f) accBad = true;
      bool blink = attempt > 0 && (millis() / 250) % 2;
      setLed(blink, false, blink);   // 紫燈閃：請放著不動
      delay(5);
    }
    bool calm = !accBad;
    for (int k = 0; k < 3; k++) if (gmax[k] - gmin[k] > CAL_RANGE_DPS) calm = false;
    if (calm) {
      for (int k = 0; k < 3; k++) { bias[k] = gs[k] / N; up[k] = as[k] / N; }
      Serial.print("開機校正完成，零點 dps: ");
      Serial.print(bias[0], 2); Serial.print(", "); Serial.print(bias[1], 2); Serial.print(", "); Serial.println(bias[2], 2);
      break;
    }
    if (attempt % 4 == 0) Serial.println("校正中：請把頭追放著不動");
  }
  normalize(up);
  float fwd[3] = {0, 0, 0};
  fwd[FORWARD_AXIS] = FORWARD_SIGN;
  headPitch = asinf(clampf(dot(fwd, up), -1, 1)) * 57.29578f;
  if (INVERT_PITCH) headPitch = -headPitch;
}

static void recenter() {
  recenterSeq++;
  Serial.println("回正");
}

static void toggleEnabled() {
  trackingEnabled = !trackingEnabled;
  if (trackingEnabled) recenterSeq++;   // 恢復時順便回正
  Serial.println(trackingEnabled ? "頭追恢復" : "頭追暫停");
}

static void cycleSpeed() {
  speedLevel = speedLevel >= 4 ? 1 : speedLevel + 1;
  Serial.print("頭追速度 "); Serial.println(speedLevel);
}

static void powerOff() {
  Serial.println("關機");
  setLed(false, false, false);
  delay(200);
  while (digitalRead(BUTTON_PIN) == LOW) delay(10);
  delay(50);
  systemOff(BUTTON_PIN, LOW);
}

static void printParams() {
  Serial.print("頭追="); Serial.print(trackingEnabled ? "啟用" : "暫停");
  Serial.print(" 回正次數="); Serial.print(recenterSeq);
  Serial.print(" 速度="); Serial.print(speedLevel);
  Serial.print(" 靜止秒數="); Serial.print(STILL_SEC);
  Serial.print(" 反轉水平="); Serial.print(INVERT_YAW);
  Serial.print(" 反轉俯仰="); Serial.println(INVERT_PITCH);
  Serial.print("零點 dps: "); Serial.print(bias[0], 3); Serial.print(", ");
  Serial.print(bias[1], 3); Serial.print(", "); Serial.println(bias[2], 3);
}

static void handleSerial() {
  if (!Serial.available()) return;
  String line = Serial.readStringUntil('\n');
  line.trim();
  if (line.length() == 0) return;
  char c = line.charAt(0);
  float v = line.length() > 2 ? line.substring(2).toFloat() : 0;
  switch (c) {
    case 'r': recenter(); break;
    case 'e': toggleEnabled(); break;
    case 'w': if (v >= 0.5f) STILL_SEC = v; printParams(); break;
    case 'x': INVERT_YAW = !INVERT_YAW; recenter(); printParams(); break;
    case 'y': INVERT_PITCH = !INVERT_PITCH; headPitch = -headPitch; recenter(); printParams(); break;
    case 's': streaming = !streaming; break;
    case 'p': printParams(); break;
    case 'v': if (v >= 1 && v <= 4) speedLevel = (uint8_t)v; printParams(); break;
    default: Serial.println("未知指令"); break;
  }
}

// 短按回正、雙擊暫停／恢復、三連按切換速度、長按關機
static void handleButton() {
  static bool wasDown = false;
  static uint32_t downAt = 0, lastClickAt = 0;
  static uint8_t clicks = 0;
  bool down = digitalRead(BUTTON_PIN) == LOW;
  uint32_t now = millis();
  if (down && !wasDown) downAt = now;
  if (down && now - downAt >= LONG_PRESS_MS) powerOff();
  if (!down && wasDown && now - downAt >= 30) {
    clicks++;
    lastClickAt = now;
  }
  if (clicks && !down && now - lastClickAt > DOUBLE_CLICK_MS) {
    if (clicks == 1) recenter();
    else if (clicks == 2) toggleEnabled();
    else cycleSpeed();
    clicks = 0;
  }
  wasDown = down;
}

static void startAdv() {
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addService(htService);   // App 依這個服務 UUID 掃描
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);
}

static void sendPose() {
  if (!Bluefruit.connected() || !poseChar.notifyEnabled()) return;
  float y = headYaw, p = headPitch;
  int16_t yt = (int16_t)lroundf(clampf(y, -3000, 3000) * 10.0f);
  int16_t pt = (int16_t)lroundf(clampf(p, -90, 90) * 10.0f);
  uint8_t buf[6] = {(uint8_t)(yt & 0xFF), (uint8_t)((yt >> 8) & 0xFF),
                    (uint8_t)(pt & 0xFF), (uint8_t)((pt >> 8) & 0xFF),
                    recenterSeq, (uint8_t)((trackingEnabled ? 1 : 0) | (speedLevel << 1))};
  poseChar.notify(buf, sizeof(buf));
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_RED, OUTPUT); pinMode(LED_GREEN, OUTPUT); pinMode(LED_BLUE, OUTPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  setLed(true, false, false);

  int err = imuBegin();
  if (err != 0) {
    while (true) {
      setLed(true, false, false); delay(200); setLed(false, false, false); delay(800);
      Serial.print("IMU 初始化失敗，錯誤碼 "); Serial.println(err);
      scanBus(Wire1, "Wire1（板內 IMU）");
      scanBus(Wire, "Wire（D4/D5）");
    }
  }
  initialCalibrate();

  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("HeadTrack-POV");
  Bluefruit.Periph.setConnInterval(6, 12);   // 7.5–15 ms
  bledis.setManufacturer("NoWind");
  bledis.setModel("HeadTrack F2 v0.5");
  bledis.begin();

  htService.begin();
  poseChar.setProperties(CHR_PROPS_NOTIFY);
  poseChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  poseChar.setFixedLen(6);
  poseChar.begin();

  startAdv();
  Serial.println("頭追 F2 v0.5 啟動，輸入 p 看參數");
}

void loop() {
  static uint32_t lastSampleUs = 0, lastNotifyMs = 0, lastStreamMs = 0;
  uint32_t nowUs = micros();
  handleSerial();
  handleButton();

  if (nowUs - lastSampleUs >= SAMPLE_US) {
    float dt = (nowUs - lastSampleUs) * 1e-6f;
    if (dt > 0.05f) dt = 0.05f;
    lastSampleUs = nowUs;

    float g[3], a[3];
    readGyro(g); readAccel(a);
    float gc[3] = {g[0] - bias[0], g[1] - bias[1], g[2] - bias[2]};

    float an[3] = {a[0], a[1], a[2]};
    float amag = sqrtf(dot(an, an));
    bool accOk = amag > 0.8f && amag < 1.2f;
    if (accOk) {
      normalize(an);
      float k = dt / (GRAV_TAU_S + dt);
      for (int i = 0; i < 3; i++) up[i] += k * (an[i] - up[i]);
      normalize(up);
    }

    // 靜止判斷看「讀值有沒有在變」，不看扣掉零點後的大小：零點錯了也認得出靜止，才能修回來
    static float gAvg[3] = {0, 0, 0};
    static bool gAvgInit = false;
    if (!gAvgInit) { for (int i = 0; i < 3; i++) gAvg[i] = g[i]; gAvgInit = true; }
    float ka = dt / (RAW_AVG_TAU_S + dt);
    bool still = amag > 0.9f && amag < 1.1f;
    for (int i = 0; i < 3; i++) {
      gAvg[i] += ka * (g[i] - gAvg[i]);
      if (fabsf(g[i] - gAvg[i]) > STILL_JITTER_DPS || fabsf(gc[i]) > STILL_MAX_DPS) still = false;
    }
    uint32_t nowMs = millis();
    if (!still) stillSinceMs = nowMs;
    else if (nowMs - stillSinceMs >= (uint32_t)(STILL_SEC * 1000)) {
      for (int i = 0; i < 3; i++) bias[i] += BIAS_ALPHA * (g[i] - bias[i]);
    }

    float fwd[3] = {0, 0, 0};
    fwd[FORWARD_AXIS] = FORWARD_SIGN;
    float left[3];
    cross(up, fwd, left);
    normalize(left);
    float yaw = -dot(gc, up);        // 向右為正
    float pitch = -dot(gc, left);    // 抬頭為正
    if (INVERT_YAW) yaw = -yaw;
    if (INVERT_PITCH) pitch = -pitch;

    if (fabsf(yaw) > YAW_GATE_DPS) headYaw += yaw * dt;

    headPitch += pitch * dt;
    if (accOk) {
      float ai[3] = {a[0] / amag, a[1] / amag, a[2] / amag};
      float accPitch = asinf(clampf(dot(fwd, ai), -1, 1)) * 57.29578f;
      if (INVERT_PITCH) accPitch = -accPitch;
      float kp = dt / (PITCH_TAU_S + dt);
      headPitch += kp * (accPitch - headPitch);
    }
  }

  uint32_t nowMs = millis();
  if (nowMs - lastNotifyMs >= NOTIFY_MS) {
    lastNotifyMs = nowMs;
    sendPose();
    bool connected = Bluefruit.connected();
    if (!connected) setLed(false, false, (nowMs / 500) % 2);                 // 藍燈閃：等待連線
    else if (!trackingEnabled) setLed((nowMs / 500) % 2, (nowMs / 500) % 2, false);  // 黃燈閃：暫停
    else setLed(false, (nowMs % 2000) < 60, false);                          // 綠燈短閃：頭追中
  }

  if (streaming && nowMs - lastStreamMs >= 100) {
    lastStreamMs = nowMs;
    Serial.print("水平 "); Serial.print(headYaw, 1);
    Serial.print("  俯仰 "); Serial.print(headPitch, 1);
    Serial.print("  回正 "); Serial.print(recenterSeq);
    Serial.println(trackingEnabled ? "  啟用" : "  暫停");
  }
}
