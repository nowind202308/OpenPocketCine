/*
 * 帶屏控制器 S0：只接收頭追器封包並顯示（不連 Pocket）
 * 硬體：Waveshare ESP32-S3-Touch-LCD-1.28（ESP32-S3R2、GC9A01 圓屏 240×240、CST816S 觸控）
 *
 * 做什麼：
 *   掃描並連上頭追器（XIAO，韌體 headtrack_f2，藍牙名稱 HeadTrack-POV），訂閱角度封包，
 *   在圓屏上顯示水平角、俯仰角、連線狀態、封包頻率，以及速度、模式、回正次數。
 *   這一版只驗證「手機以外的裝置也能收頭追封包」，不碰 Pocket、不做閉迴路控制。
 *
 * 注意：頭追器韌體目前只接受一個連線。測試時先關掉手機 App（或在 App 裡中斷頭追），
 *       不然頭追器會被手機佔住，這邊會一直停在 SCAN。
 *
 * Arduino IDE 設定：
 *   開發板 ESP32S3 Dev Module（arduino-esp32 3.x）
 *   Flash Size 16MB、PSRAM「QSPI PSRAM」、USB CDC On Boot「Disabled」（Type-C 走 CH343P）
 * 程式庫（程式庫管理員安裝）：
 *   NimBLE-Arduino 2.x（h2zero）
 *   GFX Library for Arduino（moononournation，1.6 以上）
 *
 * 畫面：
 *   外圈刻度每 30°，紅色刻度是正前方；外圈青色三角 = 水平角（向右為正）
 *   左側直條 = 俯仰角（±45° 滿格，抬頭往上）
 *   USB-C 朝下時畫面是正的
 *   上方：SCAN（黃，找頭追器中）／LINK nnHz（綠）／PAUSE（黃，頭追器暫停）／STALE（紅，連著但超過 0.5 秒沒收到封包）
 *   下方：M 模式、S 速度、R 回正次數
 * 序列埠（115200）每秒印一行：水平、俯仰、封包頻率
 *
 * 封包格式（與 headtrack_f2.ino sendPose 相同，小端序，共 7 位元組）：
 *   i16 水平（0.1°）、i16 俯仰（0.1°）、u8 回正次數、
 *   u8 旗標（bit0 啟用、bit1-3 速度、bit4-6 傳輸間隔）、u8 模式
 */

#include <NimBLEDevice.h>
#include <Arduino_GFX_Library.h>
#include <math.h>

// ===== 腳位（微雪官方 wiki，Touch 版）=====
const int PIN_LCD_DC   = 8;
const int PIN_LCD_CS   = 9;
const int PIN_LCD_CLK  = 10;
const int PIN_LCD_MOSI = 11;
const int PIN_LCD_MISO = 12;
const int PIN_LCD_RST  = 14;
const int PIN_LCD_BL   = 2;

// SPI 速度：畫面若出現雜訊或錯位，改成 40000000
const int32_t LCD_SPI_HZ = 80000000;

// ===== 頭追器藍牙服務 =====
static const NimBLEUUID SERVICE_UUID("6e8a0001-4b1e-4a3c-9d5f-2f0c8a7b4e10");
static const NimBLEUUID POSE_UUID("6e8a0002-4b1e-4a3c-9d5f-2f0c8a7b4e10");

const uint32_t STALE_MS   = 500;   // 連著但超過這麼久沒封包 → STALE
const uint32_t FRAME_MS   = 40;    // 畫面 25 fps
const float    PITCH_FULL = 45.0f; // 俯仰條滿格角度

Arduino_DataBus *bus = new Arduino_ESP32SPI(PIN_LCD_DC, PIN_LCD_CS, PIN_LCD_CLK, PIN_LCD_MOSI, PIN_LCD_MISO);
Arduino_GFX *panel = new Arduino_GC9A01(bus, PIN_LCD_RST, 0 /* rotation */, true /* IPS */);
Arduino_Canvas *gfx = new Arduino_Canvas(240, 240, panel);

// ===== 封包資料（notify 回呼在藍牙工作執行緒，用 spinlock 保護）=====
struct Pose {
  float yaw = 0, pitch = 0;
  uint8_t recenter = 0, flags = 0, mode = 0;
  uint32_t lastMs = 0;
  uint32_t count = 0;   // 累計封包數
};
static Pose pose;
static portMUX_TYPE poseMux = portMUX_INITIALIZER_UNLOCKED;

static volatile bool haveTarget = false;
static NimBLEAddress targetAddr;
static NimBLEClient *client = nullptr;
static volatile bool linked = false;

static void onPose(NimBLERemoteCharacteristic *, uint8_t *d, size_t len, bool) {
  if (len < 6) return;
  int16_t y = (int16_t)(d[0] | (d[1] << 8));
  int16_t p = (int16_t)(d[2] | (d[3] << 8));
  portENTER_CRITICAL(&poseMux);
  pose.yaw = y / 10.0f;
  pose.pitch = p / 10.0f;
  pose.recenter = d[4];
  pose.flags = d[5];
  pose.mode = len >= 7 ? d[6] : 0;
  pose.lastMs = millis();
  pose.count++;
  portEXIT_CRITICAL(&poseMux);
}

class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice *dev) override {
    if (haveTarget || !dev->isAdvertisingService(SERVICE_UUID)) return;
    targetAddr = dev->getAddress();
    haveTarget = true;
    NimBLEDevice::getScan()->stop();
    Serial.printf("找到頭追器 %s RSSI %d\n", targetAddr.toString().c_str(), dev->getRSSI());
  }
};
static ScanCB scanCB;

class ClientCB : public NimBLEClientCallbacks {
  void onDisconnect(NimBLEClient *, int reason) override {
    linked = false;
    haveTarget = false;
    Serial.printf("頭追器斷線，原因 %d\n", reason);
  }
};
static ClientCB clientCB;

static void startScan() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  if (scan->isScanning()) return;
  scan->start(0, false, true);   // 0 = 一直掃，直到找到
}

static bool connectTarget() {
  if (!client) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(&clientCB, false);
    client->setConnectionParams(6, 12, 0, 200);   // 7.5–15 ms，逾時 2 秒
    client->setConnectTimeout(5000);
  }
  if (!client->connect(targetAddr)) {
    Serial.println("連線失敗，重新掃描");
    return false;
  }
  NimBLERemoteService *svc = client->getService(SERVICE_UUID);
  NimBLERemoteCharacteristic *chr = svc ? svc->getCharacteristic(POSE_UUID) : nullptr;
  if (!chr || !chr->canNotify() || !chr->subscribe(true, onPose)) {
    Serial.println("找不到角度特徵或訂閱失敗，中斷");
    client->disconnect();
    return false;
  }
  NimBLEConnInfo ci = client->getConnInfo();
  Serial.printf("已連上頭追器並訂閱，連線間隔 %.2f ms，MTU %u\n", ci.getConnInterval() * 1.25f, ci.getMTU());
  linked = true;
  return true;
}

// ===== 畫面 =====
const int CX = 120, CY = 120;

static void polar(float deg, float r, int &x, int &y) {
  float a = (deg - 90.0f) * (float)M_PI / 180.0f;   // 0° 在正上方，順時鐘為正
  x = CX + (int)lroundf(r * cosf(a));
  y = CY + (int)lroundf(r * sinf(a));
}

static void drawCentered(const char *s, int y, uint8_t size, uint16_t color) {
  gfx->setTextSize(size);
  gfx->setTextColor(color);
  int w = strlen(s) * 6 * size;
  gfx->setCursor(CX - w / 2, y);
  gfx->print(s);
}

static void drawFrame(const Pose &p, bool isLinked, float hz) {
  uint32_t now = millis();
  bool stale = isLinked && (p.count == 0 || now - p.lastMs > STALE_MS);
  bool enabled = p.flags & 0x01;
  uint8_t speed = (p.flags >> 1) & 0x07;

  gfx->fillScreen(RGB565_BLACK);

  // 外圈刻度
  for (int d = 0; d < 360; d += 30) {
    int x0, y0, x1, y1;
    polar(d, 118, x0, y0);
    polar(d, d % 90 == 0 ? 104 : 110, x1, y1);
    gfx->drawLine(x0, y0, x1, y1, d == 0 ? RGB565_RED : RGB565_DARKGREY);
  }

  // 水平角指針（只在有資料時畫）
  if (isLinked && p.count > 0) {
    float yawWrapped = fmodf(p.yaw, 360.0f);
    int tx, ty, lx, ly, rx, ry;
    polar(yawWrapped, 97, tx, ty);          // 外圈指標：尖端朝內，避開中間文字
    polar(yawWrapped - 5, 119, lx, ly);
    polar(yawWrapped + 5, 119, rx, ry);
    uint16_t c = stale ? RGB565_RED : (enabled ? RGB565_CYAN : RGB565_YELLOW);
    gfx->fillTriangle(tx, ty, lx, ly, rx, ry, c);
  }

  // 左側俯仰條
  const int bx = 44, top = 72, bot = 168, mid = (top + bot) / 2;
  gfx->drawRect(bx - 5, top, 10, bot - top, RGB565_DARKGREY);
  gfx->drawFastHLine(bx - 9, mid, 18, RGB565_DARKGREY);
  if (isLinked && p.count > 0) {
    float f = constrain(p.pitch / PITCH_FULL, -1.0f, 1.0f);
    int py = mid - (int)lroundf(f * (mid - top));
    if (py < mid) gfx->fillRect(bx - 4, py, 8, mid - py, RGB565_GREEN);
    else gfx->fillRect(bx - 4, mid, 8, py - mid + 1, RGB565_ORANGE);
  }

  // 上方狀態
  char buf[24];
  if (!isLinked) {
    drawCentered(haveTarget ? "CONNECT" : "SCAN", 44, 2, RGB565_YELLOW);
  } else if (stale) {
    drawCentered("STALE", 44, 2, RGB565_RED);
  } else if (!enabled) {
    drawCentered("PAUSE", 44, 2, RGB565_YELLOW);
  } else {
    snprintf(buf, sizeof(buf), "LINK %.0fHz", hz);
    drawCentered(buf, 44, 2, RGB565_GREEN);
  }

  // 中央數值
  if (isLinked && p.count > 0) {
    snprintf(buf, sizeof(buf), "%+.1f", p.yaw);
    drawCentered(buf, 92, 3, RGB565_WHITE);
    snprintf(buf, sizeof(buf), "P %+.1f", p.pitch);
    drawCentered(buf, 128, 2, RGB565_LIGHTGREY);
  } else {
    drawCentered("--", 100, 3, RGB565_DARKGREY);
  }

  // 下方：模式、速度、回正次數
  if (isLinked && p.count > 0) {
    snprintf(buf, sizeof(buf), "M%u S%u R%u", p.mode, speed, p.recenter);
    drawCentered(buf, 176, 2, RGB565_LIGHTGREY);
  }

  gfx->flush();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_LCD_BL, OUTPUT);
  digitalWrite(PIN_LCD_BL, HIGH);
  if (!gfx->begin(LCD_SPI_HZ)) Serial.println("畫面初始化失敗");
  gfx->fillScreen(RGB565_BLACK);
  gfx->flush();

  NimBLEDevice::init("HT-Screen");
  NimBLEDevice::setPower(9);   // dBm
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&scanCB, false);
  scan->setActiveScan(true);
  scan->setInterval(100);
  scan->setWindow(100);
  startScan();
  Serial.println("帶屏控制器 S0 啟動，掃描頭追器中");
}

void loop() {
  static uint32_t lastFrame = 0, lastHzMs = 0, lastCount = 0, lastLog = 0;
  static float hz = 0;
  uint32_t now = millis();

  if (!linked) {
    if (haveTarget) {
      if (!connectTarget()) { haveTarget = false; delay(200); }
    } else {
      startScan();
    }
  }

  Pose snap;
  portENTER_CRITICAL(&poseMux);
  snap = pose;
  portEXIT_CRITICAL(&poseMux);

  if (now - lastHzMs >= 1000) {
    hz = (snap.count - lastCount) * 1000.0f / (now - lastHzMs);
    lastCount = snap.count;
    lastHzMs = now;
  }

  if (now - lastFrame >= FRAME_MS) {
    lastFrame = now;
    drawFrame(snap, linked, hz);
  }

  if (now - lastLog >= 1000) {
    lastLog = now;
    if (linked) Serial.printf("水平 %+.1f  俯仰 %+.1f  %.0fHz  模式 %u  速度 %u  回正 %u%s\n",
                              snap.yaw, snap.pitch, hz, snap.mode, (snap.flags >> 1) & 0x07,
                              snap.recenter, (snap.flags & 1) ? "" : "  暫停");
    else Serial.println(haveTarget ? "連線中…" : "掃描中…");
  }
  delay(2);
}
