package life.xstar.music.player.cast

data class CastDevice(
    val udn: String,
    val friendlyName: String,
    val deviceType: String,
    val modelName: String?,
    val location: String,
    val baseUrl: String,
    val controlUrl: String?,
    val eventSubUrl: String?,
    val discoveredAt: Long = System.currentTimeMillis()
) {
    val id: String get() = udn
    val supportsAvTransport: Boolean get() = !controlUrl.isNullOrBlank()
}

data class CastDeviceStatus(
    val scanning: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val error: String? = null
)
