package com.albscript.autotreefarmer;

/**
 * Trạng thái của bộ máy trạng thái Auto Tree Farmer
 */
public enum FarmerState {
    /** Không hoạt động */
    IDLE,
    /** Quét khu vực xung quanh để tìm ô farm */
    SCANNING_AREA,
    /** Chọn mục tiêu tiếp theo cần xử lý */
    SELECTING_TARGET,
    /** Trồng sapling vào ô đất trống */
    PLANTING,
    /** Bón bột xương cho sapling */
    BONE_MEALING,
    /** Chặt cây (phá log blocks) */
    CHOPPING,
    /** Chờ thu thập drops */
    COLLECTING,
    /** Hồi phục máu/đói, tạm dừng hoạt động */
    HEALING,
    /** Chuyển sang khu tiếp theo */
    NEXT_AREA
}
