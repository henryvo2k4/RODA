package com.example;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 4055;
    private static final int CAMERA_REQ_CODE = 795;
    private static final int GALLERY_REQ_CODE = 796;

    // QUẢN LÝ NHIỀU ẢNH
    private List<byte[]> pendingImagesList = new ArrayList<>();
    private LinearLayout dialogImagesContainer = null; // Container chứa các ảnh review

    private MapView mapView;
    private BottomNavigationView bottomNavigationView;
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private FloatingActionButton fabGps;
    private ExtendedFloatingActionButton fabFinishDrawing;

    private TextView txtPotholes, txtFlood, txtConstruction, txtDanger, txtTotalWarnings, txtDashboardTitle, txtSafetyIndex;
    private TextView txtEmptyRoute;
    private LinearLayout containerRouteSteps;
    private Button btnTabStats, btnTabSteps;
    private LinearLayout layoutTabStats, dashboardTabs;
    private View layoutTabSteps;
    private EditText edtSearch;
    private ImageButton btnSearchSubmit;
    private ImageView imgLogo;

    private SupabaseHelper supabaseHelper;
    private GisHelper gisHelper;
    private FusedLocationProviderClient fusedLocationClient;

    private List<Incident> incidentsList = new ArrayList<>();
    private final List<Marker> incidentMarkers = new ArrayList<>();
    private final List<Polyline> incidentRoadPolylines = new ArrayList<>();
    private Marker myLocationMarker;
    private Polyline routePolyline;

    private boolean isDrawingMode = false;
    private final List<GeoPoint> drawPoints = new ArrayList<>();
    private Polygon currentPolygon;

    private boolean isRouteSelectionMode = false;
    private boolean isSelectingStartPoint = true;
    private GeoPoint startPoint;
    private GeoPoint endPoint;
    private Marker startMarker;
    private Marker endMarker;

    private Polyline freehandPolyline;
    private boolean isPinReportMode = false;
    private double reportLat = 0;
    private double reportLng = 0;

    private double currentLatitude = 10.8231;
    private double currentLongitude = 106.6297;

    private List<IncidentCluster> currentClusters = new ArrayList<>();

    // Lớp chứa dữ liệu Gộp điểm
    public static class IncidentCluster {
        double lat;
        double lng;
        String type;
        List<Incident> items = new ArrayList<>();

        IncidentCluster(Incident first) {
            this.lat = first.getLat();
            this.lng = first.getLng();
            this.type = first.getType();
            this.items.add(first);
        }
        void add(Incident incident) { this.items.add(incident); }
    }

    // ==============================================================
    // CUSTOM INFOWINDOW (POPUP NÂNG CAO GIỐNG BẢN WEB)
    // ==============================================================
    private class CustomInfoWindow extends InfoWindow {
        public CustomInfoWindow(int layoutResId, MapView mapView) {
            super(layoutResId, mapView);
        }

        @Override
        public void onOpen(Object item) {
            Marker marker = (Marker) item;
            IncidentCluster cluster = (IncidentCluster) marker.getRelatedObject();
            if (cluster == null) return;

            TextView txtTitle = mView.findViewById(R.id.txtPopupTitle);
            LinearLayout container = mView.findViewById(R.id.containerClusterItems);
            container.removeAllViews(); // Xoá data cũ

            String titleText = "Sự cố: " + cluster.type;
            if (cluster.items.size() > 1) {
                titleText += " (Gộp " + cluster.items.size() + " báo cáo)";
            }
            txtTitle.setText(titleText);

            // Đổ dữ liệu từng báo cáo vào popup
            for (Incident inc : cluster.items) {
                View itemView = LayoutInflater.from(mView.getContext()).inflate(R.layout.layout_custom_infowindow, null); // Dummy inflate to get Context simply

                LinearLayout itemLayout = new LinearLayout(mView.getContext());
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setPadding(0, 8, 0, 16);

                // Dòng kẻ ngăn cách
                View divider = new View(mView.getContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2));
                divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
                itemLayout.addView(divider);

                // Thời gian
                TextView txtTime = new TextView(mView.getContext());
                txtTime.setText("Thời gian: " + formatTime(inc.getCreatedAt()));
                txtTime.setTextSize(13f);
                txtTime.setTextColor(Color.parseColor("#475569"));
                txtTime.setPadding(0, 8, 0, 4);
                itemLayout.addView(txtTime);

                // Mô tả
                if (inc.getDescription() != null && !inc.getDescription().isEmpty()) {
                    TextView txtDesc = new TextView(mView.getContext());
                    txtDesc.setText("Mô tả: " + inc.getDescription());
                    txtDesc.setTextSize(13f);
                    txtDesc.setTextColor(Color.parseColor("#1E293B"));
                    itemLayout.addView(txtDesc);
                }

                // Xử lý Load Ảnh
                String imgJson = inc.getImageUrl();
                if (imgJson != null && !imgJson.equals("[]") && !imgJson.isEmpty()) {
                    try {
                        JSONArray imgArray = new JSONArray(imgJson);
                        if (imgArray.length() > 0) {
                            HorizontalScrollView hScrollView = new HorizontalScrollView(mView.getContext());
                            hScrollView.setPadding(0, 8, 0, 0);
                            hScrollView.setHorizontalScrollBarEnabled(false);

                            LinearLayout imgContainer = new LinearLayout(mView.getContext());
                            imgContainer.setOrientation(LinearLayout.HORIZONTAL);

                            for (int i = 0; i < imgArray.length(); i++) {
                                String url = imgArray.getString(i);

                                ImageView imgView = new ImageView(mView.getContext());
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                        (int)(80 * getResources().getDisplayMetrics().density),
                                        (int)(80 * getResources().getDisplayMetrics().density)
                                );
                                lp.setMargins(0, 0, 12, 0);
                                imgView.setLayoutParams(lp);
                                imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                imgView.setBackgroundColor(Color.parseColor("#F1F5F9")); // Nền xám chờ load

                                imgContainer.addView(imgView);

                                // Load ảnh bằng Thread bất đồng bộ để tránh lag app
                                new Thread(() -> {
                                    try {
                                        InputStream is = (InputStream) new URL(url).getContent();
                                        final Bitmap bmp = BitmapFactory.decodeStream(is);
                                        runOnUiThread(() -> {
                                            imgView.setImageBitmap(bmp);
                                            // Gọi invalidate để bong bóng vẽ lại kích thước
                                            mView.invalidate();
                                        });
                                    } catch (Exception e) { Log.e(TAG, "Load image error: " + url); }
                                }).start();
                            }
                            hScrollView.addView(imgContainer);
                            itemLayout.addView(hScrollView);
                        }
                    } catch (Exception e) { Log.e(TAG, "JSON Parse Error", e); }
                }
                container.addView(itemLayout);
            }
        }

        @Override public void onClose() { }

        private String formatTime(String isoString) {
            try {
                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = isoFormat.parse(isoString.substring(0, 19));

                SimpleDateFormat outFormat = new SimpleDateFormat("HH:mm • dd/MM/yyyy", new Locale("vi", "VN"));
                return outFormat.format(date);
            } catch (Exception e) { return isoString; }
        }
    }

    // ==============================================================
    // THUẬT TOÁN LINE SLICING (GIẢ LẬP TURF.JS CHO ANDROID NATIVE)
    // ==============================================================
    private static class ProjectedPoint {
        GeoPoint point;
        int segmentIndex;
        double distFromStart;
        double perpDistance;
    }

    private ProjectedPoint getProjectedPoint(List<GeoPoint> line, GeoPoint p) {
        ProjectedPoint best = new ProjectedPoint();
        best.perpDistance = Double.MAX_VALUE;
        double accumDist = 0;

        for (int i = 0; i < line.size() - 1; i++) {
            GeoPoint p1 = line.get(i);
            GeoPoint p2 = line.get(i + 1);

            float[] segDist = new float[1];
            Location.distanceBetween(p1.getLatitude(), p1.getLongitude(), p2.getLatitude(), p2.getLongitude(), segDist);
            double L = segDist[0];

            double x = p.getLongitude(), y = p.getLatitude();
            double x1 = p1.getLongitude(), y1 = p1.getLatitude();
            double x2 = p2.getLongitude(), y2 = p2.getLatitude();

            double A = x - x1; double B = y - y1;
            double C = x2 - x1; double D = y2 - y1;

            double dot = A * C + B * D;
            double len_sq = C * C + D * D;
            double param = -1;
            if (len_sq != 0) param = dot / len_sq;

            double xx, yy;
            double distOnSegment;

            if (param < 0) {
                xx = x1; yy = y1; distOnSegment = 0;
            } else if (param > 1) {
                xx = x2; yy = y2; distOnSegment = L;
            } else {
                xx = x1 + param * C; yy = y1 + param * D;
                distOnSegment = param * L;
            }

            float[] perpD = new float[1];
            Location.distanceBetween(y, x, yy, xx, perpD);

            if (perpD[0] < best.perpDistance) {
                best.perpDistance = perpD[0];
                best.point = new GeoPoint(yy, xx);
                best.segmentIndex = i;
                best.distFromStart = accumDist + distOnSegment;
            }
            accumDist += L;
        }
        return best;
    }

    private List<GeoPoint> lineSliceAlong(List<GeoPoint> line, double startDist, double endDist) {
        List<GeoPoint> slice = new ArrayList<>();
        double accumDist = 0;
        boolean started = false;

        for (int i = 0; i < line.size() - 1; i++) {
            GeoPoint p1 = line.get(i);
            GeoPoint p2 = line.get(i + 1);

            float[] segDist = new float[1];
            Location.distanceBetween(p1.getLatitude(), p1.getLongitude(), p2.getLatitude(), p2.getLongitude(), segDist);
            double L = segDist[0];
            double nextAccumDist = accumDist + L;

            if (!started && startDist >= accumDist && startDist <= nextAccumDist) {
                started = true;
                double ratio = (L == 0) ? 0 : (startDist - accumDist) / L;
                double lat = p1.getLatitude() + ratio * (p2.getLatitude() - p1.getLatitude());
                double lng = p1.getLongitude() + ratio * (p2.getLongitude() - p1.getLongitude());
                slice.add(new GeoPoint(lat, lng));
            }

            if (started) {
                if (endDist > nextAccumDist) {
                    slice.add(p2);
                } else {
                    double ratio = (L == 0) ? 0 : (endDist - accumDist) / L;
                    double lat = p1.getLatitude() + ratio * (p2.getLatitude() - p1.getLatitude());
                    double lng = p1.getLongitude() + ratio * (p2.getLongitude() - p1.getLongitude());
                    slice.add(new GeoPoint(lat, lng));
                    break;
                }
            }
            accumDist = nextAccumDist;
        }
        return slice;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().load(getApplicationContext(),
                getSharedPreferences("osmdroid_prefs", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        supabaseHelper = new SupabaseHelper();
        gisHelper = new GisHelper();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        mapView = findViewById(R.id.mapView);
        bottomNavigationView = findViewById(R.id.bottomNavigation);
        fabGps = findViewById(R.id.fabGps);
        fabFinishDrawing = findViewById(R.id.fabFinishDrawing);

        txtPotholes = findViewById(R.id.txtPotholes);
        txtFlood = findViewById(R.id.txtFlood);
        txtConstruction = findViewById(R.id.txtConstruction);
        txtDanger = findViewById(R.id.txtDanger);
        txtTotalWarnings = findViewById(R.id.txtTotalWarnings);
        txtSafetyIndex = findViewById(R.id.txtSafetyIndex);
        txtDashboardTitle = findViewById(R.id.txtDashboardTitle);
        txtEmptyRoute = findViewById(R.id.txtEmptyRoute);
        containerRouteSteps = findViewById(R.id.containerRouteSteps);

        dashboardTabs = findViewById(R.id.dashboardTabs);
        btnTabStats = findViewById(R.id.btnTabStats);
        btnTabSteps = findViewById(R.id.btnTabSteps);
        layoutTabStats = findViewById(R.id.layoutTabStats);
        layoutTabSteps = findViewById(R.id.layoutTabSteps);

        edtSearch = findViewById(R.id.edtSearch);
        btnSearchSubmit = findViewById(R.id.btnSearchSubmit);
        imgLogo = findViewById(R.id.img_logo);

        mapView.setBuiltInZoomControls(true);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(new GeoPoint(currentLatitude, currentLongitude));

        View bottomSheet = findViewById(R.id.dashboardBottomSheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);

        int peekHeightPx = (int) (35 * getResources().getDisplayMetrics().density);
        bottomSheetBehavior.setPeekHeight(peekHeightPx);
        bottomSheetBehavior.setHideable(false);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

        checkAndRequestPermissions();
        bindEvents();
        fetchAndRenderIncidents();
    }

    private void checkAndRequestPermissions() {
        String[] permissions = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA };
        List<String> needed = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            retrieveLocation(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            retrieveLocation(true);
        }
    }

    private void retrieveLocation(final boolean centerOnLocation) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                    if (location != null) {
                        currentLatitude = location.getLatitude();
                        currentLongitude = location.getLongitude();
                        drawMyLocationMarker(new GeoPoint(currentLatitude, currentLongitude));
                        if (centerOnLocation) mapView.getController().setCenter(new GeoPoint(currentLatitude, currentLongitude));
                    }
                });
            }
        } catch (Exception e) { Log.e(TAG, "Failed retrieving GPS", e); }
    }

    private void drawMyLocationMarker(GeoPoint point) {
        if (myLocationMarker != null) mapView.getOverlays().remove(myLocationMarker);
        myLocationMarker = new Marker(mapView);
        myLocationMarker.setPosition(point);
        myLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        myLocationMarker.setTitle("📍 Vị trí hiện tại");

        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.parseColor("#0061A4"));
        paint.setAntiAlias(true);
        canvas.drawCircle(32, 32, 18, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(4);
        canvas.drawCircle(32, 32, 18, paint);

        myLocationMarker.setIcon(new BitmapDrawable(getResources(), bitmap));
        mapView.getOverlays().add(myLocationMarker);
        mapView.invalidate();
    }

    private void bindEvents() {
        fabGps.setOnClickListener(v -> retrieveLocation(true));
        fabFinishDrawing.setOnClickListener(v -> disableSpecialModes());

        btnSearchSubmit.setOnClickListener(v -> performSearch());
        edtSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        btnTabStats.setOnClickListener(v -> switchTab(true));
        btnTabSteps.setOnClickListener(v -> switchTab(false));

        mapView.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) { return false; }
            @Override
            public boolean onZoom(ZoomEvent event) {
                updateMarkerSizes(event.getZoomLevel());
                return true;
            }
        });

        bottomNavigationView.setOnNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_route) {
                disableSpecialModes();
                isRouteSelectionMode = true;
                isSelectingStartPoint = true;
                txtDashboardTitle.setText("🧭 Chỉ đường - Chọn điểm xuất phát");
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                dashboardTabs.setVisibility(View.VISIBLE);

                fabFinishDrawing.setText("❌ Hủy chỉ đường");
                fabFinishDrawing.setVisibility(View.VISIBLE);
                Toast.makeText(MainActivity.this, "Chạm bản đồ để chọn 'Điểm bắt đầu'", Toast.LENGTH_SHORT).show();
                return true;

            } else if (id == R.id.action_draw) {
                disableSpecialModes();
                isDrawingMode = true;

                fabFinishDrawing.setText("❌ Hủy khoanh vùng");
                fabFinishDrawing.setVisibility(View.VISIBLE);
                txtDashboardTitle.setText("✏️ Chế độ khoanh vùng");
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                Toast.makeText(MainActivity.this, "Vuốt tay tự do trên bản đồ để khoanh vùng", Toast.LENGTH_LONG).show();
                return true;

            } else if (id == R.id.action_report) {
                showReportOptionsDialog();
                return true;
            }
            return false;
        });

        mapView.setOnTouchListener((v, event) -> {
            if (!isDrawingMode) return false;

            org.osmdroid.views.Projection proj = mapView.getProjection();
            GeoPoint geoPoint = (GeoPoint) proj.fromPixels((int) event.getX(), (int) event.getY());

            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    drawPoints.clear();
                    clearCurrentPolygon();
                    if (freehandPolyline != null) mapView.getOverlays().remove(freehandPolyline);

                    freehandPolyline = new Polyline();
                    freehandPolyline.setColor(Color.parseColor("#FF5500"));
                    freehandPolyline.setWidth(6f);
                    mapView.getOverlays().add(freehandPolyline);
                    drawPoints.add(geoPoint);
                    return true;

                case android.view.MotionEvent.ACTION_MOVE:
                    if (drawPoints.isEmpty()) return false;
                    GeoPoint lastPoint = drawPoints.get(drawPoints.size() - 1);
                    if (lastPoint.distanceToAsDouble(geoPoint) > 5) {
                        drawPoints.add(geoPoint);
                        freehandPolyline.setPoints(drawPoints);
                        mapView.invalidate();
                    }
                    return true;

                case android.view.MotionEvent.ACTION_UP:
                    if (drawPoints.size() > 2) {
                        drawPoints.add(drawPoints.get(0));
                        currentPolygon = new Polygon(mapView);
                        currentPolygon.setPoints(drawPoints);
                        currentPolygon.setFillColor(Color.parseColor("#22FF5500"));
                        currentPolygon.setStrokeColor(Color.parseColor("#FF5500"));
                        currentPolygon.setStrokeWidth(3f);

                        mapView.getOverlays().remove(freehandPolyline);
                        mapView.getOverlays().add(currentPolygon);
                        mapView.invalidate();

                        calculatePointInPolygonStats();
                    }
                    return true;
            }
            return false;
        });

        org.osmdroid.views.overlay.Overlay doubleTapOverlay = new org.osmdroid.views.overlay.Overlay() {
            @Override
            public boolean onDoubleTap(android.view.MotionEvent e, MapView mapView) {
                if (isPinReportMode) {
                    org.osmdroid.views.Projection proj = mapView.getProjection();
                    GeoPoint p = (GeoPoint) proj.fromPixels((int) e.getX(), (int) e.getY());
                    reportLat = p.getLatitude();
                    reportLng = p.getLongitude();
                    pendingImagesList.clear(); // Xóa list ảnh cũ
                    openReportSubmissionForm(null, true);
                    isPinReportMode = false;
                    disableSpecialModes();
                    return true;
                }
                return super.onDoubleTap(e, mapView);
            }
        };
        mapView.getOverlays().add(doubleTapOverlay);

        MapEventsReceiver receiver = new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (isRouteSelectionMode) { handleRouteTap(p); return true; }
                return false;
            }
            @Override public boolean longPressHelper(GeoPoint p) { return false; }
        };
        mapView.getOverlays().add(new MapEventsOverlay(receiver));
    }

    private void updateMarkerSizes(double zoomLevel) {
        if (zoomLevel < 11.5) {
            for (Marker m : incidentMarkers) m.setVisible(false);
            for (Polyline p : incidentRoadPolylines) p.setVisible(false);
        } else {
            double scaleFactor = Math.pow(1.2, zoomLevel - 15.0);
            int baseSize = 100;
            int newSize = (int) (baseSize * scaleFactor);
            if (newSize < 50) newSize = 50;
            if (newSize > 180) newSize = 180;

            for (Polyline p : incidentRoadPolylines) p.setVisible(true);

            int index = 0;
            for (Marker m : incidentMarkers) {
                m.setVisible(true);
                if (index < currentClusters.size()) {
                    IncidentCluster clus = currentClusters.get(index);
                    String emojiStr = "⚠️";
                    int cardColor = Color.parseColor("#DC2626");
                    switch (clus.type) {
                        case "Hố gà": cardColor = Color.parseColor("#DB2777"); emojiStr = "🕳️"; break;
                        case "Lũ lụt": cardColor = Color.parseColor("#1D4ED8"); emojiStr = "🌊"; break;
                        case "Thi công": cardColor = Color.parseColor("#CA8A04"); emojiStr = "🏗️"; break;
                    }
                    m.setIcon(createMarkerBitmapIconBadge(emojiStr, cardColor, clus.items.size(), newSize));
                }
                index++;
            }
        }
        mapView.invalidate();
    }

    private void showReportOptionsDialog() {
        String[] options = { "📍 Chọn vị trí trên bản đồ (Click đúp)", "📸 Báo cáo tại vị trí hiện tại (Chụp ảnh)" };
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Chọn phương thức báo cáo");
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                disableSpecialModes();
                isPinReportMode = true;
                fabFinishDrawing.setText("❌ Hủy báo cáo map");
                fabFinishDrawing.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Hãy chạm 2 lần (Double-tap) vào vị trí sự cố trên bản đồ", Toast.LENGTH_LONG).show();
            } else {
                startLiveReportFlow();
            }
        });
        builder.show();
    }

    private void performSearch() {
        String query = edtSearch.getText().toString().trim();
        if (query.isEmpty()) return;

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edtSearch.getWindowToken(), 0);

        Toast.makeText(this, "Đang tìm kiếm \"" + query + "\"...", Toast.LENGTH_SHORT).show();
        gisHelper.searchLocation(query, new GisHelper.SearchCallback() {
            @Override
            public void onSuccess(double lat, double lng, String name, List<List<GeoPoint>> boundaries) {
                GeoPoint target = new GeoPoint(lat, lng);
                mapView.getController().setCenter(target);
                mapView.getController().setZoom(16.0);

                Marker searchMarker = new Marker(mapView);
                searchMarker.setPosition(target);
                searchMarker.setTitle(name);
                searchMarker.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_myplaces));
                mapView.getOverlays().add(searchMarker);

                if (!boundaries.isEmpty()) {
                    clearCurrentPolygon();
                    isDrawingMode = false;

                    final List<GeoPoint> points = boundaries.get(0);
                    currentPolygon = new Polygon(mapView);
                    currentPolygon.setPoints(points);
                    currentPolygon.setFillColor(Color.parseColor("#440061A4"));
                    currentPolygon.setStrokeColor(Color.parseColor("#0061A4"));
                    currentPolygon.setStrokeWidth(4);
                    mapView.getOverlays().add(currentPolygon);

                    calculatePointInPolygonStats(points, "🔎 Tìm thấy ranh giới: " + name);
                    fabFinishDrawing.setVisibility(View.VISIBLE);
                } else {
                    Toast.makeText(MainActivity.this, "Đã di chuyển tới " + name, Toast.LENGTH_LONG).show();
                }
                mapView.invalidate();
            }
            @Override public void onError(Exception e) { Toast.makeText(MainActivity.this, "Lỗi tìm kiếm", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void disableSpecialModes() {
        isRouteSelectionMode = false;
        isDrawingMode = false;
        isPinReportMode = false;

        fabFinishDrawing.setVisibility(View.GONE);
        dashboardTabs.setVisibility(View.GONE);
        switchTab(true);

        clearRouteOverlay();
        clearCurrentPolygon();
        if (freehandPolyline != null) {
            mapView.getOverlays().remove(freehandPolyline);
            freehandPolyline = null;
        }
        clearRouteMarkers();
        drawPoints.clear();
        uncheckAllTabs();
        calculateTotalStats(incidentsList);

        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

        mapView.invalidate();
    }

    private void uncheckAllTabs() {
        android.view.Menu menu = bottomNavigationView.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setCheckable(false);
            menu.getItem(i).setChecked(false);
            menu.getItem(i).setCheckable(true);
        }
    }

    private void switchTab(boolean statsTabActive) {
        if (statsTabActive) {
            btnTabStats.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0061A4")));
            btnTabStats.setTextColor(Color.WHITE);
            btnTabSteps.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F1F5F9")));
            btnTabSteps.setTextColor(Color.parseColor("#475569"));
            layoutTabStats.setVisibility(View.VISIBLE);
            layoutTabSteps.setVisibility(View.GONE);
        } else {
            btnTabStats.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F1F5F9")));
            btnTabStats.setTextColor(Color.parseColor("#475569"));
            btnTabSteps.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0061A4")));
            btnTabSteps.setTextColor(Color.WHITE);
            layoutTabStats.setVisibility(View.GONE);
            layoutTabSteps.setVisibility(View.VISIBLE);
        }
    }

    private void handleRouteTap(GeoPoint p) {
        if (isSelectingStartPoint) {
            startPoint = p;
            isSelectingStartPoint = false;
            if (startMarker != null) mapView.getOverlays().remove(startMarker);
            startMarker = new Marker(mapView);
            startMarker.setPosition(startPoint);
            startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            startMarker.setTitle("🚦 Điểm bắt đầu");
            startMarker.setIcon(getResources().getDrawable(android.R.drawable.presence_online));
            mapView.getOverlays().add(startMarker);
            mapView.invalidate();
            txtDashboardTitle.setText("Chỉ đường - Chọn điểm kết thúc");
            Toast.makeText(this, "Chạm để chọn 'Điểm kết thúc'.", Toast.LENGTH_SHORT).show();
        } else {
            endPoint = p;
            isRouteSelectionMode = false;
            if (endMarker != null) mapView.getOverlays().remove(endMarker);
            endMarker = new Marker(mapView);
            endMarker.setPosition(endPoint);
            endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            endMarker.setTitle("🏁 Điểm kết thúc");
            endMarker.setIcon(getResources().getDrawable(android.R.drawable.presence_busy));
            mapView.getOverlays().add(endMarker);
            mapView.invalidate();
            calculateNavRoutePath();
        }
    }

    private void calculateNavRoutePath() {
        Toast.makeText(this, "Đang tải dữ liệu đường đi OSRM...", Toast.LENGTH_SHORT).show();
        gisHelper.getRoute(startPoint.getLatitude(), startPoint.getLongitude(), endPoint.getLatitude(), endPoint.getLongitude(), new GisHelper.RouteCallback() {
            @Override
            public void onSuccess(List<GeoPoint> routeCoords, List<GisHelper.RouteInstruction> instructions) {
                clearRouteOverlay();
                routePolyline = new Polyline(mapView);
                routePolyline.setPoints(routeCoords);
                routePolyline.setColor(Color.parseColor("#0061A4"));
                routePolyline.setWidth(10f);
                mapView.getOverlays().add(routePolyline);

                containerRouteSteps.removeAllViews();
                txtEmptyRoute.setVisibility(View.GONE);

                for (GisHelper.RouteInstruction inst : instructions) {
                    TextView stepView = new TextView(MainActivity.this);
                    stepView.setText("• " + inst.text + " (khoảng " + Math.round(inst.distance) + " m)");
                    stepView.setTextColor(Color.parseColor("#1E293B"));
                    stepView.setPadding(8, 8, 8, 8);
                    stepView.setTextSize(13);
                    containerRouteSteps.addView(stepView);
                }

                int potholes = 0, flood = 0, construction = 0, danger = 0;
                for (Incident inc : incidentsList) {
                    GeoPoint incidentPt = new GeoPoint(inc.getLat(), inc.getLng());
                    if (GisHelper.isPointNearRoute(incidentPt, routeCoords)) {
                        switch (inc.getType()) {
                            case "Hố gà": potholes++; break;
                            case "Lũ lụt": flood++; break;
                            case "Thi công": construction++; break;
                            case "Nguy hiểm": danger++; break;
                        }
                    }
                }
                updateDashboardUI(potholes, flood, construction, danger, "🧭 Sự cố dọc lộ trình di chuyển");
                switchTab(true);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                mapView.invalidate();
            }
            @Override public void onError(Exception e) { Toast.makeText(MainActivity.this, "Lỗi chỉ đường", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void calculatePointInPolygonStats() {
        calculatePointInPolygonStats(drawPoints, "✏️ Sự cố trong vùng khoanh vẽ");
    }

    private void calculatePointInPolygonStats(List<GeoPoint> polygonPts, String title) {
        int potholes = 0, flood = 0, construction = 0, danger = 0;
        for (Incident inc : incidentsList) {
            GeoPoint incPt = new GeoPoint(inc.getLat(), inc.getLng());
            if (GisHelper.isPointInPolygon(incPt, polygonPts)) {
                switch (inc.getType()) {
                    case "Hố gà": potholes++; break;
                    case "Lũ lụt": flood++; break;
                    case "Thi công": construction++; break;
                    case "Nguy hiểm": danger++; break;
                }
            }
        }
        updateDashboardUI(potholes, flood, construction, danger, title);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private void startLiveReportFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQ_CODE);
            return;
        }
        pendingImagesList.clear(); // Xóa ảnh cũ
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getPackageManager()) != null) startActivityForResult(cameraIntent, CAMERA_REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // KẾT QUẢ TỪ CAMERA (Chụp 1 ảnh)
        if (requestCode == CAMERA_REQ_CODE && resultCode == RESULT_OK && data != null) {
            Bundle extras = data.getExtras();
            if (extras != null) {
                Bitmap photoBitmap = (Bitmap) extras.get("data");
                if (photoBitmap != null) {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    photoBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
                    pendingImagesList.add(stream.toByteArray());
                    openReportSubmissionForm(photoBitmap, false);
                }
            }
        }

        // KẾT QUẢ TỪ THƯ VIỆN CHỌN NHIỀU ẢNH (Từ Báo cáo Map hoặc Thêm Ảnh)
        if (requestCode == GALLERY_REQ_CODE && resultCode == RESULT_OK && data != null) {
            try {
                // Xử lý khi chọn nhiều ảnh
                if (data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    int count = clipData.getItemCount();
                    for (int i = 0; i < count; i++) {
                        if (pendingImagesList.size() >= 5) {
                            Toast.makeText(this, "Chỉ được chọn tối đa 5 ảnh!", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        Uri imageUri = clipData.getItemAt(i).getUri();
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream); // Nén nhẹ 70% để gửi nhanh hơn
                        pendingImagesList.add(stream.toByteArray());
                        addImageToDialogPreview(bitmap);
                    }
                }
                // Xử lý khi chỉ chọn 1 ảnh
                else if (data.getData() != null) {
                    if (pendingImagesList.size() >= 5) {
                        Toast.makeText(this, "Chỉ được chọn tối đa 5 ảnh!", Toast.LENGTH_SHORT).show();
                    } else {
                        Uri imageUri = data.getData();
                        Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
                        pendingImagesList.add(stream.toByteArray());
                        addImageToDialogPreview(bitmap);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // Hàm phụ trợ đẩy ảnh vào Giao diện Scroll
    private void addImageToDialogPreview(Bitmap bmp) {
        if (dialogImagesContainer != null) {
            ImageView imgView = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int)(100 * getResources().getDisplayMetrics().density),
                    (int)(100 * getResources().getDisplayMetrics().density)
            );
            lp.setMargins(0, 0, (int)(8 * getResources().getDisplayMetrics().density), 0);
            imgView.setLayoutParams(lp);
            imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgView.setImageBitmap(bmp);

            // Chèn vào trước nút "Thêm ảnh"
            dialogImagesContainer.addView(imgView, dialogImagesContainer.getChildCount() - 1);
        }
    }

    private void openReportSubmissionForm(Bitmap initialPhoto, boolean isFromMap) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_report, null);
        builder.setView(dialogView);
        final Dialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.setCancelable(false);
        dialog.show();

        dialogImagesContainer = dialogView.findViewById(R.id.containerImages);
        CardView btnAddImage = dialogView.findViewById(R.id.btnAddImage);

        TextView txtLocationCoords = dialogView.findViewById(R.id.txtLocationCoords);
        final Spinner spinIncidentType = dialogView.findViewById(R.id.spinIncidentType);
        final LinearLayout layoutDistanceField = dialogView.findViewById(R.id.layoutDistanceField);
        final EditText edtDistance = dialogView.findViewById(R.id.edtDistance);
        final EditText edtDescription = dialogView.findViewById(R.id.edtDescription);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        final Button btnSubmit = dialogView.findViewById(R.id.btnSubmit);

        final double finalLat = isFromMap ? reportLat : currentLatitude;
        final double finalLng = isFromMap ? reportLng : currentLongitude;
        txtLocationCoords.setText(String.format("Lat: %.5f, Lng: %.5f", finalLat, finalLng));

        // Load ảnh gốc (nếu chụp từ camera) vào giao diện
        if (initialPhoto != null) {
            addImageToDialogPreview(initialPhoto);
        }

        // Bấm nút thêm ảnh sẽ gọi Intent chọn NHIỀU ẢNH
        btnAddImage.setOnClickListener(v -> {
            if (pendingImagesList.size() >= 5) {
                Toast.makeText(this, "Đã đạt giới hạn 5 ảnh", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galleryIntent.setType("image/*");
            galleryIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // CHOP PHÉP CHỌN NHIỀU ẢNH
            startActivityForResult(Intent.createChooser(galleryIntent, "Chọn tối đa 5 ảnh"), GALLERY_REQ_CODE);
        });

        String[] incidentCategories = {"Hố gà", "Lũ lụt", "Thi công", "Nguy hiểm"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, incidentCategories);
        spinIncidentType.setAdapter(adapter);

        spinIncidentType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                if ("Lũ lụt".equals(selected) || "Thi công".equals(selected)) layoutDistanceField.setVisibility(View.VISIBLE);
                else layoutDistanceField.setVisibility(View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            uncheckAllTabs();
            pendingImagesList.clear();
        });

        btnSubmit.setOnClickListener(v -> {
            final String type = spinIncidentType.getSelectedItem().toString();
            final String desc = edtDescription.getText().toString().trim();
            String distStr = edtDistance.getText().toString().trim();
            final int distance = distStr.isEmpty() ? 50 : Integer.parseInt(distStr);

            btnSubmit.setEnabled(false);
            btnSubmit.setText("⏳ Đang xử lý tải lên...");

            if (!pendingImagesList.isEmpty()) {
                List<String> uploadedUrls = new ArrayList<>();
                // Gọi hàm đệ quy tải từng ảnh lên
                uploadMultipleImages(0, pendingImagesList, uploadedUrls, type, desc, distance, dialog, finalLat, finalLng, btnSubmit);
            } else {
                saveReportToDatabase(dialog, finalLat, finalLng, type, desc, "[]", distance, "pending");
            }
        });
    }

    // Hàm đệ quy tải lên nhiều ảnh liên tiếp
    private void uploadMultipleImages(int index, List<byte[]> images, List<String> urls, String type, String desc, int distance, Dialog dialog, double finalLat, double finalLng, Button btnSubmit) {
        if (index >= images.size()) {
            // Đã upload xong toàn bộ ảnh -> Biến List URLs thành JSON Array -> Gọi AI AI phân tích ảnh đầu tiên
            JSONArray jsonArray = new JSONArray(urls);
            String jsonUrls = jsonArray.toString();

            supabaseHelper.analyzeWithAI(urls.get(0), type, new SupabaseHelper.Callback<String>() {
                @Override public void onSuccess(String finalStatusFromAI) {
                    saveReportToDatabase(dialog, finalLat, finalLng, type, desc, jsonUrls, distance, finalStatusFromAI);
                }
                @Override public void onError(Exception e) {
                    saveReportToDatabase(dialog, finalLat, finalLng, type, desc, jsonUrls, distance, "pending");
                }
            });
            return;
        }

        btnSubmit.setText(String.format("⏳ Đang tải ảnh %d/%d...", index + 1, images.size()));

        supabaseHelper.uploadImage(images.get(index), new SupabaseHelper.Callback<String>() {
            @Override public void onSuccess(String publicUrl) {
                urls.add(publicUrl);
                // Tiếp tục đệ quy ảnh tiếp theo
                uploadMultipleImages(index + 1, images, urls, type, desc, distance, dialog, finalLat, finalLng, btnSubmit);
            }
            @Override public void onError(Exception e) {
                Toast.makeText(MainActivity.this, "Lỗi tải ảnh thứ " + (index + 1), Toast.LENGTH_SHORT).show();
                btnSubmit.setEnabled(true);
                btnSubmit.setText("Gửi Báo Cáo");
            }
        });
    }

    private void saveReportToDatabase(Dialog dialog, double lat, double lng, String type, String desc, String imgUrl, int distance, String status) {
        supabaseHelper.submitReport(lat, lng, type, desc, imgUrl, distance, status, new SupabaseHelper.Callback<Boolean>() {
            @Override public void onSuccess(Boolean success) {
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "✅ Gửi thành công!", Toast.LENGTH_SHORT).show();
                fetchAndRenderIncidents();
                mapView.getController().setCenter(new GeoPoint(lat, lng));
                uncheckAllTabs();
                pendingImagesList.clear(); // Xóa bộ nhớ đệm
            }
            @Override public void onError(Exception e) { Toast.makeText(MainActivity.this, "Lỗi DB", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void fetchAndRenderIncidents() {
        supabaseHelper.fetchApprovedEvents(new SupabaseHelper.Callback<List<Incident>>() {
            @Override
            public void onSuccess(List<Incident> result) {
                incidentsList = result;
                currentClusters.clear();
                for (Incident inc : result) {
                    boolean grouped = false;
                    for (IncidentCluster clus : currentClusters) {
                        float[] distResults = new float[1];
                        Location.distanceBetween(inc.getLat(), inc.getLng(), clus.lat, clus.lng, distResults);
                        if (distResults[0] <= 10 && inc.getType().equals(clus.type)) {
                            clus.add(inc);
                            grouped = true;
                            break;
                        }
                    }
                    if (!grouped) currentClusters.add(new IncidentCluster(inc));
                }
                renderClusteredMarkers(currentClusters);
                calculateTotalStats(result);
            }
            @Override public void onError(Exception e) {}
        });
    }

    private void renderClusteredMarkers(List<IncidentCluster> clusters) {
        for (Marker old : incidentMarkers) mapView.getOverlays().remove(old);
        for (Polyline oldP : incidentRoadPolylines) mapView.getOverlays().remove(oldP);
        incidentMarkers.clear();
        incidentRoadPolylines.clear();

        int currentZoomSize = 100;

        for (IncidentCluster clus : clusters) {
            Marker m = new Marker(mapView);
            m.setPosition(new GeoPoint(clus.lat, clus.lng));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

            // GÁN DỮ LIỆU ĐỂ TRUYỀN VÀO CUSTOM INFOWINDOW MỚI
            m.setRelatedObject(clus);
            m.setInfoWindow(new CustomInfoWindow(R.layout.layout_custom_infowindow, mapView));

            int cardColor;
            String emojiStr;
            switch (clus.type) {
                case "Hố gà": cardColor = Color.parseColor("#DB2777"); emojiStr = "🕳️"; break;
                case "Lũ lụt": cardColor = Color.parseColor("#1D4ED8"); emojiStr = "🌊"; break;
                case "Thi công": cardColor = Color.parseColor("#CA8A04"); emojiStr = "🏗️"; break;
                default: cardColor = Color.parseColor("#DC2626"); emojiStr = "⚠️"; break;
            }

            m.setIcon(createMarkerBitmapIconBadge(emojiStr, cardColor, clus.items.size(), currentZoomSize));
            mapView.getOverlays().add(m);
            incidentMarkers.add(m);

            if ("Lũ lụt".equals(clus.type) || "Thi công".equals(clus.type)) {
                int dist = clus.items.get(0).getDistance();
                fetchAndDrawOverpassRoadSegment(clus.lat, clus.lng, dist > 0 ? dist : 50, clus.type);
            }
        }
        mapView.invalidate();
    }

    private void fetchAndDrawOverpassRoadSegment(double lat, double lng, int distance, String type) {
        new Thread(() -> {
            try {
                String query = "[out:json];way(around:50," + lat + "," + lng + ")[highway];out geom;";
                String url = "https://overpass-api.de/api/interpreter?data=" + Uri.encode(query);

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(url).build();
                Response response = client.newCall(request).execute();

                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    JSONObject root = new JSONObject(json);
                    JSONArray elements = root.optJSONArray("elements");
                    if (elements != null && elements.length() > 0) {
                        JSONObject way = elements.getJSONObject(0);
                        JSONArray geom = way.optJSONArray("geometry");
                        if (geom != null) {
                            List<GeoPoint> fullRoadPts = new ArrayList<>();
                            for (int i = 0; i < geom.length(); i++) {
                                JSONObject pt = geom.getJSONObject(i);
                                fullRoadPts.add(new GeoPoint(pt.getDouble("lat"), pt.getDouble("lon")));
                            }

                            if (fullRoadPts.size() < 2) return;

                            ProjectedPoint proj = getProjectedPoint(fullRoadPts, new GeoPoint(lat, lng));

                            double halfDist = distance / 2.0;
                            double startDist = Math.max(0, proj.distFromStart - halfDist);
                            double endDist = proj.distFromStart + halfDist;

                            List<GeoPoint> finalSegment = lineSliceAlong(fullRoadPts, startDist, endDist);

                            runOnUiThread(() -> {
                                if (finalSegment.size() > 1) {
                                    if ("Lũ lụt".equals(type)) {
                                        Polyline floodLine = new Polyline(mapView);
                                        floodLine.setPoints(finalSegment);
                                        floodLine.setColor(Color.parseColor("#B32B8CFF"));
                                        floodLine.setWidth(18f);
                                        floodLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
                                        floodLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);

                                        mapView.getOverlays().add(floodLine);
                                        incidentRoadPolylines.add(floodLine);

                                    } else if ("Thi công".equals(type)) {
                                        Polyline blackLine = new Polyline(mapView);
                                        blackLine.setPoints(finalSegment);
                                        blackLine.setColor(Color.parseColor("#CC000000"));
                                        blackLine.setWidth(18f);

                                        Polyline yellowLine = new Polyline(mapView);
                                        yellowLine.setPoints(finalSegment);
                                        yellowLine.setColor(Color.parseColor("#FFFFCC00"));
                                        yellowLine.setWidth(18f);
                                        yellowLine.getOutlinePaint().setPathEffect(new DashPathEffect(new float[]{30f, 30f}, 0));

                                        mapView.getOverlays().add(blackLine);
                                        mapView.getOverlays().add(yellowLine);
                                        incidentRoadPolylines.add(blackLine);
                                        incidentRoadPolylines.add(yellowLine);
                                    }

                                    for (Marker m : incidentMarkers) {
                                        mapView.getOverlays().remove(m);
                                        mapView.getOverlays().add(m);
                                    }
                                    mapView.invalidate();
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Overpass Error", e); }
        }).start();
    }

    private Drawable createMarkerBitmapIconBadge(String emojiText, int themeColor, int clusterSize, int canvasSize) {
        Bitmap b = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint();
        p.setAntiAlias(true);

        p.setColor(themeColor);
        canvas.drawCircle(canvasSize / 2f, canvasSize / 1.7f, canvasSize / 3.2f, p);

        p.setColor(Color.WHITE);
        canvas.drawCircle(canvasSize / 2f, canvasSize / 1.7f, canvasSize / 4.4f, p);

        p.setTextSize(canvasSize / 3.3f);
        p.setTextAlign(Paint.Align.CENTER);
        float verticalYShift = (canvasSize / 1.7f) - ((p.descent() + p.ascent()) / 2f);
        canvas.drawText(emojiText, canvasSize / 2f, verticalYShift, p);

        if (clusterSize > 1) {
            p.setColor(Color.RED);
            float badgeRadius = canvasSize / 6.6f;
            float badgeX = canvasSize - badgeRadius - (canvasSize / 10f);
            float badgeY = badgeRadius + (canvasSize / 10f);
            canvas.drawCircle(badgeX, badgeY, badgeRadius, p);

            p.setColor(Color.WHITE);
            p.setTextSize(canvasSize / 6f);
            p.setFakeBoldText(true);
            float textY = badgeY - ((p.descent() + p.ascent()) / 2f);
            canvas.drawText(String.valueOf(clusterSize), badgeX, textY, p);
        }

        return new BitmapDrawable(getResources(), b);
    }

    private void calculateTotalStats(List<Incident> list) {
        int potholes = 0, flood = 0, construction = 0, danger = 0;
        for (Incident inc : list) {
            switch (inc.getType()) {
                case "Hố gà": potholes++; break;
                case "Lũ lụt": flood++; break;
                case "Thi công": construction++; break;
                case "Nguy hiểm": danger++; break;
            }
        }
        updateDashboardUI(potholes, flood, construction, danger, "🧭 Thông tin cảnh báo toàn bản đồ");
    }

    private void updateDashboardUI(int p, int f, int c, int d, String title) {
        txtPotholes.setText("🕳️ Hố gà (Ổ gà): " + p);
        txtFlood.setText("🌊 Lũ lụt: " + f);
        txtConstruction.setText("🏗️ Thi công: " + c);
        txtDanger.setText("⚠️ Nguy hiểm: " + d);

        int total = p + f + c + d;
        txtTotalWarnings.setText(String.format(java.util.Locale.US, "%02d", total));
        if (txtSafetyIndex != null) {
            int safetyPercent = Math.max(40, 100 - total * 6);
            txtSafetyIndex.setText(safetyPercent + "%\nAn toàn");
        }
        txtDashboardTitle.setText(title);
    }

    private void clearRouteOverlay() { if (routePolyline != null) { mapView.getOverlays().remove(routePolyline); routePolyline = null; } }
    private void clearCurrentPolygon() { if (currentPolygon != null) { mapView.getOverlays().remove(currentPolygon); currentPolygon = null; } }
    private void clearRouteMarkers() {
        if (startMarker != null) { mapView.getOverlays().remove(startMarker); startMarker = null; }
        if (endMarker != null) { mapView.getOverlays().remove(endMarker); endMarker = null; }
        startPoint = null; endPoint = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }
}