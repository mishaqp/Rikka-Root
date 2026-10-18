// Adapted from ExTV/rikkahub-agent, local/ContactsTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private data class ContactItems(val values: JsonArray, val truncated: Boolean)

private fun contactItems(context: Context, id: Long, uri: Uri, value: String, type: String, contactId: String, key: String, phone: Boolean): ContactItems {
    val cursor = context.contentResolver.query(uri, arrayOf(value, type), "$contactId = ?", arrayOf(id.toString()), null)
        ?: error("Служба контактов Android недоступна. Проверьте доступ к контактам и включите приложение контактов.")
    return cursor.use { c ->
        val valueIndex = c.getColumnIndexOrThrow(value); val typeIndex = c.getColumnIndexOrThrow(type)
        val values = buildJsonArray {
            var count = 0
            while (count < 20 && c.moveToNext()) {
                addJsonObject {
                    val text = c.getString(valueIndex).orEmpty()
                    put(key, text.take(4096))
                    if (text.length > 4096) {
                        put("${key}_truncated", true)
                        put("truncation_note", "Значение ограничено 4096 символами; полные реквизиты доступны в приложении контактов Android.")
                    }
                    put("type", when (c.getInt(typeIndex)) { 1 -> "home"; 2 -> if (phone) "mobile" else "work"; 3 -> if (phone) "work" else "other"; else -> "other" })
                }
                count++
            }
        }
        ContactItems(values, c.moveToNext())
    }
}

private fun queryContacts(context: Context, uri: Uri, limit: Int): JsonObject {
    val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null,
        "${ContactsContract.Contacts.DISPLAY_NAME} ASC") ?: error("Служба контактов Android недоступна. Проверьте доступ к контактам и включите приложение контактов.")
    var hasMore = false
    val contacts = cursor.use { c ->
        val idIndex = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val nameIndex = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
        val result = buildJsonArray {
            var count = 0
            while (count < limit && c.moveToNext()) {
                val id = c.getLong(idIndex)
                val phones = contactItems(context, id, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, "number", true)
                val emails = contactItems(context, id, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.CONTACT_ID, "address", false)
                addJsonObject {
                    val name = c.getString(nameIndex).orEmpty()
                    put("id", id); put("display_name", name.take(4096))
                    if (name.length > 4096) {
                        put("display_name_truncated", true)
                        put("truncation_note", "Имя ограничено 4096 символами; полное имя доступно в приложении контактов Android.")
                    }
                    put("phones", phones.values); put("phones_truncated", phones.truncated)
                    put("emails", emails.values); put("emails_truncated", emails.truncated)
                    if (phones.truncated || emails.truncated) put("details_note", "На контакт показано до 20 номеров и 20 email. Полные реквизиты доступны в приложении контактов Android; увеличение limit не расширяет этот предел.")
                }
                count++
            }
        }
        hasMore = c.moveToNext()
        result
    }
    return buildJsonObject {
        put("contacts", contacts); put("count", contacts.size)
        put("truncated", hasMore); put("has_more", hasMore)
        if (hasMore) put("continuation", "Показана только часть контактов. Увеличьте limit в пределах этого инструмента или уточните query через search_contacts; автоматического курсора продолжения нет.")
    }
}

fun listContactsTool(context: Context): Tool = personalJsonTool(context, "list_contacts", "Читает контакты телефона с номерами и email после разрешения контактов. limit 1–500, по умолчанию 50; для поиска конкретного человека используйте search_contacts. Не изменяет адресную книгу. Результат JSON contacts/count, truncated/has_more показывают дополнительную строку; для большого списка увеличьте limit или сузьте поиск. На один контакт возвращает до 20 номеров и 20 email; значения ограничены 4096 символами и помечаются *_truncated; полные реквизиты доступны в приложении контактов Android.", LocalToolOption.Contacts,
    InputSchema.Obj(buildJsonObject { put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500); put("description", "Максимум возвращаемых записей: 1–500, по умолчанию 50. Для большого результата увеличьте limit в этих пределах или сузьте фильтр."); put("default", 50) }) }),
    validate = { personalLimit(it, 50, 500) }, read = { limit ->
        queryContacts(context, ContactsContract.Contacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, (limit + 1).toString()).build(), limit)
    })

fun searchContactsTool(context: Context): Tool = personalJsonTool(context, "search_contacts", "Ищет контакты телефона по имени или номеру через Android. query — непустая строка до 256 символов, limit 1–100, по умолчанию 20. Для просмотра без поиска используйте list_contacts. Не изменяет контакты; результат JSON contacts/count с truncated/has_more для дополнительных строк, с номерами и email (до 20 каждого вида, значения до 4096 символов с *_truncated; полные реквизиты в приложении контактов Android).", LocalToolOption.Contacts,
    InputSchema.Obj(buildJsonObject { put("query", buildJsonObject { put("type", "string"); put("maxLength", 256); put("description", "Имя или номер для поиска контактов Android, непустая строка 1–256 символов."); put("minLength", 1) }); put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100); put("description", "Максимум возвращаемых записей: 1–100, по умолчанию 20. Для большого результата увеличьте limit в этих пределах или сузьте фильтр."); put("default", 20) }) }, listOf("query")),
    validate = { personalQuery(it) to personalLimit(it, 20, 100) }, read = { (query, limit) ->
        queryContacts(context, ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(query).appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, (limit + 1).toString()).build(), limit)
    })
