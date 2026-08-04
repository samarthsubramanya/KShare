package com.beam.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BeamDiscovery"
private const val SERVICE_TYPE = "$BEAM_SERVICE_NAME.$BEAM_SERVICE_PROTOCOL."
private const val ID_ATTR = "id"

class AndroidDiscoveryService(context: Context) : DiscoveryService {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers = _peers.asStateFlow()

    private val discovered = mutableMapOf<String, Peer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var localServiceName: String? = null

    override fun start(localDeviceId: String, localDeviceName: String, servicePort: Int) {
        localServiceName = localDeviceName
        registerService(localDeviceId, localDeviceName, servicePort)
        browseServices()
    }

    private fun registerService(localDeviceId: String, localDeviceName: String, servicePort: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localDeviceName
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute(ID_ATTR, localDeviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun browseServices() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == localServiceName) return
                resolve(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                discovered.remove(service.serviceName)
                _peers.value = discovered.values.toList()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val id = info.attributes[ID_ATTR]?.decodeToString() ?: info.serviceName
                discovered[info.serviceName] = Peer(id, info.serviceName, host, info.port)
                _peers.value = discovered.values.toList()
            }
        })
    }

    override fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        discovered.clear()
        _peers.value = emptyList()
    }
}
