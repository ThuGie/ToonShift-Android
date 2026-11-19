package nl.toonshift.android

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads an immutable model archive. Microphone audio never leaves the phone. */
internal object OfflineSpeechModel {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.MINUTES).build()

    private fun name(language: String) =
        if (language == "nl") "vosk-model-small-nl-0.22" else "vosk-model-small-en-us-0.15"

    fun installed(context: Context, language: String): File? {
        val dir = File(context.noBackupFilesDir, "speech/" + name(language))
        return dir.takeIf { File(it, "am/final.mdl").isFile && File(it, ".complete").isFile }
    }

    @Synchronized
    fun download(context: Context, language: String): File {
        installed(context,language)?.let { return it }
        val name = name(language)
        val root = File(context.noBackupFilesDir,"speech").apply { mkdirs() }
        val staging = java.nio.file.Files.createTempDirectory(root.toPath(),"model-").toFile()
        try {
            val archive = File(staging,"model.zip")
            val expected = if (language == "nl")
                "039811c3b829de64e4f123a9f684a53784005b212a346ac0b899dc7efce2ed0a"
            else "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url("https://alphacephei.com/vosk/models/$name.zip").build()
            client.newCall(request).execute().use { response ->
                val body = response.body.takeIf { response.isSuccessful } ?: throw IOException("Model download failed")
                require(body.contentLength() in -1..(64L * 1024 * 1024))
                var downloaded = 0L
                body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            require(downloaded <= 64L * 1024 * 1024)
                            digest.update(buffer,0,count)
                            output.write(buffer,0,count)
                        }
                    }
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected)
            val bytes = ByteArray(32 * 1024)
            var total = 0L
            var entries = 0
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 128)
                    val path = entry.name.removePrefix("$name/")
                    require(entry.name.startsWith("$name/"))
                    if (path.isEmpty()) continue
                    require(path.split('/').filter { it.isNotEmpty() }.all { it != "." && it != ".." } && '\\' !in path)
                    val target = File(staging,path)
                    require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            while (true) {
                                val count = zip.read(bytes)
                                if (count < 0) break
                                total += count
                                require(total <= 128L * 1024 * 1024)
                                out.write(bytes,0,count)
                            }
                        }
                    }
                }
            }
            check(archive.delete())
            require(File(staging,"am/final.mdl").isFile)
            File(staging,".complete").writeText(name)
            val target = File(root,name)
            check(staging.renameTo(target))
            return target
        } finally {
            staging.deleteRecursively()
        }
    }
}
