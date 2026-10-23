package com.halo.host.linux

import com.halo.sdk.provider.*
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private var cases = 0
private fun case(name: String, action: () -> Unit) {
    action(); cases++; println("PASS $name")
}
private inline fun blocked(action: () -> Unit) {
    try { action(); error("Expected dispatch refusal") } catch (_: RemoteDispatchBlocked) { }
}
private inline fun invalid(action: () -> Unit) {
    try { action(); error("Expected invalid endpoint") } catch (_: IllegalArgumentException) { }
}
private fun request(operation: RemoteOperation = RemoteOperation.INFERENCE,
                    categories: Set<RemoteDataCategory> = setOf(RemoteDataCategory.TEXT),
                    provider: String = "openai-compatible") = RemoteDispatchRequest(provider, operation, categories)
private fun policy(url: String = "https://example.invalid/v1", consent: Boolean = false) =
    LinuxRemoteDispatchPolicy("openai-compatible", LinuxInferenceEndpoint.parse(url), consent)

fun main() {
    case("literal IPv4 loopback") { check(LinuxInferenceEndpoint.parse("http://127.0.0.1:11434/v1").isLoopback) }
    case("bracketed IPv6 loopback") { check(LinuxInferenceEndpoint.parse("http://[::1]:11434/v1").isLoopback) }
    case("localhost cannot invoke DNS") {
        val endpoint = LinuxInferenceEndpoint.parse("http://LOCALHOST:11434/v1")
        val addresses = endpoint.resolveHost("localhost") { error("Unexpected DNS") }
        check(addresses.single().address.contentEquals(byteArrayOf(127,0,0,1)))
    }
    case("IPv6 resolver has no DNS lookup") {
        val endpoint = LinuxInferenceEndpoint.parse("http://[::1]/v1")
        check(endpoint.resolveHost("::1") { error("Unexpected DNS") }.single().isLoopbackAddress)
    }
    case("remote lookup is explicit") {
        val endpoint = LinuxInferenceEndpoint.parse("https://example.invalid/v1")
        var calls = 0
        endpoint.resolveHost("example.invalid") { calls++; listOf(InetAddress.getByAddress(byteArrayOf(1,2,3,4))) }
        check(calls == 1 && !endpoint.isLoopback)
    }
    case("DNS cannot substitute a different configured host") {
        invalid { LinuxInferenceEndpoint.parse("https://example.invalid/v1").resolveHost("other.invalid") { error("DNS must not run") } }
    }
    for (url in listOf("http://192.168.1.2/v1", "http://example.invalid/v1", "http://localhost.evil/v1", "http://127.1/v1", "http://localhost./v1")) {
        case("noncanonical or off-device HTTP rejected ${cases}") { invalid { LinuxInferenceEndpoint.parse(url) } }
    }
    for (url in listOf("https://user:password@example.invalid/v1", "https://example.invalid/v1?key=x", "https://example.invalid/#key", "ftp://example.invalid", "https://example.invalid:0/v1", "https://example.invalid:65536/v1", "https://example.invalid:-2/v1", "not a URL", "https://example.invalid/" + "a".repeat(4096))) {
        case("invalid endpoint rejected ${cases}") { invalid { LinuxInferenceEndpoint.parse(url) } }
    }
    case("private mode blocks remote inference") { policy().use { p -> blocked { p.acquire(request(),p.revision) {} } } }
    case("private mode blocks remote health probes") {
        policy().use { p -> blocked { p.acquire(request(RemoteOperation.PROVIDER_CHECK,setOf(RemoteDataCategory.ACCOUNT_METADATA)),p.revision) {} } }
    }
    case("loopback works without remote consent") {
        policy("http://127.0.0.1:11434/v1").use { p -> p.acquire(request(),p.revision) {}.use { it.requireCurrent() } }
    }
    case("explicit consent permits remote text") { policy(consent=true).use { p -> p.acquire(request(),p.revision) {}.use { it.requireCurrent() } } }
    case("explicit consent permits provider metadata") {
        policy(consent=true).use { p -> p.acquire(request(RemoteOperation.PROVIDER_CHECK,setOf(RemoteDataCategory.ACCOUNT_METADATA)),p.revision) {}.close() }
    }
    case("provider identity mismatch is denied without echo") {
        policy(consent=true).use { p ->
            try { p.acquire(request(provider="secret-private-marker"),p.revision) {}; error("Expected rejection") }
            catch (e: RemoteDispatchBlocked) { check(!e.message.orEmpty().contains("secret-private-marker")) }
        }
    }
    for (category in RemoteDataCategory.values().filter { it != RemoteDataCategory.TEXT }) {
        case("unapproved inference category ${category.name}") {
            policy(consent=true).use { p -> blocked { p.acquire(request(categories=setOf(RemoteDataCategory.TEXT,category)),p.revision) {} } }
        }
    }
    for (operation in RemoteOperation.values().filter { it !in setOf(RemoteOperation.INFERENCE,RemoteOperation.PROVIDER_CHECK) }) {
        case("unimplemented operation ${operation.name}") { policy(consent=true).use { p -> blocked { p.acquire(request(operation),p.revision) {} } } }
    }
    case("empty category set fails closed") { policy(consent=true).use { p -> blocked { p.acquire(request(categories=emptySet()),p.revision) {} } } }
    case("wrong probe category fails closed") { policy(consent=true).use { p -> blocked { p.acquire(request(RemoteOperation.PROVIDER_CHECK),p.revision) {} } } }
    case("revision mismatch fails closed") { policy(consent=true).use { p -> blocked { p.acquire(request(),p.revision+1) {} }; blocked { p.acquire(request(),-1) {} } } }
    case("closed policy cannot admit") { val p=policy(consent=true); val revision=p.revision; p.close(); check(p.revision > revision); blocked { p.acquire(request(),p.revision) {} } }
    case("finish is idempotent and expires a lease") {
        policy(consent=true).use { p -> val lease=p.acquire(request(),p.revision) {}; lease.finish(RemoteDispatchOutcome.REPLIED); lease.close(); blocked { lease.requireCurrent() } }
    }
    case("shutdown revokes all leases exactly once") {
        val p=policy(consent=true); val n=AtomicInteger(); val a=p.acquire(request(),p.revision) { n.incrementAndGet() }; val b=p.acquire(request(),p.revision) { n.incrementAndGet() }
        p.close(); p.close(); check(n.get()==2); blocked { a.requireCurrent() }; blocked { b.requireCurrent() }; a.close(); b.close(); check(n.get()==2)
    }
    case("finished leases are not cancelled again") {
        val p=policy(consent=true); var n=0; p.acquire(request(),p.revision) { n++ }.close(); p.close(); check(n==0)
    }
    case("one callback failure cannot prevent remaining cancellation") {
        val p=policy(consent=true); var n=0
        p.acquire(request(),p.revision) { throw IllegalStateException("private details") }
        p.acquire(request(),p.revision) { n++ }; p.close(); check(n==1)
    }
    case("cancellation callbacks do not run with the policy lock held") {
        val p=policy(consent=true); val callbackDone=CountDownLatch(1); val completedInside=AtomicBoolean()
        p.acquire(request(),p.revision) {
            val t=thread { check(p.revision > 0); callbackDone.countDown() }
            completedInside.set(callbackDone.await(2,TimeUnit.SECONDS)); t.join(3000)
        }
        p.close(); check(completedInside.get() && callbackDone.count==0L)
    }
    case("lease capacity is bounded and slots can be reused") {
        val p=policy(consent=true); val leases=(1..LinuxRemoteDispatchPolicy.MAX_LEASES).map { p.acquire(request(),p.revision) {} }
        blocked { p.acquire(request(),p.revision) {} }; leases.first().close(); p.acquire(request(),p.revision) {}.close(); p.close()
    }
    case("concurrent admission versus shutdown") {
        repeat(30) {
            val p=policy(consent=true); val start=CountDownLatch(1); val accepted=AtomicInteger(); val cancelled=AtomicInteger(); val unexpected=AtomicInteger()
            val workers=(1..8).map { thread {
                start.await()
                try { p.acquire(request(),0) { cancelled.incrementAndGet() }; accepted.incrementAndGet() }
                catch (_: RemoteDispatchBlocked) { } catch (_: Throwable) { unexpected.incrementAndGet() }
            } }
            start.countDown(); p.close(); workers.forEach { it.join(2000); check(!it.isAlive) }
            check(unexpected.get()==0 && accepted.get()==cancelled.get())
        }
    }
    case("missing remote consent defaults to false") { check(!LinuxInferenceSettings.from(emptyMap<String,Any>()).allowRemote) }
    for (consent in listOf(true,false)) {
        case("boolean consent $consent") { check(LinuxInferenceSettings.from(mapOf("allow_remote" to consent)).allowRemote == consent) }
    }
    for (consent in listOf(null,"true","false",1,0,emptyList<String>())) {
        case("nonboolean consent rejected ${cases}") { invalid { LinuxInferenceSettings.from(mapOf("allow_remote" to consent)) } }
    }
    case("paired local endpoint and model") {
        val s=LinuxInferenceSettings.from(mapOf("endpoint" to " http://[::1]:11434/v1 ","model" to " qwen "))
        check(s.endpoint=="http://[::1]:11434/v1" && s.model=="qwen" && !s.allowRemote)
    }
    case("remote endpoint does not imply sharing consent") {
        val s=LinuxInferenceSettings.from(mapOf("endpoint" to "https://example.invalid/v1","model" to "qwen"))
        check(!s.allowRemote)
    }
    for (config in listOf(mapOf("endpoint" to "http://localhost/v1"),mapOf("model" to "qwen"),
        mapOf("endpoint" to 123,"model" to 456),mapOf("endpoint" to false),mapOf("model" to false),
        mapOf("endpoint" to "http://localhost/v1","model" to "x".repeat(257)))) {
        case("malformed inference setting rejected ${cases}") { invalid { LinuxInferenceSettings.from(config) } }
    }
    println("ALL DISPATCH CASES PASSED: $cases")
}
