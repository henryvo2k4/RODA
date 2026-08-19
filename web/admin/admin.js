// =====================================================
// MAP
// =====================================================

const map = L.map("map", {
    zoomControl: false,
    doubleClickZoom: false
}).setView([10.8231, 106.6297], 13);

L.tileLayer(
    "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    { maxZoom: 19 }
).addTo(map);

// =====================================================
// BIẾN TOÀN CỤC CHO FILTER & MARKERS
// =====================================================
let currentFilter = 'pending'; // Mặc định hiển thị điểm chưa duyệt
let mapMarkers = []; // Mảng chứa các marker đang hiển thị trên bản đồ

// Hàm chuyển đổi bộ lọc khi bấm nút
function setFilter(status) {
    currentFilter = status;

    // Đổi màu nút đang được chọn
    document.querySelectorAll('.filter-btn').forEach(btn => btn.classList.remove('active'));
    document.getElementById('btn-' + status).classList.add('active');

    // Tải lại dữ liệu
    loadReports();
}

// =====================================================
// LOAD REPORTS & DRAW MARKERS
// =====================================================

async function loadReports() {

    // 1. Lọc dữ liệu trên Supabase dựa theo nút được bấm
    let query = supabaseClient.from("road_events").select("*").order("created_at", { ascending: false });
    
    if (currentFilter !== 'all') {
        query = query.eq("status", currentFilter);
    }

    const { data, error } = await query;

    if (error) {
        console.log("Database error:", error);
        return;
    }

    const container = document.getElementById("reportList");
    container.innerHTML = "";

    // 2. Xoá tất cả các điểm cũ trên bản đồ trước khi vẽ điểm mới
    mapMarkers.forEach(m => map.removeLayer(m));
    mapMarkers = [];
    if (viewMarker) map.removeLayer(viewMarker); // Xoá luôn marker đang highlight (nếu có)

    // 3. Render Danh sách và cắm điểm lên bản đồ
    data.forEach(r => {
        const lat = Number(r.lat);
        const lng = Number(r.lng);
        const date = new Date(r.created_at);

        const timeString = date.toLocaleString("vi-VN", {
            hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit", year: "numeric"
        });

        // --- XỬ LÝ ẢNH ---
        let images = [];
        if (r.image_url) {
            try { images = JSON.parse(r.image_url); } catch { images = [r.image_url]; }
        }

        let galleryHTML = "";
        if (images.length > 0) {
            galleryHTML = `<div class="report-gallery">
                ${images.map(img => `<img src="${img}" class="report-img" onclick="showImage('${img}')">`).join("")}
            </div>`;
        }

        // --- VẼ ĐIỂM LÊN BẢN ĐỒ ---
        // Điểm Chưa duyệt: Icon Cam | Điểm Đã duyệt: Icon Xanh
        const iconUrl = r.status === 'approved' 
            ? "https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-green.png"
            : "https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-orange.png";

        const marker = L.marker([lat, lng], {
            icon: L.icon({
                iconUrl: iconUrl,
                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
            })
        }).addTo(map);

        marker.bindPopup(`<b>Sự cố:</b> ${r.type}<br><b>Trạng thái:</b> ${r.status === 'approved' ? '✅ Đã duyệt' : '⏳ Chưa duyệt'}`);
        mapMarkers.push(marker);

        // --- TẠO GIAO DIỆN CARD TRONG SIDEBAR ---
        // Nếu đã duyệt rồi thì làm mờ nút "Duyệt" và không cho bấm nữa
        let approveBtnHTML = r.status === 'pending'
            ? `<button class="approve-btn" onclick="approve(${r.id})">Duyệt</button>`
            : `<button class="approve-btn" style="background:#94a3b8; cursor:not-allowed;" disabled>Đã duyệt</button>`;

        let card = document.createElement("div");
        card.className = "report-card";
        card.innerHTML = `
            <b>Cảnh báo:</b> ${r.type}<br>
            <b>Tọa độ:</b> ${lat.toFixed(5)}, ${lng.toFixed(5)}<br>
            <b>Thời gian gửi:</b> ${timeString}<br>
            <b>Trạng thái:</b> <span style="color:${r.status === 'approved' ? 'green' : 'orange'}">${r.status}</span><br>
            <b>Mô tả:</b> ${r.description || "Không có"}<br>
            ${galleryHTML}
            <div class="buttons">
                <button class="view-btn" onclick="viewIncident(${lat},${lng})">Xem</button>
                ${approveBtnHTML}
                <button class="delete-btn" onclick="removeReport(${r.id})">Xóa</button>
            </div>
        `;
        container.appendChild(card);
    });
}

// Chạy lần đầu khi mở trang (sẽ mặc định load các điểm Chưa duyệt)
loadReports();


// =====================================================
// VIEW INCIDENT
// =====================================================

let viewMarker = null;

function viewIncident(lat, lng) {

    map.setView([lat, lng], 17);

    if (viewMarker) {
        map.removeLayer(viewMarker);
    }

    viewMarker = L.marker(
        [lat, lng],
        {
            icon: L.icon({
                iconUrl: "https://cdn-icons-png.flaticon.com/512/684/684908.png",
                iconSize: [40, 40],
                iconAnchor: [20, 40]
            })
        }
    ).addTo(map);

}


// =====================================================
// APPROVE 
// =====================================================
async function approve(id) {

    const { error } = await supabaseClient
        .from("road_events")
        .update({ 
            status: "approved",
            approved_at: new Date().toISOString() // TỰ ĐỘNG LƯU THỜI GIAN DUYỆT TẠI ĐÂY
        })
        .eq("id", id);

    if (error) {
        console.log(error);
    }

    loadReports();

}


// =====================================================
// DELETE
// =====================================================

async function removeReport(id) {

    if (!confirm("Xóa báo cáo này?")) return;

    const { error } = await supabaseClient
        .from("road_events")
        .delete()
        .eq("id", id);

    if (error) {
        console.log(error);
    }

    loadReports();

}

// =====================================================
// IMAGE MODAL
// =====================================================
function showImage(src) {

    const modal = document.getElementById("imgModal");
    const img = document.getElementById("imgPreview");

    img.src = src;

    modal.style.display = "flex";

}

function closeImage() {

    document.getElementById("imgModal").style.display = "none";

}