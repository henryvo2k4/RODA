// =====================================================
// MAP KHỞI TẠO
// =====================================================

const map = L.map("map", {
    zoomControl: false,
    doubleClickZoom: false,
    tap: false
}).setView([10.8231, 106.6297], 13);


map.getContainer().addEventListener(
    "touchmove",
    function (e) {
        if (drawMode) {
            e.preventDefault();
        }
    },
    { passive: false }
);


// =====================================================
// TILE MAP
// =====================================================

L.tileLayer(
    "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    { maxZoom: 19 }
).addTo(map);

L.control.zoom({
    position: "bottomright"
}).addTo(map);


// =====================================================
// FREEHAND DRAW SYSTEM
// =====================================================

var drawMode = false;
var isDrawing = false;

var drawPoints = [];
var drawLayer = null;

var drawnAreas = [];


// =====================================================
// GEOCODER SEARCH
// =====================================================

const geocoder = L.Control.geocoder({
    placeholder: "🔎 Tìm địa điểm...",
    defaultMarkGeocode: false, // Tắt tự động zoom/vẽ mặc định để chúng ta tự can thiệp
    position: "topleft",
    collapsed: false,
    geocoder: L.Control.Geocoder.nominatim({
        geocodingQueryParams: {
            polygon_geojson: 1 // Yêu cầu API OpenStreetMap trả về đường viền ranh giới (Polygon)
        }
    })
}).addTo(map);


// =====================================================
// ICON SCALE
// =====================================================

const baseZoom = 13;
const baseSize = 12;

function getIconSize() {

    const zoom = map.getZoom();
    const scale = Math.pow(1.15, zoom - baseZoom);

    return baseSize * scale;
}


// =====================================================
// ICON DEFINITIONS
// =====================================================

function createIcon(url, size) {
    return L.icon({
        iconUrl: url,
        iconSize: [size, size],
        iconAnchor: [size / 2, size],
        popupAnchor: [0, -size]
    });
}

const ICONS = {
    "Hố gà": "https://cdn-icons-png.flaticon.com/512/1684/1684423.png",
    "Lũ lụt": "https://cdn-icons-png.flaticon.com/512/1576/1576457.png",
    "Thi công": "https://cdn-icons-png.flaticon.com/512/4930/4930409.png",
    "Nguy hiểm": "https://cdn-icons-png.flaticon.com/512/564/564619.png"
};


// =====================================================
// MARKER STORAGE
// =====================================================

let markers = [];
let tempMarker = null;
let reportLatLng = null;


// =====================================================
// SCALE ICON THEO ZOOM
// =====================================================

map.on("zoomend", function () {

    const size = getIconSize();

    markers.forEach(m => {

        const type = m.incidentType || "Hố gà";
        m.setIcon(createIcon(ICONS[type], size));

    });

    if (tempMarker) {
        tempMarker.setIcon(createIcon(ICONS["Hố gà"], size));
    }

});


// =====================================================
// DOUBLE CLICK → BÁO CÁO
// =====================================================

map.on("dblclick", async function (e) {

    if (drawMode) return;

    const snapped = await snapToRoad(e.latlng.lat, e.latlng.lng);

    reportLatLng = snapped;

    if (tempMarker) map.removeLayer(tempMarker);

    tempMarker = L.marker(
        [snapped.lat, snapped.lng],
        { icon: createIcon(ICONS["Hố gà"], getIconSize()) }
    ).addTo(map);

    openReportForm();

});


// =====================================================
// CLICK NGOÀI → XOÁ MARKER TẠM
// =====================================================

map.on("click", function () {

    if (drawMode) return;

    if (tempMarker) {

        map.removeLayer(tempMarker);
        tempMarker = null;
        map.closePopup();

    }

});


// =====================================================
// FORM BÁO CÁO
// =====================================================

// 1. Khai báo hàm ẩn/hiện ở phạm vi toàn cục (global) để HTML gọi được
window.toggleDistanceField = function() {
    const type = document.getElementById("incidentType").value;
    const distField = document.getElementById("distanceField");
    
    if (distField) {
        if (type === "Lũ lụt" || type === "Thi công") {
            distField.style.display = "block";
        } else {
            distField.style.display = "none";
        }
    }
};

// 2. Hàm mở form (đã xóa thẻ <script> bên trong chuỗi HTML)
function openReportForm() {
    const formHTML = `
    <div style="width:220px">
        <b>🚨 Báo cáo sự cố</b><br><br>

        <select id="incidentType" onchange="toggleDistanceField()">
            <option value="">-- chọn loại --</option>
            <option value="Hố gà">🚧 Hố gà</option>
            <option value="Lũ lụt">🌊 Lũ lụt</option>
            <option value="Thi công">🏗️ Thi công</option>
            <option value="Nguy hiểm">⚠️ Nguy hiểm</option>
        </select>
        <br><br>

        <div id="distanceField" style="display: none; margin-bottom: 15px;">
            <input type="number" id="incidentDistance" placeholder="Quãng đường (m) - VD: 50" style="width:100%; padding: 5px; box-sizing: border-box; border-radius: 4px; border: 1px solid #ccc;">
        </div>

        <textarea 
            id="incidentDesc"
            placeholder="Mô tả sự cố..."
            style="width:100%;height:60px; box-sizing: border-box;"
        ></textarea>
        <br><br>

        <input type="file" id="incidentImage" multiple accept="image/*">
        <small>Tối đa 5 ảnh</small>
        <br><br>

        <button onclick="submitReport()" style="width:100%; padding: 8px; border-radius: 6px; background: #2b8cff; color: white; border: none; cursor: pointer;">Gửi báo cáo</button>
    </div>
    `;

    L.popup()
        .setLatLng(reportLatLng)
        .setContent(formHTML)
        .openOn(map);
}


// =====================================================
// SUBMIT REPORT
// =====================================================

// =====================================================
// SUBMIT REPORT (CÓ TÍCH HỢP AI DUYỆT NGẦM)
// =====================================================

async function submitReport() {

    const type = document.getElementById("incidentType").value;
    const description = document.getElementById("incidentDesc").value;

    // Lấy distance, nếu người dùng không nhập hoặc loại khác thì mặc định là 50m
    const distanceInput = document.getElementById("incidentDistance");
    const distance = distanceInput && distanceInput.value ? parseInt(distanceInput.value) : 50;

    if (!type) {
        alert("Hãy chọn loại sự cố");
        return;
    }

    const fileInput = document.getElementById("incidentImage");
    let imageURLs = [];

    if (fileInput.files.length > 0) {

        if (fileInput.files.length > 5) {
            alert("Chỉ được tối đa 5 ảnh");
            return;
        }

        // Đổi giao diện nút gửi thành Đang xử lý để tránh user bấm nhiều lần
        const btn = document.querySelector("button[onclick='submitReport()']");
        if (btn) {
            btn.innerText = "⏳ Đang xử lý dữ liệu...";
            btn.disabled = true;
        }

        for (const file of fileInput.files) {

            const fileName = Date.now() + "_" + file.name;

            const { error } = await supabaseClient
                .storage
                .from("road-images")
                .upload(fileName, file);

            if (error) {
                console.log("Upload lỗi:", error);
                continue;
            }

            const { data } = supabaseClient
                .storage
                .from("road-images")
                .getPublicUrl(fileName);

            imageURLs.push(data.publicUrl);

        }
    } else {
        const btn = document.querySelector("button[onclick='submitReport()']");
        if (btn) {
            btn.innerText = "⏳ Đang xử lý dữ liệu...";
            btn.disabled = true;
        }
    }

    // =====================================================
    // GỌI AI PHÂN TÍCH NGẦM & LƯU DATABASE
    // =====================================================
    
    // Mặc định trạng thái ban đầu là 'pending' (Chờ duyệt)
    let finalStatus = "pending"; 

    // Chỉ gọi AI nếu user có gửi ảnh lên
    if (imageURLs.length > 0) {
        try {
             const AI_API_URL = "https://hen2k4-roda-ai-api.hf.space/analyze-incident"; 
            
            const response = await fetch(AI_API_URL, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ image_url: imageURLs[0] }) // Gửi ảnh đầu tiên cho AI
            });

            const aiResult = await response.json();
            
            // Xử lý logic tự động đổi cờ (ẩn hoàn toàn với user)
            if (aiResult.detected) {
                let mappedUserType = "unknown";
                if (type === "Hố gà") mappedUserType = "pothole";
                if (type === "Lũ lụt") mappedUserType = "flood";
                if (type === "Thi công") mappedUserType = "construction";

                // AI đoán TRÚNG loại sự cố & độ tự tin > 70% -> Lật cờ thành approved ngầm!
                if (mappedUserType === aiResult.ai_label && aiResult.confidence > 0.7) {
                    finalStatus = "approved";
                }
            }
        } catch (error) {
            console.error("Lỗi khi kết nối AI Server (Chuyển về pending):", error);
            // Nếu AI sập, hệ thống vẫn an toàn giữ cờ pending để Admin tự duyệt
        }
    }

    // =====================================================
    // LƯU DATABASE CUỐI CÙNG
    // =====================================================

    const { error } = await supabaseClient
        .from("road_events")
        .insert([
            {
                lat: reportLatLng.lat,
                lng: reportLatLng.lng,
                type: type,
                description: description,
                image_url: JSON.stringify(imageURLs),
                distance: distance,
                status: finalStatus, // Lưu cờ (pending hoặc approved) do AI quyết định
                created_at: new Date().toISOString(),
                approved_at: finalStatus === "approved" ? new Date().toISOString() : null
            }
        ]);

    if (error) {
        console.log("Lỗi lưu DB:", error);
        alert("❌ Đã xảy ra sự cố, vui lòng thử lại sau.");
        
        // Trả lại nút nếu lỗi
        const btn = document.querySelector("button[onclick='submitReport()']");
        if (btn) {
            btn.innerText = "Gửi báo cáo";
            btn.disabled = false;
        }
        return;
    }

    alert("✅ Báo cáo của bạn đã được hệ thống ghi nhận!");

    // Dọn dẹp bản đồ
    map.removeLayer(tempMarker);
    tempMarker = null;
    map.closePopup();

    // Load lại dữ liệu ngay lập tức
    // Nếu AI vừa lật cờ thành "approved", người dùng sẽ thấy điểm nhảy lên bản đồ liền!
    loadIncidents();
}


// =====================================================
// LOAD INCIDENTS 
// =====================================================

async function loadIncidents() {
    if (markers.length > 0) {
        markers.forEach(m => map.removeLayer(m));
        markers = [];
    }

    const { data, error } = await supabaseClient
        .from("road_events")
        .select("*")
        .eq("status", "approved");

    if (error) {
        console.log(error);
        return;
    }

    

    // 1. Kéo tất cả các điểm vào đường trước
    let rawPoints = [...data];

    // 2. Thuật toán gộp các điểm (Cùng loại & cách nhau <= 10m)
    let groupedPoints = [];

    for (const pt of rawPoints) {
        let foundGroup = false;
        const ptLatLng = L.latLng(pt.lat, pt.lng);

        for (let group of groupedPoints) {
            const groupLatLng = L.latLng(group.lat, group.lng);
            
            // Nếu khoảng cách <= 10 mét và cùng loại sự cố thì gộp chung vào group
            if (groupLatLng.distanceTo(ptLatLng) <= 10 && group.type === pt.type) {
                group.incidents.push(pt);
                group.count++;
                foundGroup = true;
                break;
            }
        }

        // Nếu điểm này không nằm gần nhóm nào đã có, tạo nhóm mới
        if (!foundGroup) {
            groupedPoints.push({
                lat: pt.lat,
                lng: pt.lng,
                type: pt.type,
                count: 1,
                incidents: [pt] // Lưu mảng các báo cáo để tí hiển thị trong Popup
            });
        }
    }

    // 3. Vẽ marker từ danh sách đã gộp
    for (const group of groupedPoints) {
        const size = getIconSize();
        const icon = createIcon(ICONS[group.type], size);

        const marker = L.marker(
            [group.lat, group.lng],
            { icon: icon }
        ).addTo(map);

        marker.incidentType = group.type; 

        let popupContent = `<div style="max-height: 250px; overflow-y: auto; padding-right: 5px;">`;
        
        popupContent += `<b style="font-size:15px;">Sự cố:</b> ${group.type}`;
        
        // Nếu có nhiều báo cáo bị gộp, thêm nhãn màu đỏ thông báo
        if (group.count > 1) {
            popupContent += ` <span style="color:#ff4d4f; font-weight:bold;">(Gộp ${group.count} báo cáo)</span>`;
        }

        // Lặp qua từng báo cáo trong nhóm để hiển thị chi tiết (Thời gian, mô tả, ảnh)
        group.incidents.forEach((inc, index) => {
            popupContent += `<hr style="margin:8px 0; border: 0.5px solid #eee;">`;
            
            if (inc.created_at) {
                const date = new Date(inc.created_at);
                const timeString = date.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" }) 
                                 + " • " + date.toLocaleDateString("vi-VN");
                popupContent += `<b>Thời gian:</b> ${timeString}<br>`;
            }

            if (inc.description) {
                popupContent += `<b>Mô tả:</b> ${inc.description}<br>`;
            }

            if (inc.image_url) {
                let images = [];
                try {
                    images = JSON.parse(inc.image_url);
                } catch {
                    images = [inc.image_url];
                }
                if (images.length > 0) {
                    popupContent += `<div style="margin-top:5px;">`;
                    images.forEach(img => {
                        popupContent += `<img src="${img}" width="70" height="70" style="object-fit: cover; margin:2px; border-radius:4px; border: 1px solid #ddd;">`;
                    });
                    popupContent += `</div>`;
                }
            }
        });

        popupContent += `</div>`; // Đóng thẻ div có thanh cuộn

        marker.bindPopup(popupContent);
        markers.push(marker);

        if (group.type === "Lũ lụt" || group.type === "Thi công") {
            // Lấy distance từ báo cáo đầu tiên trong group (hoặc 50m nếu ko có)
            const distance = group.incidents[0].distance || 50; 
            
            // Chạy bất đồng bộ (không await để không chặn vòng lặp render marker)
            getRoadSegment(group.lat, group.lng, distance).then(roadCoords => {
                if (roadCoords) {
                    drawIncidentLine(group.type, roadCoords);
                }
            });
        }
    }

    // Cập nhật lại số liệu cho Dashboard
    calculateAllIncidents();
}

// =====================================================
// GPS LOCATION
// =====================================================

let userMarker = null;
let userLocation = null;

document.getElementById("gpsBtn").onclick = function () {
    map.locate({ setView: true, maxZoom: 16 });
};

map.on("locationfound", function (e) {

    userLocation = e.latlng;

    if (userMarker) map.removeLayer(userMarker);

    userMarker = L.marker(e.latlng)
        .addTo(map)
        .bindPopup("📍 Bạn đang ở đây")
        .openPopup();

    L.circle(e.latlng, {
        radius: e.accuracy
    }).addTo(map);

});


// =====================================================
// ROUTING SYSTEM
// =====================================================

let routingControl = null;
let selectingRoute = false;

let startMarker = null;
let endMarker = null;

let startPoint = null;
let endPoint = null;


// =====================================================
// ROUTE ICONS
// =====================================================

function createStartIcon() {
    return L.icon({
        iconUrl: "https://cdn-icons-png.flaticon.com/512/684/684908.png",
        iconSize: [32, 32],
        iconAnchor: [16, 32]
    });
}

function createEndIcon() {
    return L.icon({
        iconUrl: "https://cdn-icons-png.flaticon.com/512/149/149060.png",
        iconSize: [32, 32],
        iconAnchor: [16, 32]
    });
}


// =====================================================
// ROUTE BUTTON
// =====================================================

document.getElementById("routeBtn").onclick = function () {

    if (selectingRoute || routingControl) {
        cancelRoute();
        // SỬA DÒNG NÀY
        this.innerHTML = "🧭 <span class='btn-text'>Chỉ đường</span>";
        return;
    }

    // SỬA DÒNG NÀY
    this.innerHTML = "❌ <span class='btn-text'>Huỷ chỉ đường</span>";

    selectingRoute = true;

    alert(
        "🧭 Chế độ chỉ đường\n\n" +
        "1️⃣ Click chọn điểm bắt đầu\n" +
        "2️⃣ Click chọn điểm kết thúc"
    );

};


// =====================================================
// SNAP TO ROAD
// =====================================================

async function snapToRoad(lat, lng) {

    const url = `https://router.project-osrm.org/nearest/v1/driving/${lng},${lat}`;

    const res = await fetch(url);
    const data = await res.json();

    if (data.waypoints && data.waypoints.length > 0) {

        const snapped = data.waypoints[0].location;

        return {
            lat: snapped[1],
            lng: snapped[0]
        };

    }

    return { lat, lng };
}


// =====================================================
// CHỌN ĐIỂM ROUTE
// =====================================================

map.on("click", function (e) {

    if (drawMode) return;

    if (!selectingRoute) return;

    if (!startPoint) {

        startPoint = userLocation || e.latlng;

        startMarker = L.marker(startPoint, {
            icon: createStartIcon()
        }).addTo(map)
            .bindPopup("🚦 Điểm bắt đầu")
            .openPopup();

        return;
    }

    if (!endPoint) {

        endPoint = e.latlng;

        endMarker = L.marker(endPoint, {
            icon: createEndIcon()
        }).addTo(map)
            .bindPopup("🏁 Điểm kết thúc")
            .openPopup();

        createRoute(startPoint, endPoint);

        selectingRoute = false;

    }

});


// =====================================================
// TẠO ROUTE
// =====================================================

function createRoute(start, end) {

    if (routingControl) {
        map.removeControl(routingControl);
    }

    routingControl = L.Routing.control({

        waypoints: [
            L.latLng(start.lat, start.lng),
            L.latLng(end.lat, end.lng)
        ],

        routeWhileDragging: false,

        show: false,

        lineOptions: {
            styles: [{ color: "#2b8cff", weight: 7 }]
        },

        createMarker: function () { return null }

    }).addTo(map);

    routingControl.on("routesfound", function (e) {
        const route = e.routes[0];

        document.getElementById("dashboard").classList.add("route-active");

        document.getElementById("dashboard").classList.add("show-stats");
        document.getElementById("dashboard").classList.remove("show-steps");

        showInstructions(route.instructions);
        const coords = route.coordinates;
        calculateRouteIncidents(coords);
    });

}


// =====================================================
// HIỂN THỊ HƯỚNG DẪN
// =====================================================

function showInstructions(instructions) {

    let html = "";

    instructions.forEach(step => {

        html += `
        <p>
        ${step.text}
        <br>
        <span style="color:gray;font-size:12px">
        ${Math.round(step.distance)} m
        </span>
        </p>
        `;

    });

    document.getElementById("routeSteps").innerHTML = html;

}


// =====================================================
// POINT IN POLYGON
// =====================================================

function isPointInPolygon(point, polygon) {

    let x = point.lng;
    let y = point.lat;

    let inside = false;

    let vs = polygon.getLatLngs()[0];

    for (let i = 0, j = vs.length - 1; i < vs.length; j = i++) {

        let xi = vs[i].lng, yi = vs[i].lat;
        let xj = vs[j].lng, yj = vs[j].lat;

        let intersect =
            ((yi > y) != (yj > y)) &&
            (x < (xj - xi) * (y - yi) / (yj - yi) + xi);

        if (intersect) inside = !inside;

    }

    return inside;

}

// =====================================================
// DISTANCE TO SEGMENT
// =====================================================
function distanceToSegment(p, p1, p2) {

    const x = p.lng;
    const y = p.lat;

    const x1 = p1.lng;
    const y1 = p1.lat;

    const x2 = p2.lng;
    const y2 = p2.lat;

    const A = x - x1;
    const B = y - y1;
    const C = x2 - x1;
    const D = y2 - y1;

    const dot = A * C + B * D;
    const len_sq = C * C + D * D;

    let param = -1;

    if (len_sq !== 0) {
        param = dot / len_sq;
    }

    let xx, yy;

    if (param < 0) {
        xx = x1;
        yy = y1;
    }
    else if (param > 1) {
        xx = x2;
        yy = y2;
    }
    else {
        xx = x1 + param * C;
        yy = y1 + param * D;
    }

    const dx = x - xx;
    const dy = y - yy;

    return Math.sqrt(dx * dx + dy * dy);
}

// =====================================================
// TÍNH CÁC SỰ CỐ TRÊN TUYẾN ĐƯỜNG (Bao phủ hết chiều ngang đường)
// =====================================================
function calculateRouteIncidents(routeCoords) {

    let pothole = 0;
    let flood = 0;
    let construction = 0;
    let danger = 0;

    
    const buffer = 0.00045; 

    markers.forEach(m => {

        const pos = m.getLatLng();
        let nearRoute = false;

        for (let i = 0; i < routeCoords.length - 1; i++) {

            const d = distanceToSegment(
                pos,
                routeCoords[i],
                routeCoords[i + 1]
            );

            if (d < buffer) {
                nearRoute = true;
                break;
            }

        }

        if (nearRoute) {

            const type = m.incidentType;

            if (type === "Hố gà") pothole++;
            if (type === "Lũ lụt") flood++;
            if (type === "Thi công") construction++;
            if (type === "Nguy hiểm") danger++;

        }

    });

    updateDashboard(pothole, flood, construction, danger);
}

// =====================================================
// CANCEL ROUTE
// =====================================================

function cancelRoute() {

    selectingRoute = false;

    if (routingControl) {
        map.removeControl(routingControl);
        routingControl = null;
    }

    if (startMarker) {
        map.removeLayer(startMarker);
        startMarker = null;
    }

    if (endMarker) {
        map.removeLayer(endMarker);
        endMarker = null;
    }

    startPoint = null;
    endPoint = null;

    // Reset lại Dashboard: Tắt chế độ chỉ đường và xoá các trạng thái Tab
    const dash = document.getElementById("dashboard");
    dash.classList.remove("route-active");
    dash.classList.remove("show-stats");
    dash.classList.remove("show-steps");

    // Đưa nút Tab về lại màu sắc mặc định cho lần chỉ đường tiếp theo
    document.getElementById("tab-stats").classList.add("active");
    document.getElementById("tab-steps").classList.remove("active");

    // Tính lại số lượng cảnh báo trên bản đồ
    calculateAllIncidents();
}


// =====================================================
// UPDATE DASHBOARD
// =====================================================

function updateDashboard(pothole, flood, construction, danger) {

    const total = pothole + flood + construction + danger;

    document.getElementById("potholeRoute").innerText = pothole;
    document.getElementById("floodRoute").innerText = flood;
    document.getElementById("constructionRoute").innerText = construction;
    document.getElementById("dangerRoute").innerText = danger;
    document.getElementById("warningRoute").innerText = total;

}


// =====================================================
// DRAW BUTTON
// =====================================================

document.getElementById("drawBtn").onclick = function () {

    drawMode = !drawMode;

    if (drawMode) {

        this.innerHTML = "❌ <span class='btn-text'>Huỷ vẽ</span>";

        // tắt kéo bản đồ
        map.dragging.disable();
        map.touchZoom.disable();
        map.doubleClickZoom.disable();
        map.scrollWheelZoom.disable();
        map.boxZoom.disable();
        map.keyboard.disable();

        alert(
            "✏️ Chế độ khoanh vùng\n\n" +
            "• Nhấn giữ chuột để vẽ\n" +
            "• Thả chuột để hoàn thành vùng\n" +
            "• Có thể vẽ nhiều khu vực"
        );

    } else {

        this.innerHTML = "✏️ <span class='btn-text'>Khoanh vùng</span>";

        // bật lại kéo bản đồ
        map.dragging.enable();
        map.touchZoom.enable();
        map.doubleClickZoom.enable();
        map.scrollWheelZoom.enable();
        map.boxZoom.enable();
        map.keyboard.enable();

        clearAllDrawings();

    }

};


// =====================================================
// FREEHAND DRAW EVENTS
// =====================================================

map.on("mousedown", function (e) {

    if (!drawMode) return;

    isDrawing = true;

    drawPoints = [e.latlng];

    if (drawLayer) {
        map.removeLayer(drawLayer);
    }

});


map.on("mousemove", function (e) {

    if (!isDrawing) return;

    const last = drawPoints[drawPoints.length - 1];

    if (!last || last.distanceTo(e.latlng) > 5) {
        drawPoints.push(e.latlng);
    }

    if (drawLayer) {
        map.removeLayer(drawLayer);
    }

    drawLayer = L.polyline(drawPoints, {
        color: "#ff5500",
        weight: 3
    }).addTo(map);

});

map.on("mouseup", function () {

    if (!isDrawing) return;

    isDrawing = false;

    if (drawPoints.length < 3) {
        return;
    }

    drawPoints.push(drawPoints[0]);

    if (drawLayer) {
        map.removeLayer(drawLayer);
    }

    var polygon = L.polygon(drawPoints, {
        color: "#ff5500",
        weight: 2,
        fillOpacity: 0.15
    }).addTo(map);

    drawnAreas.push(polygon);

    calculateAllAreas();

});

// =====================================================
// TOUCH EVENTS 
// =====================================================

const mapContainer = map.getContainer();

mapContainer.addEventListener("touchstart", function (e) {
    if (!drawMode) return;
    if (e.touches.length !== 1) return; // chỉ xử lý 1 ngón

    e.preventDefault();
    e.stopPropagation();

    isDrawing = true;

    const latlng = map.mouseEventToLatLng(e.touches[0]);
    drawPoints = [latlng];

    if (drawLayer) {
        map.removeLayer(drawLayer);
        drawLayer = null;
    }

}, { passive: false, capture: true }); // capture: true để bắt trước Leaflet


mapContainer.addEventListener("touchmove", function (e) {
    if (!drawMode || !isDrawing) return;
    if (e.touches.length !== 1) return;

    e.preventDefault();
    e.stopPropagation();

    const latlng = map.mouseEventToLatLng(e.touches[0]);
    const last = drawPoints[drawPoints.length - 1];

    if (!last || last.distanceTo(latlng) > 5) {
        drawPoints.push(latlng);
    }

    if (drawLayer) map.removeLayer(drawLayer);

    drawLayer = L.polyline(drawPoints, {
        color: "#ff5500",
        weight: 3
    }).addTo(map);

}, { passive: false, capture: true });


mapContainer.addEventListener("touchend", function (e) {
    if (!drawMode || !isDrawing) return;

    e.preventDefault();
    e.stopPropagation();

    isDrawing = false;

    if (drawPoints.length < 3) {
        drawPoints = [];
        return;
    }

    // Đóng polygon
    drawPoints.push(drawPoints[0]);

    if (drawLayer) {
        map.removeLayer(drawLayer);
        drawLayer = null;
    }

    const polygon = L.polygon(drawPoints, {
        color: "#ff5500",
        weight: 2,
        fillOpacity: 0.15
    }).addTo(map);

    drawnAreas.push(polygon);
    calculateAllAreas();

    drawPoints = [];

}, { passive: false, capture: true });


// =====================================================
// TÍNH TỔNG CẢNH BÁO TRÊN TOÀN BẢN ĐỒ
// =====================================================

function calculateAllIncidents() {

    let pothole = 0;
    let flood = 0;
    let construction = 0;
    let danger = 0;

    markers.forEach(m => {

        const type = m.incidentType || "Hố gà";

        if (type === "Hố gà") pothole++;
        if (type === "Lũ lụt") flood++;
        if (type === "Thi công") construction++;
        if (type === "Nguy hiểm") danger++;

    });

    updateDashboard(pothole, flood, construction, danger);

}


// =====================================================
// TÍNH TỔNG CẢNH BÁO TRONG CÁC VÙNG
// =====================================================

function calculateAllAreas() {

    var pothole = 0;
    var flood = 0;
    var construction = 0;
    var danger = 0;

    markers.forEach(function (m) {

        var pos = m.getLatLng();
        var insideAnyArea = false;

        drawnAreas.forEach(function (area) {

            if (isPointInPolygon(pos, area)) {
                insideAnyArea = true;
            }

        });

        if (insideAnyArea) {

            var type = m.incidentType || "Hố gà";

            if (type === "Hố gà") pothole++;
            if (type === "Lũ lụt") flood++;
            if (type === "Thi công") construction++;
            if (type === "Nguy hiểm") danger++;

        }

    });

    updateDashboard(pothole, flood, construction, danger);

    document.getElementById("dashboard")
        .classList.remove("route-active");

}


// =====================================================
// CLEAR ALL DRAWINGS
// =====================================================

function clearAllDrawings() {

    drawnAreas.forEach(function (area) {
        map.removeLayer(area);
    });

    drawnAreas = [];

    if (drawLayer) {
        map.removeLayer(drawLayer);
    }

    calculateAllIncidents();

}


// =====================================================
// SEARCH → KHOANH VÙNG 
// =====================================================

geocoder.on("markgeocode", function (e) {
    
    // 1. Di chuyển bản đồ đến khu vực vừa tìm
    map.fitBounds(e.geocode.bbox);

    // 2. Lấy dữ liệu ranh giới (Polygon GeoJSON) từ API
    const geojson = e.geocode.properties.geojson;

    // 3. Kiểm tra xem khu vực này có ranh giới cụ thể không (Quận, Phường, Tỉnh...)
    if (geojson && (geojson.type === "Polygon" || geojson.type === "MultiPolygon")) {
        
        // Phân tích dữ liệu GeoJSON thành các lớp Polygon của Leaflet
        const geojsonLayer = L.geoJSON(geojson);
        
        // Tách từng vùng ra để vẽ 
        geojsonLayer.eachLayer(function (layer) {
            if (layer instanceof L.Polygon) {
                // Áp dụng màu sắc giống hệt nét vẽ tay của bạn
                layer.setStyle({
                    color: "#2b8cff",
                    weight: 2,
                    fillOpacity: 0.15
                });
                
                // Vẽ lên bản đồ và đẩy vào mảng tính toán
                layer.addTo(map);
                drawnAreas.push(layer);
            }
        });

    } else {
        // 4. Fallback: Nếu tìm số nhà/quán ăn (không có ranh giới hành chính) -> Vẽ hình vuông bao quanh như cũ
        const bounds = L.latLngBounds(e.geocode.bbox);
        const rect = L.rectangle(bounds, {
            color: "#2b8cff",
            weight: 2,
            fillOpacity: 0.15
        }).addTo(map);

        drawnAreas.push(rect);
    }

    // 5. Cập nhật lại số lượng sự cố nằm trong vùng
    calculateAllAreas();
});

function switchTab(tabName) {
    const dash = document.getElementById("dashboard");
    const btnStats = document.getElementById("tab-stats");
    const btnSteps = document.getElementById("tab-steps");

    if (tabName === 'stats') {
        dash.classList.add("show-stats");
        dash.classList.remove("show-steps");
        btnStats.classList.add("active");
        btnSteps.classList.remove("active");
    } else {
        dash.classList.add("show-steps");
        dash.classList.remove("show-stats");
        btnSteps.classList.add("active");
        btnStats.classList.remove("active");
    }
}

// HÀM GIS: LẤY VÀ CẮT TUYẾN ĐƯỜNG (OVERPASS + TURF.JS)
async function getRoadSegment(lat, lng, distanceMeters) {
    const overpassUrl = `https://overpass-api.de/api/interpreter?data=[out:json];way(around:20,${lat},${lng})[highway];out geom;`;
    try {
        const response = await fetch(overpassUrl);
        const data = await response.json();
        
        if (!data.elements || data.elements.length === 0) return null;
        
        const way = data.elements[0];
        const coords = way.geometry.map(pos => [pos.lon, pos.lat]);
        
        const line = turf.lineString(coords);
        const centerPt = turf.point([lng, lat]);
        
        const snapped = turf.nearestPointOnLine(line, centerPt);
        const distKm = distanceMeters / 1000;
        const centerDist = snapped.properties.location; 
        
        let startDist = Math.max(0, centerDist - (distKm / 2));
        let endDist = centerDist + (distKm / 2);
        
        const totalLength = turf.length(line);
        if (endDist > totalLength) endDist = totalLength;
        
        const segment = turf.lineSliceAlong(line, startDist, endDist);
        return segment.geometry.coordinates.map(c => [c[1], c[0]]);
    } catch (error) {
        console.error("Lỗi GIS:", error);
        return null;
    }
}

//  HÀM VẼ HIỆU ỨNG ĐƯỜNG LÊN BẢN ĐỒ
function drawIncidentLine(incidentType, latlngs) {
    if (!latlngs || latlngs.length === 0) return;
    if (incidentType === "Lũ lụt") {
        L.polyline(latlngs, { color: "#2b8cff", weight: 8, opacity: 0.7, lineCap: "round", lineJoin: "round" }).addTo(map);
    } else if (incidentType === "Thi công") {
        L.polyline(latlngs, { color: "#000000", weight: 8, opacity: 0.8, lineCap: "butt" }).addTo(map);
        L.polyline(latlngs, { color: "#ffcc00", weight: 8, dashArray: "15, 15", opacity: 1, lineCap: "butt" }).addTo(map);
    }
}

// =====================================================
// Database Testing with Supabase
// =====================================================

// 1. Thông tin kết nối Supabase
const supabaseUrl = "https://sweqvobmlntyhyeuurfr.supabase.co";
const supabaseKey = "sb_publishable_xsqRVFRoQSh0c9wzwc5vxA_Hw9aj9fF";

// 2. Tạo client kết nối database
const supabaseClient = window.supabase.createClient(
    supabaseUrl,
    supabaseKey
);

// 4. Chạy test
loadIncidents();