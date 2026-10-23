import com.halo.app.backup.BrainArchiveCrypto
import java.io.File

fun main(args: Array<String>) {
    val password = "fixture-✓-🔒-пароль".toCharArray()
    File(args[1]).outputStream().use { output ->
        BrainArchiveCrypto.encrypting(output, password).use { encrypted ->
            File(args[0]).inputStream().use { it.copyTo(encrypted) }
        }
    }
    password.fill('\u0000')
}
