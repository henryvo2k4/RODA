#include <WiFi.h>
#include <WiFiManager.h> // Thư viện cấu hình WiFi tự động
#include <HTTPClient.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <TinyGPSPlus.h>
#include <SD.h>
#include <SPI.h>
#include <ArduinoJson.h>

// ================= CẤU HÌNH SUPABASE =================
const String SUPABASE_URL = "https://sweqvobmlntyhyeuurfr.supabase.co/rest/v1/road_events";
const String SUPABASE_KEY = "sb_publishable_xsqRVFRoQSh0c9wzwc5vxA_Hw9aj9fF";
const String DEVICE_ID = "NODE_DIDONG_01"; // Khai báo mã thiết bị di động

// ================= CẤU HÌNH CHÂN (PINS) =================
#define GPS_RX_PIN 16
#define GPS_TX_PIN 17
#define SD_CS_PIN 5 

#define LED_POWER_PIN 25 // LED 1: Báo nguồn / Trạng thái
#define LED_WIFI_PIN 26  // LED 2: Báo WiFi
#define BTN_SYNC_PIN 27  // Nút nhấn đồng bộ (Nối với GND)

// ================= CẤU HÌNH CẢM BIẾN & THẺ NHỚ =================
Adafruit_MPU6050 mpu;
TinyGPSPlus gps;
bool sdReady = false; 

// ================= THÔNG SỐ THUẬT TOÁN HỐ GÀ =================
float emaAccel = 9.81;
const float EMA_ALPHA = 0.05;
const float ACCEL_DELTA_THRESHOLD = 5.0; 
const float GYRO_MAG_THRESHOLD = 0.5;    

unsigned long lastReportTime = 0;
const unsigned long COOLDOWN_TIME = 10000; 

// ================= QUẢN LÝ GPS =================
float lastValidLat = 0.0;
float lastValidLng = 0.0;
bool hasValidGPS = false;

// ================= QUẢN LÝ TRẠNG THÁI =================
enum SystemState {
  STATE_SENSING, 
  STATE_SYNCING  
};
SystemState currentState = STATE_SENSING;

unsigned long lastDebugTime = 0;
const unsigned long DEBUG_INTERVAL = 2000;

int lastBtnState = HIGH;
unsigned long lastDebounceTime = 0;
const unsigned long DEBOUNCE_DELAY = 50;

// ================= HÀM TIỆN ÍCH =================
void updateGPS() {
  while (Serial2.available() > 0) {
    if (gps.encode(Serial2.read())) {
      if (gps.location.isValid()) {
        lastValidLat = gps.location.lat();
        lastValidLng = gps.location.lng();
        hasValidGPS = true;
      }
    }
  }
}

// ================= HÀM SETUP =================
void setup() {
  Serial.begin(115200);
  Serial2.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  pinMode(LED_POWER_PIN, OUTPUT);
  pinMode(LED_WIFI_PIN, OUTPUT);
  pinMode(BTN_SYNC_PIN, INPUT_PULLUP); 

  digitalWrite(LED_POWER_PIN, HIGH);
  digitalWrite(LED_WIFI_PIN, LOW);
  
  Serial.println("\n--- KHỞI ĐỘNG HỆ THỐNG NODE DI ĐỘNG ---");

  if (!mpu.begin()) {
    Serial.println("[LỖI] Không tìm thấy MPU6050!");
    while (1) { delay(10); } 
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 
  Serial.println("[MPU] Khởi tạo thành công!");

  if (!SD.begin(SD_CS_PIN)) {
    Serial.println("[LỖI] Không tìm thấy thẻ SD hoặc thẻ bị hỏng!");
    sdReady = false;
  } else {
    Serial.println("[SD] Khởi tạo thành công!");
    sdReady = true;
  }

  Serial.println("[WIFI] Đang kiểm tra WiFi / Mở AP cài đặt...");
  WiFiManager wm;
  wm.setConfigPortalTimeout(60); 

  if (!wm.autoConnect("RODA_NodeDiDong")) {
    Serial.println("Quá 60s không có mạng. Chuyển sang CHẾ ĐỘ OFFLINE!");
    digitalWrite(LED_WIFI_PIN, LOW); 
  } else {
    Serial.println("Đã kết nối WiFi thành công! (CHẾ ĐỘ ONLINE)");
    digitalWrite(LED_WIFI_PIN, HIGH); 
  }

  for(int i = 0; i < 3; i++){
    digitalWrite(LED_POWER_PIN, LOW); delay(200);
    digitalWrite(LED_POWER_PIN, HIGH); delay(200);
  }

  Serial.println("--- SẴN SÀNG HOẠT ĐỘNG ---\n");
}

// ================= HÀM LOOP CHÍNH =================
void loop() {
  
  updateGPS();

  // Kiểm tra trạng thái đèn WiFi
  if (WiFi.status() == WL_CONNECTED) {
    digitalWrite(LED_WIFI_PIN, HIGH);
  } else {
    digitalWrite(LED_WIFI_PIN, LOW);
  }

  // ---------------------------------------------------------
  // TRẠNG THÁI 1: CHẠY XE (ĐỌC CẢM BIẾN & XỬ LÝ SỰ CỐ)
  // ---------------------------------------------------------
  if (currentState == STATE_SENSING) {
    
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);

    float totalAccel = sqrt(a.acceleration.x * a.acceleration.x + 
                            a.acceleration.y * a.acceleration.y + 
                            a.acceleration.z * a.acceleration.z);

    emaAccel = EMA_ALPHA * totalAccel + (1.0 - EMA_ALPHA) * emaAccel;
    float delta = fabs(totalAccel - emaAccel);

    float gyroMagnitude = sqrt(g.gyro.x * g.gyro.x + 
                               g.gyro.y * g.gyro.y + 
                               g.gyro.z * g.gyro.z);

    if (delta > ACCEL_DELTA_THRESHOLD && gyroMagnitude > GYRO_MAG_THRESHOLD) {
      if (millis() - lastReportTime > COOLDOWN_TIME) {
        lastReportTime = millis(); 
        
        Serial.println("\n💥 PHÁT HIỆN HỐ GÀ!");
        Serial.printf("Delta Gia tốc: %.2f m/s2 | Độ chao đảo Gyro: %.2f Rad/s\n", delta, gyroMagnitude);

        if (hasValidGPS) {
          // [THAY ĐỔI]: Xử lý logic Online/Offline ngay khi phát hiện
          if (WiFi.status() == WL_CONNECTED) {
            Serial.println("🌐 Đang ở chế độ ONLINE - Gửi trực tiếp lên database...");
            bool success = sendDataAPI(lastValidLat, lastValidLng);
            if (!success) {
              Serial.println("⚠️ Gửi thất bại, lưu tạm vào thẻ nhớ để chờ đồng bộ sau!");
              savePotholeToSD(lastValidLat, lastValidLng);
            }
          } else {
            Serial.println("📴 Đang ở chế độ OFFLINE - Lưu sự cố vào thẻ nhớ...");
            savePotholeToSD(lastValidLat, lastValidLng);
          }
        } else {
          Serial.println("⚠️ Mất sóng GPS chưa có tọa độ. BỎ QUA ĐIỂM NÀY!");
        }
      }
    }

    if (millis() - lastDebugTime > DEBUG_INTERVAL) {
      Serial.println("\n=============== ĐANG CHẠY XE ===============");
      Serial.printf("Trạng thái mạng: %s\n", (WiFi.status() == WL_CONNECTED) ? "ONLINE" : "OFFLINE");
      Serial.printf("🧭 Vector Tổng: %.2f | Nền EMA: %.2f | Delta: %.2f\n", totalAccel, emaAccel, delta);
      Serial.printf("🌀 Vector Gyro: %.2f\n", gyroMagnitude);
      Serial.printf("🛰️  GPS Tọa độ chốt: %.6f, %.6f\n", lastValidLat, lastValidLng);
      Serial.println("==============================================");
      lastDebugTime = millis();
    }

    // Lắng nghe Nút Nhấn (Debounce)
    int currentBtnReading = digitalRead(BTN_SYNC_PIN);
    
    if (currentBtnReading != lastBtnState) {
      lastDebounceTime = millis();
    }

    if ((millis() - lastDebounceTime) > DEBOUNCE_DELAY) {
      if (currentBtnReading == LOW && currentState == STATE_SENSING) {
        // [THAY ĐỔI]: Nút chỉ hoạt động khi đang Offline
        if (WiFi.status() != WL_CONNECTED) {
          Serial.println("\n[NÚT NHẤN] Thiết bị đang Offline. Bắt đầu tìm WiFi và đồng bộ...");
          currentState = STATE_SYNCING;
        } else {
          Serial.println("\n[NÚT NHẤN] Thiết bị ĐÃ ONLINE sẵn. Các sự kiện đã tự động đẩy lên Database.");
        }
      }
    }
    lastBtnState = currentBtnReading;
  }

  // ---------------------------------------------------------
  // TRẠNG THÁI 2: DỪNG ĐỖ & ĐỒNG BỘ DỮ LIỆU TỪ THẺ NHỚ
  // ---------------------------------------------------------
  else if (currentState == STATE_SYNCING) {
    digitalWrite(LED_POWER_PIN, LOW); 
    
    // Gọi WiFi.begin() không tham số để tự động kết nối lại mạng đã lưu trong NVS của WiFiManager
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("Đang tìm và kết nối WiFi đã lưu để đẩy dữ liệu...");
      WiFi.begin(); 
      int attempts = 0;
      while (WiFi.status() != WL_CONNECTED && attempts < 20) { // Đợi tối đa ~10 giây
        delay(500);
        Serial.print(".");
        attempts++;
        updateGPS(); 
      }
    }

    if (WiFi.status() == WL_CONNECTED) {
      Serial.println("\nWiFi OK. Bắt đầu đẩy dữ liệu lưu trữ từ thẻ nhớ...");
      digitalWrite(LED_WIFI_PIN, HIGH);
      
      syncDataWithSupabase();
      
    } else {
      Serial.println("\nLỗi: Không tìm thấy WiFi đã lưu hoặc kết nối thất bại!");
      digitalWrite(LED_WIFI_PIN, LOW);
    }

    lastBtnState = digitalRead(BTN_SYNC_PIN); 
    digitalWrite(LED_POWER_PIN, HIGH); 
    currentState = STATE_SENSING;
    Serial.println("\n--- TIẾP TỤC ĐO ĐẠC HỐ GÀ ---");
  }
}

// ================= CÁC HÀM XỬ LÝ DỮ LIỆU =================

void savePotholeToSD(float lat, float lng) {
  if (!sdReady) {
    Serial.println("❌ Lỗi: Thẻ SD không sẵn sàng. Hủy thao tác ghi để chống crash.");
    return;
  }

  File file = SD.open("/unsent.txt", FILE_APPEND);
  if (file) {
    file.print(lat, 6);
    file.print(",");
    file.println(lng, 6);
    file.close();
    Serial.println("📁 Đã lưu vào thẻ nhớ thành công!");
  } else {
    Serial.println("❌ Lỗi: Có thẻ SD nhưng không thể mở file để ghi!");
  }
}

bool sendDataAPI(float lat, float lng) {
  StaticJsonDocument<200> doc;
  doc["lat"] = lat;
  doc["lng"] = lng;
  doc["type"] = "Hố gà";
  doc["device_id"] = DEVICE_ID; 
  doc["description"] = "Cảnh báo tự động từ phương tiện (Đã đồng bộ).";
  doc["status"] = "approved";
  doc["image_url"] = "[]";

  String requestBody;
  serializeJson(doc, requestBody);

  bool success = false;

  for (int retry = 1; retry <= 3; retry++) {
    HTTPClient http;
    http.setTimeout(10000); 
    http.begin(SUPABASE_URL);

    http.addHeader("apikey", SUPABASE_KEY);
    http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("Prefer", "return=minimal");

    Serial.printf("  -> Lần thử API %d/3... ", retry);
    int httpResponseCode = http.POST(requestBody);
    
    Serial.printf("Mã HTTP: %d\n", httpResponseCode);

    if (httpResponseCode >= 200 && httpResponseCode < 300) {
      success = true;
      http.end();
      break; 
    } 

    http.end();
    
    unsigned long waitStart = millis();
    while (millis() - waitStart < 1000) {
      updateGPS();
      delay(10);
    }
  }

  return success;
}

void syncDataWithSupabase() {
  if (!sdReady) {
    Serial.println("❌ Lỗi: Thẻ SD không sẵn sàng. Không thể đồng bộ.");
    return;
  }

  if (!SD.exists("/unsent.txt")) {
    Serial.println("✅ Thẻ nhớ sạch: Không có dữ liệu hố gà nào cần đồng bộ.");
    return;
  }
  
  Serial.println("\n🔄 Đang xử lý file unsent.txt...");
  SD.rename("/unsent.txt", "/sync.txt");

  File syncFile = SD.open("/sync.txt", FILE_READ);
  File failFile = SD.open("/unsent.txt", FILE_APPEND); 

  if (!syncFile) {
    Serial.println("❌ Lỗi: Không mở được file sync.txt");
    return;
  }

  int count = 0;
  while (syncFile.available()) {
    updateGPS();

    String line = syncFile.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;

    int commaIndex = line.indexOf(',');
    if (commaIndex > 0) {
      String latStr = line.substring(0, commaIndex);
      String lngStr = line.substring(commaIndex + 1);
                 
      float lat = latStr.toFloat();
      float lng = lngStr.toFloat();

      bool success = sendDataAPI(lat, lng);
      
      if (success) {
        Serial.printf("✅ Gửi OK: %s, %s\n", latStr.c_str(), lngStr.c_str());
        count++;
      } else {
        Serial.printf("❌ Gửi LỖI 3 LẦN: %s, %s -> Trả lại vào thẻ nhớ.\n", latStr.c_str(), lngStr.c_str());
        if (failFile) failFile.println(line); 
      }
    }
  }

  syncFile.close();
  if (failFile) failFile.close();

  SD.remove("/sync.txt"); // Dữ liệu đồng bộ xong sẽ bị làm sạch an toàn
  if (count > 0) {
    Serial.printf("🎉 Đồng bộ hoàn tất! Tải lên %d sự kiện thành công.\n\n", count);
  } else {
    Serial.println("⚠️ Không có sự kiện nào được tải lên hoặc toàn bộ đều lỗi mạng.");
  }
}