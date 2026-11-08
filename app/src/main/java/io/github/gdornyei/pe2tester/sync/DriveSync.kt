package io.github.gdornyei.pe2tester.sync

import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.data.Store
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Google Drive v3 REST client (scope drive.file: the app only sees files it created).
 * Layout in Drive:  PE2 Tester/ projects/<id>.json, photos/<name>.jpg, reports/<pdf|csv>
 */
class DriveSync(private val token: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"

    data class RemoteFile(val id: String, val name: String, val modified: String, val appId: String?)

    class DriveException(msg: String, val code: Int) : Exception(msg)

    private fun http(method: String, url: String, body: ByteArray? = null, contentType: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000; c.readTimeout = 60000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType ?: "application/json; charset=UTF-8")
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) throw DriveException("Drive HTTP $code: ${txt.take(200)}", code)
        return txt
    }

    private fun httpBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        if (c.responseCode !in 200..299) throw DriveException("Drive HTTP ${c.responseCode}", c.responseCode)
        return c.inputStream.use { it.readBytes() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    fun list(q: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>(); var page: String? = null
        do {
            val url = "$api/files?q=${enc(q)}&fields=${enc("nextPageToken,files(id,name,modifiedTime,appProperties)")}&pageSize=200" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val o = json.parseToJsonElement(http("GET", url)).jsonObject
            o["files"]?.jsonArray?.forEach { f ->
                val fo = f.jsonObject
                out += RemoteFile(fo["id"]!!.jsonPrimitive.content, fo["name"]!!.jsonPrimitive.content,
                    fo["modifiedTime"]?.jsonPrimitive?.content ?: "",
                    (fo["appProperties"] as? JsonObject)?.get("pid")?.jsonPrimitive?.content)
            }
            page = o["nextPageToken"]?.jsonPrimitive?.content
        } while (page != null)
        return out
    }

    fun folder(name: String, parent: String?): String {
        val q = "mimeType='application/vnd.google-apps.folder' and name='${esc(name)}' and trashed=false" + (parent?.let { " and '$it' in parents" } ?: "")
        list(q).firstOrNull()?.let { return it.id }
        val meta = buildString {
            append("{\"name\":\"${esc(name)}\",\"mimeType\":\"application/vnd.google-apps.folder\"")
            if (parent != null) append(",\"parents\":[\"$parent\"]")
            append("}")
        }
        return json.parseToJsonElement(http("POST", "$api/files?fields=id", meta.toByteArray())).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Creates or updates a file (multipart upload). Returns the file id. */
    fun put(name: String, mime: String, data: ByteArray, parent: String, existingId: String?, appProps: Map<String, String> = emptyMap()): String {
        val b = "pe2boundary${System.nanoTime()}"
        val props = appProps.entries.joinToString(",") { "\"${it.key}\":\"${esc(it.value)}\"" }
        val meta = if (existingId == null) "{\"name\":\"${esc(name)}\",\"parents\":[\"$parent\"],\"appProperties\":{$props}}"
                   else "{\"name\":\"${esc(name)}\",\"appProperties\":{$props}}"
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        val url = if (existingId == null) "$upload/files?uploadType=multipart&fields=id" else "$upload/files/$existingId?uploadType=multipart&fields=id"
        val r = http(if (existingId == null) "POST" else "PATCH", url, body, "multipart/related; boundary=$b")
        return json.parseToJsonElement(r).jsonObject["id"]!!.jsonPrimitive.content
    }

    fun get(id: String): ByteArray = httpBytes("$api/files/$id?alt=media")

    fun delete(id: String) { runCatching { http("DELETE", "$api/files/$id") } }

    data class Result(val projects: List<Project>, val uploaded: Int, val downloaded: Int, val photosUp: Int, val photosDown: Int)

    /**
     * Two-way sync. Every project is merged with its Drive copy (union of measurements, tombstones respected),
     * then written back where it changed. Photos are uploaded/downloaded by name.
     */
    fun sync(local: List<Project>, deleted: List<String>, photoDir: File, log: (String) -> Unit): Result {
        log("Folder: PE2 Tester")
        val root = folder("PE2 Tester", null)
        val pf = folder("projects", root)
        val phf = folder("photos", root)
        val remote = list("'$pf' in parents and trashed=false").associateBy { it.appId ?: it.name.removeSuffix(".json") }
        val byId = local.associateBy { it.id }.toMutableMap()
        var up = 0; var down = 0

        for ((pid, rf) in remote) {
            if (pid in deleted) { log("Deleted from Drive: ${rf.name}"); delete(rf.id); continue }
            val rp = runCatching { json.decodeFromString<Project>(get(rf.id).toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            val lp = byId[pid]
            val merged = if (lp == null) rp.also { down++ } else Store.merge(lp, rp).also { if (it != lp) down++ }
            byId[pid] = merged
            if (merged != rp) { put("$pid.json", "application/json", json.encodeToString(merged).toByteArray(), pf, rf.id, mapOf("pid" to pid, "name" to merged.name)); up++ }
        }
        for ((pid, p) in byId) if (pid !in remote) {
            put("$pid.json", "application/json", json.encodeToString(p).toByteArray(), pf, null, mapOf("pid" to pid, "name" to p.name)); up++
        }

        // photos
        val wanted = byId.values.flatMap { p -> p.measurements.flatMap { it.photos } }.toSet()
        val rPhotos = list("'$phf' in parents and trashed=false").associateBy { it.name }
        var pu = 0; var pd = 0
        for (n in wanted) {
            val lf = File(photoDir, n)
            if (lf.exists() && n !in rPhotos) { put(n, "image/jpeg", lf.readBytes(), phf, null); pu++ }
            else if (!lf.exists() && n in rPhotos) { lf.parentFile?.mkdirs(); lf.writeBytes(get(rPhotos.getValue(n).id)); pd++ }
        }
        return Result(byId.values.toList(), up, down, pu, pd)
    }

    /** Uploads a generated report into "PE2 Tester/reports". */
    fun uploadReport(f: File): String {
        val root = folder("PE2 Tester", null)
        val rf = folder("reports", root)
        return put(f.name, if (f.extension == "pdf") "application/pdf" else "text/csv", f.readBytes(), rf, null)
    }
}
