package com.beam.app.discovery

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.AF_INET
import platform.posix.memcpy
import platform.posix.sockaddr_in

private const val SERVICE_TYPE = "$BEAM_SERVICE_NAME.$BEAM_SERVICE_PROTOCOL."
private const val DOMAIN = "local."
private const val ID_KEY = "id"
private const val RESOLVE_TIMEOUT_SECONDS = 8.0

@OptIn(ExperimentalForeignApi::class)
class IosDiscoveryService : DiscoveryService {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers = _peers.asStateFlow()

    private val discovered = mutableMapOf<String, Peer>()
    private val resolvingServices = mutableSetOf<NSNetService>()

    private var publishedService: NSNetService? = null
    private var browser: NSNetServiceBrowser? = null
    private var localServiceName: String? = null

    private val publishDelegate = object : NSObject(), NSNetServiceDelegateProtocol {}

    private val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            if (didFindService.name == localServiceName) return
            resolvingServices += didFindService
            didFindService.delegate = resolveDelegate
            didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) {
            discovered.remove(didRemoveService.name)
            _peers.value = discovered.values.toList()
        }
    }

    private val resolveDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            val address = sender.addresses
                ?.filterIsInstance<NSData>()
                ?.firstNotNullOfOrNull { it.toIPv4Address() }
            if (address != null) {
                val id = sender.txtRecordId() ?: sender.name
                discovered[sender.name] = Peer(id, sender.name, address, sender.port.toInt())
                _peers.value = discovered.values.toList()
            }
            resolvingServices.remove(sender)
        }

        override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
            resolvingServices.remove(sender)
        }
    }

    override fun start(localDeviceId: String, localDeviceName: String, servicePort: Int) {
        localServiceName = localDeviceName

        val service = NSNetService(DOMAIN, SERVICE_TYPE, localDeviceName, servicePort.convert())
        service.delegate = publishDelegate
        service.setTXTRecordData(
            NSNetService.dataFromTXTRecordDictionary(mapOf(ID_KEY to localDeviceId.encodeToByteArray().toNSData()))
        )
        service.publish()
        publishedService = service

        val netServiceBrowser = NSNetServiceBrowser()
        netServiceBrowser.delegate = browserDelegate
        netServiceBrowser.searchForServicesOfType(SERVICE_TYPE, DOMAIN)
        browser = netServiceBrowser
    }

    override fun stop() {
        publishedService?.stop()
        publishedService = null
        browser?.stop()
        browser = null
        resolvingServices.forEach { it.stop() }
        resolvingServices.clear()
        discovered.clear()
        _peers.value = emptyList()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSNetService.txtRecordId(): String? {
    val txtData = TXTRecordData() ?: return null
    val dict = NSNetService.dictionaryFromTXTRecordData(txtData)
    val idData = dict[ID_KEY] as? NSData ?: return null
    return idData.toByteArray().decodeToString()
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) {
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
    return result
}

/**
 * Parses the IPv4 address out of a resolved [NSNetService.addresses] entry (raw sockaddr_in bytes).
 * `sin_addr.s_addr` is a 4-byte blob already in address (network) byte order, so reading its bytes
 * back out in order gives the dotted-quad octets directly — no inet_ntop needed.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toIPv4Address(): String? = memScoped {
    val sockaddr = bytes?.reinterpret<sockaddr_in>()?.pointed ?: return@memScoped null
    if (sockaddr.sin_family.toInt() != AF_INET) return@memScoped null
    val addr = sockaddr.sin_addr.s_addr
    val o1 = (addr and 0xFFu).toInt()
    val o2 = ((addr shr 8) and 0xFFu).toInt()
    val o3 = ((addr shr 16) and 0xFFu).toInt()
    val o4 = ((addr shr 24) and 0xFFu).toInt()
    "$o1.$o2.$o3.$o4"
}
