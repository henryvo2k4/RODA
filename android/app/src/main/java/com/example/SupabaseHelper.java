package com.example;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SupabaseHelper {
    private static final String TAG = "SupabaseHelper";

    // Database credentials
    private static final String SUPABASE_URL = "https://sweqvobmlntyhyeuurfr.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_xsqRVFRoQSh0c9wzwc5vxA_Hw9aj9fF";
    private static final String AI_API_URL = "https://hen2k4-roda-ai-api.hf.space/analyze-incident";

    private final OkHttpClient client;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public SupabaseHelper() {
        this.client = new OkHttpClient();
        this.executorService = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // =========================================================================
    // THUẬT TOÁN "SNAP TO ROAD" - TỰ ĐỘNG HÚT TỌA ĐỘ VÀO CHÍNH GIỮA CON ĐƯỜNG
    // =========================================================================
    private double[] snapToRoadSync(double lat, double lng) {
        try {
            // Gọi API OSRM Nearest để tìm con đường gần tọa độ này nhất
            String url = String.format(Locale.US, "https://router.project-osrm.org/nearest/v1/driving/%f,%f", lng, lat);
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "RODA-Android-App")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject root = new JSONObject(response.body().string());
                    JSONArray waypoints = root.optJSONArray("waypoints");
                    if (waypoints != null && waypoints.length() > 0) {
                        JSONArray location = waypoints.getJSONObject(0).getJSONArray("location");
                        // OSRM trả về mảng theo thứ tự [kinh độ, vĩ độ]
                        return new double[]{location.getDouble(1), location.getDouble(0)};
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi Snap To Road", e);
        }
        // Nếu API lỗi, trả về tọa độ gốc ban đầu
        return new double[]{lat, lng};
    }

    public void fetchApprovedEvents(final Callback<List<Incident>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = SUPABASE_URL + "/rest/v1/road_events?status=eq.approved";
                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("apikey", SUPABASE_KEY)
                            .addHeader("Authorization", "Bearer " + SUPABASE_KEY)
                            .get()
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("Unexpected HTTP response code " + response);
                        }
                        String responseBody = response.body().string();
                        JSONArray jsonArray = new JSONArray(responseBody);
                        List<Incident> list = new ArrayList<>();
                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject obj = jsonArray.getJSONObject(i);
                            double lat = obj.optDouble("lat", 0.0);
                            double lng = obj.optDouble("lng", 0.0);
                            String type = obj.optString("type", "Hố gà");
                            String desc = obj.optString("description", "");
                            String imgUrl = obj.optString("image_url", "[]");
                            int dist = obj.optInt("distance", 50);
                            String status = obj.optString("status", "approved");
                            String created = obj.optString("created_at", "");
                            String approved = obj.optString("approved_at", "");


                            // SỬ DỤNG SNAP TO ROAD ĐỂ CHỈNH LẠI CÁC ĐIỂM CŨ TRONG DB CHO CHUẨN XÁC
                            double[] snapped = snapToRoadSync(lat, lng);
                            list.add(new Incident(snapped[0], snapped[1], type, desc, imgUrl, dist, status, created, approved));
                        }

                        sendOnSuccess(callback, list);
                    }
                } catch (Exception e) {
                    sendOnError(callback, e);
                }
            }
        });
    }

    public void uploadImage(final byte[] imageBytes, final Callback<String> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileName = UUID.randomUUID().toString() + "_roda_report.jpg";
                    String uploadUrl = SUPABASE_URL + "/storage/v1/object/road-images/" + fileName;

                    RequestBody body = RequestBody.create(imageBytes, MediaType.parse("image/jpeg"));

                    Request request = new Request.Builder()
                            .url(uploadUrl)
                            .addHeader("apikey", SUPABASE_KEY)
                            .addHeader("Authorization", "Bearer " + SUPABASE_KEY)
                            .post(body)
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("Failed to upload image. Server code: " + response.code());
                        }

                        String publicUrl = SUPABASE_URL + "/storage/v1/object/public/road-images/" + fileName;
                        sendOnSuccess(callback, publicUrl);
                    }
                } catch (Exception e) {
                    sendOnError(callback, e);
                }
            }
        });
    }

    public void analyzeWithAI(final String imageUrl, final String userTypeSelect, final Callback<String> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject requestJson = new JSONObject();
                    requestJson.put("image_url", imageUrl);

                    RequestBody body = RequestBody.create(
                            requestJson.toString(),
                            MediaType.parse("application/json; charset=utf-8")
                    );

                    Request request = new Request.Builder()
                            .url(AI_API_URL)
                            .post(body)
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        String status = "pending";

                        if (response.isSuccessful() && response.body() != null) {
                            String respString = response.body().string();
                            Log.d(TAG, "AI API Answer: " + respString);
                            JSONObject aiResult = new JSONObject(respString);

                            boolean detected = aiResult.optBoolean("detected", false);
                            String aiLabel = aiResult.optString("ai_label", "unknown");
                            double confidence = aiResult.optDouble("confidence", 0.0);

                            if (detected) {
                                String mappedUserType = "unknown";
                                if ("Hố gà".equals(userTypeSelect)) mappedUserType = "pothole";
                                if ("Lũ lụt".equals(userTypeSelect)) mappedUserType = "flood";
                                if ("Thi công".equals(userTypeSelect)) mappedUserType = "construction";

                                if (mappedUserType.equals(aiLabel) && confidence > 0.7) {
                                    status = "approved";
                                }
                            }
                        }
                        sendOnSuccess(callback, status);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "AI service error, fallback to status pending", e);
                    sendOnSuccess(callback, "pending");
                }
            }
        });
    }

    public void submitReport(final double lat, final double lng, final String type, final String description,
                             final String imageUrl, final int distance, final String finalStatus, final Callback<Boolean> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // CĂN CHỈNH TỌA ĐỘ NGƯỜI DÙNG VÀO GIỮA ĐƯỜNG TRƯỚC KHI LƯU DB
                    double[] snapped = snapToRoadSync(lat, lng);
                    double finalLat = snapped[0];
                    double finalLng = snapped[1];

                    SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                    isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String timestamp = isoFormat.format(new Date());

                    JSONObject reportObj = new JSONObject();
                    reportObj.put("lat", finalLat);
                    reportObj.put("lng", finalLng);
                    reportObj.put("type", type);
                    reportObj.put("description", description);

                    JSONArray imgArray = new JSONArray();
                    imgArray.put(imageUrl);
                    reportObj.put("image_url", imgArray.toString());

                    reportObj.put("distance", distance);
                    reportObj.put("status", finalStatus);
                    reportObj.put("created_at", timestamp);
                    if ("approved".equals(finalStatus)) {
                        reportObj.put("approved_at", timestamp);
                    } else {
                        reportObj.put("approved_at", JSONObject.NULL);
                    }

                    RequestBody body = RequestBody.create(
                            reportObj.toString(),
                            MediaType.parse("application/json; charset=utf-8")
                    );

                    String url = SUPABASE_URL + "/rest/v1/road_events";
                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("apikey", SUPABASE_KEY)
                            .addHeader("Authorization", "Bearer " + SUPABASE_KEY)
                            .post(body)
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("Insert event error response: " + response.code());
                        }
                        sendOnSuccess(callback, true);
                    }
                } catch (Exception e) {
                    sendOnError(callback, e);
                }
            }
        });
    }

    private <T> void sendOnSuccess(final Callback<T> callback, final T result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(result);
            }
        });
    }

    private <T> void sendOnError(final Callback<T> callback, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }
}