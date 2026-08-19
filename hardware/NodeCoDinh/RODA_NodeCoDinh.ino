#include <WiFi.h>
#include <WiFiManager.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <ArduinoJson.h>

// ================= CẤU HÌNH PHẦN CỨNG =================
#define TRIG_PIN 5
#define ECHO_PIN 18
#define POWER_LED_PIN 4 

// ================= THÔNG TIN WIFI CỐ ĐỊNH =================
const char* default_ssid = "Henry";
const char* default_password = "88888888";

// ================= CẤU HÌNH SUPABASE =================
const String SUPABASE_URL = "https://sweqvobmlntyhyeuurfr.supabase.co";
const String SUPABASE_KEY = "sb_publishable_xsqRVFRoQSh0c9wzwc5vxA_Hw9aj9fF";
const String DEVICE_ID = "NODE_CODINH_01"; 

// ================= THÔNG SỐ VẬN HÀNH & CHỐNG NHIỄU =================
const int SLEEP_MINUTES = 15;
const float FLOOD_THRESHOLD = 1.0;         // Ngưỡng bắt đầu tính là ngập (cm)
#define WATER_CHANGE_THRESHOLD 1.0         // Ngưỡng thay đổi mực nước tối thiểu để UPDATE (tránh spam DB do nhiễu cảm biến)

float base_distance = 0;
float node_lat = 0;
float node_lng = 0;

void setup() {
  Serial.begin(115200);
  
  // Khởi tạo và bật LED báo nguồn ngay khi vừa thức dậy
  pinMode(POWER_LED_PIN, OUTPUT);
  digitalWrite(POWER_LED_PIN, HIGH);

  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);

  Serial.println("\n--- KHỞI ĐỘNG TRẠM QUAN TRẮC NGẬP LỤT (WIFI & SUPABASE EVENT MANAGEMENT) ---");

  // 1. ĐO SÓNG SIÊU ÂM
  float current_distance = getAverageDistance();
  if (current_distance < 0) {
    Serial.println("❌ Lỗi: Cảm biến siêu âm bị nhiễu hoặc không phản hồi.");
    goToSleep();
  }
  Serial.printf("📏 Khoảng cách cảm biến đo được: %.2f cm\n", current_distance);

  // 2. KẾT NỐI WIFI
  Serial.println("\nĐang thử kết nối WiFi mặc định trong code...");
  WiFi.begin(default_ssid, default_password);
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 20) { 
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\n⚠️ Không thấy WiFi mặc định. Chuyển sang quét mạng đã lưu hoặc mở AP...");
    WiFiManager wm;
    wm.setConfigPortalTimeout(180); 
    
    if (!wm.autoConnect("RODA_NodeCoDinh")) {
      Serial.println("❌ Không ai cấu hình mạng hoặc rớt mạng. Đi ngủ chờ lần sau...");
      goToSleep();
    }
  }
  
  Serial.print("\n✅ Đã kết nối WiFi thành công! IP: ");
  Serial.println(WiFi.localIP());

  // 3. ĐỒNG BỘ THỜI GIAN (NTP) TRƯỚC KHI XỬ LÝ
  Serial.print("⏳ Đang đồng bộ thời gian (NTP)...");
  // Dùng UTC 0 để format ra chuỗi chuẩn Z (Zulu Time) gửi lên Supabase
  configTime(0, 0, "pool.ntp.org", "time.nist.gov"); 
  int ntpRetry = 0;
  while (time(nullptr) < 100000 && ntpRetry < 10) {
    delay(500);
    Serial.print(".");
    ntpRetry++;
  }
  Serial.println(" Hoàn tất!");

  // 4. TẢI CẤU HÌNH TỪ SUPABASE (Bảng devices)
  if (fetchDeviceConfig()) {
    
    // Tính mức ngập thực tế
    float water_level = base_distance - current_distance;
    if (water_level < 0) {
      water_level = 0.0; // Không để mức nước âm do sai số vật lý
    }
    Serial.printf("🌊 Mức ngập thực tế tính toán: %.2f cm\n", water_level);

    // 5. XỬ LÝ LOGIC TRẠNG THÁI SỰ KIỆN (STATE MACHINE CHO NGẬP LỤT)
    processFloodEvent(water_level);
  }

  // 6. NGẮT KẾT NỐI & ĐI NGỦ
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  Serial.println("\nĐã tắt WiFi an toàn.");
  
  goToSleep();
}

void loop() {
}

// ================= CÁC HÀM XỬ LÝ LÕI =================

void goToSleep() {
  Serial.printf("💤 Hệ thống đi ngủ sâu %d phút...\n", SLEEP_MINUTES);
  Serial.flush(); 
  
  digitalWrite(POWER_LED_PIN, LOW); // Tắt LED trước khi đi ngủ để tiết kiệm năng lượng
  
  esp_sleep_enable_timer_wakeup(SLEEP_MINUTES * 60 * 1000000ULL);
  esp_deep_sleep_start();
}

float getAverageDistance() {
  float sum = 0;
  int validReadings = 0;
  for (int i = 0; i < 5; i++) { 
    digitalWrite(TRIG_PIN, LOW); delayMicroseconds(2);
    digitalWrite(TRIG_PIN, HIGH); delayMicroseconds(10);
    digitalWrite(TRIG_PIN, LOW);
    
    long duration = pulseIn(ECHO_PIN, HIGH, 30000); 
    
    if (duration == 0) { delay(50); continue; }

    float distance = (duration * 0.0343) / 2;
    if (distance > 2.0 && distance < 450.0) {
      sum += distance;
      validReadings++;
    }
    
    if (validReadings >= 3) break; 
    delay(50);
  }

  if (validReadings == 0) return -1.0; 
  return sum / validReadings;
}


String getCurrentTimestamp() {
  time_t now = time(nullptr);
  if (now < 100000) {
    return "now"; 
  }
  
  struct tm timeinfo;
  gmtime_r(&now, &timeinfo);
  
  char buffer[30];
  // Định dạng ISO 8601 (VD: 2026-07-28T09:30:00Z)
  strftime(buffer, sizeof(buffer), "%Y-%m-%dT%H:%M:%SZ", &timeinfo);
  return String(buffer);
}

bool fetchDeviceConfig() {
  WiFiClientSecure secureClient;
  secureClient.setInsecure();
  
  HTTPClient http;
  String url = SUPABASE_URL + "/rest/v1/devices?id=eq." + DEVICE_ID + "&select=base_distance,lat,lng";
  
  Serial.println("Đang tải dữ liệu cấu hình từ Supabase...");
  http.begin(secureClient, url);
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);

  int statusCode = http.GET();
  String response = http.getString();
  http.end();
  
  if (statusCode == 200) {
    StaticJsonDocument<256> doc;
    DeserializationError error = deserializeJson(doc, response);
    if (!error && doc.size() > 0) {
      base_distance = doc[0]["base_distance"];
      node_lat = doc[0]["lat"];
      node_lng = doc[0]["lng"];
      Serial.printf("📥 Tải thành công: Khoảng cách gốc = %.2f cm | Lat = %.6f\n", base_distance, node_lat);
      return true;
    }
  }
  
  Serial.printf("❌ Lỗi tải cấu hình! Mã lỗi: %d\n", statusCode);
  return false;
}

// ================= HÀM XỬ LÝ SỰ KIỆN NGẬP (QUẢN LÝ approved / UPDATE / RESOLVED) =================
void processFloodEvent(float waterLevel) {
  WiFiClientSecure secureClient;
  secureClient.setInsecure();
  
  HTTPClient http;
  
  // Truy vấn tìm xem thiết bị hiện tại có bản ghi nào đang ở trạng thái 'approved' hay không
  String checkUrl = SUPABASE_URL + "/rest/v1/road_events?device_id=eq." + DEVICE_ID + "&status=eq.approved&select=id,distance";
  
  Serial.println("🔍 Đang kiểm tra trạng thái sự cố approved trên Supabase...");
  http.begin(secureClient, checkUrl);
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);

  int statusCode = http.GET();
  String response = http.getString();
  http.end();

  if (statusCode != 200) {
    Serial.printf("❌ Lỗi khi kiểm tra approved event! Mã HTTP: %d\n", statusCode);
    return;
  }

  StaticJsonDocument<512> doc;
  DeserializationError error = deserializeJson(doc, response);
  if (error) {
    Serial.println("❌ Lỗi phân tích JSON phản hồi kiểm tra approved event!");
    return;
  }

  bool hasapprovedEvent = (doc.size() > 0);
  
  if (hasapprovedEvent) {
    // ---- TRƯỜNG HỢP ĐÃ CÓ approved EVENT ----
    int eventId = doc[0]["id"];
    float lastDistance = doc[0]["distance"];
    
    Serial.printf("ℹ️ Tìm thấy approved Event ID: %d | Mức ngập cũ đang lưu: %.2f cm\n", eventId, lastDistance);

    // TRƯỜNG HỢP 4: Nếu waterLevel = 0 (nước đã rút) -> Chuyển status thành 'resolved', distance = 0
    if (waterLevel <= 0.0) {
      Serial.println("✅ Nước đã rút hoàn toàn (waterLevel = 0). Đóng sự cố -> chuyển status = resolved...");
      updateEventStatus(eventId, "resolved", 0.0);
    } 
    // TRƯỜNG HỢP 2 & 3: Đã có sự cố approved, nước dâng lên hoặc giảm nhưng chưa về 0 -> UPDATE distance nếu thay đổi >= ngưỡng chống nhiễu
    else {
      float diff = abs(waterLevel - lastDistance);
      if (diff >= WATER_CHANGE_THRESHOLD) {
        Serial.printf("🔄 Mức nước thay đổi (Delta: %.2f cm >= ngưỡng %.1f cm). Đang cập nhật distance mới...\n", diff, (float)WATER_CHANGE_THRESHOLD);
        updateEventDistance(eventId, waterLevel);
      } else {
        Serial.printf("💤 Biến động mực nước (%.2f cm) nhỏ hơn ngưỡng chống nhiễu (%.1f cm). Bỏ qua update.\n", diff, (float)WATER_CHANGE_THRESHOLD);
      }
    }

  } else {
    // ---- TRƯỜNG HỢP KHÔNG CÓ approved EVENT NÀO ----
    // TRƯỜNG HỢP 1: Nếu waterLevel đạt hoặc vượt ngưỡng cảnh báo -> INSERT một bản ghi mới hoàn toàn với status = 'approved'
    if (waterLevel >= FLOOD_THRESHOLD) {
      Serial.printf("🚨 Phát hiện ngập mới (%.2f cm >= ngưỡng %.1f cm). Tạo bản ghi 'approved' mới...\n", waterLevel, FLOOD_THRESHOLD);
      createNewapprovedEvent(waterLevel);
    } else {
      Serial.println("✅ Mức nước an toàn dưới ngưỡng cảnh báo. Không có hành động nào được thực hiện.");
    }
  }
}

// Hàm INSERT bản ghi mới khi bắt đầu đợt ngập
void createNewapprovedEvent(float depth) {
  WiFiClientSecure secureClient;
  secureClient.setInsecure();
  
  HTTPClient http;
  http.begin(secureClient, SUPABASE_URL + "/rest/v1/road_events");
  
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Prefer", "return=minimal");

  // Tăng size JSON Document lên một chút để an toàn khi nối chuỗi
  StaticJsonDocument<384> doc; 
  doc["lat"] = node_lat;
  doc["lng"] = node_lng;
  doc["type"] = "Lũ lụt";
  doc["device_id"] = DEVICE_ID;
  doc["description"] = "Cảnh báo ngập lụt tự động! Mức ngập ban đầu: " + String(depth, 1) + " cm.";
  doc["status"] = "approved";
  doc["distance"] = depth;
  doc["approved_at"] = getCurrentTimestamp(); // Ghi nhận thời gian tạo sự cố
  doc.createNestedArray("image_url");

  String requestBody;
  serializeJson(doc, requestBody);

  int statusCode = http.POST(requestBody);
  http.end();
  
  if (statusCode >= 200 && statusCode < 300) {
    Serial.println("✅ Tạo bản ghi ngập lụt mới (approved) THÀNH CÔNG trên Supabase!");
  } else {
    Serial.printf("❌ Tạo bản ghi thất bại, mã HTTP: %d\n", statusCode);
    Serial.println(http.getString());
  }
}

// Hàm PATCH cập nhật giá trị distance (mực nước) cho sự cố approved hiện tại
void updateEventDistance(int eventId, float depth) {
  WiFiClientSecure secureClient;
  secureClient.setInsecure();
  
  HTTPClient http;
  String url = SUPABASE_URL + "/rest/v1/road_events?id=eq." + String(eventId);
  http.begin(secureClient, url);
  
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Prefer", "return=minimal");

  StaticJsonDocument<384> doc;
  doc["distance"] = depth;
  doc["description"] = "Cập nhật mực ngập hiện tại: " + String(depth, 1) + " cm.";
  doc["approved_at"] = getCurrentTimestamp(); // Ghi nhận mốc thời gian update

  String requestBody;
  serializeJson(doc, requestBody);

  int statusCode = http.sendRequest("PATCH", (uint8_t*)requestBody.c_str(), requestBody.length());
  http.end();
  
  if (statusCode >= 200 && statusCode < 300) {
    Serial.printf("✅ Cập nhật distance = %.2f cm cho event ID %d THÀNH CÔNG!\n", depth, eventId);
  } else {
    Serial.printf("❌ Cập nhật distance thất bại, mã HTTP: %d\n", statusCode);
  }
}

// Hàm PATCH chuyển trạng thái thành 'resolved' khi nước rút hoàn toàn về 0
void updateEventStatus(int eventId, String newStatus, float depth) {
  WiFiClientSecure secureClient;
  secureClient.setInsecure();
  
  HTTPClient http;
  String url = SUPABASE_URL + "/rest/v1/road_events?id=eq." + String(eventId);
  http.begin(secureClient, url);
  
  http.addHeader("apikey", SUPABASE_KEY);
  http.addHeader("Authorization", "Bearer " + SUPABASE_KEY);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Prefer", "return=minimal");

  StaticJsonDocument<384> doc;
  doc["status"] = newStatus;
  doc["distance"] = depth;
  doc["description"] = "Nước đã rút hoàn toàn. Sự cố kết thúc.";
  doc["approved_at"] = getCurrentTimestamp(); // Ghi nhận mốc thời gian nước rút hoàn toàn

  String requestBody;
  serializeJson(doc, requestBody);

  int statusCode = http.sendRequest("PATCH", (uint8_t*)requestBody.c_str(), requestBody.length());
  http.end();
  
  if (statusCode >= 200 && statusCode < 300) {
    Serial.printf("✅ Đã đóng sự cố (status = resolved, distance = 0) cho event ID %d THÀNH CÔNG!\n", eventId);
  } else {
    Serial.printf("❌ Đóng sự cố thất bại, mã HTTP: %d\n", statusCode);
  }
}