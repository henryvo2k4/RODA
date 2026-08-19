// 1. Khởi tạo Supabase
const supabaseUrl = "https://sweqvobmlntyhyeuurfr.supabase.co";
const supabaseKey = "sb_publishable_xsqRVFRoQSh0c9wzwc5vxA_Hw9aj9fF";
const supabaseClient = window.supabase.createClient(supabaseUrl, supabaseKey);

// Biến toàn cục cho bản đồ chọn tọa độ
let pickerMap = null;
let pickerMarker = null;

// 2. Load danh sách thiết bị
async function loadDevices() {
    const { data, error } = await supabaseClient.from("devices").select("*").order("created_at", { ascending: false });
    
    if (error) {
        console.error("Lỗi tải thiết bị:", error);
        return;
    }

    const grid = document.getElementById("deviceGrid");
    grid.innerHTML = "";

    data.forEach(dev => {
        const isFixed = dev.type === 'fixed';
        const typeBadge = isFixed 
            ? `<span class="badge fixed">📍 Cố định</span>` 
            : `<span class="badge mobile">🚌 Di động</span>`;

        const detailsHTML = isFixed 
            ? `<p><b>Tọa độ:</b> ${dev.lat}, ${dev.lng}</p>
               <p><b>Base:</b> ${dev.base_distance} cm</p>`
            : `<p><i>Ghi dữ liệu theo vị trí thời gian thực</i></p>`;

        const card = document.createElement("div");
        card.className = "device-card";
        card.onclick = () => openInfoModal(dev);
        card.innerHTML = `
            <h3>${dev.name}</h3>
            <p><b>ID:</b> ${dev.id}</p>
            <p>${typeBadge}</p>
            ${detailsHTML}
        `;
        grid.appendChild(card);
    });
}

// 3. Logic Ẩn/Hiện Form & Render Bản Đồ
function toggleConfigFields() {
    const type = document.getElementById("devType").value;
    const configDiv = document.getElementById("fixedNodeConfig");
    
    if (type === 'fixed') {
        configDiv.style.display = "block";
        if (pickerMap) {
            setTimeout(() => pickerMap.invalidateSize(), 150);
        }
    } else {
        configDiv.style.display = "none";
    }
}

function openAddModal() {
    document.getElementById("devId").value = "";
    document.getElementById("devName").value = "";
    document.getElementById("devType").value = "fixed";
    document.getElementById("devLat").value = "";
    document.getElementById("devLng").value = "";
    document.getElementById("devBase").value = "";
    
    toggleConfigFields();
    document.getElementById("addModal").style.display = "flex";

    // Khởi tạo bản đồ nếu đây là lần đầu mở Modal
    if (!pickerMap) {
        // Tọa độ mặc định: Hồ Chí Minh
        pickerMap = L.map('pickerMap', { doubleClickZoom: false }).setView([10.8231, 106.6297], 13);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(pickerMap);

        // Sự kiện click lên bản đồ
        pickerMap.on('click', function(e) {
            const lat = e.latlng.lat.toFixed(6);
            const lng = e.latlng.lng.toFixed(6);

            // Điền số vào 2 ô input
            document.getElementById("devLat").value = lat;
            document.getElementById("devLng").value = lng;

            // Di chuyển Marker
            if (pickerMarker) {
                pickerMarker.setLatLng(e.latlng);
            } else {
                pickerMarker = L.marker(e.latlng).addTo(pickerMap);
            }
        });
    }

    // Reset marker nếu đã có từ lần bật trước, đồng thời fix lỗi render
    if (pickerMarker) {
        pickerMap.removeLayer(pickerMarker);
        pickerMarker = null;
    }
    setTimeout(() => pickerMap.invalidateSize(), 200);
}

function closeModal(modalId) {
    document.getElementById(modalId).style.display = "none";
}

// 4. Lưu Thiết Bị Lên Supabase
async function saveDevice() {
    const id = document.getElementById("devId").value;
    const name = document.getElementById("devName").value;
    const type = document.getElementById("devType").value;
    
    if (!id || !name) {
        alert("Vui lòng nhập ID và Tên thiết bị");
        return;
    }

    let lat = null, lng = null, base_distance = null;
    
    if (type === 'fixed') {
        lat = parseFloat(document.getElementById("devLat").value);
        lng = parseFloat(document.getElementById("devLng").value);
        base_distance = parseFloat(document.getElementById("devBase").value) || null;

        if (!lat || !lng) {
            alert("Vui lòng chấm một vị trí trên bản đồ!");
            return;
        }
    }

    const { error } = await supabaseClient.from("devices").insert([
        { id, name, type, lat, lng, base_distance }
    ]);

    if (error) {
        alert("Lỗi khi thêm: " + error.message);
        return;
    }

    alert("✅ Thêm thiết bị thành công!");
    closeModal('addModal');
    loadDevices(); 
}

// 5. Mở Modal Chi tiết & Lịch sử báo cáo
async function openInfoModal(dev) {
    document.getElementById("infoId").innerText = dev.id;
    document.getElementById("infoName").innerText = dev.name;
    
    const isFixed = dev.type === 'fixed';
    const paramsDiv = document.getElementById("infoFixedParams");
    
    if (isFixed) {
        paramsDiv.style.display = "block";
        document.getElementById("infoCoords").innerText = `${dev.lat || 'N/A'}, ${dev.lng || 'N/A'}`;
        document.getElementById("infoBase").innerText = dev.base_distance || 'Chưa cài đặt';
    } else {
        paramsDiv.style.display = "none";
    }

    document.getElementById("infoModal").style.display = "flex";

    const historyContainer = document.getElementById("historyContainer");
    historyContainer.innerHTML = "⏳ Đang tải dữ liệu...";

    const { data, error } = await supabaseClient
        .from("road_events")
        .select("*")
        .eq("device_id", dev.id)
        .order("created_at", { ascending: false })
        .limit(10); 

    if (error) {
        historyContainer.innerHTML = "❌ Lỗi tải lịch sử.";
        return;
    }

    if (data.length === 0) {
        historyContainer.innerHTML = "Thiết bị này chưa có báo cáo nào.";
        return;
    }

    let historyHTML = "";
    data.forEach(event => {
        const time = new Date(event.created_at).toLocaleString("vi-VN");
        historyHTML += `
            <div class="history-item">
                <b>⏱ ${time}</b><br>
                Sự cố: ${event.type} | Trạng thái: ${event.status === 'approved' ? '✅ Đã duyệt' : '⏳ Chờ duyệt'}<br>
                Vị trí sự cố: ${parseFloat(event.lat).toFixed(5)}, ${parseFloat(event.lng).toFixed(5)}<br>
                Mô tả: ${event.description || 'Không có'}
            </div>
        `;
    });

    historyContainer.innerHTML = historyHTML;
    const btnDelete = document.getElementById("btnDeleteDevice");
    btnDelete.onclick = () => deleteDevice(dev.id);

}

// =====================================================
// 7. XÓA THIẾT BỊ
// =====================================================
async function deleteDevice(deviceId) {
    // 1. Hiển thị cảnh báo xác nhận
    const isConfirm = confirm(`⚠️ Bạn có chắc chắn muốn xóa thiết bị [${deviceId}] không?\nDữ liệu này không thể khôi phục!`);
    
    if (!isConfirm) return; // Nếu chọn Cancel thì thoát luôn

    // 2. Đổi chữ trên nút để báo hiệu đang xử lý
    const btnDelete = document.getElementById("btnDeleteDevice");
    btnDelete.innerText = "⏳ Đang xóa...";
    btnDelete.disabled = true;

    // 3. Gọi lệnh xóa trên Supabase
    const { error } = await supabaseClient
        .from("devices")
        .delete()
        .eq("id", deviceId);

    if (error) {
        alert("❌ Lỗi khi xóa: " + error.message);
        btnDelete.innerText = "Xóa thiết bị";
        btnDelete.disabled = false;
        return;
    }

    // 4. Thông báo thành công, đóng modal và tải lại lưới thiết bị
    alert("✅ Xóa thiết bị thành công!");
    btnDelete.innerText = "Xóa thiết bị";
    btnDelete.disabled = false;
    
    closeModal('infoModal');
    loadDevices();
}

// 6. Chạy khi load trang
loadDevices();