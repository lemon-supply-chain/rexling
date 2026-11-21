package dev.lemon.rex

import org.json.JSONObject
import java.util.UUID

/** Metadata only: UI state never contains decrypted private keys. */
internal data class SshKeyInfo(val id: String,val name: String,val fingerprint: String,val kind: String)
internal data class SshKeyImportResult(val id: String?=null,val error: String?=null)

/** One encrypted secret per identity, with independent endpoint references. */
internal class SshKeyLibrary(raw: String?=null) {
    private val json=raw?.let(::JSONObject) ?: JSONObject().put("entries",JSONObject()).put("bindings",JSONObject())
    private val entries get()=json.getJSONObject("entries")
    private val bindings get()=json.getJSONObject("bindings")
    fun list(): List<SshKeyInfo> = entries.keys().asSequence().map {id->
        val item=entries.getJSONObject(id)
        SshKeyInfo(id,item.getString("name"),item.optString("fingerprint"),item.optString("kind"))
    }.sortedWith(compareBy<SshKeyInfo> {it.name.lowercase()}.thenBy {it.id}).toList()
    fun selected(endpoint: String): String?=bindings.optString(endpoint).takeIf {it.isNotBlank() && entries.has(it)}
    fun secret(id: String): String=entries.getJSONObject(id).getString("secret")
    fun endpoints(id: String)=bindings.keys().asSequence().filter {bindings.optString(it)==id}.toList()
    fun assigned(): Map<String,String> = bindings.keys().asSequence().mapNotNull {endpoint->selected(endpoint)?.let {endpoint to it}}.toMap()
    fun add(name: String,fingerprint: String,kind: String,encrypted: String): String {
        require(name.trim().isNotEmpty() && name.trim().length<=80) {"Enter a key name of up to 80 characters"}
        if(fingerprint.isNotBlank()) list().firstOrNull {it.fingerprint==fingerprint}?.let {return it.id}
        val id=UUID.randomUUID().toString()
        entries.put(id,JSONObject().put("name",name.trim()).put("fingerprint",fingerprint).put("kind",kind).put("secret",encrypted))
        return id
    }
    fun assign(endpoint: String,id: String?) {
        if(id==null) bindings.remove(endpoint)
        else {require(entries.has(id)) {"That SSH key is no longer available"};bindings.put(endpoint,id)}
    }
    fun rename(id: String,name: String) {
        require(name.trim().isNotEmpty() && name.trim().length<=80) {"Enter a key name of up to 80 characters"}
        entries.getJSONObject(id).put("name",name.trim())
    }
    fun remove(id: String) {endpoints(id).forEach(bindings::remove);entries.remove(id)}
    fun encode(): String=json.toString()
    companion object {
        /** Keep the original ciphertext, even when metadata cannot be inspected. */
        fun migrate(legacy: JSONObject,describe: (String)->Pair<String,String>): SshKeyLibrary {
            val library=SshKeyLibrary()
            legacy.keys().asSequence().sorted().forEach {endpoint->
                val encrypted=legacy.getString(endpoint)
                val identity=runCatching {describe(encrypted)}.getOrDefault("" to "")
                val host=runCatching {java.net.URI(endpoint).host}.getOrNull()?.takeIf {it.isNotBlank()} ?: "Imported"
                val id=library.add("$host key".take(80),identity.first,identity.second,encrypted)
                library.assign(endpoint,id)
            }
            return library
        }
    }
}
