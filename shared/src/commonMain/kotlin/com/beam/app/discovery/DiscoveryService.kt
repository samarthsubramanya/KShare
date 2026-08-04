package com.beam.app.discovery

import kotlinx.coroutines.flow.StateFlow

/** mDNS service type Beam advertises/browses under (platform code adapts to each API's format). */
const val BEAM_SERVICE_NAME = "_beam"
const val BEAM_SERVICE_PROTOCOL = "_tcp"

data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Advertises this device on the LAN and browses for other Beam devices via mDNS.
 * One instance per app process; start()/stop() are idempotent.
 */
interface DiscoveryService {
    val peers: StateFlow<List<Peer>>
    fun start(localDeviceId: String, localDeviceName: String, servicePort: Int)
    fun stop()
}
