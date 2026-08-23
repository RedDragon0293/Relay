package cn.reddragon.relay.tcp

data class ServerConfig(
    val host: String,
    val port: Int
) {
    val isValid: Boolean = host.isNotBlank() && isPortValid(port)
    companion object {
        fun isPortValid(port: Int): Boolean = port in VALID_PORT_RANGE
        private val VALID_PORT_RANGE = 1..65535
    }
}
