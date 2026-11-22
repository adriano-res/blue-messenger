package com.bluemessenger.presentation.constants

/**
 * Centralized UI layout dimensions, typography metrics, colors, Safety Number formatting rules,
 * QR matrix parameters, and structured logging tags for the presentation layer.
 */
internal object PresentationConstants {
    const val DESKTOP_SPLIT_BREAKPOINT_DP: Int = 840

    const val WEIGHT_FILL: Float = 1f
    const val WEIGHT_MASTER_PANE: Float = 0.38f
    const val WEIGHT_DETAIL_PANE: Float = 0.62f

    const val SCREEN_PADDING_DP: Int = 16
    const val SECTION_SPACING_DP: Int = 12
    const val ITEM_SPACING_DP: Int = 8
    const val COMPACT_SPACING_DP: Int = 4
    const val CARD_PADDING_DP: Int = 14
    const val BANNER_PADDING_DP: Int = 12
    const val MESSAGE_BUBBLE_PADDING_H_DP: Int = 12
    const val MESSAGE_BUBBLE_PADDING_V_DP: Int = 8
    const val MESSAGE_BUBBLE_MAX_WIDTH_DP: Int = 360
    const val BADGE_PADDING_H_DP: Int = 8
    const val BADGE_PADDING_V_DP: Int = 4
    const val CORNER_RADIUS_MD_DP: Int = 12
    const val CORNER_RADIUS_SM_DP: Int = 8
    const val DIVIDER_THICKNESS_DP: Int = 1
    const val BORDER_WIDTH_DP: Int = 1
    const val PROGRESS_INDICATOR_SIZE_DP: Int = 18
    const val PROGRESS_STROKE_WIDTH_DP: Int = 2

    const val SAFETY_CODE_FONT_SIZE_SP: Int = 15
    const val SAFETY_CODE_LINE_HEIGHT_SP: Int = 22

    const val SAFETY_NUMBER_TOTAL_DIGITS: Int = 60
    const val SAFETY_NUMBER_GROUP_SIZE: Int = 5
    const val SAFETY_NUMBER_GROUPS_PER_ROW: Int = 4

    const val QR_MATRIX_MARGIN: Int = 0
    const val QR_REQUEST_DIMENSION_PX: Int = 0
    const val QR_QUIET_ZONE_MODULES: Int = 2
    const val QR_QUIET_ZONE_SIDES: Int = 2
    const val QR_CANVAS_SIZE_DP: Int = 220

    const val INPUT_SINGLE_LINE: Int = 1
    const val INPUT_MAX_LINES_CHAT: Int = 4
    const val MAX_PREVIEW_LINES: Int = 1

    const val COLOR_STATUS_ACTIVE: Long = 0xFF2E7D32L
    const val COLOR_STATUS_CONNECTING: Long = 0xFFED6C02L
    const val COLOR_STATUS_DISCONNECTED: Long = 0xFF757575L
    const val COLOR_VERIFIED_BADGE: Long = 0xFF1B5E20L
    const val COLOR_FAILED_TEXT: Long = 0xFFB3261EL
    const val COLOR_QR_BACKGROUND: Long = 0xFFFFFFFFL
    const val COLOR_QR_FOREGROUND: Long = 0xFF000000L
    const val BADGE_CONTAINER_ALPHA: Float = 0.14f

    const val TAG_DISCOVERY_VIEW_MODEL: String = "DiscoveryViewModel"
    const val TAG_CHAT_VIEW_MODEL: String = "ChatViewModel"
}
