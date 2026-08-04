package com.beam.app.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SERVICE_TYPE = "$BEAM_SERVICE_NAME.$BEAM_SERVICE_PROTOCOL.local."
private const val ID_KEY = "id"

class JvmDiscoveryService : DiscoveryService {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers = _peers.asStateFlow()

    private val discovered = mutableMapOf<String, Peer>()
    private var jmdns: JmDNS? = null
    private var localServiceName: String? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            jmdns?.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            discovered.remove(event.name)
            _peers.value = discovered.values.toList()
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            if (info.name == localServiceName) return
            val host = info.inetAddresses.firstOrNull()?.hostAddress ?: return
            val id = info.getPropertyString(ID_KEY) ?: info.name
            discovered[info.name] = Peer(id, info.name, host, info.port)
            _peers.value = discovered.values.toList()
        }
    }

    override fun start(localDeviceId: String, localDeviceName: String, servicePort: Int) {
        localServiceName = localDeviceName
        val instance = JmDNS.create(InetAddress.getLocalHost(), localDeviceName)
        jmdns = instance

        val serviceInfo = ServiceInfo.create(
            SERVICE_TYPE,
            localDeviceName,
            servicePort,
            0,
            0,
            mapOf(ID_KEY to localDeviceId),
        )
        instance.registerService(serviceInfo)
        instance.addServiceListener(SERVICE_TYPE, listener)
    }

    override fun stop() {
        jmdns?.let { instance ->
            instance.removeServiceListener(SERVICE_TYPE, listener)
            runCatching { instance.unregisterAllServices() }
            runCatching { instance.close() }
        }
        jmdns = null
        discovered.clear()
        _peers.value = emptyList()
    }
}
