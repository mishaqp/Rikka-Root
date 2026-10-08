// Adapted from ExTV/rikkahub-agent, local/ContactsTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private fun contactItems(context: Context, id: Long, uri: Uri, value: String, type: String, contactId: String, key: String, phone: Boolean): JsonArray = buildJsonArray {
    val cursor = context.contentResolver.query(uri, arrayOf(value, type), "$contactId = ?", arrayOf(id.toString()), null)
        ?: error("Contacts provider unavailable")
    cursor.use { c ->
        val valueIndex = c.getColumnIndexOrThrow(value); val typeIndex = c.getColumnIndexOrThrow(type)
        var count = 0
        while (count++ < 20 && c.moveToNext()) addJsonObject {
            put(key, c.getString(valueIndex).orEmpty().take(4096))
            put("type", when (c.getInt(typeIndex)) { 1 -> "home"; 2 -> if (phone) "mobile" else "work"; 3 -> if (phone) "work" else "other"; else -> "other" })
        }
    }
}

private fun queryContacts(context: Context, uri: Uri, limit: Int): JsonObject = buildJsonObject {
    put("contacts", buildJsonArray {
        val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC") ?: error("Contacts provider unavailable")
        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIndex = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            var count = 0
            while (count++ < limit && c.moveToNext()) {
                val id = c.getLong(idIndex)
                addJsonObject {
                    put("id", id); put("display_name", c.getString(nameIndex).orEmpty().take(4096))
                    put("phones", contactItems(context, id, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, "number", true))
                    put("emails", contactItems(context, id, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.CONTACT_ID, "address", false))
                }
            }
        }
    })
}

fun listContactsTool(context: Context): Tool = personalJsonTool(context, "list_contacts", "List device contacts with phone numbers and emails. Default limit 50, maximum 500.", LocalToolOption.Contacts,
    InputSchema.Obj(buildJsonObject { put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500) }) }),
    validate = { personalLimit(it, 50, 500) }, read = { limit ->
        queryContacts(context, ContactsContract.Contacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString()).build(), limit)
    })

fun searchContactsTool(context: Context): Tool = personalJsonTool(context, "search_contacts", "Search contacts by name or phone number. Default limit 20, maximum 100.", LocalToolOption.Contacts,
    InputSchema.Obj(buildJsonObject { put("query", buildJsonObject { put("type", "string"); put("maxLength", 256) }); put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) }) }, listOf("query")),
    validate = { personalQuery(it) to personalLimit(it, 20, 100) }, read = { (query, limit) ->
        queryContacts(context, ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(query).appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString()).build(), limit)
    })
