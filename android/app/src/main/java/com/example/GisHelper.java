package com.example;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GisHelper {
    private static final String TAG = "GisHelper";

    private final OkHttpClient client;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public interface RouteCallback {
        void onSuccess(List<GeoPoint> routeCoords, List<RouteInstruction> instructions);
        void onError(Exception e);
    }

    public interface SearchCallback {
        void onSuccess(double lat, double lng, String displayName, List<List<GeoPoint>> boundaryPolygons);
        void onError(Exception e);
    }

    public static class RouteInstruction {
        public String text;
        public double distance;

        public RouteInstruction(String text, double distance) {
            this.text = text;
            this.distance = distance;
        }
    }

    public GisHelper() {
        this.client = new OkHttpClient();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // Call OSRM to find driving route
    public void getRoute(final double lat1, final double lng1, final double lat2, final double lng2, final RouteCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = String.format("https://router.project-osrm.org/route/v1/driving/%s,%s;%s,%s?overview=full&geometries=geojson&steps=true",
                            lng1, lat1, lng2, lat2);

                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", "RODA-Android-App")
                            .get()
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("OSRM error response: " + response.code());
                        }

                        String respBody = response.body().string();
                        JSONObject root = new JSONObject(respBody);
                        JSONArray routes = root.getJSONArray("routes");
                        if (routes.length() == 0) {
                            throw new IOException("No route found");
                        }

                        JSONObject firstRoute = routes.getJSONObject(0);

                        // Extract Geometry Coordinates
                        JSONObject geometry = firstRoute.getJSONObject("geometry");
                        JSONArray coordsArray = geometry.getJSONArray("coordinates");
                        List<GeoPoint> routeCoords = new ArrayList<>();
                        for (int i = 0; i < coordsArray.length(); i++) {
                            JSONArray point = coordsArray.getJSONArray(i);
                            double lng = point.getDouble(0);
                            double lat = point.getDouble(1);
                            routeCoords.add(new GeoPoint(lat, lng));
                        }

                        // Extract navigation steps instructions
                        List<RouteInstruction> instructions = new ArrayList<>();
                        JSONArray legs = firstRoute.getJSONArray("legs");
                        if (legs.length() > 0) {
                            JSONArray steps = legs.getJSONObject(0).getJSONArray("steps");
                            for (int i = 0; i < steps.length(); i++) {
                                JSONObject step = steps.getJSONObject(i);
                                double distance = step.optDouble("distance", 0.0);
                                JSONObject maneuver = step.optJSONObject("maneuver");
                                String text = "";
                                if (maneuver != null) {
                                    text = maneuver.optString("instruction", "");
                                }
                                if (text.isEmpty()) {
                                    text = "Từ đường " + step.optString("name", "không tên");
                                }
                                instructions.add(new RouteInstruction(text, distance));
                            }
                        }

                        sendOnRouteSuccess(callback, routeCoords, instructions);
                    }
                } catch (Exception e) {
                    sendOnRouteError(callback, e);
                }
            }
        });
    }

    // Call Nominatim Geocoder to search locations
    public void searchLocation(final String query, final SearchCallback callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String encodedQuery;
                    try {
                        encodedQuery = URLEncoder.encode(query, "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        encodedQuery = query;
                    }
                    String url = "https://nominatim.openstreetmap.org/search?q=" + encodedQuery + "&format=json&polygon_geojson=1&limit=1";

                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", "RODA-Android-App")
                            .get()
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            throw new IOException("Geocoding failed. Status: " + response.code());
                        }

                        String respBody = response.body().string();
                        JSONArray results = new JSONArray(respBody);
                        if (results.length() == 0) {
                            throw new IOException("Không tìm thấy địa điểm nào.");
                        }

                        JSONObject firstResult = results.getJSONObject(0);
                        double lat = firstResult.getDouble("lat");
                        double lng = firstResult.getDouble("lon");
                        String displayName = firstResult.getString("display_name");

                        // Extract Boundary Polygons if available
                        List<List<GeoPoint>> boundaries = new ArrayList<>();
                        JSONObject geojson = firstResult.optJSONObject("geojson");
                        if (geojson != null) {
                            String type = geojson.optString("type", "");
                            if ("Polygon".equalsIgnoreCase(type)) {
                                JSONArray rings = geojson.getJSONArray("coordinates");
                                if (rings.length() > 0) {
                                    JSONArray outerRing = rings.getJSONArray(0);
                                    List<GeoPoint> polygonPts = new ArrayList<>();
                                    for (int j = 0; j < outerRing.length(); j++) {
                                        JSONArray pt = outerRing.getJSONArray(j);
                                        polygonPts.add(new GeoPoint(pt.getDouble(1), pt.getDouble(0)));
                                    }
                                    boundaries.add(polygonPts);
                                }
                            } else if ("MultiPolygon".equalsIgnoreCase(type)) {
                                JSONArray polys = geojson.getJSONArray("coordinates");
                                for (int p = 0; p < polys.length(); p++) {
                                    JSONArray rings = polys.getJSONArray(p);
                                    if (rings.length() > 0) {
                                        JSONArray outerRing = rings.getJSONArray(0);
                                        List<GeoPoint> polygonPts = new ArrayList<>();
                                        for (int j = 0; j < outerRing.length(); j++) {
                                            JSONArray pt = outerRing.getJSONArray(j);
                                            polygonPts.add(new GeoPoint(pt.getDouble(1), pt.getDouble(0)));
                                        }
                                        boundaries.add(polygonPts);
                                    }
                                }
                            }
                        }

                        sendOnSearchSuccess(callback, lat, lng, displayName, boundaries);
                    }
                } catch (Exception e) {
                    sendOnSearchError(callback, e);
                }
            }
        });
    }

    // Point in Polygon Algorithm
    public static boolean isPointInPolygon(GeoPoint point, List<GeoPoint> polygon) {
        double x = point.getLongitude();
        double y = point.getLatitude();
        boolean inside = false;
        int size = polygon.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            double xi = polygon.get(i).getLongitude();
            double yi = polygon.get(i).getLatitude();
            double xj = polygon.get(j).getLongitude();
            double yj = polygon.get(j).getLatitude();

            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    // Distance in Degrees to segment (Approximate on flat surface for short distances)
    public static double distanceToSegment(GeoPoint p, GeoPoint p1, GeoPoint p2) {
        double x = p.getLongitude();
        double y = p.getLatitude();

        double x1 = p1.getLongitude();
        double y1 = p1.getLatitude();

        double x2 = p2.getLongitude();
        double y2 = p2.getLatitude();

        double A = x - x1;
        double B = y - y1;
        double C = x2 - x1;
        double D = y2 - y1;

        double dot = A * C + B * D;
        double len_sq = C * C + D * D;

        double param = -1;
        if (len_sq != 0) {
            param = dot / len_sq;
        }

        double xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }

        double dx = x - xx;
        double dy = y - yy;

        return Math.sqrt(dx * dx + dy * dy);
    }

    // Check if point is near any segments on route coordinates (within custom buffer threshold, e.g. 0.00045 degrees approx 50m)
    public static boolean isPointNearRoute(GeoPoint p, List<GeoPoint> routeCoords) {
        final double buffer = 0.00045; // Approximately 50 meters
        for (int i = 0; i < routeCoords.size() - 1; i++) {
            double d = distanceToSegment(p, routeCoords.get(i), routeCoords.get(i + 1));
            if (d < buffer) {
                return true;
            }
        }
        return false;
    }

    private void sendOnRouteSuccess(final RouteCallback callback, final List<GeoPoint> coords, final List<RouteInstruction> insts) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(coords, insts);
            }
        });
    }

    private void sendOnRouteError(final RouteCallback callback, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }

    private void sendOnSearchSuccess(final SearchCallback callback, final double lat, final double lng, final String name, final List<List<GeoPoint>> bounds) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(lat, lng, name, bounds);
            }
        });
    }

    private void sendOnSearchError(final SearchCallback callback, final Exception e) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }
}
