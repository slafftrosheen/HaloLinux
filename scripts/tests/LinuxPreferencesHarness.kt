package com.halo.adapters.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object LinuxPreferencesHarness {
    private var passed = 0
    private fun ok(value: Boolean, message: String = "assertion failed") { if (!value) throw AssertionError(message) }
    private fun check(name: String, action: () -> Unit) {
        try { action(); passed++; println("PASS $name") }
        catch (error: Throwable) { System.err.println("FAIL $name: ${error::class.simpleName}: ${error.message}"); throw error }
    }
    private inline fun <reified T: Throwable> fails(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.simpleName}, got ${error::class.simpleName}")
        }
        throw AssertionError("Expected ${T::class.simpleName} but no exception")
    }
    private val filePerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val dirPerms = filePerms + PosixFilePermission.OWNER_EXECUTE
    private fun fresh(): Path {
        val path=Files.createTempDirectory("halo-pref-tests-")
        Files.setPosixFilePermissions(path,dirPerms)
        return path
    }
    private fun prefs(dir: Path): LinuxPreferenceStorage = LinuxPreferenceStorage(dir)
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun data(dir: Path): Path = dir.resolve("preferences.v1")
    private fun lock(dir: Path): Path = dir.resolve("preferences.lock")
    private fun size(dir: Path): Long = Files.size(data(dir))
    private fun newProcess(vararg arguments: String): Process {
        val java = Path.of(System.getProperty("java.home"),"bin","java").toString()
        return ProcessBuilder(java, "-jar", System.getProperty("harness.jar"), *arguments)
            .redirectErrorStream(true).start()
    }
    @JvmStatic fun main(args: Array<String>) {
        if (args.isNotEmpty()) {
            val s=prefs(Path.of(args[1]))
            when (args[0]) {
                "worker" -> repeat(args[3].toInt()) { s.putInt(args[2]+it,it) }
                "halt" -> { s.beforeReplace = { Runtime.getRuntime().halt(91) }; s.putString("k", "new-secret") }
                else -> throw AssertionError("bad fixture command")
            }
            return
        }
        val d=fresh()
        check("defaults-string-null") { ok(prefs(d).getString("missing")==null) }
        check("defaults-string-chosen") { ok(prefs(d).getString("missing","fallback")=="fallback") }
        check("defaults-int") { ok(prefs(d).getInt("missing",9)==9) }
        check("defaults-bool") { ok(prefs(d).getBoolean("missing",true)) }
        check("write-string") { prefs(d).putString("name","Halo"); ok(prefs(d).getString("name")=="Halo") }
        check("write-null-string") { prefs(d).putString("nullable",null); ok(prefs(d).getString("nullable","fallback")==null) }
        check("write-int-negative") { prefs(d).putInt("offset",-77);ok(prefs(d).getInt("offset")==-77) }
        check("write-int-limits") { prefs(d).putInt("min",Int.MIN_VALUE);prefs(d).putInt("max",Int.MAX_VALUE);ok(prefs(d).getInt("min")==Int.MIN_VALUE);ok(prefs(d).getInt("max")==Int.MAX_VALUE) }
        check("write-bool-false") { prefs(d).putBoolean("false",false);ok(!prefs(d).getBoolean("false",true)) }
        check("write-bool-true") { prefs(d).putBoolean("true",true);ok(prefs(d).getBoolean("true")) }
        check("unicode-key-value") { prefs(d).putString("задача✓","Hello 🌍\nПривет");ok(prefs(d).getString("задача✓")=="Hello 🌍\nПривет") }
        check("key-with-colon-slash") { prefs(d).putString("halo:voice/last-device","A");ok(prefs(d).getString("halo:voice/last-device")=="A") }
        check("empty-string-value") { prefs(d).putString("empty","");ok(prefs(d).getString("empty","fallback")=="") }
        check("remove-existing") { prefs(d).remove("name");ok(prefs(d).getString("name","default")=="default") }
        check("remove-missing") { prefs(d).remove("missing2") }
        check("type-mismatch-string") { fails<IllegalStateException>{prefs(d).getString("offset","fallback")} }
        check("type-mismatch-int") { fails<IllegalStateException>{prefs(d).getInt("false",33)} }
        check("type-mismatch-bool") { fails<IllegalStateException>{prefs(d).getBoolean("offset",true)} }
        check("typed-batch") { prefs(d).putBatch(mapOf("first" to "one", "second" to null),mapOf("flag" to true));ok(prefs(d).getString("first")=="one");ok(prefs(d).getBoolean("flag"));ok(prefs(d).getString("second","fallback")==null) }
        check("batch-overwrite") { prefs(d).putBatch(mapOf("first" to "two"),mapOf("flag" to false));ok(prefs(d).getString("first")=="two");ok(!prefs(d).getBoolean("flag")) }
        check("snapshot-deterministic-noop") { val before=Files.readAllBytes(data(d));prefs(d).putBatch();ok(before.contentEquals(Files.readAllBytes(data(d)))) }
        check("batch-duplicate-key-rejected") {val before=sha(Files.readAllBytes(data(d)));fails<IllegalArgumentException>{prefs(d).putBatch(mapOf("same" to "text"),mapOf("same" to true))};ok(before==sha(Files.readAllBytes(data(d))))}
        check("batch-invalid-key-atomic") {val before=sha(Files.readAllBytes(data(d)));fails<IllegalArgumentException>{prefs(d).putBatch(mapOf("safe" to "value", "bad\nkey" to "x"),emptyMap())};ok(before==sha(Files.readAllBytes(data(d))))}
        check("empty-key-rejected") {fails<IllegalArgumentException>{prefs(d).putString("","x")}}
        check("control-key-rejected") {fails<IllegalArgumentException>{prefs(d).putInt("\u0000",3)}}
        check("oversized-key-rejected") {fails<IllegalArgumentException>{prefs(d).putString("a".repeat(129),"x")}}
        check("oversized-value-rejected") {fails<IllegalArgumentException>{prefs(d).putString("too-long","Z".repeat(65_537))}}
        check("unicode-byte-boundary") {fails<IllegalArgumentException>{prefs(d).putString("k".repeat(126)+"✅","x")}}
        check("file-permissions") { ok(Files.getPosixFilePermissions(data(d))==filePerms);ok(Files.getPosixFilePermissions(lock(d))==filePerms) }
        check("directory-permissions-preserved") { ok(Files.getPosixFilePermissions(d)==dirPerms) }
        check("constructor-does-not-overwrite-config") {val before=sha(Files.readAllBytes(data(d)));prefs(d);ok(before==sha(Files.readAllBytes(data(d))))}
        check("unrelated-config-remains") {Files.writeString(d.resolve("config.yaml"),"node: test\n");prefs(d).putInt("more",9);ok(Files.readString(d.resolve("config.yaml"))=="node: test\n")}
        check("read-after-process-restart") {val p=newProcess("worker",d.toString(),"reboot-", "4");ok(p.waitFor()==0);ok(prefs(d).getInt("reboot-3")==3)}
        check("missing-directory-rejected") {fails<Exception>{prefs(d.resolve("unknown")).getInt("x")};ok(!Files.exists(d.resolve("unknown")))}
        check("loose-directory-rejected") {val x=fresh();Files.setPosixFilePermissions(x,filePerms + PosixFilePermission.GROUP_READ + PosixFilePermission.OWNER_EXECUTE);fails<Exception>{prefs(x).getInt("x")};ok(Files.getPosixFilePermissions(x)!=dirPerms)}
        check("symlink-directory-rejected") {val x=fresh();val link=d.resolve("aliased");Files.createSymbolicLink(link,x);fails<Exception>{prefs(link).getInt("x")}}
        check("symlink-parent-rejected") {val x=fresh();val sub=x.resolve("inner");Files.createDirectory(sub);Files.setPosixFilePermissions(sub,dirPerms);val link=d.resolve("parent");Files.createSymbolicLink(link,x);fails<Exception>{prefs(link.resolve("inner")).getInt("x")}}
        check("symlink-data-rejected") {val x=fresh();Files.createSymbolicLink(data(x),data(d));fails<Exception>{prefs(x).getInt("x")};ok(Files.isSymbolicLink(data(x)))}
        check("symlink-lock-rejected") {val x=fresh();Files.createSymbolicLink(lock(x),lock(d));fails<Exception>{prefs(x).getInt("x")}}
        check("hardlink-data-rejected") {val x=fresh();Files.createLink(data(x),data(d));fails<Exception>{prefs(x).getInt("x")};Files.delete(data(x))}
        check("hardlink-lock-rejected") {val x=fresh();Files.createLink(lock(x),lock(d));fails<Exception>{prefs(x).getInt("x")};Files.delete(lock(x))}
        check("world-readable-data-rejected") {val x=fresh();prefs(x).putString("hi","hello");Files.setPosixFilePermissions(data(x),filePerms+PosixFilePermission.GROUP_READ);fails<Exception>{prefs(x).getString("hi")}}
        check("world-readable-lock-rejected") {val x=fresh();prefs(x).putString("hi","hello");Files.setPosixFilePermissions(lock(x),filePerms+PosixFilePermission.OTHERS_READ);fails<Exception>{prefs(x).getString("hi")}}
        check("corrupt-checksum-rejected") {val x=fresh();prefs(x).putString("hi","hello");val bytes=Files.readAllBytes(data(x));bytes[25]=(bytes[25].toInt() xor 1).toByte();Files.write(data(x),bytes);fails<Exception>{prefs(x).getString("hi")};fails<Exception>{prefs(x).putString("extra","bad")}}
        check("truncated-data-rejected") {val x=fresh();prefs(x).putInt("t",5);Files.write(data(x), Files.readAllBytes(data(x)).copyOf(8));fails<Exception>{prefs(x).getInt("t")}}
        check("extra-trailing-data-rejected") {val x=fresh();prefs(x).putInt("t",5);Files.write(data(x), Files.readAllBytes(data(x))+byteArrayOf(0));fails<Exception>{prefs(x).getInt("t")}}
        check("unsupported-magic-rejected") {val x=fresh();Files.write(data(x), ByteArray(45){0});Files.setPosixFilePermissions(data(x),filePerms);fails<Exception>{prefs(x).getInt("t")}}
        check("clear-all") {prefs(d).clear();ok(prefs(d).getInt("offset",999)==999);ok(prefs(d).getString("first","none")=="none")}
        check("fresh-after-clear") {prefs(d).putString("k","old-secret");ok(prefs(d).getString("k")=="old-secret")}
        check("atomic-failure-does-not-persist") {val before=Files.readAllBytes(data(d));val p=prefs(d);p.beforeReplace={error("Injected failure")};fails<IllegalStateException>{p.putString("k","bad-secret")};ok(Files.readAllBytes(data(d)).contentEquals(before));ok(prefs(d).getString("k")=="old-secret")}
        check("atomic-killed-before-replace") {val p=newProcess("halt",d.toString());ok(p.waitFor()==91);ok(prefs(d).getString("k")=="old-secret")}
        check("same-process-concurrent-no-lost-writes") {
            val x=fresh();val a=prefs(x);val b=prefs(x);val start=CountDownLatch(1);val errors=AtomicInteger()
            val t1=thread {start.await();try{repeat(80){a.putInt("A$it",it)}}catch(t:Throwable){errors.incrementAndGet();t.printStackTrace()}}
            val t2=thread {start.await();try{repeat(80){b.putInt("B$it",it)}}catch(t:Throwable){errors.incrementAndGet();t.printStackTrace()}}
            start.countDown();t1.join(20000);t2.join(20000);ok(!t1.isAlive&&!t2.isAlive);ok(errors.get()==0)
            repeat(80){ok(prefs(x).getInt("A$it",-1)==it);ok(prefs(x).getInt("B$it",-1)==it)}
        }
        check("cross-process-concurrent-no-lost-writes") {
            val x=fresh();val a=newProcess("worker",x.toString(),"X-","100");val b=newProcess("worker",x.toString(),"Y-","100")
            ok(a.waitFor()==0);ok(b.waitFor()==0)
            repeat(100){ok(prefs(x).getInt("X-$it",-1)==it);ok(prefs(x).getInt("Y-$it",-1)==it)}
        }
        check("payload-limit-exact") {val x=fresh();prefs(x).putString("limit","x".repeat(65_536));ok(prefs(x).getString("limit")?.length==65_536)}
        check("large-store-bound") {val x=fresh();val p=prefs(x);repeat(2048){p.putInt("K$it",it)};fails<IllegalArgumentException>{p.putInt("one-more",1)};ok(p.getInt("K2047")==2047)}
        check("no-corrupt-state-on-limit") {val x=fresh();val p=prefs(x);p.putInt("i",1);val before=Files.readAllBytes(data(x));fails<IllegalArgumentException>{p.putString("too-large","z".repeat(65_537))};ok(Files.readAllBytes(data(x)).contentEquals(before))}
        check("wrong-directory-argument-relative") {fails<IllegalArgumentException>{prefs(Path.of("relative")).getString("foo")}}
        check("preserves-explicit-null-across-instances") {val x=fresh();prefs(x).putString("x",null);ok(prefs(x).getString("x","fallback")==null)}
        check("no-output-of-secret-on-error") {val x=fresh();prefs(x).putString("hidden-value","MY-PRIVATE-SECRET");val bytes=Files.readAllBytes(data(x));bytes[18]=0;Files.write(data(x),bytes);try{prefs(x).getString("hidden-value")}catch(e:Exception){ok(!e.message.orEmpty().contains("MY-PRIVATE-SECRET"));ok(!e.message.orEmpty().contains("hidden-value"))}}
        check("no-silent-corrupt-reset") {val x=fresh();prefs(x).putString("secret","KEEP");val bytes=Files.readAllBytes(data(x));bytes[20]=123;Files.write(data(x),bytes);val original=sha(bytes);fails<Exception>{prefs(x).putString("secret","DELETE")};ok(sha(Files.readAllBytes(data(x)))==original)}

        check("global-snapshot-size-limited") {
            val x=fresh();val p=prefs(x);val chunk="x".repeat(65_536)
            repeat(15){p.putString("big$it",chunk)}
            val before=sha(Files.readAllBytes(data(x)))
            fails<IllegalArgumentException>{p.putString("big15",chunk)}
            ok(sha(Files.readAllBytes(data(x)))==before)
            ok(p.getString("big14")?.length==65_536)
        }
        check("duplicate-binary-key-refused") {
            val x=fresh()
            val body=ByteArrayOutputStream()
            DataOutputStream(body).use { out ->
                out.write("HALOPREF".toByteArray());out.writeInt(1);out.writeInt(2)
                repeat(2) {out.writeShort(1);out.write('x'.code);out.writeByte(2);out.writeInt(it)}
            }
            val raw=body.toByteArray()
            Files.write(data(x),raw+MessageDigest.getInstance("SHA-256").digest(raw))
            Files.setPosixFilePermissions(data(x),filePerms)
            fails<Exception>{prefs(x).getInt("x")}
        }
        check("unknown-binary-type-refused") {
            val x=fresh();val body=ByteArrayOutputStream()
            DataOutputStream(body).use {out ->
                out.write("HALOPREF".toByteArray());out.writeInt(1);out.writeInt(1)
                out.writeShort(1);out.write('x'.code);out.writeByte(99)
            }
            val raw=body.toByteArray()
            Files.write(data(x),raw+MessageDigest.getInstance("SHA-256").digest(raw))
            Files.setPosixFilePermissions(data(x),filePerms)
            fails<Exception>{prefs(x).getInt("x")}
        }
        check("invalid-utf8-refused") {
            val x=fresh();val body=ByteArrayOutputStream()
            DataOutputStream(body).use {out ->
                out.write("HALOPREF".toByteArray());out.writeInt(1);out.writeInt(1)
                out.writeShort(1);out.write('x'.code);out.writeByte(1)
                out.writeInt(1);out.writeByte(0xff)
            }
            val raw=body.toByteArray()
            Files.write(data(x),raw+MessageDigest.getInstance("SHA-256").digest(raw))
            Files.setPosixFilePermissions(data(x),filePerms)
            fails<Exception>{prefs(x).getString("x")}
        }
        check("invalid-boolean-refused") {
            val x=fresh();val body=ByteArrayOutputStream()
            DataOutputStream(body).use {out ->
                out.write("HALOPREF".toByteArray());out.writeInt(1);out.writeInt(1)
                out.writeShort(1);out.write('x'.code);out.writeByte(3);out.writeByte(2)
            }
            val raw=body.toByteArray()
            Files.write(data(x),raw+MessageDigest.getInstance("SHA-256").digest(raw))
            Files.setPosixFilePermissions(data(x),filePerms)
            fails<Exception>{prefs(x).getBoolean("x")}
        }
        check("future-version-refused") {
            val x=fresh();val body=ByteArrayOutputStream()
            DataOutputStream(body).use {out ->out.write("HALOPREF".toByteArray());out.writeInt(2);out.writeInt(0)}
            val raw=body.toByteArray()
            Files.write(data(x),raw+MessageDigest.getInstance("SHA-256").digest(raw))
            Files.setPosixFilePermissions(data(x),filePerms)
            fails<Exception>{prefs(x).getString("anything")}
        }
        check("existing-data-not-changed-by-deleted-lock") {
            val x=fresh();val p=prefs(x);p.putString("value","hello")
            val before=sha(Files.readAllBytes(data(x)))
            Files.delete(lock(x))
            ok(prefs(x).getString("value")=="hello")
            ok(sha(Files.readAllBytes(data(x)))==before)
        }
        check("empty-batch-does-not-create-data") {
            val x=fresh();prefs(x).putBatch();ok(!Files.exists(data(x)))
        }
                println("TOTAL $passed PASS, 0 FAIL")
    }
}
