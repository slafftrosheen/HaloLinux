package com.halo.sdk.provider

import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Preserve the host client's diagnostics when installing dispatch enforcement. */
internal open class ForwardingRemoteEventListener(private val delegate: EventListener) : EventListener() {
    override fun callStart(call: Call) = delegate.callStart(call)
    override fun proxySelectStart(call: Call, url: HttpUrl) = delegate.proxySelectStart(call, url)
    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = delegate.proxySelectEnd(call, url, proxies)
    override fun dnsStart(call: Call, domainName: String) = delegate.dnsStart(call, domainName)
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = delegate.dnsEnd(call, domainName, inetAddressList)
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = delegate.connectStart(call, inetSocketAddress, proxy)
    override fun secureConnectStart(call: Call) = delegate.secureConnectStart(call)
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = delegate.secureConnectEnd(call, handshake)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = delegate.connectEnd(call, inetSocketAddress, proxy, protocol)
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = delegate.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    override fun connectionAcquired(call: Call, connection: Connection) = delegate.connectionAcquired(call, connection)
    override fun connectionReleased(call: Call, connection: Connection) = delegate.connectionReleased(call, connection)
    override fun requestHeadersStart(call: Call) = delegate.requestHeadersStart(call)
    override fun requestHeadersEnd(call: Call, request: Request) = delegate.requestHeadersEnd(call, request)
    override fun requestBodyStart(call: Call) = delegate.requestBodyStart(call)
    override fun requestBodyEnd(call: Call, byteCount: Long) = delegate.requestBodyEnd(call, byteCount)
    override fun requestFailed(call: Call, ioe: IOException) = delegate.requestFailed(call, ioe)
    override fun responseHeadersStart(call: Call) = delegate.responseHeadersStart(call)
    override fun responseHeadersEnd(call: Call, response: Response) = delegate.responseHeadersEnd(call, response)
    override fun responseBodyStart(call: Call) = delegate.responseBodyStart(call)
    override fun responseBodyEnd(call: Call, byteCount: Long) = delegate.responseBodyEnd(call, byteCount)
    override fun responseFailed(call: Call, ioe: IOException) = delegate.responseFailed(call, ioe)
    override fun callEnd(call: Call) = delegate.callEnd(call)
    override fun callFailed(call: Call, ioe: IOException) = delegate.callFailed(call, ioe)
    override fun canceled(call: Call) = delegate.canceled(call)
    override fun satisfactionFailure(call: Call, response: Response) = delegate.satisfactionFailure(call, response)
    override fun cacheHit(call: Call, response: Response) = delegate.cacheHit(call, response)
    override fun cacheMiss(call: Call) = delegate.cacheMiss(call)
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) = delegate.cacheConditionalHit(call, cachedResponse)
}
